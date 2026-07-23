// games/bomberman/test/bomberman-frontend-smoke.test.mjs
//
// HEADLESS SMOKE for the bomberman web frontend — the DOM-LIGHT path.
//
// WHY this path (documented per the task, same as chess/reversi): Playwright
// reports a version but is NOT usable here — its browser binaries are not installed
// and neither `playwright` nor `@playwright/test` resolves as a module; jsdom is not
// installed either. Installing either means a network download out of scope for a
// smoke test. So we run the pure-node DOM-light smoke: a hand-rolled minimal DOM + a
// RECORDING 2D canvas context. Fully deterministic, zero-install, and proves the two
// required things:
//
//   (a) bomberman.js is loaded as a real ES module against a mocked canvas 2D
//       context; render() of the initial arena issues the EXPECTED draw-call counts
//       — one fillRect per cell (143 terrain tiles) + one arc per live player (2).
//   (b) the real hugmesh_bomberman_wasm.wasm is driven through the shared
//       WasmHost (the shell's marshalling), and an INPUT (P1 presses ArrowRight)
//       applied THROUGH the game's onLocalInput keydown path is recorded into the
//       engine, then the host-authoritative tick (_tickOnce) advances the sim: the
//       tick counter increments, P1 steps one cell right, and the canvas repaints —
//       exactly the browser flow, minus the browser.
//
// This is a REALTIME DUEL (host-authoritative tick loop): the rAF clock is NOT
// started in the test (no requestAnimationFrame in node); we drive the single
// authoritative step directly via GAME._tickOnce, which is precisely what the loop
// calls on its fixed-step cadence.
//
// Run:  node games/bomberman/test/bomberman-frontend-smoke.test.mjs

import { readFile } from 'node:fs/promises';
import { fileURLToPath } from 'node:url';
import { dirname, join } from 'node:path';

const here = dirname(fileURLToPath(import.meta.url));
const ROOT = join(here, '..', '..', '..'); // repo root
const BOMBERMAN_DIR = join(here, '..');
const WASM_PATH = join(BOMBERMAN_DIR, 'wasm', 'hugmesh_bomberman_wasm.wasm');

const WIDTH = 13;
const HEIGHT = 11;

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

// ── a minimal DOM element (only what bomberman.js touches) ────────────────────
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
      return { left: 0, top: 0, width: this.width || 400, height: this.height || 400 };
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

// ── install a minimal global DOM the shell + bomberman.js need ────────────────
function installDom() {
  const board = makeElement('canvas');
  board.id = 'board';
  board.width = 390;
  board.height = 330;

  const status = makeElement('span');
  const tick = makeElement('span');

  const byId = { board, status, tick };
  const container = makeElement('body');
  container._byId = byId;
  container._listeners = {};

  // A window that CAPTURES keydown listeners so the test can dispatch keys (the
  // game binds its keymap on window, not the canvas — first-keypress-no-click).
  const winListeners = {};
  global.window = {
    innerWidth: 480,
    innerHeight: 480,
    addEventListener(type, fn) {
      (winListeners[type] = winListeners[type] || []).push(fn);
    },
    removeEventListener(type, fn) {
      winListeners[type] = (winListeners[type] || []).filter((f) => f !== fn);
    },
    _dispatch(type, evt) {
      for (const fn of winListeners[type] || []) fn(evt);
    },
    parent: undefined, // single-player (no distinct parent) → no network
  };
  global.window.parent = global.window;
  // No requestAnimationFrame / performance in the browser-shaped globals → the rAF
  // path is unavailable. Node DOES expose setInterval, so the loop's headless
  // fallback would register a timer; we stub setInterval/clearInterval to inert
  // no-ops so NO tick fires on its own and the test drives every authoritative step
  // deterministically via GAME._tickOnce (exactly what the rAF loop calls).
  global.setInterval = () => 0;
  global.clearInterval = () => {};

  global.document = {
    body: container,
    createElement: (t) => makeElement(t),
    getElementById: (id) => byId[id] || null,
  };

  return { container, byId, win: global.window };
}

async function main() {
  console.log('DOM-LIGHT smoke (no browser, no jsdom, no network).');

  const { container, byId, win } = installDom();

  // Import the shell + game AS REAL ES MODULES (the same files the bundle ships).
  const { WasmHost } = await import(join(ROOT, 'games', '_shared', 'wasm-host.js'));
  const bombMod = await import(join(BOMBERMAN_DIR, 'bomberman.js'));

  // ── boot the real wasm engine through the shared WasmHost (loadBytes path) ──
  const bytes = await readFile(WASM_PATH);
  const host = new WasmHost(WASM_PATH);
  await host.loadBytes(bytes);
  assert(
    typeof host.exports.bomberman_tick === 'function',
    'WasmHost bound the real hugmesh_bomberman_wasm.wasm (bomberman_tick export present)',
  );

  // Sanity: the marshalling contract works through WasmHost (proves the shell
  // drives the real .wasm, not a re-implementation).
  assert(host.callStatus('bomberman_new', null, [BigInt(0x5eed)]) === 1, 'WasmHost.callStatus(bomberman_new) === 1');
  const openSnap = host.callPacked('bomberman_snapshot');
  assert(
    openSnap && openSnap.type === 'bomberman.snapshot' && openSnap.board.length === WIDTH * HEIGHT,
    'WasmHost.callPacked(bomberman_snapshot) is the opening snapshot',
  );

  // ── build the GAME and mount it WITHOUT the network (single-player hot-seat) ─
  const GAME = bombMod.createBombermanGame();
  const ctx = {
    host,
    client: { sendInput() {}, sendControl() {}, sendWorld() {}, on() {}, connect() {}, close() {} },
    params: { sessionId: null, characterId: null, slotIndex: 0, chapterId: null },
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
  assert(initial && initial.board === openSnap.board, 'GAME.init() returns the opening-arena state');
  assert(initial.tick === 0 && initial.status === 'playing', 'GAME.init() reports tick 0 / playing');
  // The host owns the authoritative clock: init armed the loop, but with the timer
  // stubbed inert NO tick fires on its own — the sim stays at tick 0 until we step
  // it. (The browser drives this via requestAnimationFrame at TICK_MS cadence.)
  assert(GAME._loop.running === true, 'host armed the authoritative tick loop on init');
  assert(host.callPacked('bomberman_snapshot').tick === 0, 'no tick auto-fired (sim still at tick 0 after init)');

  // ── (a) render() draw-call counts for the opening arena ─────────────────────
  const cx = byId.board.getContext('2d');
  cx._calls.fillRect = 0;
  cx._calls.arc = 0;
  cx._calls.fill = 0;
  GAME.render(initial, ctx);

  // One terrain fillRect per cell (WIDTH*HEIGHT = 143); no bombs/flames yet.
  assert(
    cx._calls.fillRect >= WIDTH * HEIGHT,
    `render() painted >= ${WIDTH * HEIGHT} terrain tiles (fillRect=${cx._calls.fillRect})`,
  );
  // Exactly one arc per live player at the opening (2 players, 0 bombs, 0 flames).
  assert(
    cx._calls.arc === 2,
    `render() drew exactly 2 player arcs at the opening (arc=${cx._calls.arc})`,
  );

  // HUD reflects the opening.
  assert(byId.tick.textContent === 'Tick 0', `HUD shows "Tick 0" (got "${byId.tick.textContent}")`);
  assert(byId.status.textContent === 'Fight!', `HUD status shows "Fight!" (got "${byId.status.textContent}")`);

  // ── (b) a keydown is captured at the window and recorded into the engine ────
  // The game binds its keymap on window; dispatching ArrowRight there exercises the
  // exact first-keypress-no-click path the browser uses.
  let preventedDefault = false;
  const arrowRight = {
    type: 'keydown',
    key: 'ArrowRight',
    preventDefault() {
      preventedDefault = true;
    },
  };
  const p1Before = host.callPacked('bomberman_snapshot').players.find((p) => p.id === 'p1');
  win._dispatch('keydown', arrowRight);
  assert(preventedDefault, 'the game preventDefault()ed the captured arrow key');

  // Recording an input must NOT advance the tick by itself (the loop owns ticks).
  assert(
    host.callPacked('bomberman_snapshot').tick === 0,
    'recording the ArrowRight input did not advance the tick',
  );

  // ── (b cont.) the host-authoritative tick advances the sim + repaints ───────
  cx._calls.fillRect = 0;
  cx._calls.arc = 0;
  const after = GAME._tickOnce(ctx);
  assert(after.tick === 1, `_tickOnce advanced the tick to 1 (got ${after.tick})`);
  const p1After = after.players.find((p) => p.id === 'p1');
  assert(
    p1After.x === p1Before.x + 1 && p1After.y === p1Before.y,
    `P1 stepped one cell right after the tick (from (${p1Before.x},${p1Before.y}) to (${p1After.x},${p1After.y}))`,
  );
  // The tick repainted the arena (terrain + the 2 players again).
  assert(cx._calls.fillRect >= WIDTH * HEIGHT, `the tick repainted the arena (fillRect=${cx._calls.fillRect})`);
  assert(cx._calls.arc === 2, `the tick re-drew the 2 player arcs (arc=${cx._calls.arc})`);
  assert(byId.tick.textContent === 'Tick 1', `HUD shows "Tick 1" after the tick (got "${byId.tick.textContent}")`);

  // ── (c) a bomb input renders as a bomb on the board after a tick ────────────
  win._dispatch('keydown', {
    type: 'keydown',
    key: ' ', // space → P1 places a bomb
    preventDefault() {},
  });
  cx._calls.arc = 0;
  const afterBomb = GAME._tickOnce(ctx);
  assert(afterBomb.bombs.length === 1, `a bomb is on the board after the bomb input + tick (got ${afterBomb.bombs.length})`);
  // Now 2 players + 1 bomb (2 arcs) = 4 arcs minimum (bomb body + fuse pip = 2).
  assert(cx._calls.arc >= 4, `the bomb adds arcs to the render (arc=${cx._calls.arc})`);

  // ── (d) an unmapped key is ignored (no engine change, no relay) ─────────────
  const tickBeforeNoop = host.callPacked('bomberman_snapshot').tick;
  const noop = GAME.onLocalInput({ type: 'keydown', key: 'q', preventDefault() {} }, ctx);
  assert(noop === null, 'an unmapped key returns null (no input)');
  assert(
    host.callPacked('bomberman_snapshot').tick === tickBeforeNoop,
    'an unmapped key did not advance or mutate the engine',
  );

  // ── (d2) P2's UPPER-CASE WASD (Shift held / Caps Lock) still steers P2 ──────
  // Regression: P2 bombs with Shift, so holding Shift while moving makes the
  // browser report 'W'/'A'/'S'/'D' (upper-case). A lower-case-only KEYMAP silently
  // dropped every P2 move under Shift. Single-player hot-seat (characterId null)
  // lets both keysets through, so onLocalInput must map 'W' → p2:up.
  const upperW = GAME.onLocalInput({ type: 'keydown', key: 'W', preventDefault() {} }, ctx);
  assert(
    upperW && upperW.input && upperW.input.payload && upperW.input.payload.player === 'p2' && upperW.input.payload.action === 'up',
    `upper-case 'W' (Shift held) maps to p2:up (got ${upperW && upperW.input ? JSON.stringify(upperW.input.payload) : 'null'})`,
  );
  const upperD = GAME.onLocalInput({ type: 'keydown', key: 'D', preventDefault() {} }, ctx);
  assert(
    upperD && upperD.input && upperD.input.payload && upperD.input.payload.player === 'p2' && upperD.input.payload.action === 'right',
    `upper-case 'D' (Shift held) maps to p2:right (got ${upperD && upperD.input ? JSON.stringify(upperD.input.payload) : 'null'})`,
  );

  // ── (e) guest path: applying a host world snapshot renders it directly ──────
  // A guest (isHost false) does NOT re-simulate; it renders the host's snapshot.
  const guestGame = bombMod.createBombermanGame();
  const guestHost = new WasmHost(WASM_PATH);
  await guestHost.loadBytes(await readFile(WASM_PATH));
  const guestCtx = {
    host: guestHost,
    client: { sendInput() {}, sendControl() {}, sendWorld() {}, on() {}, connect() {}, close() {} },
    params: { sessionId: 'sess-1', characterId: 7, slotIndex: 1, chapterId: null },
    isHost: false,
    container,
    GAME: guestGame,
  };
  await guestGame.init(guestHost, container, guestCtx);
  assert(guestGame._loop.running === false, 'guest does NOT start the authoritative tick loop');
  // Feed the host's authoritative snapshot on the world channel.
  const worldSnap = after; // the host's tick-1 snapshot (P1 at (2,1))
  const rendered = guestGame.onRemoteEvent('world', { payload: worldSnap }, guestCtx);
  assert(
    rendered && rendered.tick === worldSnap.tick && rendered.players.find((p) => p.id === 'p1').x === worldSnap.players.find((p) => p.id === 'p1').x,
    'guest applied the host world snapshot directly (renders host state, no re-sim)',
  );

  // ── report ──────────────────────────────────────────────────────────────────
  console.log(`\n${passed} assertions passed, ${failures.length} failed.`);
  if (failures.length > 0) {
    console.error('FAILURES:\n  - ' + failures.join('\n  - '));
    process.exit(1);
  }
  console.log('OK — bomberman frontend renders the arena and advances the tick loop through the real wasm.');
}

main().catch((e) => {
  console.error('smoke crashed:', e);
  process.exit(1);
});
