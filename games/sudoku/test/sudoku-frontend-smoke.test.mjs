// games/sudoku/test/sudoku-frontend-smoke.test.mjs
//
// HEADLESS SMOKE for the Sudoku web frontend — the DOM-LIGHT path, mirroring
// games/connect-four/test/connect-four-frontend-smoke.test.mjs one-for-one.
//
// WHY this path: Playwright/jsdom aren't installed here. So we run the pure-node
// DOM-light smoke: a hand-rolled minimal DOM + a RECORDING 2D canvas context.
// Deterministic, zero-install, and proves:
//
//   (a) sudoku.js is loaded as a real ES module against a mocked canvas 2D
//       context; render() of the START state issues the EXPECTED draw-call
//       counts — the board background fillRect, the 20 grid lines (10 vertical +
//       10 horizontal strokes), and one fillText per filled (given) cell.
//   (b) the real hugmesh_sudoku_wasm.wasm is driven through the shared WasmHost
//       (the shell's marshalling), and a LOCAL input is applied THROUGH the
//       game's onLocalInput path: select an empty cell, place its correct
//       solution digit, and the engine state changes (the cell fills, the filled
//       count rises) — exactly the browser flow, minus the browser. A
//       conflicting digit and a given-cell overwrite are both REJECTED with no
//       engine mutation. This is SINGLE-PLAYER: no relay payload is ever emitted.
//
// To make the play-through deterministic the smoke loads the engine's fixed
// teaching puzzle via sudoku_from_str (the GAME's seeded sudoku_new is exercised
// by init()/the wasm functional test; here we want known cell coordinates).
//
// Run:  node games/sudoku/test/sudoku-frontend-smoke.test.mjs

import { readFile } from 'node:fs/promises';
import { fileURLToPath } from 'node:url';
import { dirname, join } from 'node:path';

const here = dirname(fileURLToPath(import.meta.url));
const ROOT = join(here, '..', '..', '..'); // repo root
const SUDOKU_DIR = join(here, '..');
const WASM_PATH = join(SUDOKU_DIR, 'wasm', 'hugmesh_sudoku_wasm.wasm');

const KNOWN_PUZZLE =
  '53..7....' +
  '6..195...' +
  '.98....6.' +
  '8...6...3' +
  '4..8.3..1' +
  '7...2...6' +
  '.6....28.' +
  '...419..5' +
  '....8..79';
const KNOWN_SOLUTION =
  '534678912' +
  '672195348' +
  '198342567' +
  '859761423' +
  '426853791' +
  '713924856' +
  '961537284' +
  '287419635' +
  '345286179';

// ── tiny assertion harness ───────────────────────────────────────────────────
let passed = 0;
const failures = [];
function assert(cond, msg) {
  if (cond) {
    passed++;
    console.log(`  PASS  ${msg}`);
  } else {
    failures.push(msg);
    console.error(`  FAIL  ${msg}`);
  }
}

// ── a RECORDING canvas 2D context (counts the draw calls) ─────────────────────
function makeRecordingContext() {
  const calls = { fillRect: 0, fillText: 0, strokeText: 0, arc: 0, fill: 0, stroke: 0, moveTo: 0, lineTo: 0 };
  const ctx = {
    _calls: calls,
    fillStyle: '#000',
    strokeStyle: '#000',
    lineWidth: 1,
    font: '',
    textAlign: 'start',
    textBaseline: 'alphabetic',
    fillRect() {
      calls.fillRect++;
    },
    fillText() {
      calls.fillText++;
    },
    strokeText() {
      calls.strokeText++;
    },
    beginPath() {},
    moveTo() {
      calls.moveTo++;
    },
    lineTo() {
      calls.lineTo++;
    },
    arc() {
      calls.arc++;
    },
    fill() {
      calls.fill++;
    },
    stroke() {
      calls.stroke++;
    },
    clearRect() {},
  };
  return ctx;
}

// ── a minimal DOM element (only what sudoku.js touches) ───────────────────────
function makeElement(tag) {
  const el = {
    tagName: (tag || 'div').toUpperCase(),
    id: '',
    style: {},
    width: 0,
    height: 0,
    disabled: false,
    textContent: '',
    children: [],
    _listeners: {},
    _ctx: null,
    classList: {
      _set: new Set(),
      toggle(c, on) {
        if (on === undefined) on = !this._set.has(c);
        if (on) this._set.add(c);
        else this._set.delete(c);
        return on;
      },
      contains(c) {
        return this._set.has(c);
      },
      add(c) {
        this._set.add(c);
      },
      remove(c) {
        this._set.delete(c);
      },
    },
    getContext() {
      if (!this._ctx) this._ctx = makeRecordingContext();
      return this._ctx;
    },
    getBoundingClientRect() {
      return { left: 0, top: 0, width: this.width || 540, height: this.height || 540 };
    },
    appendChild(c) {
      this.children.push(c);
      return c;
    },
    setAttribute(k, v) {
      this[k] = v;
    },
    getAttribute(k) {
      return this[k];
    },
    addEventListener(type, fn) {
      (this._listeners[type] = this._listeners[type] || []).push(fn);
    },
    removeEventListener() {},
    querySelector(sel) {
      const id = sel.replace('#', '');
      return this._byId[id] || null;
    },
    querySelectorAll() {
      return [];
    },
    dispatch(type, evt) {
      for (const fn of this._listeners[type] || []) fn(evt);
    },
  };
  return el;
}

function installDom() {
  const board = makeElement('canvas');
  board.id = 'board';
  board.width = 540;
  board.height = 540;

  const status = makeElement('span');
  const hint = makeElement('button');
  const reset = makeElement('button');

  const byId = { board, status, hint, reset };
  const container = makeElement('body');
  container._byId = byId;
  container._listeners = {};

  global.window = {
    innerWidth: 640,
    innerHeight: 720,
    addEventListener() {},
    removeEventListener() {},
    parent: undefined, // single-player (no distinct parent) → no network
  };
  global.window.parent = global.window;

  global.document = {
    body: container,
    createElement: (t) => makeElement(t),
    getElementById: (id) => byId[id] || null,
  };

  return { container, byId };
}

async function main() {
  console.log('DOM-LIGHT smoke (no browser, no jsdom, no network).');

  const { container, byId } = installDom();

  // Import the shell + game AS REAL ES MODULES (the same files the bundle ships).
  const { WasmHost } = await import(join(ROOT, 'games', '_shared', 'wasm-host.js'));
  const sudokuMod = await import(join(SUDOKU_DIR, 'sudoku.js'));

  // ── boot the real wasm engine through the shared WasmHost (loadBytes path) ──
  const bytes = await readFile(WASM_PATH);
  const host = new WasmHost(WASM_PATH);
  await host.loadBytes(bytes);
  assert(
    typeof host.exports.sudoku_place === 'function',
    'WasmHost bound the real hugmesh_sudoku_wasm.wasm (sudoku_place export present)',
  );

  // ── build the GAME and mount it WITHOUT the network (single-player) ─────────
  const GAME = sudokuMod.createSudokuGame();
  const ctx = {
    host,
    client: { sendInput() {}, sendControl() {}, sendWorld() {}, on() {}, connect() {}, close() {} },
    params: { sessionId: null, characterId: null, slotIndex: 0 },
    isHost: true,
    container,
    GAME,
  };
  let lastRelayed = null;
  ctx.handleLocalInput = (evt) => {
    const r = GAME.onLocalInput(evt, ctx);
    if (r && r.state) GAME.render(r.state, ctx);
    // Capture any relay payload the game emitted — for single-player there must
    // be NONE (no input/world/control).
    if (r && (r.input || r.world || r.control)) lastRelayed = r;
    return r;
  };
  ctx.onTeardown = () => {};

  // init() builds the DOM + seeds the GAME's deterministic puzzle.
  const initial = await GAME.init(host, container, ctx);
  assert(
    initial && initial.type === 'sudoku.snapshot' && initial.status === 'playing',
    'GAME.init() returns a playing sudoku.snapshot',
  );
  assert(
    initial.givenCount >= 17 && initial.givenCount <= 81,
    `init seeded a puzzle with a sane given count (got ${initial.givenCount})`,
  );

  // For DETERMINISTIC cell coordinates, override the engine with the fixed
  // teaching puzzle, then render that.
  function loadKnown() {
    const enc = new TextEncoder();
    const buf = enc.encode(KNOWN_PUZZLE);
    const ptr = host.exports.alloc(buf.length);
    new Uint8Array(host.memory.buffer, ptr, buf.length).set(buf);
    const ok = host.exports.sudoku_from_str(ptr, buf.length);
    host.exports.dealloc(ptr, buf.length);
    return ok;
  }
  assert(loadKnown() === 1, 'loaded the fixed teaching puzzle for deterministic coordinates');
  const known = host.callPacked('sudoku_state');
  assert(known.board === KNOWN_PUZZLE.replace(/0/g, '.'), 'engine board is the known puzzle');

  // ── (a) render() draw-call counts for the known start position ──────────────
  const cx = byId.board.getContext('2d');
  cx._calls.fillRect = 0;
  cx._calls.stroke = 0;
  cx._calls.fillText = 0;
  GAME.render(known, ctx);

  assert(
    cx._calls.fillRect >= 1,
    `render() filled the board background (fillRect=${cx._calls.fillRect})`,
  );
  // 10 vertical + 10 horizontal grid lines = 20 stroke() calls.
  assert(
    cx._calls.stroke === 20,
    `render() stroked the 9x9 grid as 20 lines (stroke=${cx._calls.stroke})`,
  );
  // One fillText per filled cell — the known puzzle has 30 givens.
  const givenCount = [...KNOWN_PUZZLE].filter((c) => c >= '1' && c <= '9').length;
  assert(
    cx._calls.fillText === givenCount,
    `render() drew one numeral per given (fillText=${cx._calls.fillText}, givens=${givenCount})`,
  );

  // HUD reflects the start position.
  assert(
    byId.status.textContent.includes('to go'),
    `HUD status shows cells remaining (got "${byId.status.textContent}")`,
  );

  // ── (b) a LOCAL input applied through the REAL wasm changes state ───────────
  const cell = GAME._ui.cell;
  const pad = GAME._ui.padding;
  assert(cell > 0, `board laid out with a positive cell size (cell=${cell})`);

  function clickCell(row, col) {
    const offsetX = pad + col * cell + Math.floor(cell / 2);
    const offsetY = pad + row * cell + Math.floor(cell / 2);
    return ctx.handleLocalInput({ type: 'click', offsetX, offsetY });
  }

  // Select the empty cell (0,2) — its correct solution digit is 4.
  const pick = clickCell(0, 2);
  assert(pick && pick.state, 'clicking a cell selects it (returns state)');
  assert(GAME._ui.selRow === 0 && GAME._ui.selCol === 2, 'selection is (0,2)');

  // A CONFLICTING digit (5 is in row 0) is rejected — no engine mutation.
  const beforeBad = host.callPacked('sudoku_state').board;
  const bad = ctx.handleLocalInput({ type: 'digit', digit: 5 });
  assert(bad && bad.state, 'a digit keypress returns state');
  assert(
    host.callPacked('sudoku_state').board === beforeBad,
    'a conflicting digit did NOT mutate the engine',
  );
  assert(
    byId.status.textContent.toLowerCase().includes('conflict'),
    `HUD flashed a conflict message (got "${byId.status.textContent}")`,
  );

  // The CORRECT digit (4) IS accepted — the cell fills, filled count rises.
  const filledBefore = host.callPacked('sudoku_state').filled;
  cx._calls.fillText = 0;
  ctx.handleLocalInput({ type: 'digit', digit: 4 });
  const after = host.callPacked('sudoku_state');
  assert(after.board.charAt(2) === '4', `the correct 4 landed at (0,2) (board[2]="${after.board.charAt(2)}")`);
  assert(
    after.filled === filledBefore + 1,
    `filled count rose by one (${filledBefore} → ${after.filled})`,
  );
  assert(
    cx._calls.fillText === givenCount + 1,
    `post-place render drew one extra numeral (fillText=${cx._calls.fillText})`,
  );

  // A GIVEN cell cannot be overwritten: select (0,0)=5, try to place 1.
  clickCell(0, 0);
  const givenBoard = host.callPacked('sudoku_state').board;
  ctx.handleLocalInput({ type: 'digit', digit: 1 });
  assert(
    host.callPacked('sudoku_state').board === givenBoard,
    'placing into a given cell did NOT mutate the engine',
  );

  // Erase the player entry at (0,2) — it clears; a given erase is a no-op.
  clickCell(0, 2);
  ctx.handleLocalInput({ type: 'erase' });
  assert(
    host.callPacked('sudoku_state').board.charAt(2) === '.',
    'erasing the player entry at (0,2) cleared it',
  );

  // ── SINGLE-PLAYER guarantee: no relay payload was ever emitted ──────────────
  assert(
    lastRelayed === null,
    'no input/world/control relay payload was emitted (single-player, local-only)',
  );

  // ── solve the whole puzzle locally → status flips to "solved" ───────────────
  loadKnown();
  for (let i = 0; i < 81; i++) {
    const r = Math.floor(i / 9);
    const c = i % 9;
    if (host.exports.sudoku_is_given(r, c) === 1) continue;
    clickCell(r, c);
    ctx.handleLocalInput({ type: 'digit', digit: KNOWN_SOLUTION.charCodeAt(i) - 48 });
  }
  const solved = host.callPacked('sudoku_state');
  assert(solved.status === 'solved', `completing the grid wins (status="${solved.status}")`);
  assert(
    sudokuMod.isSolved(GAME._getState().status),
    'GAME state reports solved',
  );
  assert(
    byId.status.textContent.toLowerCase().includes('solved'),
    `HUD shows a solved message (got "${byId.status.textContent}")`,
  );

  // ── report ──────────────────────────────────────────────────────────────────
  console.log(`\n${passed} assertions passed, ${failures.length} failed.`);
  if (failures.length > 0) {
    console.error('FAILURES:\n  - ' + failures.join('\n  - '));
    process.exit(1);
  }
  console.log('OK — Sudoku frontend renders the grid and applies local input through the real wasm.');
}

main().catch((e) => {
  console.error('smoke crashed:', e);
  process.exit(1);
});
