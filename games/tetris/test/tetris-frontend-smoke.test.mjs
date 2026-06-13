// games/tetris/test/tetris-frontend-smoke.test.mjs
//
// HEADLESS SMOKE for the tetris web frontend — the DOM-LIGHT path.
//
// WHY this path (same as chess / reversi): Playwright reports a version but is
// NOT usable here — its browser binaries are not installed and neither
// `playwright` nor `@playwright/test` resolves as a module; jsdom is not
// installed either. Installing either means a network download out of scope for
// a smoke test. So we run the pure-node DOM-light smoke: a hand-rolled minimal
// DOM + a RECORDING 2D canvas context. Fully deterministic, zero-install, and
// proves the two required things:
//
//   (a) tetris.js is loaded as a real ES module against a mocked canvas 2D
//       context; render() of the seed-1 opening issues the EXPECTED draw-call
//       counts — 200 well fillRects (the 10x20 grid) PLUS the 4 active-piece
//       blocks (4 more fillRects), plus the next/hold preview repaints.
//   (b) the real snapdragon_tetris_wasm.wasm is driven through the shared
//       WasmHost (the shell's marshalling), and inputs applied THROUGH the
//       game's onLocalInput action path mutate the engine exactly: a left nudge
//       shifts the active piece (x 3 -> 2), a hard drop LOCKS four cells into the
//       well, and the move returns ONLY `{ state }` (SINGLE-PLAYER — no relay
//       input/world/control), exactly the browser flow minus the browser.
//
// SINGLE-PLAYER PUZZLE — LOCAL ONLY: there is no SessionClient, no relay. The
// engine is seeded deterministically from the URL `?seed=` param (here seed 1).
//
// Run:  node games/tetris/test/tetris-frontend-smoke.test.mjs

import { readFile } from 'node:fs/promises';
import { fileURLToPath } from 'node:url';
import { dirname, join } from 'node:path';

const here = dirname(fileURLToPath(import.meta.url));
const ROOT = join(here, '..', '..', '..'); // repo root
const TETRIS_DIR = join(here, '..');
const WASM_PATH = join(TETRIS_DIR, 'wasm', 'snapdragon_tetris_wasm.wasm');

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
  return {
    _calls: calls,
    fillStyle: '#000',
    strokeStyle: '#000',
    lineWidth: 1,
    font: '',
    textAlign: 'start',
    textBaseline: 'alphabetic',
    fillRect() { calls.fillRect++; },
    strokeRect() { calls.strokeRect++; },
    fillText() { calls.fillText++; },
    beginPath() {},
    arc() { calls.arc++; },
    fill() { calls.fill++; },
    stroke() { calls.stroke++; },
    clearRect() {},
  };
}

// ── a minimal DOM element (only what tetris.js touches) ───────────────────────
function makeElement(tag) {
  return {
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
    _pcell: 0,
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
      return { left: 0, top: 0, width: this.width || 240, height: this.height || 480 };
    },
    appendChild(c) { this.children.push(c); return c; },
    setAttribute(k, v) { this[k] = v; },
    getAttribute(k) { return this[k]; },
    addEventListener(type, fn) { (this._listeners[type] = this._listeners[type] || []).push(fn); },
    removeEventListener() {},
    querySelector(sel) {
      const id = sel.replace('#', '');
      return (this._byId && this._byId[id]) || null;
    },
    querySelectorAll() { return []; },
    dispatch(type, evt) { for (const fn of this._listeners[type] || []) fn(evt); },
  };
}

// ── install a minimal global DOM the shell + tetris.js need ───────────────────
function installDom() {
  const well = makeElement('canvas');
  well.id = 'well';
  well.width = 240;
  well.height = 480;
  const next = makeElement('canvas');
  next.id = 'next';
  const hold = makeElement('canvas');
  hold.id = 'hold';
  const score = makeElement('dd');
  const lines = makeElement('dd');
  const level = makeElement('dd');
  const status = makeElement('p');
  const restart = makeElement('button');

  const byId = { well, next, hold, score, lines, level, status, restart };
  const container = makeElement('body');
  container._byId = byId;

  // No requestAnimationFrame in this headless DOM → tetris.js's startGravity
  // returns early, so the gravity loop is inert and the test drives every step
  // explicitly (deterministic; no wall-clock dependence).
  global.window = {
    innerWidth: 520,
    innerHeight: 640,
    location: { search: '?seed=1' },
    addEventListener() {},
    removeEventListener() {},
  };
  global.window.parent = global.window;
  global.document = {
    body: container,
    createElement: (t) => makeElement(t),
    getElementById: (id) => byId[id] || null,
  };

  return { container, byId };
}

// Count locked (non-empty) board cells in a flat board string.
const countFilled = (board) => {
  let n = 0;
  for (const c of board) if (c !== '.') n++;
  return n;
};

async function main() {
  console.log('DOM-LIGHT smoke (no browser, no jsdom, no network).');

  const { container, byId } = installDom();

  // Import the shell + game AS REAL ES MODULES (the same files the bundle ships).
  const { WasmHost } = await import(join(ROOT, 'games', '_shared', 'wasm-host.js'));
  const tetrisMod = await import(join(TETRIS_DIR, 'tetris.js'));

  // ── pure-function checks (no engine) ────────────────────────────────────────
  assert(tetrisMod.parseSeed('?seed=42') === '42', 'parseSeed reads ?seed= from the URL');
  assert(tetrisMod.parseSeed('') === 1, 'parseSeed defaults to 1 when absent');
  assert(tetrisMod.keyToAction({ key: 'ArrowLeft' }) === 'left', 'ArrowLeft maps to the left action');
  assert(tetrisMod.keyToAction({ key: ' ' }) === 'hard', 'Space maps to the hard-drop action');
  assert(tetrisMod.keyToAction({ key: 'Shift' }) === 'hold', 'Shift maps to the hold action');
  assert(tetrisMod.keyToAction({ key: 'q' }) === null, 'an unmapped key is ignored');
  assert(tetrisMod.gravityMs(0) > tetrisMod.gravityMs(5), 'gravity speeds up (shorter period) at higher levels');
  // The active-cell geometry agrees with the engine: a spawn-state T occupies 4 cells.
  const tCells = tetrisMod.activeCells({ piece: 't', rot: 0, x: 3, y: 0 });
  assert(tCells.length === 4, 'activeCells() yields the 4 cells of the active piece');

  // ── boot the real wasm engine through the shared WasmHost (loadBytes path) ──
  const bytes = await readFile(WASM_PATH);
  const host = new WasmHost(WASM_PATH);
  await host.loadBytes(bytes);
  assert(typeof host.exports.tetris_new === 'function',
    'WasmHost bound the real snapdragon_tetris_wasm.wasm (tetris_new export present)');
  // Sanity: the marshalling contract works through WasmHost (seed passed as a
  // leading u64 arg via callRaw/callStatus leadingArgs — the documented path).
  assert(host.callStatus('tetris_new', null, [1n]) === 1, 'WasmHost.callStatus(tetris_new, [1n]) === 1');
  const probe = host.callPacked('tetris_snapshot');
  assert(probe && probe.type === 'tetris.snapshot' && probe.active.piece === 't',
    'WasmHost.callPacked(tetris_snapshot) is the seed-1 opening (T to spawn)');

  // ── build the GAME and mount it WITHOUT the network (single-player) ─────────
  const GAME = tetrisMod.createTetrisGame();
  const ctx = {
    host,
    client: { sendInput() {}, sendControl() {}, sendWorld() {}, on() {}, connect() {}, close() {} },
    params: { sessionId: null, characterId: null, slotIndex: 0, search: '?seed=1' },
    isHost: true,
    container,
    GAME,
    onTeardown() {},
  };
  ctx.handleLocalInput = (evt) => {
    const r = GAME.onLocalInput(evt, ctx);
    if (r && r.state) GAME.render(r.state, ctx);
    return r;
  };

  const initial = await GAME.init(host, container, ctx);
  assert(initial && initial.width === 10 && initial.height === 20, 'GAME.init() returns a 10x20 well state');
  assert(initial.active.piece === 't' && initial.next === 's',
    'GAME.init() seeded the engine deterministically (seed 1: T active, S next)');
  assert(initial.status === 'playing' && initial.score === 0, 'GAME.init() opening is playing with score 0');

  // ── (a) render() draw-call counts for the seed-1 opening ────────────────────
  const cx = byId.well.getContext('2d');
  cx._calls.fillRect = 0;
  cx._calls.strokeRect = 0;
  GAME.render(initial, ctx);

  // 200 well cells (one fillRect each) + 4 active-piece blocks (one fillRect each)
  // = 204 fillRects on the well. The empty opening has no locked stack.
  assert(cx._calls.fillRect === 204,
    `render() painted 200 well cells + 4 active-piece blocks = 204 fillRects (got ${cx._calls.fillRect})`);
  // 200 grid strokes + 4 active-block strokes = 204 strokeRects.
  assert(cx._calls.strokeRect === 204,
    `render() stroked the 200 grid cells + 4 active blocks = 204 strokeRects (got ${cx._calls.strokeRect})`);

  // The next-piece preview repainted (panel fill + the 4 blocks of the S piece).
  const ncx = byId.next.getContext('2d');
  assert(ncx._calls.fillRect >= 5,
    `next-piece preview drew its panel + 4 piece blocks (fillRect=${ncx._calls.fillRect})`);

  // HUD reflects the opening.
  assert(byId.score.textContent === '0', `HUD score shows 0 (got "${byId.score.textContent}")`);
  assert(byId.lines.textContent === '0', `HUD lines shows 0 (got "${byId.lines.textContent}")`);
  assert(byId.level.textContent === '0', `HUD level shows 0 (got "${byId.level.textContent}")`);
  assert(byId.status.textContent === 'Playing', `HUD status shows "Playing" (got "${byId.status.textContent}")`);

  // ── (b) apply a left nudge through onLocalInput; engine state changes ────────
  const xBefore = GAME._getState().active.x;
  const leftResult = ctx.handleLocalInput({ type: 'action', action: 'left' });
  assert(leftResult && leftResult.state, 'a left action returns a state to render');
  // SINGLE-PLAYER: NO relay payloads (no input/world/control).
  assert(!leftResult.input && !leftResult.world && !leftResult.control,
    'SINGLE-PLAYER: onLocalInput returns ONLY { state } — no relay input/world/control');
  const xAfter = GAME._getState().active.x;
  assert(xAfter === xBefore - 1,
    `left nudge shifted the active piece one column left through the wasm (x ${xBefore} -> ${xAfter})`);
  // Confirm it really hit the engine (snapshot agrees).
  assert(host.callPacked('tetris_snapshot').active.x === xAfter,
    'the engine snapshot agrees with the rendered active x (move applied authoritatively)');

  // ── (c) hard drop LOCKS four cells into the well, repaints the stack ─────────
  cx._calls.fillRect = 0;
  const dropResult = ctx.handleLocalInput({ type: 'action', action: 'hard' });
  assert(dropResult && dropResult.state, 'a hard-drop action returns a state to render');
  const afterDrop = GAME._getState();
  assert(countFilled(afterDrop.board) === 4,
    `hard drop locked exactly 4 cells into the well (got ${countFilled(afterDrop.board)})`);
  assert(afterDrop.score > 0, `hard drop scored the drop distance (score ${afterDrop.score})`);
  assert(afterDrop.active.piece === 's',
    `after the lock the next piece (S) is now active (got "${afterDrop.active.piece}")`);
  // Post-lock render repainted the 200 well cells + the now-locked + active blocks.
  assert(cx._calls.fillRect >= 204,
    `post-drop render repainted the well + stack + active piece (fillRect=${cx._calls.fillRect})`);
  assert(byId.score.textContent === String(afterDrop.score),
    `HUD score updated after the drop (got "${byId.score.textContent}")`);

  // ── (d) a gravity tick advances the falling piece one row ────────────────────
  const yBeforeTick = GAME._getState().active.y;
  ctx.handleLocalInput({ type: 'action', action: 'tick' });
  const yAfterTick = GAME._getState().active.y;
  assert(yAfterTick === yBeforeTick + 1,
    `a gravity tick dropped the active piece one row (y ${yBeforeTick} -> ${yAfterTick})`);

  // ── (e) restart re-seeds deterministically (same seed → same opening) ────────
  const restartResult = ctx.handleLocalInput({ type: 'restart' });
  assert(restartResult && restartResult.state, 'restart returns a fresh state');
  const restarted = GAME._getState();
  assert(restarted.active.piece === 't' && restarted.next === 's' &&
         countFilled(restarted.board) === 0 && restarted.score === 0,
    'restart re-seeded to the identical seed-1 opening (T active, S next, empty well, score 0)');

  // ── report ──────────────────────────────────────────────────────────────────
  console.log(`\n${passed} assertions passed, ${failures.length} failed.`);
  if (failures.length > 0) {
    console.error('FAILURES:\n  - ' + failures.join('\n  - '));
    process.exit(1);
  }
  console.log('OK — tetris frontend renders the well and applies inputs through the real wasm.');
}

main().catch((e) => {
  console.error('smoke crashed:', e);
  process.exit(1);
});
