// games/minesweeper/test/minesweeper-frontend-smoke.test.mjs
//
// HEADLESS SMOKE for the Minesweeper web frontend — the DOM-LIGHT path,
// mirroring games/connect-four/test/connect-four-frontend-smoke.test.mjs.
//
// WHY this path: Playwright/jsdom aren't installed here (browser binaries +
// libs absent; installing would mean a network download out of scope for a
// smoke test). So we run the pure-node DOM-light smoke: a hand-rolled minimal
// DOM + a RECORDING 2D canvas context. Deterministic, zero-install, and proves:
//
//   (a) minesweeper.js is loaded as a real ES module against a mocked canvas
//       2D context; render() of the start state issues the EXPECTED draw-call
//       counts — 1 board-background fillRect + one fillRect & one strokeRect per
//       cell (81 on the 9x9 beginner board) and ZERO glyph fillText on the
//       all-hidden start board, with the HUD mine counter / timer / status seeded.
//   (b) the real hugmesh_minesweeper_wasm.wasm is driven through the shared
//       WasmHost (the shell's marshalling), and a LOCAL input (a click) is
//       applied THROUGH the game's onLocalInput reveal path: the board canvas is
//       repainted, the engine board string changes (the seeded first click
//       flood-fills a region), and count-glyph fillText calls appear — exactly
//       the browser flow, minus the browser. A right-click toggles a flag and the
//       HUD mine counter decrements.
//
// SINGLE-PLAYER, LOCAL ONLY: mounted with NO session_id, onLocalInput returns no
// input/world payload — the frame relays nothing. We also assert the click path
// produced NO relay payload (`input`/`world` absent), pinning the local-only
// contract.
//
// Run:  node games/minesweeper/test/minesweeper-frontend-smoke.test.mjs

import { readFile } from 'node:fs/promises';
import { fileURLToPath } from 'node:url';
import { dirname, join } from 'node:path';

const here = dirname(fileURLToPath(import.meta.url));
const ROOT = join(here, '..', '..', '..'); // repo root
const MINE_DIR = join(here, '..');
const WASM_PATH = join(MINE_DIR, 'wasm', 'hugmesh_minesweeper_wasm.wasm');

// The default beginner board the GAME seeds with no URL params (9x9 / 10 mines /
// seed 0). The seeded first reveal at (0,0) flood-fills a region deterministically.
const W = 9;
const H = 9;
const MINES = 10;
const CELLS = W * H; // 81

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
    strokeText: 0,
    arc: 0,
    fill: 0,
    stroke: 0,
  };
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

// ── a minimal DOM element (only what minesweeper.js touches) ──────────────────
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
      // Match the laid-out canvas pixel box so the input scale factor is 1.
      return { left: 0, top: 0, width: this.width || 324, height: this.height || 324 };
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

// ── install a minimal global DOM the shell + minesweeper.js need ──────────────
function installDom() {
  const board = makeElement('canvas');
  board.id = 'board';
  board.width = 324;
  board.height = 324;

  const mines = makeElement('span');
  const timer = makeElement('span');
  const status = makeElement('span');
  const reset = makeElement('button');

  const byId = { board, mines, timer, status, reset };
  const container = makeElement('body');
  container._byId = byId;
  container._listeners = {};

  global.window = {
    innerWidth: 800,
    innerHeight: 800,
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
  const mineMod = await import(join(MINE_DIR, 'minesweeper.js'));

  // Pin the clock so the elapsed timer is deterministic in the smoke (0s).
  mineMod._setClock(() => 0);

  // ── boot the real wasm engine through the shared WasmHost (loadBytes path) ──
  const bytes = await readFile(WASM_PATH);
  const host = new WasmHost(WASM_PATH);
  await host.loadBytes(bytes);
  assert(
    typeof host.exports.mine_reveal === 'function',
    'WasmHost bound the real hugmesh_minesweeper_wasm.wasm (mine_reveal export present)',
  );

  // Sanity: the marshalling contract works through WasmHost.
  assert(
    host.callStatus('mine_new', JSON.stringify({ width: W, height: H, mines: MINES, seed: 1 })) === 1,
    'WasmHost.callStatus(mine_new) === 1',
  );
  const probe = host.callPacked('mine_state');
  assert(
    probe && probe.type === 'minesweeper.snapshot' && probe.status === 'playing',
    'WasmHost.callPacked(mine_state) is a playing minesweeper.snapshot',
  );

  // ── build the GAME and mount it WITHOUT the network (no session_id) ─────────
  const GAME = mineMod.createMinesweeperGame();
  const ctx = {
    host,
    client: { sendInput() {}, sendControl() {}, sendWorld() {}, on() {}, connect() {}, close() {} },
    params: { sessionId: null, characterId: null, slotIndex: 0 }, // no seed → default board
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
    initial && initial.type === 'minesweeper.snapshot' && initial.width === W && initial.height === H,
    `GAME.init() returns the 9x9 start-position snapshot (got ${initial && initial.width}x${initial && initial.height})`,
  );
  assert(initial.status === 'playing' && initial.started === false,
    'start state: playing, mines not yet placed');
  assert(/^#+(\/#+)*$/.test(initial.board) && initial.board.indexOf('.') === -1,
    'start board is all hidden (no revealed cells)');

  // ── (a) render() draw-call counts for the start position ────────────────────
  const cx = byId.board.getContext('2d');
  cx._calls.fillRect = 0;
  cx._calls.strokeRect = 0;
  cx._calls.fillText = 0;
  GAME.render(initial, ctx);

  assert(
    cx._calls.fillRect === CELLS + 1,
    `render() drew the board background + one fillRect per cell (fillRect=${cx._calls.fillRect}, expected ${CELLS + 1})`,
  );
  assert(
    cx._calls.strokeRect === CELLS,
    `render() stroked one border per cell (strokeRect=${cx._calls.strokeRect}, expected ${CELLS})`,
  );
  assert(
    cx._calls.fillText === 0,
    `render() drew NO glyphs on the all-hidden start board (fillText=${cx._calls.fillText})`,
  );

  // HUD reflects the start position: mine counter = 10 mines, timer 0, status idle.
  assert(
    byId.mines.textContent === mineMod.formatCounter(MINES),
    `HUD mine counter shows ${mineMod.formatCounter(MINES)} (got "${byId.mines.textContent}")`,
  );
  assert(
    byId.timer.textContent === '000',
    `HUD timer is 000 before the first reveal (got "${byId.timer.textContent}")`,
  );
  assert(
    byId.status.textContent === mineMod.statusLine('playing'),
    `HUD status shows the idle line (got "${byId.status.textContent}")`,
  );

  // ── (b) a LOCAL input applied through the REAL wasm changes state ───────────
  const cell = GAME._ui.cell;
  const pad = GAME._ui.pad;
  assert(cell > 0, `board laid out with a positive cell size (cell=${cell})`);

  function clickCell(x, y, type) {
    const offsetX = pad + x * cell + Math.floor(cell / 2);
    const offsetY = pad + y * cell + Math.floor(cell / 2);
    return ctx.handleLocalInput({ type: type || 'click', offsetX, offsetY });
  }

  const beforeBoard = host.callPacked('mine_state').board;
  cx._calls.fillText = 0;
  cx._calls.fillRect = 0;
  cx._calls.strokeRect = 0;
  const moveResult = clickCell(0, 0);
  assert(moveResult && moveResult.state, 'a click produced a rendered state (local apply)');
  // LOCAL ONLY: the click must NOT produce any relay payload.
  assert(
    moveResult && !moveResult.input && !moveResult.world && !moveResult.control,
    'single-player: the reveal produced NO relay payload (input/world/control absent)',
  );

  // The engine state changed: the seeded first reveal flood-fills a region.
  const after = host.callPacked('mine_state');
  assert(
    after.board !== beforeBoard,
    `engine board changed after revealing (0,0) (now "${after.board}")`,
  );
  assert(after.started === true, 'the first reveal placed the mines (started flipped true)');
  assert(
    after.safeRevealed > 1,
    `the seeded first click flood-filled a region of safe cells (safeRevealed=${after.safeRevealed})`,
  );
  // The post-move render drew at least one count glyph (the flood's numbered border).
  assert(
    cx._calls.fillText >= 1,
    `post-reveal render drew count glyphs for the opened region (fillText=${cx._calls.fillText})`,
  );
  // The whole 9x9 board is still drawn.
  assert(
    cx._calls.fillRect === CELLS + 1 && cx._calls.strokeRect === CELLS,
    `post-move render still drew all ${CELLS} cells (fillRect=${cx._calls.fillRect} strokeRect=${cx._calls.strokeRect})`,
  );

  // ── flag path: a right-click toggles a flag; the HUD mine counter decrements ─
  const beforeFlags = host.callPacked('mine_state').flags;
  // (8,8) is a still-hidden corner away from the flood region — flaggable.
  const flagResult = clickCell(8, 8, 'contextmenu');
  assert(flagResult && flagResult.state, 'a right-click produced a rendered state');
  assert(
    flagResult && !flagResult.input && !flagResult.world,
    'single-player: flagging produced NO relay payload',
  );
  const afterFlag = host.callPacked('mine_state');
  assert(
    afterFlag.flags === beforeFlags + 1,
    `right-click planted a flag (flags ${beforeFlags} → ${afterFlag.flags})`,
  );
  assert(
    byId.mines.textContent === mineMod.formatCounter(MINES - afterFlag.flags),
    `HUD mine counter decremented after flagging (got "${byId.mines.textContent}")`,
  );

  // ── reset path returns to a fresh hidden board ──────────────────────────────
  const resetResult = ctx.handleLocalInput({ type: 'reset' });
  assert(resetResult && resetResult.state, 'reset produced a rendered state');
  const afterReset = host.callPacked('mine_state');
  assert(
    afterReset.started === false && afterReset.board.indexOf('.') === -1,
    'reset re-seeded the engine to a fresh, all-hidden board',
  );
  assert(
    mineMod.isGameOver(GAME._getState().status) === false,
    'reset state is not game-over',
  );

  // Clean up the 1 Hz HUD timer so node exits.
  if (typeof GAME.destroy === 'function') GAME.destroy();

  // ── report ──────────────────────────────────────────────────────────────────
  console.log(`\n${passed} assertions passed, ${failures.length} failed.`);
  if (failures.length > 0) {
    console.error('FAILURES:\n  - ' + failures.join('\n  - '));
    process.exit(1);
  }
  console.log('OK — Minesweeper frontend renders the board and applies a reveal through the real wasm.');
}

main().catch((e) => {
  console.error('smoke crashed:', e);
  process.exit(1);
});
