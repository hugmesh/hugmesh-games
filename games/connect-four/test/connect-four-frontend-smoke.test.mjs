// games/connect-four/test/connect-four-frontend-smoke.test.mjs
//
// HEADLESS SMOKE for the Connect Four web frontend — the DOM-LIGHT path,
// mirroring games/chess/test/chess-frontend-smoke.test.mjs one-for-one.
//
// WHY this path: Playwright/jsdom aren't installed here (browser binaries +
// libs absent; installing would mean a network download out of scope for a
// smoke test). So we run the pure-node DOM-light smoke: a hand-rolled minimal
// DOM + a RECORDING 2D canvas context. Deterministic, zero-install, and proves:
//
//   (a) connect-four.js is loaded as a real ES module against a mocked canvas
//       2D context; render() of the start position issues the EXPECTED draw-call
//       counts — 1 board fillRect + 42 cell arcs/fills (one per hole) and ZERO
//       disc strokes on the empty board.
//   (b) the real hugmesh_connect_four.wasm is driven through the shared
//       WasmHost (the shell's marshalling), and a drop is applied THROUGH the
//       game's onLocalInput click path: the board canvas is repainted, the board
//       string changes, and the side-to-move flips red→yellow — exactly the
//       browser flow, minus the browser.
//
// Run:  node games/connect-four/test/connect-four-frontend-smoke.test.mjs

import { readFile } from 'node:fs/promises';
import { fileURLToPath } from 'node:url';
import { dirname, join } from 'node:path';

const here = dirname(fileURLToPath(import.meta.url));
const ROOT = join(here, '..', '..', '..'); // repo root
const C4_DIR = join(here, '..');
const WASM_PATH = join(C4_DIR, 'wasm', 'hugmesh_connect_four.wasm');

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
  const calls = { fillRect: 0, fillText: 0, strokeText: 0, arc: 0, fill: 0, stroke: 0 };
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

// ── a minimal DOM element (only what connect-four.js touches) ─────────────────
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
      return { left: 0, top: 0, width: this.width || 616, height: this.height || 532 };
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

// ── install a minimal global DOM the shell + connect-four.js need ─────────────
function installDom() {
  const board = makeElement('canvas');
  board.id = 'board';
  board.width = 616;
  board.height = 532;

  const turn = makeElement('span');
  const status = makeElement('span');
  const reset = makeElement('button');

  const byId = { board, turn, status, reset };
  const container = makeElement('body');
  container._byId = byId;
  container._listeners = {};

  global.window = {
    innerWidth: 640,
    innerHeight: 640,
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
  const c4Mod = await import(join(C4_DIR, 'connect-four.js'));

  // ── boot the real wasm engine through the shared WasmHost (loadBytes path) ──
  const bytes = await readFile(WASM_PATH);
  const host = new WasmHost(WASM_PATH);
  await host.loadBytes(bytes);
  assert(
    typeof host.exports.c4_apply_drop === 'function',
    'WasmHost bound the real hugmesh_connect_four.wasm (c4_apply_drop export present)',
  );

  // Sanity: the marshalling contract works through WasmHost.
  assert(host.callStatus('c4_new') === 1, 'WasmHost.callStatus(c4_new) === 1');
  const startLegal = host.callPacked('c4_legal');
  assert(
    Array.isArray(startLegal) && startLegal.length === 7,
    `WasmHost.callPacked(c4_legal) === 7 columns at start (got ${startLegal && startLegal.length})`,
  );
  const startState = host.callPacked('c4_state');
  assert(
    startState.type === 'connect_four.state' && startState.toMove === 'red',
    'WasmHost.callPacked(c4_state) is the start position, red to move',
  );

  // ── build the GAME and mount it WITHOUT the network (no session_id) ─────────
  const GAME = c4Mod.createConnectFourGame();
  const ctx = {
    host,
    client: { sendInput() {}, sendControl() {}, sendWorld() {}, on() {}, connect() {}, close() {} },
    params: { sessionId: null, characterId: null, slotIndex: 0 },
    isHost: true,
    container,
    GAME,
  };
  ctx.handleLocalInput = (evt) => {
    const r = GAME.onLocalInput(evt, ctx);
    if (r && r.state) GAME.render(r.state, ctx);
    return r;
  };

  const initial = await GAME.init(host, container, ctx);
  assert(
    initial && initial.board === '......./......./......./......./......./.......',
    'GAME.init() returns the empty start-position state',
  );
  assert(initial.toMove === 'red', 'start state: red to move');

  // ── (a) render() draw-call counts for the start position ────────────────────
  const cx = byId.board.getContext('2d');
  cx._calls.fillRect = 0;
  cx._calls.arc = 0;
  cx._calls.fill = 0;
  cx._calls.stroke = 0;
  GAME.render(initial, ctx);

  assert(
    cx._calls.fillRect >= 1,
    `render() filled the board frame (fillRect=${cx._calls.fillRect})`,
  );
  assert(
    cx._calls.arc === 42 && cx._calls.fill === 42,
    `render() drew exactly 42 cell holes for the empty board (arc=${cx._calls.arc} fill=${cx._calls.fill})`,
  );
  assert(
    cx._calls.stroke === 0,
    `render() stroked no discs on the empty board (stroke=${cx._calls.stroke})`,
  );

  // HUD reflects the start position.
  assert(byId.turn.textContent.includes('Red'), `HUD turn shows Red (got "${byId.turn.textContent}")`);
  assert(
    byId.status.textContent === 'Red to move',
    `HUD status shows "Red to move" (got "${byId.status.textContent}")`,
  );

  // ── (b) simulate a click in column 3 and assert the drop applied ────────────
  const cell = GAME._ui.cell;
  const pad = GAME._ui.padding;
  assert(cell > 0, `board laid out with a positive cell size (cell=${cell})`);

  function clickColumn(col) {
    const offsetX = pad + col * cell + Math.floor(cell / 2);
    const offsetY = pad + cell; // anywhere inside the board vertically
    return ctx.handleLocalInput({ type: 'click', offsetX, offsetY });
  }

  cx._calls.arc = 0;
  cx._calls.fill = 0;
  cx._calls.stroke = 0;
  const moveResult = clickColumn(3);
  assert(moveResult && moveResult.input, 'click produced a relay input payload');
  assert(
    moveResult.input.payload.col === 3,
    `relayed column is 3 (got ${moveResult.input && moveResult.input.payload && moveResult.input.payload.col})`,
  );
  assert(
    moveResult.world && typeof moveResult.world.board === 'string',
    'click produced a host-authoritative world broadcast with a board',
  );

  // The engine state flipped: side-to-move now yellow, board changed, a disc landed.
  const after = host.callPacked('c4_state');
  assert(
    after.board !== '......./......./......./......./......./.......',
    `board changed after dropping col 3 (now "${after.board}")`,
  );
  assert(
    after.toMove === 'yellow',
    `side-to-move flipped red→yellow after the drop (got "${after.toMove}")`,
  );
  // The disc landed in the BOTTOM cell of column 3 (engine row 0 = the last
  // segment of the board string, gravity-correct). board rows are top→bottom.
  const bottomRow = after.board.split('/')[c4Mod.ROWS - 1];
  assert(
    bottomRow[3] === 'R',
    `the Red disc landed at the bottom of column 3 (bottom row "${bottomRow}")`,
  );
  // The post-move render drew exactly one disc edge stroke (one disc on the board).
  assert(
    cx._calls.stroke === 1,
    `post-move render stroked exactly 1 disc (stroke=${cx._calls.stroke})`,
  );
  assert(
    cx._calls.arc === 42 && cx._calls.fill === 42,
    `post-move render still drew all 42 cells (arc=${cx._calls.arc} fill=${cx._calls.fill})`,
  );

  // ── full-column guard: clicking a full column does nothing destructive ──────
  // Fill column 0 to the brim first (6 discs), then a 7th click must be a no-op.
  for (let i = 0; i < 6; i++) clickColumn(0);
  const beforeFull = host.callPacked('c4_state').board;
  const fullClick = clickColumn(0); // 7th into a 6-cell column
  assert(
    fullClick && !fullClick.input,
    'clicking a full column produced no relay input (rejected client-side)',
  );
  assert(
    host.callPacked('c4_state').board === beforeFull,
    'clicking a full column did not mutate the engine',
  );

  // ── reset path returns to the empty board ───────────────────────────────────
  const resetResult = ctx.handleLocalInput({ type: 'reset' });
  assert(
    resetResult && resetResult.input && resetResult.input.type === 'reset',
    'reset produced a relayed reset control',
  );
  assert(
    host.callPacked('c4_state').board === '......./......./......./......./......./.......',
    'reset re-seeded the engine to the empty board',
  );
  assert(
    c4Mod.isGameOver(GAME._getState().status) === false,
    'reset state is not game-over',
  );

  // ── report ──────────────────────────────────────────────────────────────────
  console.log(`\n${passed} assertions passed, ${failures.length} failed.`);
  if (failures.length > 0) {
    console.error('FAILURES:\n  - ' + failures.join('\n  - '));
    process.exit(1);
  }
  console.log('OK — Connect Four frontend renders the board and applies a drop through the real wasm.');
}

main().catch((e) => {
  console.error('smoke crashed:', e);
  process.exit(1);
});
