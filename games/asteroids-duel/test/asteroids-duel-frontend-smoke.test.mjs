// games/asteroids-duel/test/asteroids-duel-frontend-smoke.test.mjs
//
// HEADLESS SMOKE for the asteroids-duel web frontend — the DOM-LIGHT path.
//
// WHY this path (documented per the task, same as chess/reversi): Playwright
// reports a version but is NOT usable here — its browser binaries are not
// installed and neither `playwright` nor `@playwright/test` resolves as a module;
// jsdom is not installed either. Installing either means a network download out of
// scope for a smoke test. So we run the pure-node DOM-light smoke: a hand-rolled
// minimal DOM + a RECORDING 2D canvas context. Fully deterministic, zero-install,
// and proves the two required things:
//
//   (a) asteroids-duel.js is loaded as a real ES module against a mocked canvas 2D
//       context; render() of the INITIAL snapshot issues the EXPECTED vector
//       draw calls — the star-field, one closed polygon per seeded asteroid (4),
//       and one ship triangle (survival) — i.e. the field is drawn as vectors.
//   (b) the real snapdragon_asteroids_duel_wasm.wasm is driven through the shared
//       WasmHost (the shell's marshalling), and an INPUT (hold thrust) applied
//       through the game's onLocalInput keydown path THEN advanced one host tick
//       (_tickOnce — the loop's body, called directly so the test is deterministic
//       and not at the mercy of requestAnimationFrame) actually changes the engine
//       state: the ship gains +X velocity and the tick advances — exactly the
//       browser flow, minus the browser.
//
// This is the REALTIME-DUEL analogue of reversi's turn-based smoke: the assertion
// is "an input applied through the real wasm advances the authoritative tick".
//
// Run:  node games/asteroids-duel/test/asteroids-duel-frontend-smoke.test.mjs

import { readFile } from 'node:fs/promises';
import { fileURLToPath } from 'node:url';
import { dirname, join } from 'node:path';

const here = dirname(fileURLToPath(import.meta.url));
const ROOT = join(here, '..', '..', '..'); // repo root
const GAME_DIR = join(here, '..');
const WASM_PATH = join(GAME_DIR, 'wasm', 'snapdragon_asteroids_duel_wasm.wasm');

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
  const calls = {
    fillRect: 0,
    strokeRect: 0,
    fillText: 0,
    arc: 0,
    fill: 0,
    stroke: 0,
    beginPath: 0,
    moveTo: 0,
    lineTo: 0,
    closePath: 0,
  };
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
    beginPath() { calls.beginPath++; },
    moveTo() { calls.moveTo++; },
    lineTo() { calls.lineTo++; },
    closePath() { calls.closePath++; },
    arc() { calls.arc++; },
    fill() { calls.fill++; },
    stroke() { calls.stroke++; },
    clearRect() {},
  };
  return ctx;
}

// ── a minimal DOM element (only what asteroids-duel.js touches) ───────────────
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
      return { left: 0, top: 0, width: this.width || 1024, height: this.height || 768 };
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

// ── install a minimal global DOM the shell + asteroids-duel.js need ───────────
function installDom() {
  const field = makeElement('canvas');
  field.id = 'field';
  field.width = 1024;
  field.height = 768;

  const score = makeElement('span');
  const status = makeElement('span');

  const byId = { field, score, status };
  const container = makeElement('body');
  container._byId = byId;
  container._listeners = {};

  // window WITHOUT requestAnimationFrame → the game's requestFrame() falls back to
  // setTimeout; we stop the loop immediately after init and step _tickOnce()
  // ourselves so the test is deterministic.
  const winListeners = {};
  global.window = {
    innerWidth: 1024,
    innerHeight: 768,
    location: { search: '' },
    addEventListener(type, fn) { (winListeners[type] = winListeners[type] || []).push(fn); },
    removeEventListener() {},
    parent: undefined, // single-player (no distinct parent) → no network
    _listeners: winListeners,
    _emit(type, evt) { for (const fn of winListeners[type] || []) fn(evt); },
  };
  global.window.parent = global.window;

  global.document = {
    body: container,
    createElement: (t) => makeElement(t),
    getElementById: (id) => byId[id] || null,
  };

  // performance.now for the loop timing (unused once stopped, but referenced).
  if (typeof global.performance === 'undefined') {
    global.performance = { now: () => Date.now() };
  }

  return { container, byId };
}

async function main() {
  console.log('DOM-LIGHT smoke (no browser, no jsdom, no network).');

  const { container, byId } = installDom();

  // Import the shell + game AS REAL ES MODULES (the same files the bundle ships).
  const { WasmHost } = await import(join(ROOT, 'games', '_shared', 'wasm-host.js'));
  const mod = await import(join(GAME_DIR, 'asteroids-duel.js'));

  // ── boot the real wasm engine through the shared WasmHost (loadBytes path) ──
  const bytes = await readFile(WASM_PATH);
  const host = new WasmHost(WASM_PATH);
  await host.loadBytes(bytes);
  assert(
    typeof host.exports.asteroids_tick === 'function',
    'WasmHost bound the real snapdragon_asteroids_duel_wasm.wasm (asteroids_tick export present)',
  );

  // Sanity: the marshalling contract works through WasmHost (proves the shell
  // drives the real .wasm, not a re-implementation).
  assert(host.callStatus('asteroids_new', null, [42, 0, 1]) === 1, 'WasmHost.callStatus(asteroids_new) === 1');
  const openSnap = host.callPacked('asteroids_snapshot');
  assert(
    openSnap && openSnap.type === 'asteroids.snapshot' && openSnap.ships.length === 1,
    'WasmHost.callPacked(asteroids_snapshot) is the opening survival snapshot',
  );

  // ── build the GAME and mount it WITHOUT the network (no session_id) ─────────
  const GAME = mod.createAsteroidsGame();
  const relayed = [];
  const ctx = {
    host,
    client: {
      sendInput(cid, p) { relayed.push(['input', cid, p]); },
      sendControl() {},
      sendWorld(cid, slug, w) { relayed.push(['world', cid, slug, w]); },
      on() {},
      connect() {},
      close() {},
    },
    params: { sessionId: null, characterId: null, slotIndex: 0, _search: '' },
    isHost: true,
    container,
    GAME,
  };
  ctx.handleLocalInput = (evt) => {
    const r = GAME.onLocalInput(evt, ctx);
    if (r && r.state) GAME.render(r.state, ctx);
    return r;
  };
  ctx.onTeardown = () => {};

  const initial = await GAME.init(host, container, ctx);
  // Stop the auto-started loop immediately — we drive ticks by hand for determinism.
  GAME._stopLoop();
  assert(initial && initial.type === 'asteroids.snapshot', 'GAME.init() returns the opening snapshot');
  assert(GAME._players() === 1, 'single-player init is a 1-ship survival game');
  assert(initial.ships[0].score === 0, 'GAME.init() reports a 0 opening score');

  // ── (a) render() vector draw-call counts for the opening position ───────────
  const cx = byId.field.getContext('2d');
  Object.keys(cx._calls).forEach((k) => (cx._calls[k] = 0));
  GAME.render(initial, ctx);

  // backdrop is one fillRect (the space rectangle).
  assert(cx._calls.fillRect >= 1, `render() filled the space backdrop (fillRect=${cx._calls.fillRect})`);
  // 4 seeded asteroids → 4 closed polygons, each: beginPath + 8 lineTo-ish + closePath + stroke.
  assert(
    cx._calls.closePath >= 5,
    `render() closed >= 5 polygons (4 asteroids + 1 ship triangle) (closePath=${cx._calls.closePath})`,
  );
  // the survival ship is a stroked triangle (3 verts: moveTo + 2 lineTo).
  assert(cx._calls.stroke >= 5, `render() stroked the asteroids + ship as vectors (stroke=${cx._calls.stroke})`);
  // stars are arc+fill; bullets too (none at start) — 80 stars → >= 80 arcs.
  assert(cx._calls.arc >= 80, `render() drew the decorative star-field (arc=${cx._calls.arc})`);

  // HUD reflects the opening position.
  assert(byId.score.textContent === 'Score 0', `HUD score shows "Score 0" (got "${byId.score.textContent}")`);
  assert(
    byId.status.textContent === 'Survive the field',
    `HUD status shows the survival prompt (got "${byId.status.textContent}")`,
  );

  // ── (b) apply an input through the real wasm + one host tick ────────────────
  // Hold thrust (ArrowUp). The held-key map flips, then we step the loop body once.
  const beforeShip = host.callPacked('asteroids_snapshot').ships[0];
  assert(beforeShip.vx === 0 && beforeShip.heading === 0, 'ship is at rest, heading +X before input');

  const keyResult = ctx.handleLocalInput({ type: 'keydown', key: 'ArrowUp', preventDefault() {} });
  assert(keyResult && keyResult.input, 'ArrowUp keydown produced a relay input payload');
  assert(
    keyResult.input.payload && keyResult.input.payload.keys && keyResult.input.payload.keys.thrust === true,
    'the relayed input payload carries thrust=true',
  );
  assert(GAME._held[0].thrust === true, 'the held-key map recorded thrust held on ship 0');

  // Step exactly one authoritative tick (the loop body): applies held input, ticks.
  cx._calls.fillRect = 0;
  GAME._tickOnce();

  const afterSnap = host.callPacked('asteroids_snapshot');
  const afterShip = afterSnap.ships[0];
  assert(afterSnap.tick === 1, `one host tick advanced the simulation to tick 1 (got ${afterSnap.tick})`);
  assert(afterShip.vx > 0, `thrust at heading 0 built +X velocity through the real wasm (vx=${afterShip.vx})`);
  assert(afterShip.vy === 0, `no Y velocity at heading +X (vy=${afterShip.vy})`);
  // the tick re-rendered the field.
  assert(cx._calls.fillRect >= 1, `the host tick re-rendered the field (fillRect=${cx._calls.fillRect})`);
  // single-player broadcasts nothing (characterId is null).
  assert(relayed.filter((r) => r[0] === 'world').length === 0, 'single-player tick relays no world (characterId null)');

  // ── (c) fire applies a bullet through the real wasm ─────────────────────────
  ctx.handleLocalInput({ type: 'keydown', key: ' ', preventDefault() {} });
  assert(GAME._held[0].fire === true, 'Space keydown recorded fire held on ship 0');
  GAME._tickOnce();
  const afterFire = host.callPacked('asteroids_snapshot');
  assert(afterFire.bullets.length === 1, `firing through the real wasm spawned a bullet (bullets=${afterFire.bullets.length})`);
  assert(afterFire.bullets[0].owner === 'p1', `the bullet is owned by p1 (got "${afterFire.bullets[0].owner}")`);

  // ── (d) keyup releases the held key (no further turn/thrust persists) ───────
  ctx.handleLocalInput({ type: 'keyup', key: 'ArrowUp' });
  assert(GAME._held[0].thrust === false, 'ArrowUp keyup released the thrust held-state');

  // ── (e) unknown keys are ignored ────────────────────────────────────────────
  const ignored = ctx.handleLocalInput({ type: 'keydown', key: 'q', preventDefault() {} });
  assert(ignored === null, 'an unmapped key (q) is ignored (returns null)');

  GAME.destroy();

  // ── report ──────────────────────────────────────────────────────────────────
  console.log(`\n${passed} assertions passed, ${failures.length} failed.`);
  if (failures.length > 0) {
    console.error('FAILURES:\n  - ' + failures.join('\n  - '));
    process.exit(1);
  }
  console.log('OK — asteroids-duel frontend renders the vector field and applies inputs through the real wasm tick loop.');
}

main().catch((e) => {
  console.error('smoke crashed:', e);
  process.exit(1);
});
