// games/gomoku/test/gomoku-frontend-smoke.test.mjs
//
// HEADLESS SMOKE for the gomoku web frontend — the DOM-LIGHT path (the same
// approach proven for chess: Playwright's browser binaries aren't installed and
// jsdom isn't available, so we run a pure-node DOM-light smoke with a hand-rolled
// minimal DOM + a RECORDING 2D canvas context). Deterministic, zero-install, and
// proves the two required things:
//
//   (a) gomoku.js is loaded as a real ES module against a mocked canvas 2D
//       context; render() of the START position issues the EXPECTED draw-call
//       counts — 30 grid-line strokes (15 horizontal + 15 vertical) and ZERO
//       stone fills (an empty board), with the 9 star-point fills.
//   (b) the real hugmesh_gomoku.wasm is driven through the shared WasmHost,
//       and a placement (h8) applied THROUGH the game's onLocalInput click path
//       changes the engine state: the board gains a stone and the turn flips
//       black→white — exactly the browser flow, minus the browser.
//
// Run:  node games/gomoku/test/gomoku-frontend-smoke.test.mjs

import { readFile } from 'node:fs/promises';
import { fileURLToPath } from 'node:url';
import { dirname, join } from 'node:path';

const here = dirname(fileURLToPath(import.meta.url));
const ROOT = join(here, '..', '..', '..'); // repo root
const GOMOKU_DIR = join(here, '..');
const WASM_PATH = join(GOMOKU_DIR, 'wasm', 'hugmesh_gomoku.wasm');

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
    moveTo() {
      calls.moveTo++;
    },
    lineTo() {
      calls.lineTo++;
    },
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

// ── a minimal DOM element (only what gomoku.js touches) ───────────────────────
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
      return { left: 0, top: 0, width: this.width || 540, height: this.height || 540 };
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

// ── install a minimal global DOM the shell + gomoku.js need ───────────────────
function installDom() {
  const board = makeElement('canvas');
  board.id = 'board';
  board.width = 540;
  board.height = 540;

  const turn = makeElement('span');
  const status = makeElement('span');

  const byId = { board, turn, status };
  const container = makeElement('body');
  container._byId = byId;
  container._listeners = {};

  global.window = {
    innerWidth: 560,
    innerHeight: 560,
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

  // Import the shell + game AS REAL ES MODULES (the same files the bundle ships).
  const { WasmHost } = await import(join(ROOT, 'games', '_shared', 'wasm-host.js'));
  const gomokuMod = await import(join(GOMOKU_DIR, 'gomoku.js'));

  // ── boot the real wasm engine through the shared WasmHost (loadBytes path) ──
  const bytes = await readFile(WASM_PATH);
  const host = new WasmHost(WASM_PATH);
  await host.loadBytes(bytes);
  assert(
    typeof host.exports.gomoku_apply === 'function',
    'WasmHost bound the real hugmesh_gomoku.wasm (gomoku_apply export present)',
  );

  // Sanity: the marshalling contract works through WasmHost.
  assert(host.callStatus('gomoku_new') === 1, 'WasmHost.callStatus(gomoku_new) === 1');
  const startMoves = host.callPacked('gomoku_legal');
  assert(
    Array.isArray(startMoves) && startMoves.length === 225,
    `WasmHost.callPacked(gomoku_legal) === 225 at start (got ${startMoves && startMoves.length})`,
  );
  const startState = host.callPacked('gomoku_state');
  assert(
    startState && startState.turn === 'black' && startState.board.length === 225,
    'WasmHost.callPacked(gomoku_state) reports an empty board, black to move',
  );

  // ── build the GAME and mount it WITHOUT the network (no session_id) ─────────
  const GAME = gomokuMod.createGomokuGame();
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
    initial && initial.board === '.'.repeat(225) && initial.turn === 'black',
    'GAME.init() returns the start-position state (empty board, black to move)',
  );

  // ── (a) render() draw-call counts for the START position ────────────────────
  const cx = byId.board.getContext('2d');
  cx._calls.stroke = 0;
  cx._calls.fill = 0;
  cx._calls.arc = 0;
  GAME.render(initial, ctx);

  // 15 horizontal + 15 vertical grid lines = 30 stroke() calls for the grid.
  assert(
    cx._calls.stroke === 30,
    `render() stroked exactly 30 grid lines for the empty board (stroke=${cx._calls.stroke})`,
  );
  // 9 star points (hoshi) each get an arc + fill; no stones on an empty board.
  assert(
    cx._calls.arc === 9,
    `render() drew exactly 9 star-point arcs and NO stones at start (arc=${cx._calls.arc})`,
  );
  assert(
    cx._calls.fill === 9,
    `render() filled exactly the 9 star points at start (wood uses fillRect) (fill=${cx._calls.fill})`,
  );
  assert(
    cx._calls.fillRect === 1,
    `render() painted the wood background with one fillRect (fillRect=${cx._calls.fillRect})`,
  );

  // HUD reflects the start position.
  assert(byId.turn.textContent.includes('Black'), `HUD turn shows Black (got "${byId.turn.textContent}")`);
  assert(
    byId.status.textContent === 'Black to move',
    `HUD status shows "Black to move" (got "${byId.status.textContent}")`,
  );

  // ── (b) simulate a click on the centre intersection h8 and assert it applied ─
  const ui = GAME._ui;
  assert(ui.cell > 0, `board laid out with a positive cell size (cell=${ui.cell})`);

  // h8 = file h (col index 7), row 8 (row index 7). Compute its pixel centre.
  const COL = 7;
  const ROW = 7;
  const offsetX = ui.margin + COL * ui.cell;
  const offsetY = ui.margin + ROW * ui.cell;

  cx._calls.arc = 0;
  cx._calls.fill = 0;
  const beforeState = host.callPacked('gomoku_state');
  const moveResult = ctx.handleLocalInput({ type: 'click', offsetX, offsetY });

  assert(moveResult && moveResult.input, 'click produced a relay input payload');
  assert(
    moveResult.input.payload.coord === 'h8',
    `relayed coordinate is "h8" (got "${moveResult.input && moveResult.input.payload && moveResult.input.payload.coord}")`,
  );
  assert(
    moveResult.world && typeof moveResult.world.board === 'string',
    'click produced a host-authoritative world broadcast with a board',
  );

  // The engine state changed: one stone placed, turn flipped black→white.
  const afterState = host.callPacked('gomoku_state');
  assert(
    afterState.moves === beforeState.moves + 1,
    `stone count grew by one after the placement (was ${beforeState.moves}, now ${afterState.moves})`,
  );
  assert(
    afterState.turn === 'white',
    `side-to-move flipped black→white after h8 (got "${afterState.turn}")`,
  );
  // The placed stone sits at h8 (idx 7*15 + 7 = 112) and is Black ('X').
  assert(
    afterState.board[7 * 15 + 7] === 'X',
    `the h8 intersection now holds a Black stone (got "${afterState.board[7 * 15 + 7]}")`,
  );
  // The post-move render drew 9 hoshi arcs + the stone disc + its last-move ring
  // (the just-placed stone is the last move) = 11 arcs.
  assert(
    cx._calls.arc === 11,
    `post-move render drew 9 hoshi + 1 stone + 1 last-move ring = 11 arcs (arc=${cx._calls.arc})`,
  );

  // ── occupied-cell guard: clicking h8 again does nothing destructive ─────────
  const before = host.callPacked('gomoku_state').board;
  const reclick = ctx.handleLocalInput({ type: 'click', offsetX, offsetY });
  assert(reclick === null, 'clicking an already-occupied intersection is ignored (returns null)');
  assert(
    host.callPacked('gomoku_state').board === before,
    'the occupied re-click did not mutate the engine',
  );

  // ── a winning line renders game-over + a win line ───────────────────────────
  // Reset, then drive Black to a horizontal five on row 8 (a8..e8) with White
  // replying on row 1. Done through the SAME click path the player uses.
  host.callStatus('gomoku_new');
  GAME.render(GAME._getState() && { board: '.'.repeat(225), turn: 'black', status: 'ongoing', lastMove: null, winLine: [] }, ctx);
  function clickCoord(col, row) {
    const x = ui.margin + col * ui.cell;
    const y = ui.margin + row * ui.cell;
    return ctx.handleLocalInput({ type: 'click', offsetX: x, offsetY: y });
  }
  // a..e are cols 0..4; row 8 is row index 7; row 1 is row index 0.
  clickCoord(0, 7); // B a8
  clickCoord(0, 0); // W a1
  clickCoord(1, 7); // B b8
  clickCoord(1, 0); // W b1
  clickCoord(2, 7); // B c8
  clickCoord(2, 0); // W c1
  clickCoord(3, 7); // B d8
  clickCoord(3, 0); // W d1
  const winResult = clickCoord(4, 7); // B e8 → five in a row

  assert(
    winResult && winResult.world && winResult.world.win === true,
    'the fifth-in-a-row click broadcast win:true',
  );
  assert(
    gomokuMod.isGameOver(GAME._getState().status) && GAME._getState().status === 'win:black',
    `winning click produced status "win:black" (got "${GAME._getState().status}")`,
  );
  assert(
    Array.isArray(GAME._getState().winLine) && GAME._getState().winLine.length === 5,
    `the rendered state carries a 5-point winning line (got ${JSON.stringify(GAME._getState().winLine)})`,
  );

  // ── report ──────────────────────────────────────────────────────────────────
  console.log(`\n${passed} assertions passed, ${failures.length} failed.`);
  if (failures.length > 0) {
    console.error('FAILURES:\n  - ' + failures.join('\n  - '));
    process.exit(1);
  }
  console.log('OK — gomoku frontend renders the board and applies a move through the real wasm.');
}

main().catch((e) => {
  console.error('smoke crashed:', e);
  process.exit(1);
});
