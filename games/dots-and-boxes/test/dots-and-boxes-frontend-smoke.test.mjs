// games/dots-and-boxes/test/dots-and-boxes-frontend-smoke.test.mjs
//
// HEADLESS SMOKE for the dots-and-boxes web frontend — the DOM-LIGHT path
// (same approach as games/chess/test/chess-frontend-smoke.test.mjs).
//
// WHY this path: Playwright's browser binaries are not installed here and jsdom
// is not available; installing either means a network download out of scope for
// a smoke test. So we run the pure-node DOM-light smoke: a hand-rolled minimal
// DOM + a RECORDING 2D canvas context. It is deterministic, zero-install, and
// proves the two required things:
//
//   (a) dots-and-boxes.js is loaded as a real ES module against a mocked canvas
//       2D context; render() of the START position issues the EXPECTED draw-call
//       counts — 24 edge strokes (all edges open) + 16 dots (arc+fill) and ZERO
//       owned-box fills.
//   (b) the real hugmesh_dots_and_boxes.wasm is driven through the shared
//       WasmHost; a click near an edge, fed through the game's onLocalInput,
//       claims that edge AUTHORITATIVELY: the board edge flips to claimed, the
//       turn flips player one→two, and the relay payloads (control claim +
//       world board) are produced — exactly the browser flow, minus the browser.
//
// Run:  node games/dots-and-boxes/test/dots-and-boxes-frontend-smoke.test.mjs

import { readFile } from 'node:fs/promises';
import { fileURLToPath } from 'node:url';
import { dirname, join } from 'node:path';

const here = dirname(fileURLToPath(import.meta.url));
const ROOT = join(here, '..', '..', '..'); // repo root
const GAME_DIR = join(here, '..');
const WASM_PATH = join(GAME_DIR, 'wasm', 'hugmesh_dots_and_boxes.wasm');

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
  const calls = { fillRect: 0, fillText: 0, stroke: 0, arc: 0, fill: 0, clearRect: 0 };
  const ctx = {
    _calls: calls,
    fillStyle: '#000',
    strokeStyle: '#000',
    lineWidth: 1,
    lineCap: 'butt',
    font: '',
    textAlign: 'start',
    textBaseline: 'alphabetic',
    fillRect() { calls.fillRect++; },
    fillText() { calls.fillText++; },
    clearRect() { calls.clearRect++; },
    beginPath() {},
    moveTo() {},
    lineTo() {},
    stroke() { calls.stroke++; },
    arc() { calls.arc++; },
    fill() { calls.fill++; },
  };
  return ctx;
}

// ── a minimal DOM element (only what dots-and-boxes.js touches) ───────────────
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
        if (on) this._set.add(c); else this._set.delete(c);
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
      return { left: 0, top: 0, width: this.width || 480, height: this.height || 480 };
    },
    appendChild(c) { this.children.push(c); return c; },
    setAttribute(k, v) { this[k] = v; },
    getAttribute(k) { return this[k]; },
    addEventListener(type, fn) { (this._listeners[type] = this._listeners[type] || []).push(fn); },
    removeEventListener() {},
    querySelector(sel) {
      const id = sel.replace('#', '');
      return this._byId[id] || null;
    },
    querySelectorAll() { return []; },
    dispatch(type, evt) { for (const fn of this._listeners[type] || []) fn(evt); },
  };
  return el;
}

function installDom() {
  const board = makeElement('canvas');
  board.id = 'board';
  board.width = 480;
  board.height = 480;

  const turn = makeElement('span');
  const status = makeElement('span');
  const reset = makeElement('button');

  const byId = { board, turn, status, reset };
  const container = makeElement('body');
  container._byId = byId;
  container._listeners = {};

  global.window = {
    innerWidth: 480,
    innerHeight: 480,
    addEventListener() {},
    removeEventListener() {},
    parent: undefined,
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

  const { WasmHost } = await import(join(ROOT, 'games', '_shared', 'wasm-host.js'));
  const dabMod = await import(join(GAME_DIR, 'dots-and-boxes.js'));

  // ── boot the real wasm engine through the shared WasmHost (loadBytes path) ──
  const bytes = await readFile(WASM_PATH);
  const host = new WasmHost(WASM_PATH);
  await host.loadBytes(bytes);
  assert(
    typeof host.exports.dab_apply === 'function',
    'WasmHost bound the real hugmesh_dots_and_boxes.wasm (dab_apply export present)',
  );

  // Sanity: the marshalling contract works through WasmHost.
  assert(host.callStatus('dab_new') === 1, 'WasmHost.callStatus(dab_new) === 1');
  const startLegal = host.callPacked('dab_legal');
  assert(
    Array.isArray(startLegal) && startLegal.length === 24,
    `WasmHost.callPacked(dab_legal) === 24 at start (got ${startLegal && startLegal.length})`,
  );
  const startFull = host.callPacked('dab_state');
  assert(startFull.type === 'dab.full' && startFull.turn === 'one',
    'WasmHost.callPacked(dab_state) is the empty start board, player one to move');

  // ── build the GAME and mount it WITHOUT the network (no session_id) ─────────
  const GAME = dabMod.createDotsAndBoxesGame();
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
  assert(initial && Array.isArray(initial.edges) && initial.edges.length === 24,
    'GAME.init() returns the start-position state (24 edges)');
  assert(initial.edges.every((e) => e === false), 'start state: all 24 edges unclaimed');

  // ── (a) render() draw-call counts for the start position ────────────────────
  const cx = byId.board.getContext('2d');
  cx._calls.stroke = 0;
  cx._calls.arc = 0;
  cx._calls.fill = 0;
  cx._calls.fillRect = 0;
  cx._calls.fillText = 0;
  GAME.render(initial, ctx);

  assert(
    cx._calls.stroke === 24,
    `render() stroked exactly 24 open edges at the start (stroke=${cx._calls.stroke})`,
  );
  assert(
    cx._calls.arc === 16 && cx._calls.fill === 16,
    `render() drew 16 dots (arc=${cx._calls.arc}, fill=${cx._calls.fill})`,
  );
  assert(
    cx._calls.fillRect === 0,
    `render() filled 0 owned-box territories at the start (fillRect=${cx._calls.fillRect})`,
  );

  // HUD reflects the start position.
  assert(byId.turn.textContent.includes('0 — 0'), `HUD score shows 0–0 (got "${byId.turn.textContent}")`);
  assert(
    byId.status.textContent === 'Player 1 to move',
    `HUD status shows "Player 1 to move" (got "${byId.status.textContent}")`,
  );

  // ── (b) one click near an edge claims it through the real wasm ──────────────
  const ui = GAME._ui;
  assert(ui.step > 0, `board laid out with a positive dot spacing (step=${ui.step})`);

  // Click the midpoint of the TOP edge of box (0,0): horizontal edge H(0,0)=0,
  // which spans dot (0,0)→(0,1). Midpoint x = pad + step/2, y = pad.
  const clickX = ui.pad + ui.step / 2;
  const clickY = ui.pad;

  const before = host.callPacked('dab_state');
  cx._calls.stroke = 0;
  const moveResult = ctx.handleLocalInput({ type: 'click', offsetX: clickX, offsetY: clickY });

  assert(moveResult && moveResult.input, 'the click produced a relay input payload');
  assert(
    moveResult.input.kind === 'control' && moveResult.input.type === 'claim',
    `relay input is a control/claim (kind="${moveResult.input.kind}", type="${moveResult.input.type}")`,
  );
  assert(
    moveResult.input.payload.edge === 0,
    `relayed edge is the top edge H(0,0)=0 (got ${moveResult.input && moveResult.input.payload && moveResult.input.payload.edge})`,
  );
  assert(
    moveResult.world && Array.isArray(moveResult.world.edges) && moveResult.world.edges.includes(0),
    'the click produced a host-authoritative world board listing the claimed edge',
  );

  // The engine state CHANGED: edge 0 now claimed, turn flipped one→two.
  const after = host.callPacked('dab_state');
  assert(before.edges[0] === false && after.edges[0] === true,
    'edge 0 flipped from unclaimed to claimed in the engine');
  assert(after.turn === 'two',
    `turn flipped player one→two after a non-completing claim (got "${after.turn}")`);
  assert(host.callPacked('dab_legal').length === 23,
    'one edge consumed: 23 legal edges remain');

  // The post-move render still strokes 24 edges (1 claimed + 23 open) — proving
  // the board repainted with the new claimed line.
  assert(
    cx._calls.stroke === 24,
    `post-move render stroked 24 edges (1 claimed + 23 open) (stroke=${cx._calls.stroke})`,
  );
  assert(GAME._getState().edges[0] === true,
    'the game state object reflects the claimed edge after render');
  assert(GAME._getState().turn === 'two',
    'the game state object reflects the turn flip to player two');

  // ── illegal-move guard: a click on an already-drawn edge does nothing ───────
  const beforeDup = host.callPacked('dab_state');
  const dup = ctx.handleLocalInput({ type: 'click', offsetX: clickX, offsetY: clickY });
  assert(dup === null || (dup && !dup.input),
    'clicking an already-drawn edge produces no relay (ignored)');
  assert(host.callPacked('dab_state').turn === beforeDup.turn,
    'an ignored click did not change the turn');
  assert(host.callPacked('dab_legal').length === 23,
    'an ignored click did not consume an edge');

  // ── reset path starts a fresh board ─────────────────────────────────────────
  const resetResult = ctx.handleLocalInput({ type: 'reset' });
  assert(resetResult && resetResult.state && resetResult.state.edges.every((e) => e === false),
    'New game resets every edge to unclaimed');
  assert(host.callPacked('dab_state').turn === 'one',
    'New game returns the turn to player one');

  // ── report ──────────────────────────────────────────────────────────────────
  console.log(`\n${passed} assertions passed, ${failures.length} failed.`);
  if (failures.length > 0) {
    console.error('FAILURES:\n  - ' + failures.join('\n  - '));
    process.exit(1);
  }
  console.log('OK — dots-and-boxes frontend renders the board and claims an edge through the real wasm.');
}

main().catch((e) => {
  console.error('smoke crashed:', e);
  process.exit(1);
});
