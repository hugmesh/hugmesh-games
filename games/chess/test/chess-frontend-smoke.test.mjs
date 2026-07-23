// games/chess/test/chess-frontend-smoke.test.mjs
//
// HEADLESS SMOKE for the chess web frontend — the DOM-LIGHT path.
//
// WHY this path (documented per the task): Playwright reports a version
// (npx-cached 1.60.0) but is NOT actually usable here — its browser binaries
// are not installed (~/.cache/ms-playwright is absent) and neither `playwright`
// nor `@playwright/test` resolves as a module (e2e/node_modules is uninstalled,
// nothing global). jsdom is not installed either. Installing either would mean a
// network download of a browser/lib, which is out of scope for a smoke test. So
// we run the pure-node DOM-light smoke: a hand-rolled minimal DOM + a RECORDING
// 2D canvas context. This is fully deterministic, zero-install, and proves the
// two required things:
//
//   (a) chess.js is loaded as a real ES module against a mocked canvas 2D
//       context; render() of the start position issues the EXPECTED draw-call
//       counts — 64 board fillRect + 32 piece fillText (one per starting piece).
//   (b) the real hugmesh_chess.wasm is driven through the shared WasmHost
//       (the shell's marshalling), and a move (e2e4) is applied THROUGH the
//       game's onLocalInput two-click path: the board canvas is repainted, the
//       FEN changes, and the side-to-move flips white→black — exactly the
//       browser flow, minus the browser.
//
// Run:  node games/chess/test/chess-frontend-smoke.test.mjs

import { readFile } from 'node:fs/promises';
import { fileURLToPath } from 'node:url';
import { dirname, join } from 'node:path';

const here = dirname(fileURLToPath(import.meta.url));
const ROOT = join(here, '..', '..', '..'); // repo root
const CHESS_DIR = join(here, '..');
const WASM_PATH = join(CHESS_DIR, 'wasm', 'hugmesh_chess.wasm');

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
  const calls = { fillRect: 0, fillText: 0, strokeText: 0, arc: 0, fill: 0 };
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
    clearRect() {},
  };
  return ctx;
}

// ── a minimal DOM element (only what chess.js touches) ────────────────────────
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
      // sel like '#board'
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

// ── install a minimal global DOM the shell + chess.js need ────────────────────
function installDom() {
  const board = makeElement('canvas');
  board.id = 'board';
  board.width = 384;
  board.height = 384;

  const turn = makeElement('span');
  const status = makeElement('span');
  const resign = makeElement('button');

  const byId = { board, turn, status, resign };
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

  // EventSource undefined → SessionClient stays local even if it ever connected.
  return { container, byId };
}

async function main() {
  console.log('DOM-LIGHT smoke (no browser, no jsdom, no network).');

  const { container, byId } = installDom();

  // Import the shell + game AS REAL ES MODULES (the same files the bundle ships).
  const { WasmHost } = await import(
    join(ROOT, 'games', '_shared', 'wasm-host.js')
  );
  const frame = await import(join(ROOT, 'games', '_shared', 'game-frame.js'));
  const chessMod = await import(join(CHESS_DIR, 'chess.js'));

  // ── boot the real wasm engine through the shared WasmHost (loadBytes path) ──
  const bytes = await readFile(WASM_PATH);
  const host = new WasmHost(WASM_PATH);
  await host.loadBytes(bytes);
  assert(
    typeof host.exports.chess_apply_uci === 'function',
    'WasmHost bound the real hugmesh_chess.wasm (chess_apply_uci export present)',
  );

  // Sanity: the marshalling contract works through WasmHost (proves the shell
  // drives the real .wasm, not a re-implementation).
  assert(host.callStatus('chess_new') === 1, 'WasmHost.callStatus(chess_new) === 1');
  const startMoves = host.callPacked('chess_legal_moves');
  assert(
    Array.isArray(startMoves) && startMoves.length === 20,
    `WasmHost.callPacked(chess_legal_moves) === 20 at start (got ${startMoves && startMoves.length})`,
  );
  const startFen = host.callPacked('chess_fen').fen;
  assert(
    startFen === 'rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1',
    'WasmHost.callPacked(chess_fen) is the start FEN',
  );

  // ── build the GAME and mount it WITHOUT the network (no session_id) ─────────
  const GAME = chessMod.createChessGame();
  // We mount manually so we can hand in our DOM container + skip the real
  // WasmHost.load() (fetch). mountGame loads its own host, so instead we drive
  // GAME.init / render / onLocalInput directly against the host we already have.
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
  assert(initial && initial.fen === startFen, 'GAME.init() returns the start-position state');

  // ── (a) render() draw-call counts for the start position ────────────────────
  const cx = byId.board.getContext('2d');
  cx._calls.fillRect = 0;
  cx._calls.fillText = 0;
  GAME.render(initial, ctx);

  assert(
    cx._calls.fillRect >= 64,
    `render() painted >= 64 board squares (fillRect=${cx._calls.fillRect})`,
  );
  assert(
    cx._calls.fillText === 32,
    `render() drew exactly 32 piece glyphs for the start position (fillText=${cx._calls.fillText})`,
  );
  assert(
    cx._calls.strokeText === 32,
    `render() outlined exactly 32 piece glyphs (strokeText=${cx._calls.strokeText})`,
  );

  // HUD reflects the start position.
  assert(byId.turn.textContent.includes('White'), `HUD turn shows White (got "${byId.turn.textContent}")`);
  assert(
    byId.status.textContent === 'White to move',
    `HUD status shows "White to move" (got "${byId.status.textContent}")`,
  );

  // ── (b) simulate two clicks for e2 → e4 and assert the move applied ─────────
  // e2 = file e (index 4), rank 2 → rankIdx 6.   e4 = file e, rank 4 → rankIdx 4.
  const cell = GAME._ui.cell;
  assert(cell > 0, `board laid out with a positive cell size (cell=${cell})`);

  function clickSquare(fileIdx, rankIdx) {
    const offsetX = fileIdx * cell + Math.floor(cell / 2);
    const offsetY = rankIdx * cell + Math.floor(cell / 2);
    return ctx.handleLocalInput({ type: 'click', offsetX, offsetY });
  }

  // First click: pick up the e2 pawn (selects it; legal dots appear).
  cx._calls.fillRect = 0;
  cx._calls.fillText = 0;
  const sel = clickSquare(4, 6);
  assert(GAME._ui.selected !== null, 'first click selected the e2 pawn');
  assert(
    GAME._ui.legalFromSelected.includes('e2e4'),
    `selection exposes e2e4 as a legal destination (got ${JSON.stringify(GAME._ui.legalFromSelected)})`,
  );
  // The re-render after selection draws the selection highlight + legal dots.
  assert(cx._calls.fillRect >= 64, 'selection re-render repainted the board');
  assert(cx._calls.arc >= 1, `selection re-render drew legal-move dots (arc=${cx._calls.arc})`);

  // Second click: drop on e4 → engine applies the move.
  cx._calls.fillRect = 0;
  cx._calls.fillText = 0;
  const moveResult = clickSquare(4, 4);
  assert(moveResult && moveResult.input, 'second click produced a relay input payload');
  assert(
    moveResult.input.payload.uci === 'e2e4',
    `relayed UCI is "e2e4" (got "${moveResult.input && moveResult.input.payload && moveResult.input.payload.uci}")`,
  );
  assert(
    moveResult.world && moveResult.world.fen,
    'second click produced a host-authoritative world broadcast with a fen',
  );

  // The engine state flipped: side-to-move now black, FEN changed, e-pawn moved.
  const afterFen = host.callPacked('chess_fen').fen;
  assert(afterFen !== startFen, `FEN changed after e2e4 (was start, now "${afterFen}")`);
  assert(
    afterFen.split(' ')[1] === 'b',
    `side-to-move flipped white→black after e2e4 (got "${afterFen.split(' ')[1]}")`,
  );
  assert(
    chessMod.sideToMove(afterFen) === 'b',
    'chess.js sideToMove agrees the turn flipped to black',
  );
  // The post-move render repainted the board with 32 pieces still on it (no capture).
  assert(
    cx._calls.fillText === 32,
    `post-move render still drew 32 pieces (fillText=${cx._calls.fillText})`,
  );
  // Last-move highlight present → at least the 64 base squares + tints.
  assert(cx._calls.fillRect > 64, `post-move render added last-move highlight tints (fillRect=${cx._calls.fillRect})`);

  // ── illegal-move guard: clicking an opponent piece does nothing destructive ─
  // It is now BLACK's turn (after e2e4). Clicking a WHITE piece (a1 rook,
  // fileIdx 0 / rankIdx 7) must NOT select — it isn't the side to move.
  const before = host.callPacked('chess_fen').fen;
  clickSquare(0, 7); // a1 = white rook; not black's piece → no selection
  assert(GAME._ui.selected === null, 'clicking a non-side-to-move piece selects nothing');
  assert(host.callPacked('chess_fen').fen === before, 'illegal/invalid click did not mutate the engine');

  // ── resign path renders game-over ───────────────────────────────────────────
  GAME._resign(host, 'white');
  GAME.render(GAME._getState(), ctx);
  assert(
    chessMod.isGameOver(GAME._getState().status),
    `resign produced a game-over status (got "${GAME._getState().status}")`,
  );
  assert(byId.resign.disabled === true, 'resign button disabled once the game is over');

  // ── report ──────────────────────────────────────────────────────────────────
  console.log(`\n${passed} assertions passed, ${failures.length} failed.`);
  if (failures.length > 0) {
    console.error('FAILURES:\n  - ' + failures.join('\n  - '));
    process.exit(1);
  }
  console.log('OK — chess frontend renders the board and applies a move through the real wasm.');
}

main().catch((e) => {
  console.error('smoke crashed:', e);
  process.exit(1);
});
