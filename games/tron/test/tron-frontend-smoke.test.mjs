// games/tron/test/tron-frontend-smoke.test.mjs
//
// HEADLESS SMOKE for the tron web frontend — the DOM-LIGHT path.
//
// WHY this path (documented per the task, same as chess/reversi): Playwright
// reports a version but is NOT usable here — its browser binaries are not
// installed and neither `playwright` nor `@playwright/test` resolves as a module;
// jsdom is not installed either. Installing either means a network download out of
// scope for a smoke test. So we run the pure-node DOM-light smoke: a hand-rolled
// minimal DOM + a RECORDING 2D canvas context. Fully deterministic, zero-install,
// and proves the two required things:
//
//   (a) tron.js is loaded as a real ES module against a mocked canvas 2D context;
//       render() of the INITIAL (opening) snapshot issues the EXPECTED draw calls
//       — the arena floor fillRect, the grid strokeRects, and the trail+head
//       fillRects for the two seeded start cells.
//   (b) the real snapdragon_tron_wasm.wasm is driven through the shared WasmHost
//       (the shell's marshalling), and an INPUT applies through the game's
//       onLocalInput keydown path: a legal 90° turn is accepted by the engine,
//       and a host TICK then advances the simulation (tick counter increments,
//       both heads move, the board string changes) — exactly the realtime browser
//       flow (the host-authoritative tick loop), minus the browser's rAF clock.
//
// REALTIME NOTE: the host tick loop is driven by requestAnimationFrame, which is
// absent in node, so GAME.init() does not auto-start a clock here. The smoke
// instead steps the authoritative tick MANUALLY via GAME._tickOnce(host, ctx) —
// the exact function the rAF loop calls each frame — so the test is deterministic.
//
// Run:  node games/tron/test/tron-frontend-smoke.test.mjs

import { readFile } from 'node:fs/promises';
import { fileURLToPath } from 'node:url';
import { dirname, join } from 'node:path';

const here = dirname(fileURLToPath(import.meta.url));
const ROOT = join(here, '..', '..', '..'); // repo root
const TRON_DIR = join(here, '..');
const WASM_PATH = join(TRON_DIR, 'wasm', 'snapdragon_tron_wasm.wasm');

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
    fillRect() { calls.fillRect++; },
    strokeRect() { calls.strokeRect++; },
    fillText() { calls.fillText++; },
    beginPath() {},
    arc() { calls.arc++; },
    fill() { calls.fill++; },
    stroke() { calls.stroke++; },
    clearRect() {},
  };
  return ctx;
}

// ── a minimal DOM element (only what tron.js touches) ─────────────────────────
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
      return { left: 0, top: 0, width: this.width || 480, height: this.height || 480 };
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

// ── install a minimal global DOM + window the shell + tron.js need ────────────
function installDom() {
  const arena = makeElement('canvas');
  arena.id = 'arena';
  arena.width = 480;
  arena.height = 480;

  const players = makeElement('span');
  const tick = makeElement('span');
  const status = makeElement('span');
  const restart = makeElement('button');

  const byId = { arena, players, tick, status, restart };
  const container = makeElement('body');
  container._byId = byId;
  container._listeners = {};

  // Capture window keydown listeners so we can dispatch synthetic key events.
  const winListeners = {};
  global.window = {
    innerWidth: 480,
    innerHeight: 480,
    addEventListener(type, fn) { (winListeners[type] = winListeners[type] || []).push(fn); },
    removeEventListener() {},
    parent: undefined, // single-player (no distinct parent) → no network
    _dispatch(type, evt) { for (const fn of winListeners[type] || []) fn(evt); },
  };
  global.window.parent = global.window;
  // NO requestAnimationFrame in node → GAME.init() does not auto-start a clock;
  // the smoke drives ticks manually. (Leaving it undefined exercises the guard.)

  global.document = {
    body: container,
    createElement: (t) => makeElement(t),
    getElementById: (id) => byId[id] || null,
  };

  return { container, byId };
}

async function main() {
  console.log('DOM-LIGHT smoke (no browser, no jsdom, no network, no rAF clock).');

  const { container, byId } = installDom();

  // Import the shell + game AS REAL ES MODULES (the same files the bundle ships).
  const { WasmHost } = await import(join(ROOT, 'games', '_shared', 'wasm-host.js'));
  const tronMod = await import(join(TRON_DIR, 'tron.js'));

  // ── boot the real wasm engine through the shared WasmHost (loadBytes path) ──
  const bytes = await readFile(WASM_PATH);
  const host = new WasmHost(WASM_PATH);
  await host.loadBytes(bytes);
  assert(
    typeof host.exports.tron_tick === 'function',
    'WasmHost bound the real snapdragon_tron_wasm.wasm (tron_tick export present)',
  );

  // Sanity: the marshalling contract works through WasmHost (proves the shell
  // drives the real .wasm, not a re-implementation).
  // The seed is a u64 → the export takes an i64, so it MUST be a BigInt.
  assert(host.callStatus('tron_new', null, [1n]) === 1, 'WasmHost.callStatus(tron_new) === 1');
  const openSnap = host.callPacked('tron_snapshot');
  assert(
    openSnap && openSnap.type === 'tron.snapshot' && openSnap.board.length === openSnap.width * openSnap.height,
    'WasmHost.callPacked(tron_snapshot) is the opening snapshot (w*h flat board)',
  );

  // ── build the GAME and mount it WITHOUT the network (single-player hot-seat) ─
  const GAME = tronMod.createTronGame();
  const ctx = {
    host,
    client: { sendInput() {}, sendControl() {}, sendWorld() {}, on() {}, connect() {}, close() {} },
    params: { sessionId: null, characterId: null, slotIndex: 0, launchUid: null, chapterId: null },
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
    initial && initial.type === 'tron.snapshot' && initial.tick === 0,
    'GAME.init() returns the opening snapshot (tick 0)',
  );
  assert(
    initial.one.dir === 'right' && initial.two.dir === 'left' && initial.one.y === initial.two.y,
    'GAME.init() reports the mirror-facing start (One right, Two left, same row)',
  );
  // No rAF in node ⇒ no clock auto-started.
  assert(GAME._ui.rafId === null, 'no requestAnimationFrame in node ⇒ tick loop not auto-started (rafId null)');

  // ── (a) render() draw-call counts for the opening snapshot ──────────────────
  const cx = byId.arena.getContext('2d');
  cx._calls.fillRect = 0;
  cx._calls.strokeRect = 0;
  GAME.render(initial, ctx);

  const cols = initial.width;
  const rows = initial.height;
  // Arena floor (1) + two seeded trail cells (2) + two live heads (2) = >= 5.
  assert(
    cx._calls.fillRect >= 5,
    `render() painted the arena + 2 trails + 2 heads (fillRect=${cx._calls.fillRect}, >= 5)`,
  );
  // Grid: cols+1 vertical + rows+1 horizontal strokeRects.
  assert(
    cx._calls.strokeRect === (cols + 1) + (rows + 1),
    `render() drew the full grid (strokeRect=${cx._calls.strokeRect}, expected ${(cols + 1) + (rows + 1)})`,
  );

  // HUD reflects the opening position.
  assert(byId.tick.textContent === 'Tick 0', `HUD tick shows "Tick 0" (got "${byId.tick.textContent}")`);
  assert(byId.status.textContent === 'Racing', `HUD status shows "Racing" (got "${byId.status.textContent}")`);
  assert(byId.restart.disabled === true, 'Restart button disabled while racing');

  // ── (b) an INPUT applies through the real wasm + a host tick advances state ──
  // Steer cycle One (arrows) DOWN — a legal 90° turn from its Right heading.
  const headBefore = { x: openSnap.one.x, y: openSnap.one.y };
  const inputResult = ctx.handleLocalInput({ type: 'keydown', key: 'ArrowDown' });
  assert(inputResult && inputResult.input, 'ArrowDown produced a relay input payload');
  assert(
    inputResult.input.payload.player === 0 && inputResult.input.payload.dir === 1,
    `relayed input is { player:0 (One), dir:1 (down) } (got ${JSON.stringify(inputResult.input.payload)})`,
  );
  // The engine accepted the turn (verify directly through the host).
  const afterInput = host.callPacked('tron_snapshot');
  assert(afterInput.one.dir === 'down', `engine queued One's heading to down (got "${afterInput.one.dir}")`);
  // The board has not changed yet (set_dir takes effect on the NEXT tick).
  assert(afterInput.tick === 0, 'set_dir alone does not advance the tick counter');

  // Now drive ONE authoritative tick — the exact call the rAF loop makes.
  cx._calls.fillRect = 0;
  const tickResult = GAME._tickOnce(host, ctx);
  assert(tickResult && tickResult.outcome && tickResult.outcome.type === 'tron.tick', 'tron tick produced a tron.tick outcome');
  assert(tickResult.outcome.tick === 1, `the manual tick advanced to tick 1 (got ${tickResult.outcome.tick})`);

  const afterTick = host.callPacked('tron_snapshot');
  assert(afterTick.tick === 1, `engine tick counter advanced to 1 (got ${afterTick.tick})`);
  // One turned down: it moved one row DOWN from its start (same column).
  assert(
    afterTick.one.x === headBefore.x && afterTick.one.y === headBefore.y + 1,
    `One advanced DOWN one row after the tick (got ${afterTick.one.x},${afterTick.one.y} from ${headBefore.x},${headBefore.y})`,
  );
  // Two advanced LEFT one column (its default heading).
  assert(afterTick.two.x === openSnap.two.x - 1, `Two advanced LEFT one column after the tick (got ${afterTick.two.x})`);
  // The board string changed (fresh trail cells were laid).
  assert(afterTick.board !== openSnap.board, 'the board string changed after the tick (trails laid)');
  // The post-tick render repainted the arena + the now-longer trails.
  assert(cx._calls.fillRect >= 5, `post-tick render repainted the arena + trails + heads (fillRect=${cx._calls.fillRect})`);
  // HUD tick counter advanced.
  assert(byId.tick.textContent === 'Tick 1', `HUD tick advanced to "Tick 1" (got "${byId.tick.textContent}")`);

  // ── illegal-input guard: a 180° reversal is rejected (no heading change) ─────
  // One now heads DOWN; ArrowUp is the illegal 180° reversal → engine ignores it.
  ctx.handleLocalInput({ type: 'keydown', key: 'ArrowUp' });
  const afterReversal = host.callPacked('tron_snapshot');
  assert(afterReversal.one.dir === 'down', `illegal 180° reversal ignored — One still heading down (got "${afterReversal.one.dir}")`);

  // ── report ──────────────────────────────────────────────────────────────────
  console.log(`\n${passed} assertions passed, ${failures.length} failed.`);
  if (failures.length > 0) {
    console.error('FAILURES:\n  - ' + failures.join('\n  - '));
    process.exit(1);
  }
  console.log('OK — tron frontend renders the arena and advances the tick loop through the real wasm.');
}

main().catch((e) => {
  console.error('smoke crashed:', e);
  process.exit(1);
});
