// games/go/test/go-frontend-smoke.test.mjs
//
// HEADLESS SMOKE for the Go web frontend — the DOM-LIGHT path, mirroring
// games/connect-four/test/connect-four-frontend-smoke.test.mjs and
// games/gomoku/test/gomoku-frontend-smoke.test.mjs one-for-one.
//
// WHY this path: Playwright/jsdom aren't installed here. So we run the pure-node
// DOM-light smoke: a hand-rolled minimal DOM + a RECORDING 2D canvas context.
// Deterministic, zero-install, and proves:
//
//   (a) go.js is loaded as a real ES module against a mocked canvas 2D context;
//       render() of the start position issues the EXPECTED draw-call counts —
//       1 board fillRect + 18 grid-line strokes (9 horizontal + 9 vertical) +
//       5 hoshi arcs (9x9 star points) and ZERO stone strokes on the empty board.
//   (b) the real hugmesh_go.wasm is driven through the shared WasmHost (the
//       shell's marshalling), and a placement is applied THROUGH the game's
//       onLocalInput click path: the board canvas is repainted, the board string
//       changes, the side-to-move flips black→white, and exactly one stone is
//       drawn — exactly the browser flow, minus the browser.
//
// Run:  node games/go/test/go-frontend-smoke.test.mjs

import { readFile } from 'node:fs/promises';
import { fileURLToPath } from 'node:url';
import { dirname, join } from 'node:path';

const here = dirname(fileURLToPath(import.meta.url));
const ROOT = join(here, '..', '..', '..'); // repo root
const GO_DIR = join(here, '..');
const WASM_PATH = join(GO_DIR, 'wasm', 'hugmesh_go.wasm');

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
    fillRect() { calls.fillRect++; },
    fillText() { calls.fillText++; },
    strokeText() { calls.strokeText++; },
    beginPath() {},
    moveTo() { calls.moveTo++; },
    lineTo() { calls.lineTo++; },
    arc() { calls.arc++; },
    fill() { calls.fill++; },
    stroke() { calls.stroke++; },
    clearRect() {},
  };
  return ctx;
}

// ── a minimal DOM element (only what go.js touches) ───────────────────────────
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
      contains(c) { return this._set.has(c); },
      add(c) { this._set.add(c); },
      remove(c) { this._set.delete(c); },
    },
    getContext() {
      if (!this._ctx) this._ctx = makeRecordingContext();
      return this._ctx;
    },
    getBoundingClientRect() {
      return { left: 0, top: 0, width: this.width || 540, height: this.height || 540 };
    },
    appendChild(c) { this.children.push(c); return c; },
    setAttribute(k, v) { this[k] = v; },
    getAttribute(k) { return this[k]; },
    addEventListener(type, fn) {
      (this._listeners[type] = this._listeners[type] || []).push(fn);
    },
    removeEventListener() {},
    querySelector(sel) {
      const id = sel.replace('#', '');
      return this._byId[id] || null;
    },
    querySelectorAll() { return []; },
    dispatch(type, evt) {
      for (const fn of this._listeners[type] || []) fn(evt);
    },
  };
  return el;
}

// ── install a minimal global DOM the shell + go.js need ───────────────────────
function installDom() {
  const board = makeElement('canvas');
  board.id = 'board';
  board.width = 540;
  board.height = 540;

  const turn = makeElement('span');
  const status = makeElement('span');
  const score = makeElement('span');
  const pass = makeElement('button');

  const byId = { board, turn, status, score, pass };
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
  const goMod = await import(join(GO_DIR, 'go.js'));

  // ── boot the real wasm engine through the shared WasmHost (loadBytes path) ──
  const bytes = await readFile(WASM_PATH);
  const host = new WasmHost(WASM_PATH);
  await host.loadBytes(bytes);
  assert(
    typeof host.exports.go_apply === 'function',
    'WasmHost bound the real hugmesh_go.wasm (go_apply export present)',
  );

  // Sanity: the marshalling contract works through WasmHost.
  assert(host.callStatus('go_new') === 1, 'WasmHost.callStatus(go_new) === 1');
  const startLegal = host.callPacked('go_legal');
  assert(
    Array.isArray(startLegal) && startLegal.length === 81,
    `WasmHost.callPacked(go_legal) === 81 points at start (got ${startLegal && startLegal.length})`,
  );
  const startSnap = host.callPacked('go_state');
  assert(
    startSnap.type === 'go.snapshot' && startSnap.side === 'black' && startSnap.size === 9,
    'WasmHost.callPacked(go_state) is the empty 9x9 start position, black to move',
  );

  // ── build the GAME and mount it WITHOUT the network (no session_id) ─────────
  const GAME = goMod.createGoGame();
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
    initial && initial.board === '.'.repeat(81),
    'GAME.init() returns the empty start-position state',
  );
  assert(initial.side === 'black', 'start state: black to move');

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
  // 9 horizontal + 9 vertical grid lines = 18 stroke calls; no stones yet.
  assert(
    cx._calls.stroke === 18,
    `render() stroked exactly the 18 grid lines on the empty board (stroke=${cx._calls.stroke})`,
  );
  // 5 hoshi (4 corner 3-3 + tengen) on a 9x9 → 5 arcs + 5 fills, no stone discs.
  assert(
    cx._calls.arc === 5 && cx._calls.fill === 5,
    `render() drew exactly the 5 hoshi dots on the empty board (arc=${cx._calls.arc} fill=${cx._calls.fill})`,
  );

  // HUD reflects the start position.
  assert(byId.turn.textContent.includes('Black'), `HUD turn shows Black (got "${byId.turn.textContent}")`);
  assert(
    byId.status.textContent === 'Black to move',
    `HUD status shows "Black to move" (got "${byId.status.textContent}")`,
  );
  assert(
    byId.score.textContent.includes('Black: 0') && byId.score.textContent.includes('White: 0'),
    `HUD captures shows 0/0 (got "${byId.score.textContent}")`,
  );

  // ── (b) simulate a click on the centre intersection through onLocalInput ────
  const cell = GAME._ui.cell;
  const margin = GAME._ui.margin;
  assert(cell > 0, `board laid out with a positive cell size (cell=${cell})`);

  // Click the centre point e5 (col 4, row 4 in engine coords). The draw maps
  // row 0 to the BOTTOM, so centre is drawRow = 9-1-4 = 4 → same pixel as col 4.
  function clickPoint(col, row) {
    const drawRow = 9 - 1 - row;
    const offsetX = margin + col * cell;
    const offsetY = margin + drawRow * cell;
    return ctx.handleLocalInput({ type: 'click', offsetX, offsetY });
  }

  cx._calls.arc = 0;
  cx._calls.fill = 0;
  cx._calls.stroke = 0;
  const moveResult = clickPoint(4, 4); // e5
  assert(moveResult && moveResult.input, 'click produced a relay input payload');
  assert(
    moveResult.input.payload.move === 'e5',
    `relayed move is the centre point e5 (got ${moveResult.input && moveResult.input.payload && moveResult.input.payload.move})`,
  );
  assert(
    moveResult.world && typeof moveResult.world.board === 'string',
    'click produced a host-authoritative world broadcast with a board',
  );

  // The engine state flipped: side-to-move now white, board changed, a stone landed.
  const after = host.callPacked('go_state');
  assert(
    after.board !== '.'.repeat(81),
    `board changed after placing at e5 (now "${after.board}")`,
  );
  assert(
    after.side === 'white',
    `side-to-move flipped black→white after the placement (got "${after.side}")`,
  );
  // The stone landed at e5: idx = row4*9 + col4 = 40.
  const idxE5 = 4 * 9 + 4;
  assert(
    after.board[idxE5] === 'B',
    `the Black stone landed at e5 (board[40]="${after.board[idxE5]}")`,
  );
  // The post-move render drew the 18 grid lines + the placed stone's outline +
  // the last-move RING that go.js draws around the most-recent stone (a stroked
  // arc) = 20 strokes.
  assert(
    cx._calls.stroke === 20,
    `post-move render stroked the 18 grid lines + 1 stone outline + 1 last-move ring (stroke=${cx._calls.stroke})`,
  );
  // 5 hoshi arcs + 1 stone arc + 1 last-move-ring arc = 7 arcs; 5 hoshi + 1 stone
  // fill = 6 fills (the ring is stroked, not filled).
  assert(
    cx._calls.arc === 7 && cx._calls.fill === 6,
    `post-move render drew the 5 hoshi + 1 stone + 1 ring arc, filling 5 hoshi + 1 stone (arc=${cx._calls.arc} fill=${cx._calls.fill})`,
  );

  // ── occupied-point guard: clicking the same point does nothing destructive ──
  // Mirrors reversi's illegal-click contract: onLocalInput returns null for a
  // click that maps to no legal placement (here an already-occupied point), so
  // nothing is relayed and the engine is untouched.
  const beforeOcc = host.callPacked('go_state').board;
  const occClick = clickPoint(4, 4); // e5 is now occupied
  assert(
    occClick === null,
    `clicking an occupied point returns null (no move, nothing relayed) (got ${JSON.stringify(occClick)})`,
  );
  assert(
    host.callPacked('go_state').board === beforeOcc,
    'clicking an occupied point did not mutate the engine',
  );

  // ── pass path relays a pass control and keeps the game ongoing ──────────────
  const passResult = ctx.handleLocalInput({ type: 'pass' });
  assert(
    passResult && passResult.input && passResult.input.payload.move === 'pass',
    'Pass produced a relayed move control with payload "pass"',
  );
  assert(
    goMod.isGameOver(GAME._getState().status) === false,
    'after a single pass the game is not over',
  );
  assert(
    GAME._getState().lastMove === 'pass',
    `state lastMove records the pass (got "${GAME._getState().lastMove}")`,
  );

  // ── report ──────────────────────────────────────────────────────────────────
  console.log(`\n${passed} assertions passed, ${failures.length} failed.`);
  if (failures.length > 0) {
    console.error('FAILURES:\n  - ' + failures.join('\n  - '));
    process.exit(1);
  }
  console.log('OK — Go frontend renders the board and applies a placement through the real wasm.');
}

main().catch((e) => {
  console.error('smoke crashed:', e);
  process.exit(1);
});
