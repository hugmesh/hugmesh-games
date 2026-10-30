// games/go/go.js
//
// The Go (Weiqi / Baduk) GAME implementation for the shared web-native game
// shell (games/_shared/{wasm-host,sse-host-client,game-frame}.js). It is a single
// `GAME` object that:
//
//   • init(host, container, ctx)  — seeds the engine (go_new → 9x9), builds the
//                                    canvas + HUD + Pass button, returns the
//                                    initial state.
//   • render(stateJson, ctx)      — paints the 9x9 grid + stones from the engine
//                                    board string, plus last-move marker and
//                                    capture counts / status in the HUD.
//   • onLocalInput(evt, ctx)      — maps ONE canvas click (the nearest grid
//                                    intersection) OR a Pass control into a move,
//                                    applies it AUTHORITATIVELY through the wasm
//                                    engine, and returns the relay payload + new
//                                    state.
//   • onRemoteEvent(channel, json, ctx) — re-applies the opponent's relayed
//                                    placement/pass against the same wasm engine
//                                    (the guest re-validates); on a divergence it
//                                    resyncs from the relayed world board via
//                                    go_from_board, else surfaces an out-of-sync
//                                    banner.
//
// HOST-AUTHORITATIVE: the local browser running this engine is the source of
// truth (docs/multiplayer-chess-design.md; go is the gomoku/reversi twin — relay
// control/move + world/state). render() never mutates the engine; every state
// transition flows through go_apply / go_from_board so the board can never drift
// from the rules engine.
//
// Stones are canvas-drawn discs (no image assets). The engine is the verified
// hugmesh_go.wasm; we drive it through the shared WasmHost with the EXACT call
// pattern proven in games/_shared/test/go-wasm.test.mjs:
//   host.callStatus('go_new')                       -> 1
//   host.callPacked('go_state')                     -> { type:'go.snapshot', size, board, side, ... }
//   host.callPacked('go_legal')                     -> ["a1", ...]
//   host.callPacked('go_status')                    -> { status }
//   host.call('go_apply', 'c3')                     -> { type:'go.state', ... } | { type:'go.error', ... }
//   host.call('go_apply', 'pass')                   -> ... (pass)
//   host.callStatus('go_from_board', board, [], [sz,sd])-> 0|1 (world-resync fallback;
//                                    sz+sd are TRAILING args, after the packed board)
//
// Vanilla ES module. No build step, no dependencies.

const DEFAULT_SIZE = 9; // 9x9 board (the engine's default)
const FILES = 'abcdefghijklmnopqrstuvwxy'; // a..y (no 'i' skip — matches engine point_str)

// Board palette (clears the WCAG-audited non-text contrast bar at 3:1). Shared
// with the gomoku bundle for a consistent goban look.
const COLORS = {
  wood: '#d9b779',
  grid: '#5a3d22',
  hoshi: '#3a2713', // star-point dots
  black: '#161616',
  white: '#fafafa',
  blackStroke: '#000000',
  whiteStroke: '#444444',
  lastMove: '#7c4dff',
};

/** Empty board string of `size*size` dots. */
function emptyBoard(size) {
  return '.'.repeat(size * size);
}

/**
 * The traditional star points (hoshi) for a board size. 9x9 → the 4 corner
 * 3-3 points + the centre (tengen). 13x13/19x19 use their conventional sets;
 * any other size falls back to the centre only. Purely decorative.
 */
export function hoshiPoints(size) {
  const pts = [];
  if (size === 9) {
    for (const r of [2, 6]) for (const c of [2, 6]) pts.push({ c, r });
    pts.push({ c: 4, r: 4 });
  } else if (size === 13) {
    for (const r of [3, 9]) for (const c of [3, 9]) pts.push({ c, r });
    pts.push({ c: 6, r: 6 });
  } else if (size === 19) {
    for (const r of [3, 9, 15]) for (const c of [3, 9, 15]) pts.push({ c, r });
  } else if (size % 2 === 1) {
    const mid = (size - 1) / 2;
    pts.push({ c: mid, r: mid });
  }
  return pts;
}

/**
 * Parse an engine board string into a `size`x`size` array indexed [row][col],
 * with row 0 = the BOTTOM row (row "1") — matching the engine's index layout
 * (idx = row*size + col, row 0 = a1 bottom-left). Cells are 'X'/'B' (black),
 * 'O'/'W' (white), or null (empty). The engine's board_str()/snapshot uses
 * 'B'/'W'/'.'.
 */
export function parseBoard(boardStr, size) {
  const n = size * size;
  const s = typeof boardStr === 'string' && boardStr.length === n ? boardStr : emptyBoard(size);
  const board = [];
  for (let r = 0; r < size; r++) {
    const cells = [];
    for (let c = 0; c < size; c++) {
      const ch = s[r * size + c];
      cells.push(ch === 'B' || ch === 'X' ? 'B' : ch === 'W' || ch === 'O' ? 'W' : null);
    }
    board.push(cells);
  }
  return board;
}

/** Convert (col 0..size-1, row 0..size-1) into a coordinate like "c3". */
export function coordName(col, row) {
  return FILES[col] + String(row + 1);
}

/** Inverse of coordName: "c3" → { col, row } | null. */
export function parseCoordName(coord, size) {
  if (typeof coord !== 'string' || coord.length < 2) return null;
  const col = FILES.indexOf(coord[0].toLowerCase());
  const row = parseInt(coord.slice(1), 10) - 1;
  if (col < 0 || col >= size || Number.isNaN(row) || row < 0 || row >= size) return null;
  return { col, row };
}

/** A well-formed go coordinate for the given size: one file letter + a row. */
export function isCoordShaped(s, size) {
  return parseCoordName(s, size) !== null;
}

/** True when the game has ended (the wire status starts with "ended:"). */
export function isGameOver(status) {
  return typeof status === 'string' && status.startsWith('ended:');
}

/**
 * Human-readable status line from an engine wire string.
 *   "ongoing"            → "<side> to move"
 *   "ended:black:b:w"    → "Black wins (area b vs w)"
 *   "ended:white:b:w"    → "White wins (area w vs b)"
 *   "ended:draw:b:w"     → "Draw (b vs w)"
 */
export function statusLine(status, side) {
  if (status === 'ongoing' || !status) {
    return `${side === 'white' ? 'White' : 'Black'} to move`;
  }
  if (status.startsWith('ended:')) {
    const parts = status.split(':'); // ended, winner, black, white
    const winner = parts[1];
    const b = parts[2];
    const w = parts[3];
    if (winner === 'draw') return `Draw — area ${b} vs ${w}`;
    if (winner === 'black') return `Black wins — area ${b} vs ${w}`;
    if (winner === 'white') return `White wins — area ${w} vs ${b}`;
    return 'Game over';
  }
  return status;
}

// ── the GAME object ──────────────────────────────────────────────────────────

export function createGoGame() {
  // Per-instance UI state.
  const ui = {
    canvas: null,
    cx: null,
    size: 0, // canvas pixel size (square)
    cell: 0, // spacing between grid lines in pixels
    margin: 0, // padding from canvas edge to the first/last line
    boardSize: DEFAULT_SIZE, // board points per side
    hud: null, // { turn, status } elements
    pass: null, // Pass button
    banner: null, // transient HUD message (desync / illegal)
    onResize: null,
  };

  // Latest authoritative state we rendered.
  let state = {
    size: DEFAULT_SIZE,
    board: emptyBoard(DEFAULT_SIZE),
    side: 'black',
    status: 'ongoing',
    lastMove: null,
    blackCaptures: 0,
    whiteCaptures: 0,
  };

  /** Pull the current position out of the engine (snapshot) into our shape. */
  function readEngine(host) {
    const s = host.callPacked('go_state') || {};
    const size = typeof s.size === 'number' && s.size >= 2 ? s.size : ui.boardSize;
    return {
      size,
      board: typeof s.board === 'string' && s.board.length === size * size ? s.board : emptyBoard(size),
      side: s.side === 'white' ? 'white' : 'black',
      status: s.status || 'ongoing',
      lastMove: state.lastMove || null,
      blackCaptures: typeof s.blackCaptures === 'number' ? s.blackCaptures : 0,
      whiteCaptures: typeof s.whiteCaptures === 'number' ? s.whiteCaptures : 0,
    };
  }

  const GAME = {
    slug: 'go',

    async init(host, container, ctx) {
      // Seed the engine to the empty 9x9 board (verified: returns 1).
      host.callStatus('go_new');

      buildDom(container, ui, ctx);
      const eng = readEngine(host);
      ui.boardSize = eng.size;
      state = { ...eng, lastMove: null };
      return state;
    },

    render(stateJson, ctx) {
      if (stateJson && typeof stateJson === 'object') {
        const size = typeof stateJson.size === 'number' ? stateJson.size : state.size;
        state = {
          size,
          board: stateJson.board || state.board,
          side: stateJson.side || state.side,
          status: stateJson.status || 'ongoing',
          lastMove: stateJson.lastMove !== undefined ? stateJson.lastMove : state.lastMove,
          blackCaptures:
            stateJson.blackCaptures !== undefined ? stateJson.blackCaptures : state.blackCaptures,
          whiteCaptures:
            stateJson.whiteCaptures !== undefined ? stateJson.whiteCaptures : state.whiteCaptures,
        };
        ui.boardSize = state.size;
      }
      drawBoard(ui, state);
      drawHud(ui, state, ctx);
    },

    onLocalInput(evt, ctx) {
      const host = ctx.host;
      if (!evt) return null;

      // A Pass control (the Pass button dispatches { type: 'pass' }).
      if (evt.type === 'pass') {
        if (isGameOver(state.status)) return null;
        return applyMove('pass');
      }

      if (evt.type !== 'click') return null;
      if (isGameOver(state.status)) return null;

      const pt = canvasToPoint(ui, evt);
      if (!pt) return null;

      const coord = coordName(pt.col, pt.row);
      const board = parseBoard(state.board, state.size);
      // Ignore a click on an occupied intersection (the engine would reject it
      // anyway; keeps the optimistic render clean).
      if (board[pt.row][pt.col]) return null;

      return applyMove(coord);

      // Apply a move ("c3" or "pass") through the engine, refresh derived state,
      // build the relay payloads. Mirrors gomoku.applyMove.
      function applyMove(move) {
        const outcome = host.call('go_apply', move);

        if (!outcome || outcome.type === 'go.error') {
          flashError(ui, outcome && outcome.error);
          return { state };
        }

        // Accepted: refresh derived state from the engine snapshot + the outcome.
        const eng = readEngine(host);
        state = {
          size: eng.size,
          board: eng.board,
          side: eng.side,
          status: outcome.status || eng.status,
          lastMove: outcome.lastMove || move,
          blackCaptures: typeof outcome.blackCaptures === 'number' ? outcome.blackCaptures : eng.blackCaptures,
          whiteCaptures: typeof outcome.whiteCaptures === 'number' ? outcome.whiteCaptures : eng.whiteCaptures,
        };
        ui.banner = null;

        const world = {
          size: state.size,
          board: state.board,
          side: state.side,
          status: state.status,
          lastMove: state.lastMove,
          captured: Array.isArray(outcome.captured) ? outcome.captured : [],
          blackCaptures: state.blackCaptures,
          whiteCaptures: state.whiteCaptures,
        };
        return {
          // The relay payload the guest re-applies (input channel), AND the
          // host-authoritative world broadcast.
          input: { kind: 'control', type: 'move', payload: { move } },
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

      // The opponent's intended move (guest→host `input`) OR the host's
      // authoritative `world` broadcast (host→guest). Both re-apply against the
      // engine; the guest re-validates by letting the engine accept/reject, and
      // resyncs from the relayed board on a divergence.
      const payload = json.payload || json;
      let move = null;
      let worldBoard = null;
      let worldSize = state.size;
      let worldSide = null;
      if (payload && typeof payload === 'object') {
        move = payload.move || (payload.payload && payload.payload.move) || null;
        worldBoard = typeof payload.board === 'string' ? payload.board : null;
        if (typeof payload.size === 'number') worldSize = payload.size;
        if (payload.side === 'black' || payload.side === 'white') worldSide = payload.side;
      }

      if (move) {
        // Validate the relayed move shape before handing it to the engine. A
        // malformed (multi-byte / emoji) token can TRAP the wasm instance; we
        // reject it as a desync instead of letting it throw.
        let outcome = null;
        const ok = move === 'pass' || isCoordShaped(move, state.size);
        if (ok) {
          try {
            outcome = host.call('go_apply', move);
          } catch (_e) {
            outcome = { type: 'go.error', error: 'engine-trap' };
          }
        } else {
          outcome = { type: 'go.error', error: 'bad-move' };
        }
        if (outcome && outcome.type !== 'go.error') {
          ui.banner = null; // a good move clears any prior desync warning
          const eng = readEngine(host);
          state = {
            size: eng.size,
            board: eng.board,
            side: eng.side,
            status: outcome.status || eng.status,
            lastMove: outcome.lastMove || move,
            blackCaptures: typeof outcome.blackCaptures === 'number' ? outcome.blackCaptures : eng.blackCaptures,
            whiteCaptures: typeof outcome.whiteCaptures === 'number' ? outcome.whiteCaptures : eng.whiteCaptures,
          };
          return state;
        }
        // Engine rejected the relayed move. If the host also sent a world board,
        // trust the authoritative board (host is the source of truth).
      }

      if (worldBoard) {
        // World-resync fallback: rebuild the authoritative position THROUGH the
        // engine via go_from_board (the engine — not the relayed payload — stays
        // the single source of truth even on the recovery path). The world board
        // string is row-major from a1 (engine layout), which go_from_board reads
        // directly. The side to move is whoever the relay says is next.
        const sideByte = worldSide === 'white' ? 1 : 0;
        const ok = adoptWorldBoard(host, worldBoard, worldSize, sideByte);
        if (ok) {
          ui.banner = null;
          const eng = readEngine(host);
          // lastMove is a cosmetic marker; accept a relayed one only if it names
          // an occupied point on the verified board (or is a pass), else keep prior.
          let lastMove = state.lastMove;
          if (payload && typeof payload.lastMove === 'string') {
            if (payload.lastMove === 'pass') {
              lastMove = 'pass';
            } else {
              const pc = parseCoordName(payload.lastMove, eng.size);
              const onBoard = parseBoard(eng.board, eng.size);
              if (pc && onBoard[pc.row][pc.col]) lastMove = payload.lastMove;
            }
          }
          state = {
            size: eng.size,
            board: eng.board,
            side: eng.side,
            status: eng.status,
            lastMove,
            blackCaptures: eng.blackCaptures,
            whiteCaptures: eng.whiteCaptures,
          };
          return state;
        }
      }

      // We received a relayed move/world we could neither apply nor recover from
      // — the guest's board has diverged from the authoritative host. Surface it
      // rather than silently dropping it.
      if (move || worldBoard) {
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
 * go_from_board, then verifying the engine's resulting board matches the relayed
 * string. Returns true on a faithful reconstruction. Keeps the engine the single
 * source of truth even on the recovery path (no direct board poking).
 */
function adoptWorldBoard(host, worldBoard, size, sideByte) {
  if (typeof worldBoard !== 'string' || worldBoard.length !== size * size) return false;
  // go_from_board(boardPtr, boardLen, size, side): size+side are TRAILING args
  // (after the packed string), unlike sudoku/tron whose extra args lead. Passing
  // them as leadingArgs would call go_from_board(size, side, ptr, len) and the
  // engine would reject every resync (return 0), stranding the guest out of sync.
  const ok = host.callStatus('go_from_board', worldBoard, [], [size, sideByte]);
  if (ok === 1) {
    const eng = host.callPacked('go_state') || {};
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

  // HUD wiring (turn / status / capture counts) — built by index.html if present.
  ui.hud = {
    turn: container.querySelector('#turn'),
    status: container.querySelector('#status'),
    captures: container.querySelector('#score'),
  };

  // The Pass button dispatches a synthetic { type: 'pass' } through the same
  // host-authoritative loop (relayed as a control/move just like a placement).
  ui.pass = container.querySelector('#pass');
  if (ui.pass && typeof ui.pass.addEventListener === 'function') {
    ui.pass.addEventListener('click', () => {
      if (ctx && typeof ctx.handleLocalInput === 'function') {
        ctx.handleLocalInput({ type: 'pass' });
      }
    });
  }

  // Re-layout on resize so the board stays square + mobile-friendly. The handler
  // is tracked so GAME.destroy() can remove it on iframe teardown.
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
  const px = target;
  const n = ui.boardSize;
  ui.size = px;
  // margin is half a cell so edge stones aren't clipped; cell spans the rest.
  ui.cell = px / (n + 1);
  ui.margin = ui.cell;
  ui.canvas.width = px;
  ui.canvas.height = px;
  if (ui.canvas.style) {
    ui.canvas.style.width = px + 'px';
    ui.canvas.style.height = px + 'px';
  }
}

/**
 * The pixel centre of grid intersection (col,row). The board's row 0 is the
 * BOTTOM row (row "1"), so we flip the row when computing the y pixel so a1 is
 * drawn at the bottom-left like a real goban.
 */
function pointCenter(ui, col, row) {
  const n = ui.boardSize;
  const drawRow = n - 1 - row; // flip: row 0 (bottom) → bottom of the canvas
  return { x: ui.margin + col * ui.cell, y: ui.margin + drawRow * ui.cell };
}

/** Map a click to the NEAREST grid intersection (col,row), or null if too far. */
function canvasToPoint(ui, evt) {
  if (!ui.canvas) return null;
  const n = ui.boardSize;
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
  const drawRow = Math.round((py - ui.margin) / ui.cell);
  const row = n - 1 - drawRow; // un-flip back to engine row (0 = bottom)
  if (col < 0 || col >= n || row < 0 || row >= n) return null;
  // Reject clicks that landed too far from any intersection.
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
  const n = ui.boardSize;
  const board = parseBoard(state.board, n);

  // Wood background.
  cx.fillStyle = COLORS.wood;
  cx.fillRect(0, 0, size, size);

  // Grid lines (n horizontal + n vertical).
  cx.strokeStyle = COLORS.grid;
  cx.lineWidth = Math.max(1, cell * 0.04);
  for (let i = 0; i < n; i++) {
    const a = pointCenter(ui, 0, i);
    const b = pointCenter(ui, n - 1, i);
    cx.beginPath();
    cx.moveTo(a.x, a.y);
    cx.lineTo(b.x, b.y);
    cx.stroke();
    const c = pointCenter(ui, i, 0);
    const d = pointCenter(ui, i, n - 1);
    cx.beginPath();
    cx.moveTo(c.x, c.y);
    cx.lineTo(d.x, d.y);
    cx.stroke();
  }

  // Star points (hoshi).
  cx.fillStyle = COLORS.hoshi;
  for (const h of hoshiPoints(n)) {
    const ctr = pointCenter(ui, h.c, h.r);
    cx.beginPath();
    cx.arc(ctr.x, ctr.y, Math.max(2, cell * 0.1), 0, Math.PI * 2);
    cx.fill();
  }

  // Last-move marker coordinate (skip "pass").
  const lastPt =
    state.lastMove && state.lastMove !== 'pass' ? parseCoordName(state.lastMove, n) : null;

  // Stones.
  const radius = cell * 0.46;
  for (let r = 0; r < n; r++) {
    for (let c = 0; c < n; c++) {
      const p = board[r][c];
      if (!p) continue;
      const ctr = pointCenter(ui, c, r);

      cx.beginPath();
      cx.arc(ctr.x, ctr.y, radius, 0, Math.PI * 2);
      cx.fillStyle = p === 'B' ? COLORS.black : COLORS.white;
      cx.fill();
      cx.lineWidth = Math.max(1, cell * 0.05);
      cx.strokeStyle = p === 'B' ? COLORS.blackStroke : COLORS.whiteStroke;
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
}

function drawHud(ui, state, ctx) {
  if (!ui.hud) return;
  const over = isGameOver(state.status);
  if (ui.hud.turn) {
    const side = state.side === 'white' ? 'White' : 'Black';
    ui.hud.turn.textContent = over ? '' : `Turn: ${side}`;
  }
  if (ui.hud.status) {
    ui.hud.status.textContent = ui.banner ? ui.banner : statusLine(state.status, state.side);
    ui.hud.status.classList && ui.hud.status.classList.toggle('over', over);
  }
  if (ui.hud.captures) {
    ui.hud.captures.textContent = `Captures — Black: ${state.blackCaptures}  White: ${state.whiteCaptures}`;
  }
  // Disable the Pass button once the game is over.
  if (ui.pass) ui.pass.disabled = over;
}

function flashError(ui, reason) {
  if (ui.hud && ui.hud.status) {
    ui.hud.status.textContent = `Illegal move${reason ? ` (${reason})` : ''}`;
  }
}

export default createGoGame;
