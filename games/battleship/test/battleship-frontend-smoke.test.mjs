// games/battleship/test/battleship-frontend-smoke.test.mjs
//
// HEADLESS SMOKE for the battleship web frontend — the DOM-LIGHT path.
//
// WHY this path (documented per the task, same as chess/reversi): Playwright
// reports a version but is NOT usable here — its browser binaries are not
// installed and neither `playwright` nor `@playwright/test` resolves as a module;
// jsdom is not installed either. Installing either means a network download out
// of scope for a smoke test. So we run the pure-node DOM-light smoke: a
// hand-rolled minimal DOM + two RECORDING 2D canvas contexts (own + enemy fog).
// Fully deterministic, zero-install, and proves the two required things:
//
//   (a) battleship.js is loaded as a real ES module against mocked canvas 2D
//       contexts; render() of the initial (placement) state issues the EXPECTED
//       draw-call counts — 100 sea fillRect on EACH of the two 10x10 boards (200
//       total) and zero ship/shot markers on an empty board.
//   (b) the real snapdragon_battleship_wasm.wasm is driven through the shared
//       WasmHost (the shell's marshalling), and inputs are applied THROUGH the
//       game's onLocalInput path: placing a ship marks the OWN grid + the engine
//       snapshot advances placement progress; after readying (single-player
//       hot-seat auto-places the house fleet), a firing click on the ENEMY grid
//       RESOLVES a real shot through the wasm — the enemy fog grid records a
//       hit/miss and the engine state changes — exactly the browser flow, minus
//       the browser.
//
// Run:  node games/battleship/test/battleship-frontend-smoke.test.mjs

import { readFile } from 'node:fs/promises';
import { fileURLToPath } from 'node:url';
import { dirname, join } from 'node:path';

const here = dirname(fileURLToPath(import.meta.url));
const ROOT = join(here, '..', '..', '..'); // repo root
const BATTLESHIP_DIR = join(here, '..');
const WASM_PATH = join(BATTLESHIP_DIR, 'wasm', 'snapdragon_battleship_wasm.wasm');

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

// ── a minimal DOM element (only what battleship.js touches) ───────────────────
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
      return { left: 0, top: 0, width: this.width || 320, height: this.height || 320 };
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
}

function installDom() {
  const own = makeElement('canvas'); own.id = 'own'; own.width = 320; own.height = 320;
  const enemy = makeElement('canvas'); enemy.id = 'enemy'; enemy.width = 320; enemy.height = 320;
  const status = makeElement('span');
  const sunk = makeElement('span');
  const rotate = makeElement('button');
  const ready = makeElement('button');

  const byId = { own, enemy, status, sunk, rotate, ready };
  const container = makeElement('body');
  container._byId = byId;
  container._listeners = {};

  global.window = {
    innerWidth: 800,
    innerHeight: 600,
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
  const mod = await import(join(BATTLESHIP_DIR, 'battleship.js'));

  // ── boot the real wasm engine through the shared WasmHost (loadBytes path) ──
  const bytes = await readFile(WASM_PATH);
  const host = new WasmHost(WASM_PATH);
  await host.loadBytes(bytes);
  assert(
    typeof host.exports.battleship_resolve === 'function',
    'WasmHost bound the real snapdragon_battleship_wasm.wasm (battleship_resolve export present)',
  );
  assert(host.callStatus('battleship_new') === 1, 'WasmHost.callStatus(battleship_new) === 1');
  const openSnap = host.callPacked('battleship_snapshot');
  assert(
    openSnap && openSnap.type === 'battleship.snapshot' && openSnap.status === 'placing',
    'WasmHost.callPacked(battleship_snapshot) is the placement-phase opening',
  );

  // ── build the GAME and mount it WITHOUT the network (no session_id) ─────────
  const GAME = mod.createBattleshipGame();
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
  assert(initial && initial.status === 'placing', 'GAME.init() returns the placement-phase state');
  assert(initial.toPlace.length === 5, 'GAME.init() starts with all 5 ships to place');
  assert(GAME._localPlayer() === null, 'no session_id → hot-seat (localPlayer null, owns both fleets)');

  // ── (a) render() draw-call counts for the empty two-board opening ────────────
  const ownCx = byId.own.getContext('2d');
  const enemyCx = byId.enemy.getContext('2d');
  for (const cx of [ownCx, enemyCx]) {
    cx._calls.fillRect = 0; cx._calls.arc = 0; cx._calls.fill = 0;
  }
  GAME.render(initial, ctx);
  assert(ownCx._calls.fillRect === 100, `own board painted exactly 100 sea cells (fillRect=${ownCx._calls.fillRect})`);
  assert(enemyCx._calls.fillRect === 100, `enemy board painted exactly 100 sea cells (fillRect=${enemyCx._calls.fillRect})`);
  assert(ownCx._calls.arc === 0 && enemyCx._calls.arc === 0, 'no ship/shot markers on the empty opening (arc=0 both)');
  assert(
    byId.status.textContent.includes('carrier'),
    `HUD prompts to place the carrier (got "${byId.status.textContent}")`,
  );
  assert(byId.ready.disabled === true, 'Ready disabled until the whole fleet is placed');

  // ── (b1) placement applies through the real wasm ────────────────────────────
  const cell = GAME._ui.cell;
  assert(cell > 0, `boards laid out with a positive cell size (cell=${cell})`);
  function clickOwn(col, row) {
    return ctx.handleLocalInput({
      type: 'click', board: 'own',
      offsetX: col * cell + Math.floor(cell / 2),
      offsetY: row * cell + Math.floor(cell / 2),
    });
  }
  function clickEnemy(col, row) {
    return ctx.handleLocalInput({
      type: 'click', board: 'enemy',
      offsetX: col * cell + Math.floor(cell / 2),
      offsetY: row * cell + Math.floor(cell / 2),
    });
  }

  // Place the carrier horizontally from A1 (col 0,row 0 → 5 cells across the top).
  ownCx._calls.fillRect = 0; ownCx._calls.fill = 0;
  clickOwn(0, 0);
  const afterCarrier = host.callPacked('battleship_snapshot');
  assert(afterCarrier.p1Placed === 1, `engine snapshot shows 1 ship placed after the carrier click (got ${afterCarrier.p1Placed})`);
  assert(GAME._getState().toPlace.length === 4, 'frontend now has 4 ships left to place');
  assert(GAME._getState().own[0][0] === 'ship', 'A1 marked as a ship cell on the OWN grid');
  // The own board now renders ship hull rectangles (extra fillRect beyond the 100 sea).
  assert(ownCx._calls.fillRect > 100, `own render drew ship hull rects (fillRect=${ownCx._calls.fillRect} > 100)`);

  // Place the remaining four ships at the standard distinct-row spots.
  clickOwn(0, 1); // battleship A2 (len 4)
  clickOwn(0, 2); // cruiser A3 (len 3)
  clickOwn(0, 3); // submarine A4 (len 3)
  clickOwn(0, 4); // destroyer A5 (len 2)
  assert(GAME._getState().toPlace.length === 0, 'all five ships placed');
  assert(host.callPacked('battleship_snapshot').p1Placed === 5, 'engine confirms 5 ships placed for P1');
  assert(byId.ready.disabled === false, 'Ready enabled once the fleet is complete');

  // An out-of-bounds placement click is rejected (no ship left, but try an
  // overlap on an occupied cell instead — clicking A1 again does nothing since
  // there are no ships left to place).
  const noop = clickOwn(0, 0);
  assert(noop === null || (noop && noop.state === GAME._getState()), 'clicking after all ships placed is a no-op');

  // ── (b2) ready → firing; a real shot resolves through the wasm ──────────────
  ctx.handleLocalInput({ type: 'ready' });
  const firingSnap = host.callPacked('battleship_snapshot');
  assert(firingSnap.status === 'firing', `both fleets ready (hot-seat auto-placed the house) → firing (got "${firingSnap.status}")`);
  assert(firingSnap.turn === 'p1', 'P1 (the human) fires first');
  assert(GAME._getState().status === 'firing', 'frontend state advanced to firing');

  // Fire at A1 on the ENEMY grid — the house fleet's carrier sits on A1..E1 → HIT.
  enemyCx._calls.fillRect = 0; enemyCx._calls.arc = 0;
  const fire = clickEnemy(0, 0);
  assert(fire && fire.state, 'firing click produced a state result');
  assert(GAME._getState().enemy[0][0] === 'hit', `A1 on the enemy fog grid recorded a HIT (got "${GAME._getState().enemy[0][0]}")`);
  // The engine advanced the turn after the resolved shot.
  const afterFire = host.callPacked('battleship_snapshot');
  assert(afterFire.turn === 'p2', `after a hit the engine turn advanced to p2 (got "${afterFire.turn}")`);
  // The enemy render now draws a hit marker (an arc on the fog grid).
  assert(enemyCx._calls.arc >= 1, `enemy render drew the hit marker (arc=${enemyCx._calls.arc})`);

  // A repeat click on A1 (already fired) is a no-op (fog cell already set).
  const refire = clickEnemy(0, 0);
  assert(refire === null, 're-firing an already-fired enemy cell returns null (no-op)');

  // A shot at open water (J10 on the house fleet → MISS).
  const miss = clickEnemy(9, 9);
  // Note: it's p2's turn now in the engine; the hot-seat human still "fires" since
  // localPlayer is null (either side may move in hot-seat). Assert the miss marker.
  assert(
    GAME._getState().enemy[9][9] === 'miss',
    `J10 on the enemy fog grid recorded a MISS (got "${GAME._getState().enemy[9][9]}")`,
  );

  // ── report ──────────────────────────────────────────────────────────────────
  console.log(`\n${passed} assertions passed, ${failures.length} failed.`);
  if (failures.length > 0) {
    console.error('FAILURES:\n  - ' + failures.join('\n  - '));
    process.exit(1);
  }
  console.log('OK — battleship frontend renders two grids and applies placement + a real shot through the wasm.');
}

main().catch((e) => {
  console.error('smoke crashed:', e);
  process.exit(1);
});
