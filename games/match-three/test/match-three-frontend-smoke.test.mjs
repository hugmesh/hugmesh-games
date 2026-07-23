// games/match-three/test/match-three-frontend-smoke.test.mjs
//
// HEADLESS SMOKE for the Match-3 web frontend — the DOM-LIGHT path (mirrors
// games/reversi/test/reversi-frontend-smoke.test.mjs).
//
// WHY this path (documented per the task, same as chess/reversi): Playwright
// reports a version but is NOT usable here — its browser binaries are not installed
// and neither `playwright` nor `@playwright/test` resolves as a module; jsdom is not
// installed either. Installing either means a network download out of scope for a
// smoke test. So we run the pure-node DOM-light smoke: a hand-rolled minimal DOM +
// a RECORDING 2D canvas context. Fully deterministic, zero-install, and proves the
// two required things:
//
//   (a) match-three.js is loaded as a real ES module against a mocked canvas 2D
//       context; render() of the opening (seeded) board issues the EXPECTED
//       draw-call counts — 64 cell fillRect (8x8) + 64 gem arcs, with NO selection
//       ring before the first click.
//   (b) the real hugmesh_match_three_wasm.wasm is driven through the shared
//       WasmHost (the shell's marshalling), and a swap (the first legal swap for
//       the default seed, cells (6,0)<->(7,0)) is applied THROUGH the game's
//       onLocalInput two-click path: the board canvas is repainted, the board
//       changes, the score advances 0 -> 30, the move counter goes 0 -> 1, and the
//       status stays "playing" — exactly the browser flow, minus the browser. This
//       is a SINGLE-PLAYER puzzle, so onLocalInput returns ONLY { state } (no relay
//       input/world/control payloads).
//
// Run:  node games/match-three/test/match-three-frontend-smoke.test.mjs

import { readFile } from 'node:fs/promises';
import { fileURLToPath } from 'node:url';
import { dirname, join } from 'node:path';

const here = dirname(fileURLToPath(import.meta.url));
const ROOT = join(here, '..', '..', '..'); // repo root
const M3_DIR = join(here, '..');
const WASM_PATH = join(M3_DIR, 'wasm', 'hugmesh_match_three_wasm.wasm');

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

// ── a minimal DOM element (only what match-three.js touches) ──────────────────
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

// ── install a minimal global DOM the shell + match-three.js need ──────────────
function installDom() {
  const board = makeElement('canvas');
  board.id = 'board';
  board.width = 384;
  board.height = 384;

  const score = makeElement('span');
  const moves = makeElement('span');
  const status = makeElement('span');
  const newGame = makeElement('button');

  const byId = { board, score, moves, status, 'new-game': newGame };
  const container = makeElement('body');
  container._byId = byId;
  container._listeners = {};

  global.window = {
    innerWidth: 480,
    innerHeight: 480,
    location: { search: '' }, // default config (seed 1)
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
  const m3Mod = await import(join(M3_DIR, 'match-three.js'));

  // ── boot the real wasm engine through the shared WasmHost (loadBytes path) ──
  const bytes = await readFile(WASM_PATH);
  const host = new WasmHost(WASM_PATH);
  await host.loadBytes(bytes);
  assert(
    typeof host.exports.match3_swap === 'function',
    'WasmHost bound the real hugmesh_match_three_wasm.wasm (match3_swap export present)',
  );

  // Sanity: the marshalling contract works through WasmHost (proves the shell
  // drives the real .wasm, not a re-implementation).
  assert(host.callStatus('match3_new', '8,8,6,1') === 1, 'WasmHost.callStatus(match3_new, "8,8,6,1") === 1');
  const openSnap = host.callPacked('match3_snapshot');
  assert(
    openSnap && Array.isArray(openSnap.board) && openSnap.board.length === 8 && openSnap.status === 'playing',
    'WasmHost.callPacked(match3_snapshot) is the opening 8x8 board (playing)',
  );
  const openLegal = host.callPacked('match3_legal');
  assert(
    Array.isArray(openLegal) && openLegal.length === 18,
    `WasmHost.callPacked(match3_legal) lists 18 swaps for seed 1 (got ${openLegal && openLegal.length})`,
  );
  assert(
    JSON.stringify(openLegal[0]) === JSON.stringify([6, 0, 7, 0]),
    `the first legal swap for seed 1 is [6,0,7,0] (got ${JSON.stringify(openLegal[0])})`,
  );

  // ── build the GAME and mount it WITHOUT the network (no session_id) ─────────
  const GAME = m3Mod.createMatchThreeGame();
  const ctx = {
    host,
    client: { sendInput() {}, sendControl() {}, sendWorld() {}, on() {}, connect() {}, close() {} },
    params: { sessionId: null, characterId: null, slotIndex: 0, search: '' },
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
  assert(initial && Array.isArray(initial.board) && initial.board.length === 8, 'GAME.init() returns the opening 8x8 board state');
  assert(initial.score === 0, 'GAME.init() reports the opening score 0');
  assert(initial.status === 'playing', 'GAME.init() reports status "playing"');

  // ── (a) render() draw-call counts for the opening board ─────────────────────
  const cx = byId.board.getContext('2d');
  cx._calls.fillRect = 0;
  cx._calls.arc = 0;
  cx._calls.fill = 0;
  GAME.render(initial, ctx);

  assert(
    cx._calls.fillRect === 64,
    `render() painted 64 cell backings on the 8x8 board (fillRect=${cx._calls.fillRect})`,
  );
  // 64 gems, one arc + fill each; no selection ring before any click.
  assert(
    cx._calls.arc === 64,
    `render() drew exactly 64 gem arcs at the opening, no selection ring (arc=${cx._calls.arc})`,
  );
  assert(
    cx._calls.fill === 64,
    `render() filled 64 gems at the opening (fill=${cx._calls.fill})`,
  );

  // HUD reflects the opening.
  assert(byId.score.textContent === 'Score: 0', `HUD score shows "Score: 0" (got "${byId.score.textContent}")`);
  assert(byId.moves.textContent === 'Moves: 0', `HUD moves shows "Moves: 0" (got "${byId.moves.textContent}")`);
  assert(byId.status.textContent.startsWith('Swap two adjacent gems'), `HUD status prompts to swap (got "${byId.status.textContent}")`);

  // ── (b) two-click swap of (6,0)<->(7,0): the first legal swap ───────────────
  const cell = GAME._ui.cell;
  assert(cell > 0, `board laid out with a positive cell size (cell=${cell})`);

  // Click center of cell (x,y); y=0 is the TOP row (engine row-major, y down).
  function clickCell(x, y) {
    const offsetX = x * cell + Math.floor(cell / 2);
    const offsetY = y * cell + Math.floor(cell / 2);
    return ctx.handleLocalInput({ type: 'click', offsetX, offsetY });
  }

  // First click selects (6,0) — no engine mutation, a selection ring is drawn.
  cx._calls.stroke = 0;
  const sel = clickCell(6, 0);
  assert(sel && sel.state && !sel.input && !sel.world && !sel.control, 'first click returns ONLY { state } (single-player, no relay payloads)');
  assert(GAME._ui.selected && GAME._ui.selected.x === 6 && GAME._ui.selected.y === 0, 'first click selected gem (6,0)');
  assert(host.callPacked('match3_score').score === 0, 'first click did NOT mutate the engine (score still 0)');

  // Second click on the adjacent (7,0) applies the swap.
  const startBoard = JSON.stringify(host.callPacked('match3_snapshot').board);
  cx._calls.fillRect = 0;
  cx._calls.arc = 0;
  const moveResult = clickCell(7, 0);
  assert(moveResult && moveResult.state, 'second (adjacent) click produced a state');
  assert(!moveResult.input && !moveResult.world && !moveResult.control, 'the swap returns NO relay payloads (single-player puzzle)');
  assert(GAME._ui.selected === null, 'selection cleared after the swap');

  // The engine state advanced: board changed, score 0 -> 30, still playing.
  const afterBoard = JSON.stringify(host.callPacked('match3_snapshot').board);
  assert(afterBoard !== startBoard, 'board changed after the swap (cascade settled)');
  assert(GAME._getState().score === 30, `score advanced to 30 after the swap (got ${GAME._getState().score})`);
  assert(GAME._getState().status === 'playing', `game still playing after the swap (got "${GAME._getState().status}")`);
  assert(GAME._getMoves() === 1, `move counter incremented to 1 (got ${GAME._getMoves()})`);

  // HUD reflects the post-swap state.
  assert(byId.score.textContent === 'Score: 30', `HUD score shows "Score: 30" (got "${byId.score.textContent}")`);
  assert(byId.moves.textContent === 'Moves: 1', `HUD moves shows "Moves: 1" (got "${byId.moves.textContent}")`);

  // Post-swap render repainted the full board.
  assert(cx._calls.fillRect === 64, `post-swap render repainted the 64 cell backings (fillRect=${cx._calls.fillRect})`);
  assert(cx._calls.arc === 64, `post-swap render redrew the 64 gems (arc=${cx._calls.arc})`);

  // ── illegal-swap guard: an adjacent non-matching swap is a no-op move ───────
  const beforeScore = GAME._getState().score;
  const beforeMoves = GAME._getMoves();
  const beforeBoard = JSON.stringify(host.callPacked('match3_snapshot').board);
  // Re-seed to a clean board so we know a specific non-matching adjacent pair.
  host.callStatus('match3_new', '8,8,6,1');
  GAME.render(host.callPacked('match3_snapshot'), ctx);
  // (0,0)<->(1,0) is adjacent but forms no run for seed 1.
  clickCell(0, 0);
  const illegal = clickCell(1, 0);
  assert(illegal && illegal.state, 'an adjacent non-matching swap still returns a state');
  assert(
    byId.status.textContent.startsWith('No match'),
    `HUD flashes "No match" on an illegal swap (got "${byId.status.textContent}")`,
  );
  assert(GAME._ui.selected === null, 'selection cleared after a rejected swap attempt');

  // ── New-game button reseeds (deterministically) to a fresh board ────────────
  const cfgBefore = GAME._getConfig();
  const newRes = ctx.handleLocalInput({ type: 'new-game' });
  assert(newRes && newRes.state, 'New-game produced a fresh board state');
  assert(GAME._getMoves() === 0, 'New-game reset the move counter to 0');
  assert(GAME._getState().score === 0, 'New-game reset the score to 0');
  assert(GAME._getConfig().seed !== cfgBefore.seed, 'New-game advanced to a different seed');

  // ── report ──────────────────────────────────────────────────────────────────
  console.log(`\n${passed} assertions passed, ${failures.length} failed.`);
  if (failures.length > 0) {
    console.error('FAILURES:\n  - ' + failures.join('\n  - '));
    process.exit(1);
  }
  console.log('OK — match-three frontend renders the grid and applies a swap through the real wasm.');
}

main().catch((e) => {
  console.error('smoke crashed:', e);
  process.exit(1);
});
