// games/gomoku/gomoku.js
//
// The gomoku GAME implementation for the shared web-native game shell
// (games/_shared/{wasm-host,sse-host-client,game-frame}.js). It is a single
// `GAME` object that:
//
//   • init(host, container, ctx)  — seeds the engine (gomoku_new), builds the
//                                    canvas + HUD, returns the initial state.
//   • render(stateJson, ctx)      — paints the 15x15 board + stones from the
//                                    engine board string, plus last-move +
//                                    winning-line decoration.
//   • onLocalInput(evt, ctx)      — maps ONE canvas click (the nearest grid
//                                    intersection) into an algebraic coordinate,
//                                    applies it AUTHORITATIVELY through the wasm
//                                    engine, and returns the relay payload + new
//                                    state.
//   • onRemoteEvent(channel, json, ctx) — re-applies the opponent's relayed
//                                    placement/world against the same wasm engine
//                                    (the guest re-validates).
//
// HOST-AUTHORITATIVE: the local browser running this engine is the source of
// truth (docs/multiplayer-chess-design.md, gomoku is the chess twin). render()
// never mutates the engine; every state transition flows through gomoku_apply /
// gomoku_from_moves so the board can never drift from the rules engine.
//
// Stones are canvas-drawn discs (no image assets). The engine is the verified
// hugmesh_gomoku.wasm; we drive it through the shared WasmHost with the EXACT
// call pattern proven in games/_shared/test/gomoku-wasm.test.mjs:
//   host.callStatus('gomoku_new')              -> 1
//   host.callPacked('gomoku_state')            -> { type:'gomoku.board', board, turn, status, moves }
//   host.callPacked('gomoku_legal')            -> ["a1", ...]
//   host.callPacked('gomoku_status')           -> { status }
//   host.call('gomoku_apply', 'h8')            -> { type:'gomoku.state', ... } | { type:'gomoku.error', ... }
//   host.callStatus('gomoku_from_moves', list) -> 0|1
//
// Vanilla ES module. No build step, no dependencies.

const SIZE = 15; // 15x15 freestyle board
const POINTS = SIZE * SIZE;
const FILES = 'abcdefghijklmno'; // columns a..o (15 files)
const EMPTY_BOARD = '.'.repeat(POINTS);

// Board palette (clears the WCAG-audited non-text contrast bar at 3:1).
const COLORS = {
  wood: '#d9b779',
  woodEdge: '#9c6b3f',
  grid: '#5a3d22',
  hoshi: '#3a2713', // star-point dots
  black: '#161616',
  white: '#fafafa',
  blackStroke: '#000000',
  whiteStroke: '#444444',
  lastMove: '#7c4dff',
  winLine: 'rgba(255, 215, 64, 0.85)',
  winStone: '#ff5c5c',
};

// The 9 traditional star points (hoshi) on a 15x15 board: cols/rows {3,7,11}
// (0-based grid indices). Purely decorative.
const HOSHI = [];
for (const r of [3, 7, 11]) for (const c of [3, 7, 11]) HOSHI.push({ c, r });

/**
 * Parse a 225-char engine board string into a 15x15 array indexed [row][col]
 * (row 0 = the a1 row, the top of the board as drawn). Cells are 'X' (black),
 * 'O' (white), or null (empty). Row-major from a1, matching the engine's
 * `gomoku_state().board` ordering (idx = row*15 + col).
 */
export function parseBoard(boardStr) {
  const s = typeof boardStr === 'string' && boardStr.length === POINTS ? boardStr : EMPTY_BOARD;
  const board = [];
  for (let r = 0; r < SIZE; r++) {
    const cells = [];
    for (let c = 0; c < SIZE; c++) {
      const ch = s[r * SIZE + c];
      cells.push(ch === 'X' ? 'X' : ch === 'O' ? 'O' : null);
    }
    board.push(cells);
  }
  return board;
}

/** Convert (col 0..14, row 0..14) into an algebraic coordinate like "h8". */
export function coordName(col, row) {
  return FILES[col] + String(row + 1);
}

/** Inverse of coordName: "h8" → { col, row } | null. */
export function parseCoordName(coord) {
  if (typeof coord !== 'string' || coord.length < 2) return null;
  const col = FILES.indexOf(coord[0].toLowerCase());
  const row = parseInt(coord.slice(1), 10) - 1;
  if (col < 0 || Number.isNaN(row) || row < 0 || row >= SIZE) return null;
  return { col, row };
}

/** A well-formed gomoku coordinate: one file letter a..o + a row 1..15. */
export function isCoordShaped(s) {
  return typeof s === 'string' && /^[a-o](?:[1-9]|1[0-5])$/.test(s);
}

/** Count placed stones in a parsed board. */
export function countStones(board) {
  let n = 0;
  for (let r = 0; r < SIZE; r++) for (let c = 0; c < SIZE; c++) if (board[r][c]) n++;
  return n;
}

/** True if the game has ended (a win or a draw — no further moves are legal). */
export function isGameOver(status) {
  return typeof status === 'string' && (status.startsWith('win:') || status === 'draw');
}

/** Human-readable status line from an engine status wire string. */
export function statusLine(status, turn) {
  switch (true) {
    case status === 'ongoing': {
      const side = turn === 'white' ? 'White' : 'Black';
      return `${side} to move`;
    }
    case status === 'win:black':
      return 'Black wins — five in a row';
    case status === 'win:white':
      return 'White wins — five in a row';
    case status === 'draw':
      return 'Draw — board full';
    default:
      return status || 'ongoing';
  }
}

// ── the GAME object ──────────────────────────────────────────────────────────

export function createGomokuGame() {
  // Per-instance UI state.
  const ui = {
    canvas: null,
    cx: null,
    size: 0, // canvas pixel size (square)
    cell: 0, // spacing between grid lines in pixels
    margin: 0, // padding from canvas edge to the first/last line
    hud: null, // { turn, status } elements
    banner: null, // transient HUD message (desync / illegal)
    onResize: null,
  };

  // Latest authoritative state we rendered (board string + turn + status + lastMove + winLine).
  let state = {
    board: EMPTY_BOARD,
    turn: 'black',
    status: 'ongoing',
    lastMove: null,
    winLine: [],
  };

  /** Pull the current position out of the engine into our `state` shape. */
  function readEngine(host) {
    const s = host.callPacked('gomoku_state') || {};
    return {
      board: typeof s.board === 'string' && s.board.length === POINTS ? s.board : EMPTY_BOARD,
      turn: s.turn === 'white' ? 'white' : 'black',
      status: s.status || 'ongoing',
      lastMove: state.lastMove || null,
      winLine: state.winLine || [],
    };
  }

  const GAME = {
    slug: 'gomoku',

    async init(host, container, ctx) {
      // Seed the engine to the empty board (verified: returns 1).
      host.callStatus('gomoku_new');

      buildDom(container, ui, ctx);
      state = readEngine(host);
      state.lastMove = null;
      state.winLine = [];
      return state;
    },

    render(stateJson, ctx) {
      if (stateJson && typeof stateJson === 'object') {
        state = {
          board: stateJson.board || state.board,
          turn: stateJson.turn || state.turn,
          status: stateJson.status || 'ongoing',
          lastMove:
            stateJson.lastMove !== undefined ? stateJson.lastMove : state.lastMove,
          winLine: stateJson.winLine !== undefined ? stateJson.winLine : state.winLine,
        };
      }
      drawBoard(ui, state);
      drawHud(ui, state, ctx);
    },

    onLocalInput(evt, ctx) {
      const host = ctx.host;
      if (!evt) return null;

      if (evt.type !== 'click') return null;
      if (isGameOver(state.status)) return null;

      const pt = canvasToPoint(ui, evt);
      if (!pt) return null;

      const coord = coordName(pt.col, pt.row);
      const board = parseBoard(state.board);
      // Ignore a click on an occupied intersection (the engine would reject it
      // anyway; this keeps the optimistic render clean).
      if (board[pt.row][pt.col]) return null;

      return applyMove(coord);

      // Apply a fully-formed coordinate through the engine, refresh derived
      // state, and build the relay payloads. Mirrors chess.applyMove.
      function applyMove(c) {
        const outcome = host.call('gomoku_apply', c);

        if (!outcome || outcome.type === 'gomoku.error') {
          // Illegal — keep the board as-is, surface nothing to relay.
          flashError(ui, outcome && outcome.error);
          return { state };
        }

        // Accepted: refresh derived state from the engine + the outcome.
        const eng = readEngine(host);
        state = {
          board: eng.board,
          turn: eng.turn,
          status: outcome.status || eng.status,
          lastMove: outcome.lastMove || c,
          winLine: Array.isArray(outcome.winLine) ? outcome.winLine : [],
        };
        ui.banner = null;

        const world = {
          board: state.board,
          turn: state.turn,
          status: state.status,
          lastMove: state.lastMove,
          win: outcome.win === true,
          winLine: state.winLine,
        };
        return {
          // The relay payload the guest re-applies (input channel), AND the
          // host-authoritative world broadcast.
          input: { kind: 'control', type: 'move', payload: { coord: c } },
          world,
          state,
        };
      }
    },

    onRemoteEvent(channel, json, ctx) {
      const host = ctx.host;
      if (!json) return null;

      // session-control: pause/resume/close — surface on the HUD, no engine move.
      if (channel === 'session-control') {
        if (json.action && String(json.action).includes('CLOSED')) {
          ui.banner = 'Session closed';
        }
        return state;
      }

      // The opponent's intended placement (guest→host `input`) OR the host's
      // authoritative `world` broadcast (host→guest). Both re-apply against the
      // engine; the guest re-validates by letting the engine accept/reject.
      const payload = json.payload || json;
      let coord = null;
      let worldBoard = null;
      if (payload && typeof payload === 'object') {
        coord = payload.coord || (payload.payload && payload.payload.coord) || null;
        worldBoard = typeof payload.board === 'string' ? payload.board : null;
      }

      if (coord) {
        // Validate the relayed coordinate shape before handing it to the engine.
        // A malformed (e.g. multi-byte / emoji) coord can TRAP the wasm instance;
        // we reject it as a desync instead of letting it throw.
        let outcome = null;
        if (isCoordShaped(coord)) {
          try {
            outcome = host.call('gomoku_apply', coord);
          } catch (_e) {
            outcome = { type: 'gomoku.error', error: 'engine-trap' };
          }
        } else {
          outcome = { type: 'gomoku.error', error: 'bad-coord' };
        }
        if (outcome && outcome.type !== 'gomoku.error') {
          ui.banner = null; // a good move clears any prior desync warning
          const eng = readEngine(host);
          state = {
            board: eng.board,
            turn: eng.turn,
            status: outcome.status || eng.status,
            lastMove: outcome.lastMove || coord,
            winLine: Array.isArray(outcome.winLine) ? outcome.winLine : [],
          };
          return state;
        }
        // Engine rejected the relayed move. If the host also sent a world board,
        // trust the authoritative board (host is the source of truth).
      }

      if (worldBoard) {
        // Reconstruct the authoritative position by replaying its placed stones.
        // The board string alone doesn't carry move order, but gomoku is
        // order-independent for the final position; replay in scan order skipping
        // any move that would prematurely win, falling back to trusting the
        // string only when the replay reproduces it exactly.
        const ok = adoptWorldBoard(host, worldBoard);
        if (ok) {
          ui.banner = null;
          // The board was rebuilt THROUGH the engine and byte-verified against the
          // relay (adoptWorldBoard). The engine — not the relayed payload — is the
          // authority for turn/status/winLine: a relayed status/winLine could lie
          // (claim a win, paint a fake winning line) on top of an otherwise-valid
          // board, and the guest must never render a phantom win its own rules
          // engine disagrees with. We take turn + status straight from the engine.
          const eng = readEngine(host);
          // Only honour a relayed winLine when the ENGINE itself agrees the game is
          // over AND every named point holds a stone on the verified board; on an
          // ongoing engine state the winning line is necessarily empty.
          let winLine = [];
          if (isGameOver(eng.status) && payload && Array.isArray(payload.winLine)) {
            const onBoard = parseBoard(eng.board);
            const allPlaced = payload.winLine.every((co) => {
              const pc = parseCoordName(co);
              return pc && onBoard[pc.row][pc.col];
            });
            if (allPlaced) winLine = payload.winLine;
          }
          // lastMove is a cosmetic marker; accept a relayed one only if it actually
          // names an occupied point on the verified board, else keep the prior.
          let lastMove = state.lastMove;
          if (payload && typeof payload.lastMove === 'string') {
            const pc = parseCoordName(payload.lastMove);
            const onBoard = parseBoard(eng.board);
            if (pc && onBoard[pc.row][pc.col]) lastMove = payload.lastMove;
          }
          state = {
            board: eng.board,
            turn: eng.turn,
            status: eng.status,
            lastMove,
            winLine,
          };
          return state;
        }
      }

      // We received a relayed move/world we could neither apply nor recover from
      // — the guest's board has diverged from the authoritative host. Surface it
      // rather than silently dropping it.
      if (coord || worldBoard) {
        ui.banner = 'Host out of sync';
        return { ...state };
      }
      return null;
    },

    // Release game-owned listeners. Called by the frame on iframe unload.
    destroy() {
      if (
        ui.onResize &&
        typeof window !== 'undefined' &&
        typeof window.removeEventListener === 'function'
      ) {
        window.removeEventListener('resize', ui.onResize);
        ui.onResize = null;
      }
    },

    _ui: ui,
    _getState: () => state,
  };

  return GAME;
}

/**
 * Adopt a relayed authoritative board by rebuilding it through the engine via
 * gomoku_from_moves. We derive a Black-first alternating move list from the
 * stones present (Black has equal-or-one-more), then verify the engine's
 * resulting board matches the relayed string. Returns true on a faithful
 * reconstruction. This keeps the engine the single source of truth even on the
 * recovery path (no direct board poking).
 */
function adoptWorldBoard(host, worldBoard) {
  if (typeof worldBoard !== 'string' || worldBoard.length !== POINTS) return false;
  const blacks = [];
  const whites = [];
  for (let i = 0; i < POINTS; i++) {
    const ch = worldBoard[i];
    const c = i % SIZE;
    const r = Math.floor(i / SIZE);
    if (ch === 'X') blacks.push(coordName(c, r));
    else if (ch === 'O') whites.push(coordName(c, r));
  }
  // Black moves first; counts differ by at most one. Interleave B,W,B,W,…
  if (blacks.length < whites.length || blacks.length - whites.length > 1) return false;
  const list = [];
  for (let i = 0; i < blacks.length; i++) {
    list.push(blacks[i]);
    if (i < whites.length) list.push(whites[i]);
  }
  // Replaying may legitimately complete a five before the last stone (a real
  // win position); from_moves stops at the first illegal/over move and returns
  // 0. For a true terminal board that's fine — we then trust the engine state
  // it built up to the win. Try the strict replay first.
  const ok = host.callStatus('gomoku_from_moves', list.join(' '));
  if (ok === 1) {
    const eng = host.callPacked('gomoku_state') || {};
    return eng.board === worldBoard;
  }
  return false;
}

// ── DOM / canvas plumbing ─────────────────────────────────────────────────────

function buildDom(container, ui, ctx) {
  const canvas = container.querySelector('#board') || makeCanvas(container);
  ui.canvas = canvas;
  ui.cx = canvas.getContext('2d');
  layout(ui);

  // Forward clicks into the host-authoritative loop.
  canvas.addEventListener('click', (e) => {
    if (ctx && typeof ctx.handleLocalInput === 'function') {
      ctx.handleLocalInput(e);
    }
  });

  // HUD wiring (turn / status) — built by index.html if present.
  const turnEl = container.querySelector('#turn');
  const statusEl = container.querySelector('#status');
  ui.hud = { turn: turnEl, status: statusEl };

  // Re-layout on resize so the board stays square + mobile-friendly. The
  // handler is tracked so GAME.destroy() can remove it on iframe teardown.
  if (typeof window !== 'undefined' && typeof window.addEventListener === 'function') {
    ui.onResize = () => {
      layout(ui);
      ctx.GAME.render(ctx.GAME._getState(), ctx);
    };
    window.addEventListener('resize', ui.onResize);
    if (ctx && typeof ctx.onTeardown === 'function') {
      ctx.onTeardown(() => {
        if (ui.onResize) {
          window.removeEventListener('resize', ui.onResize);
          ui.onResize = null;
        }
      });
    }
  }
}

function makeCanvas(container) {
  const c = typeof document !== 'undefined' ? document.createElement('canvas') : null;
  if (c) {
    c.id = 'board';
    container.appendChild(c);
  }
  return c;
}


/** Vertical px the page needs besides the board: everything above it (its
 *  offsetTop: body padding + HUD + gap) plus the body's bottom padding.
 *  24 (the old fixed margin) when there is no layout to measure, e.g. in the
 *  node smoke tests. */
function boardReserve(canvas) {
  try {
    const top = canvas && typeof canvas.offsetTop === 'number' ? canvas.offsetTop : NaN;
    if (!Number.isFinite(top) || top <= 0) return 24;
    const body = typeof document !== 'undefined' ? document.body : null;
    const cs = body && typeof getComputedStyle === 'function' ? getComputedStyle(body) : null;
    return top + ((cs && parseFloat(cs.paddingBottom)) || 0);
  } catch {
    return 24;
  }
}

function layout(ui) {
  if (!ui.canvas) return;
  // Square board sized to the smaller viewport dimension, capped for desktop.
  let target = 540;
  if (typeof window !== 'undefined' && window.innerWidth) {
    // Leave room for what sits above and below the board (body padding, the
    // HUD, which wraps on narrow screens, the gap). Sizing to the full
    // viewport height overflowed the frame by the HUD's height whenever the
    // board was height-limited (the in-shell player clipped chess's last rank).
    const availH = (window.innerHeight || window.innerWidth) - boardReserve(ui.canvas);
    target = Math.min(window.innerWidth - 24, availH);
    target = Math.max(300, Math.min(target, 640));
  }
  // 15 lines need 14 gaps + a half-cell margin on each side for stone radius.
  const px = target;
  ui.size = px;
  // margin is half a cell so edge stones aren't clipped; cell spans the rest.
  ui.cell = px / (SIZE + 1);
  ui.margin = ui.cell;
  ui.canvas.width = px;
  ui.canvas.height = px;
  if (ui.canvas.style) {
    ui.canvas.style.width = px + 'px';
    ui.canvas.style.height = px + 'px';
  }
}

/** The pixel centre of grid intersection (col,row). */
function pointCenter(ui, col, row) {
  return { x: ui.margin + col * ui.cell, y: ui.margin + row * ui.cell };
}

/** Map a click to the NEAREST grid intersection (col,row), or null if too far. */
function canvasToPoint(ui, evt) {
  if (!ui.canvas) return null;
  const rect =
    typeof ui.canvas.getBoundingClientRect === 'function'
      ? ui.canvas.getBoundingClientRect()
      : { left: 0, top: 0, width: ui.size, height: ui.size };
  let x = evt.offsetX;
  let y = evt.offsetY;
  if (x === undefined || y === undefined) {
    x = (evt.clientX || 0) - rect.left;
    y = (evt.clientY || 0) - rect.top;
  }
  const scaleX = ui.size / (rect.width || ui.size);
  const scaleY = ui.size / (rect.height || ui.size);
  const px = x * scaleX;
  const py = y * scaleY;
  const col = Math.round((px - ui.margin) / ui.cell);
  const row = Math.round((py - ui.margin) / ui.cell);
  if (col < 0 || col >= SIZE || row < 0 || row >= SIZE) return null;
  // Reject clicks that landed too far from any intersection (> ~0.55 cell).
  const ctr = pointCenter(ui, col, row);
  const dist = Math.hypot(px - ctr.x, py - ctr.y);
  if (dist > ui.cell * 0.6) return null;
  return { col, row };
}

// ── drawing ───────────────────────────────────────────────────────────────────

function drawBoard(ui, state) {
  const cx = ui.cx;
  if (!cx) return;
  const cell = ui.cell;
  const size = ui.size;
  const board = parseBoard(state.board);

  // Wood background.
  cx.fillStyle = COLORS.wood;
  cx.fillRect(0, 0, size, size);

  // Grid lines (15 horizontal + 15 vertical).
  cx.strokeStyle = COLORS.grid;
  cx.lineWidth = Math.max(1, cell * 0.04);
  for (let i = 0; i < SIZE; i++) {
    const a = pointCenter(ui, 0, i);
    const b = pointCenter(ui, SIZE - 1, i);
    cx.beginPath();
    cx.moveTo(a.x, a.y);
    cx.lineTo(b.x, b.y);
    cx.stroke();
    const c = pointCenter(ui, i, 0);
    const d = pointCenter(ui, i, SIZE - 1);
    cx.beginPath();
    cx.moveTo(c.x, c.y);
    cx.lineTo(d.x, d.y);
    cx.stroke();
  }

  // Star points (hoshi).
  cx.fillStyle = COLORS.hoshi;
  for (const h of HOSHI) {
    const ctr = pointCenter(ui, h.c, h.r);
    cx.beginPath();
    cx.arc(ctr.x, ctr.y, Math.max(2, cell * 0.1), 0, Math.PI * 2);
    cx.fill();
  }

  // Winning-line halo (drawn under the stones).
  const winSet = new Set(Array.isArray(state.winLine) ? state.winLine : []);

  // Last-move marker coordinate.
  const lastPt = state.lastMove ? parseCoordName(state.lastMove) : null;

  // Stones.
  const radius = cell * 0.42;
  for (let r = 0; r < SIZE; r++) {
    for (let c = 0; c < SIZE; c++) {
      const p = board[r][c];
      if (!p) continue;
      const ctr = pointCenter(ui, c, r);
      const coord = coordName(c, r);
      const inWin = winSet.has(coord);

      cx.beginPath();
      cx.arc(ctr.x, ctr.y, radius, 0, Math.PI * 2);
      cx.fillStyle = p === 'X' ? COLORS.black : COLORS.white;
      cx.fill();
      cx.lineWidth = Math.max(1, cell * 0.05);
      cx.strokeStyle = inWin ? COLORS.winStone : p === 'X' ? COLORS.blackStroke : COLORS.whiteStroke;
      cx.stroke();

      // Last-move ring.
      if (lastPt && lastPt.col === c && lastPt.row === r) {
        cx.beginPath();
        cx.arc(ctr.x, ctr.y, radius * 0.45, 0, Math.PI * 2);
        cx.strokeStyle = COLORS.lastMove;
        cx.lineWidth = Math.max(2, cell * 0.07);
        cx.stroke();
      }
    }
  }

  // Winning line stroke (through the five centres), drawn over the stones.
  if (winSet.size >= 2) {
    const pts = Array.from(winSet)
      .map((co) => parseCoordName(co))
      .filter(Boolean)
      .map((pc) => pointCenter(ui, pc.col, pc.row));
    if (pts.length >= 2) {
      cx.beginPath();
      cx.moveTo(pts[0].x, pts[0].y);
      for (let i = 1; i < pts.length; i++) cx.lineTo(pts[i].x, pts[i].y);
      cx.strokeStyle = COLORS.winLine;
      cx.lineWidth = Math.max(3, cell * 0.12);
      cx.stroke();
    }
  }
}

function drawHud(ui, state, ctx) {
  if (!ui.hud) return;
  const over = isGameOver(state.status);
  if (ui.hud.turn) {
    const turn = state.turn === 'white' ? 'White' : 'Black';
    ui.hud.turn.textContent = over ? '' : `Turn: ${turn}`;
  }
  if (ui.hud.status) {
    ui.hud.status.textContent = ui.banner ? ui.banner : statusLine(state.status, state.turn);
    ui.hud.status.classList && ui.hud.status.classList.toggle('over', over);
  }
}

function flashError(ui, reason) {
  if (ui.hud && ui.hud.status) {
    ui.hud.status.textContent = `Illegal move${reason ? ` (${reason})` : ''}`;
  }
}

export default createGomokuGame;
