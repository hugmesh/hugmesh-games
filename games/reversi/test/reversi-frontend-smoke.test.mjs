// games/reversi/test/reversi-frontend-smoke.test.mjs
//
// HEADLESS SMOKE for the reversi web frontend — the DOM-LIGHT path.
//
// WHY this path (documented per the task, same as chess): Playwright reports a
// version but is NOT usable here — its browser binaries are not installed and
// neither `playwright` nor `@playwright/test` resolves as a module; jsdom is not
// installed either. Installing either means a network download out of scope for a
// smoke test. So we run the pure-node DOM-light smoke: a hand-rolled minimal DOM +
// a RECORDING 2D canvas context. Fully deterministic, zero-install, and proves the
// two required things:
//
//   (a) reversi.js is loaded as a real ES module against a mocked canvas 2D
//       context; render() of the opening position issues the EXPECTED draw-call
//       counts — 64 board fillRect (felt) + 4 disc arcs + the 4 legal-move hint
//       dots for the side to move.
//   (b) the real snapdragon_reversi_wasm.wasm is driven through the shared
//       WasmHost (the shell's marshalling), and a move (Black plays d3) is applied
//       THROUGH the game's onLocalInput single-click path: the board canvas is
//       repainted, the board string changes (d4 flips to Black), the score updates
//       (4–1), and the turn flips black→white — exactly the browser flow, minus
//       the browser.
//
// Run:  node games/reversi/test/reversi-frontend-smoke.test.mjs

import { readFile } from 'node:fs/promises';
import { fileURLToPath } from 'node:url';
import { dirname, join } from 'node:path';

const here = dirname(fileURLToPath(import.meta.url));
const ROOT = join(here, '..', '..', '..'); // repo root
const REVERSI_DIR = join(here, '..');
const WASM_PATH = join(REVERSI_DIR, 'wasm', 'snapdragon_reversi_wasm.wasm');

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
  const calls = { fillRect: 0, strokeRect: 0, fillText: 0, arc: 0, fill: 0, stroke: 0 };
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
    strokeRect() {
      calls.strokeRect++;
    },
    fillText() {
      calls.fillText++;
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

// ── a minimal DOM element (only what reversi.js touches) ──────────────────────
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
      return { left: 0, top: 0, width: this.width || 384, height: this.height || 384 };
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

// ── install a minimal global DOM the shell + reversi.js need ──────────────────
function installDom() {
  const board = makeElement('canvas');
  board.id = 'board';
  board.width = 384;
  board.height = 384;

  const turn = makeElement('span');
  const score = makeElement('span');
  const status = makeElement('span');
  const pass = makeElement('button');

  const byId = { board, turn, score, status, pass };
  const container = makeElement('body');
  container._byId = byId;
  container._listeners = {};

  global.window = {
    innerWidth: 480,
    innerHeight: 480,
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
  const reversiMod = await import(join(REVERSI_DIR, 'reversi.js'));

  // ── boot the real wasm engine through the shared WasmHost (loadBytes path) ──
  const bytes = await readFile(WASM_PATH);
  const host = new WasmHost(WASM_PATH);
  await host.loadBytes(bytes);
  assert(
    typeof host.exports.reversi_apply === 'function',
    'WasmHost bound the real snapdragon_reversi_wasm.wasm (reversi_apply export present)',
  );

  // Sanity: the marshalling contract works through WasmHost (proves the shell
  // drives the real .wasm, not a re-implementation).
  assert(host.callStatus('reversi_new') === 1, 'WasmHost.callStatus(reversi_new) === 1');
  const openMoves = host.callPacked('reversi_legal');
  assert(
    Array.isArray(openMoves) && openMoves.length === 4,
    `WasmHost.callPacked(reversi_legal) === 4 openings (got ${openMoves && openMoves.length})`,
  );
  const openSnap = host.callPacked('reversi_state');
  assert(
    openSnap && openSnap.board && openSnap.board.length === 64 && openSnap.side === 'black',
    'WasmHost.callPacked(reversi_state) is the opening snapshot (Black to move)',
  );

  // ── build the GAME and mount it WITHOUT the network (no session_id) ─────────
  const GAME = reversiMod.createReversiGame();
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
  assert(initial && initial.board === openSnap.board, 'GAME.init() returns the opening-position state');
  assert(initial.black === 2 && initial.white === 2, 'GAME.init() reports the 2–2 opening score');

  // ── (a) render() draw-call counts for the opening position ──────────────────
  const cx = byId.board.getContext('2d');
  cx._calls.fillRect = 0;
  cx._calls.arc = 0;
  cx._calls.fill = 0;
  GAME.render(initial, ctx);

  assert(
    cx._calls.fillRect >= 64,
    `render() painted >= 64 felt squares (fillRect=${cx._calls.fillRect})`,
  );
  // 4 opening discs (arc+fill each) + 4 legal-move hint dots (arc+fill each).
  assert(
    cx._calls.arc === 8,
    `render() drew 4 discs + 4 legal-move dots = 8 arcs at the opening (arc=${cx._calls.arc})`,
  );
  assert(
    cx._calls.fill === 8,
    `render() filled 4 discs + 4 hint dots = 8 (fill=${cx._calls.fill})`,
  );

  // HUD reflects the opening position.
  assert(byId.turn.textContent.includes('Black'), `HUD turn shows Black (got "${byId.turn.textContent}")`);
  assert(
    byId.status.textContent === 'Black to move',
    `HUD status shows "Black to move" (got "${byId.status.textContent}")`,
  );
  assert(
    byId.score.textContent === 'Black 2 – 2 White',
    `HUD score shows the 2–2 opening (got "${byId.score.textContent}")`,
  );
  // Pass is disabled at the opening (Black has 4 legal moves).
  assert(byId.pass.disabled === true, 'Pass button disabled at the opening (a move exists)');

  // ── (b) simulate a click on d3 and assert the move applied ──────────────────
  const cell = GAME._ui.cell;
  assert(cell > 0, `board laid out with a positive cell size (cell=${cell})`);

  // d3 = file d (col 3), rank 3 → rankIdx 2 → rowFromTop = 7 - 2 = 5.
  function clickSquare(col, rowFromTop) {
    const offsetX = col * cell + Math.floor(cell / 2);
    const offsetY = rowFromTop * cell + Math.floor(cell / 2);
    return ctx.handleLocalInput({ type: 'click', offsetX, offsetY });
  }

  const startBoard = host.callPacked('reversi_state').board;
  cx._calls.fillRect = 0;
  cx._calls.arc = 0;
  cx._calls.fill = 0;
  const moveResult = clickSquare(3, 5); // d3
  assert(moveResult && moveResult.input, 'clicking d3 produced a relay input payload');
  assert(
    moveResult.input.payload.square === 'd3',
    `relayed square is "d3" (got "${moveResult.input && moveResult.input.payload && moveResult.input.payload.square}")`,
  );
  assert(
    moveResult.world && moveResult.world.board,
    'click produced a host-authoritative world broadcast with a board',
  );

  // The engine state flipped: turn now white, board changed, d4 flipped to Black.
  const afterBoard = host.callPacked('reversi_state').board;
  assert(afterBoard !== startBoard, 'board string changed after d3');
  // square index for d4 = file d (3) + rankIdx 3 (rank 4) → 3 + 3*8 = 27.
  assert(afterBoard[27] === 'B', `d4 flipped to Black after d3 (board[27]="${afterBoard[27]}")`);
  const afterSide = host.callPacked('reversi_state').side;
  assert(afterSide === 'white', `side-to-move flipped black→white after d3 (got "${afterSide}")`);

  // The post-move state reports the updated 4–1 score.
  assert(
    GAME._getState().black === 4 && GAME._getState().white === 1,
    `score updated to 4–1 after d3 (got ${GAME._getState().black}–${GAME._getState().white})`,
  );
  assert(
    byId.score.textContent === 'Black 4 – 1 White',
    `HUD score shows "Black 4 – 1 White" after d3 (got "${byId.score.textContent}")`,
  );
  assert(byId.turn.textContent.includes('White'), `HUD turn now shows White (got "${byId.turn.textContent}")`);

  // The post-move render repainted the board (felt + the now-5 discs).
  assert(cx._calls.fillRect >= 64, `post-move render repainted the 64 felt squares (fillRect=${cx._calls.fillRect})`);
  // 5 discs now (4 seeded + d3 placed; d4 re-coloured not added) + N legal dots.
  assert(cx._calls.arc >= 5, `post-move render drew >= 5 disc arcs (arc=${cx._calls.arc})`);

  // ── illegal-click guard: clicking a non-legal square does nothing ───────────
  const before = host.callPacked('reversi_state').board;
  // a1 (col 0, rowFromTop 7) is empty and brackets nothing → not in legal set.
  const noop = clickSquare(0, 7);
  assert(noop === null, 'clicking a non-legal square returns null (no move)');
  assert(host.callPacked('reversi_state').board === before, 'illegal click did not mutate the engine');

  // ── report ──────────────────────────────────────────────────────────────────
  console.log(`\n${passed} assertions passed, ${failures.length} failed.`);
  if (failures.length > 0) {
    console.error('FAILURES:\n  - ' + failures.join('\n  - '));
    process.exit(1);
  }
  console.log('OK — reversi frontend renders the board and applies a move through the real wasm.');
}

main().catch((e) => {
  console.error('smoke crashed:', e);
  process.exit(1);
});
