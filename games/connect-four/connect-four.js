// games/connect-four/connect-four.js
//
// The Connect Four GAME implementation for the shared web-native game shell
// (games/_shared/{wasm-host,sse-host-client,game-frame}.js). It is a single
// `GAME` object that mirrors the proven chess foundation one-for-one:
//
//   • init(host, container, ctx)  — seeds the engine (c4_new), builds the
//                                    canvas + HUD, returns the initial state.
//   • render(stateJson, ctx)      — paints the 7x6 grid + discs from the engine's
//                                    own connect_four.state board string, plus
//                                    hovered-column / winning-line / game-over
//                                    decoration.
//   • onLocalInput(evt, ctx)      — maps a canvas click (a column) into a drop,
//                                    applies it AUTHORITATIVELY through the wasm
//                                    engine, and returns the relay payload + the
//                                    new state.
//   • onRemoteEvent(channel, json, ctx) — re-applies the opponent's relayed drop
//                                    (input) or the host's authoritative board
//                                    (world) against the same wasm engine (the
//                                    guest re-validates).
//
// HOST-AUTHORITATIVE: the local browser running this engine is the source of
// truth (docs/multiplayer-chess-design.md). render() never mutates the engine;
// every state transition flows through c4_apply_drop so the board can never
// drift from the rules engine.
//
// Discs are canvas primitives (filled circles), NO image assets. The engine is
// the verified snapdragon_connect_four.wasm; we drive it through the shared
// WasmHost with the EXACT call pattern proven in
// games/_shared/test/connect-four-wasm.test.mjs:
//   host.callStatus('c4_new')                 -> 1
//   host.callPacked('c4_legal')               -> [0,1,2,3,4,5,6]
//   host.callPacked('c4_state')               -> { type:'connect_four.state', board, toMove, status, ... }
//   host.callPacked('c4_status')              -> { status }
//   host.call('c4_apply_drop', '3')           -> { type:'connect_four.state', ... } | { type:'connect_four.error', ... }
//
// Vanilla ES module. No build step, no dependencies.

export const COLS = 7;
export const ROWS = 6;
const CONNECT = 4;

// Empty board string the engine emits for a fresh game (6 rows of 7 dots
// joined by '/'). Used as the render fallback before the engine has spoken.
const EMPTY_BOARD = Array(ROWS).fill('.'.repeat(COLS)).join('/');

// Palette — backgrounds/borders keep the brand purple (WCAG 1.4.11 non-text at
// 3:1); the disc fills are the classic red/yellow, dark enough to read on the
// board.
const COLORS = {
  boardFill: '#3949c8', // the iconic blue connect-four frame
  boardEdge: '#7c4dff',
  hole: '#14121c', // empty cell (matches the page bg, "see-through")
  red: '#e23b3b',
  redEdge: '#7a1414',
  yellow: '#f2c53d',
  yellowEdge: '#8a6b00',
  hover: 'rgba(124, 77, 255, 0.30)', // hovered-column wash
  winRing: '#ffffff', // ring drawn around the winning four
  winRingShadow: '#000000',
};

/**
 * Parse the engine's `board` string (rows top→bottom = row 5..0, each 7 chars of
 * '.'/'R'/'Y', joined by '/') into a grid indexed [rowFromTop][col]. rowFromTop
 * 0 is the TOP visual row. Cells are 'R' | 'Y' | null.
 */
export function parseBoard(boardStr) {
  const rows = (boardStr || EMPTY_BOARD).split('/');
  const grid = [];
  for (let r = 0; r < ROWS; r++) {
    const line = rows[r] || '.'.repeat(COLS);
    const cells = [];
    for (let c = 0; c < COLS; c++) {
      const ch = line[c];
      cells.push(ch === 'R' ? 'R' : ch === 'Y' ? 'Y' : null);
    }
    grid.push(cells);
  }
  return grid;
}

/** True if the game has ended (a win or a draw) — no further drops are legal. */
export function isGameOver(status) {
  return (
    typeof status === 'string' &&
    (status === 'draw' || status.startsWith('win:'))
  );
}

/** Human-readable HUD status line from an engine status + side to move. */
export function statusLine(status, toMove) {
  switch (true) {
    case status === 'ongoing': {
      const side = toMove === 'yellow' ? 'Yellow' : 'Red';
      return `${side} to move`;
    }
    case status === 'win:red':
      return 'Red wins!';
    case status === 'win:yellow':
      return 'Yellow wins!';
    case status === 'draw':
      return 'Draw — board full';
    default:
      return status || 'ongoing';
  }
}

/** The winning player from a status string, or null. */
function winnerOf(status) {
  if (status === 'win:red') return 'red';
  if (status === 'win:yellow') return 'yellow';
  return null;
}

// ── the GAME object ──────────────────────────────────────────────────────────

export function createConnectFourGame() {
  // Per-instance UI state (hover column + latest engine state).
  const ui = {
    canvas: null,
    cx: null,
    cell: 0, // one cell in pixels (square)
    padding: 0, // outer board padding in pixels
    hoverCol: -1, // column under the pointer (for the hover wash), or -1
    hud: null, // { turn, status, reset } elements
    banner: null, // a transient HUD banner (e.g. "Host out of sync")
    onResize: null, // bound resize handler, removed on destroy
  };

  // Latest authoritative state we rendered (board + toMove + status + winningLine).
  let state = {
    board: EMPTY_BOARD,
    toMove: 'red',
    status: 'ongoing',
    winningLine: [],
  };

  let legalCols = []; // columns currently droppable (from the engine)

  /** Pull the current position out of the engine into our `state` shape. */
  function readEngine(host) {
    const s = host.callPacked('c4_state') || {};
    return {
      board: s.board || EMPTY_BOARD,
      toMove: s.toMove || 'red',
      status: s.status || 'ongoing',
      winningLine: Array.isArray(s.winningLine) ? s.winningLine : [],
    };
  }

  function refreshLegal(host) {
    legalCols = host.callPacked('c4_legal') || [];
  }

  /** Adopt an engine connect_four.state payload as the new authoritative state. */
  function adopt(s) {
    state = {
      board: s.board || state.board,
      toMove: s.toMove || state.toMove,
      status: s.status || 'ongoing',
      winningLine: Array.isArray(s.winningLine) ? s.winningLine : [],
    };
    return state;
  }

  const GAME = {
    slug: 'connect-four',

    async init(host, container, ctx) {
      // Seed the engine to the empty start position (verified: returns 1).
      host.callStatus('c4_new');
      refreshLegal(host);

      buildDom(container, ui, ctx);
      state = readEngine(host);
      ui.hoverCol = -1;
      ui.banner = null;
      return state;
    },

    render(stateJson, ctx) {
      if (stateJson && typeof stateJson === 'object') {
        state = {
          board: stateJson.board || state.board,
          toMove: stateJson.toMove || state.toMove,
          status: stateJson.status || 'ongoing',
          winningLine: Array.isArray(stateJson.winningLine)
            ? stateJson.winningLine
            : state.winningLine,
        };
      }
      drawBoard(ui, state);
      drawHud(ui, state, ctx);
    },

    onLocalInput(evt, ctx) {
      const host = ctx.host;
      if (!evt) return null;

      // Reset / new game. Re-seeds the engine and relays the fresh board so the
      // opponent's board resets too. (The HUD reset button feeds this event.)
      if (evt.type === 'reset') {
        host.callStatus('c4_new');
        refreshLegal(host);
        ui.banner = null;
        state = readEngine(host);
        return {
          input: { kind: 'control', type: 'reset', payload: { reset: true } },
          world: { ...state, reset: true },
          state,
        };
      }

      // Pointer hover over a column — just a UI affordance, never relayed and
      // never touches the engine. Returns the unchanged state to repaint.
      if (evt.type === 'hover') {
        const col = colFromEvent(ui, evt);
        ui.hoverCol = col;
        return { state };
      }
      if (evt.type === 'hoverout') {
        ui.hoverCol = -1;
        return { state };
      }

      if (evt.type !== 'click') return null;
      if (isGameOver(state.status)) return null;

      const col = colFromEvent(ui, evt);
      if (col < 0 || col >= COLS) return null;
      // Don't even ask the engine for a column that's already full — the engine
      // would reject it; this keeps the optimistic UI quiet.
      if (!legalCols.includes(col)) {
        flashError(ui, 'column-full');
        return { state };
      }

      // Apply authoritatively through the engine. The engine returns its OWN
      // full-board connect_four.state payload (the exact world snapshot we relay).
      const outcome = host.call('c4_apply_drop', String(col));
      ui.hoverCol = -1;

      if (!outcome || outcome.type === 'connect_four.error') {
        flashError(ui, outcome && outcome.error);
        return { state };
      }

      ui.banner = null;
      refreshLegal(host);
      adopt(outcome);

      return {
        // The relay payload the guest re-applies (input channel), AND the
        // host-authoritative world broadcast (the engine's own state JSON).
        input: { kind: 'control', type: 'drop', payload: { col } },
        world: { ...state },
        state,
      };
    },

    onRemoteEvent(channel, json, ctx) {
      const host = ctx.host;
      if (!json) return null;

      // session-control: pause/resume/close — surface on the HUD, no engine move.
      if (channel === 'session-control') {
        if (json.action && String(json.action).includes('CLOSED')) {
          ui.banner = 'Session closed';
        }
        return { ...state };
      }

      const payload = json.payload || json;
      let col = null;
      let worldBoard = null;
      let reset = false;
      if (payload && typeof payload === 'object') {
        const inner = payload.payload && typeof payload.payload === 'object'
          ? payload.payload
          : null;
        col =
          payload.col !== undefined
            ? payload.col
            : inner && inner.col !== undefined
              ? inner.col
              : null;
        worldBoard = typeof payload.board === 'string' ? payload.board : null;
        reset = payload.reset === true || (inner && inner.reset === true);
      }

      // A relayed reset — re-seed locally so both sides start fresh.
      if (reset) {
        host.callStatus('c4_new');
        refreshLegal(host);
        ui.banner = null;
        ui.hoverCol = -1;
        state = readEngine(host);
        return { ...state };
      }

      // The opponent's intended drop (guest→host `input`) re-applied against the
      // engine; the engine accepts/rejects so the guest re-validates the move.
      if (col !== null && isColShaped(col)) {
        let outcome = null;
        try {
          outcome = host.call('c4_apply_drop', String(col));
        } catch (_e) {
          outcome = { type: 'connect_four.error', error: 'engine-trap' };
        }
        if (outcome && outcome.type !== 'connect_four.error') {
          ui.banner = null;
          refreshLegal(host);
          adopt(outcome);
          ui.hoverCol = -1;
          return { ...state };
        }
        // Engine rejected the relayed drop. Fall through to the authoritative
        // board (the host is the source of truth) if one was sent.
      }

      // The host's authoritative `world` board. The guest can't replay an
      // arbitrary board into this engine (it has no from_state op), but it CAN
      // trust + render it: the engine stays the guest's validator for the moves
      // it DID replay, and the host board is the tiebreak when they diverge.
      if (worldBoard && isBoardShaped(worldBoard)) {
        ui.banner = null;
        state = {
          board: worldBoard,
          toMove: typeof payload.toMove === 'string' ? payload.toMove : state.toMove,
          status: typeof payload.status === 'string' ? payload.status : state.status,
          winningLine: Array.isArray(payload.winningLine)
            ? payload.winningLine
            : [],
        };
        ui.hoverCol = -1;
        return { ...state };
      }

      // We received a relayed event we could neither apply nor adopt — the
      // guest's board has diverged from the authoritative host. Surface it
      // (mirrors chess's "Host out of sync") rather than silently dropping it.
      if (col !== null || worldBoard) {
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

    // Exposed for the HUD reset button + tests.
    _reset(host) {
      host.callStatus('c4_new');
      refreshLegal(host);
      ui.banner = null;
      state = readEngine(host);
      return state;
    },
    _ui: ui,
    _getState: () => state,
    _getLegal: () => legalCols,
  };

  return GAME;
}

// ── shape guards (defend the engine against malformed relayed input) ──────────

/** A column index is a small non-negative integer within the board. */
function isColShaped(c) {
  return Number.isInteger(c) && c >= 0 && c < COLS;
}

/**
 * A board string is plausible: ASCII, ROWS rows of COLS chars from the set
 * `.RY`, joined by '/'. Guards the renderer against a malformed relayed board.
 */
function isBoardShaped(s) {
  if (typeof s !== 'string') return false;
  const rows = s.split('/');
  if (rows.length !== ROWS) return false;
  return rows.every((r) => r.length === COLS && /^[.RY]+$/.test(r));
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

  // Hover feedback (which column a click would drop into). Pointer-only affordance.
  canvas.addEventListener('mousemove', (e) => {
    if (ctx && typeof ctx.handleLocalInput === 'function') {
      ctx.handleLocalInput({ type: 'hover', offsetX: e.offsetX, offsetY: e.offsetY });
    }
  });
  canvas.addEventListener('mouseleave', () => {
    if (ctx && typeof ctx.handleLocalInput === 'function') {
      ctx.handleLocalInput({ type: 'hoverout' });
    }
  });

  // HUD wiring (turn / status / reset) — built by index.html if present.
  const turnEl = container.querySelector('#turn');
  const statusEl = container.querySelector('#status');
  const resetBtn = container.querySelector('#reset');
  ui.hud = { turn: turnEl, status: statusEl, reset: resetBtn };
  if (resetBtn && ctx) {
    resetBtn.addEventListener('click', () => {
      // Routed through handleLocalInput so the reset RELAYS to the opponent.
      if (typeof ctx.handleLocalInput === 'function') {
        ctx.handleLocalInput({ type: 'reset' });
      } else {
        const next = ctx.GAME._reset(ctx.host);
        ctx.GAME.render(next, ctx);
      }
    });
  }

  // Re-layout on resize so the board stays sized to the viewport. The handler is
  // tracked so GAME.destroy() can remove it on iframe teardown.
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
  const c =
    typeof document !== 'undefined' ? document.createElement('canvas') : null;
  if (c) {
    c.id = 'board';
    container.appendChild(c);
  }
  return c;
}

function layout(ui) {
  if (!ui.canvas) return;
  // The board is 7 wide × 6 tall. Size the cell to the smaller viewport
  // dimension, capped for desktop, then derive the canvas pixel box from it.
  let target = 560;
  if (typeof window !== 'undefined' && window.innerWidth) {
    const w = window.innerWidth;
    const h = window.innerHeight || window.innerWidth;
    // Fit both the 7-wide width and the 6-tall height (+ the HUD) in the viewport.
    const byWidth = (w - 24) / COLS;
    const byHeight = (h - 96) / ROWS;
    const cell = Math.max(36, Math.min(byWidth, byHeight, 92));
    target = cell * COLS;
  }
  ui.cell = Math.floor(target / COLS);
  ui.padding = Math.max(6, Math.floor(ui.cell * 0.12));
  const wpx = ui.cell * COLS + ui.padding * 2;
  const hpx = ui.cell * ROWS + ui.padding * 2;
  ui.canvas.width = wpx;
  ui.canvas.height = hpx;
  if (ui.canvas.style) {
    ui.canvas.style.width = wpx + 'px';
    ui.canvas.style.height = hpx + 'px';
  }
}

/** Which column (0..COLS-1) an event's x falls into, or -1 if outside. */
function colFromEvent(ui, evt) {
  if (!ui.canvas) return -1;
  const rect =
    typeof ui.canvas.getBoundingClientRect === 'function'
      ? ui.canvas.getBoundingClientRect()
      : { left: 0, top: 0, width: ui.canvas.width, height: ui.canvas.height };
  let x = evt.offsetX;
  if (x === undefined) x = (evt.clientX || 0) - rect.left;
  const scaleX = ui.canvas.width / (rect.width || ui.canvas.width);
  const px = x * scaleX - ui.padding;
  if (px < 0) return -1;
  const col = Math.floor(px / ui.cell);
  if (col < 0 || col >= COLS) return -1;
  return col;
}

// ── drawing ───────────────────────────────────────────────────────────────────

function drawBoard(ui, state) {
  const cx = ui.cx;
  if (!cx) return;
  const cell = ui.cell;
  const pad = ui.padding;
  const grid = parseBoard(state.board);
  const wpx = cell * COLS + pad * 2;
  const hpx = cell * ROWS + pad * 2;

  // The blue board frame.
  cx.fillStyle = COLORS.boardFill;
  cx.fillRect(0, 0, wpx, hpx);

  // Hovered-column wash (a faint vertical band) — only while the game is live.
  if (
    ui.hoverCol >= 0 &&
    ui.hoverCol < COLS &&
    !isGameOver(state.status)
  ) {
    cx.fillStyle = COLORS.hover;
    cx.fillRect(pad + ui.hoverCol * cell, 0, cell, hpx);
  }

  // The winning four — a set of [col,row] (row 0 = BOTTOM, per the engine).
  const winSet = new Set(
    (state.winningLine || []).map((p) => `${p[0]},${p[1]}`),
  );
  const radius = cell * 0.40;

  // Holes + discs. grid[rowFromTop][col]; rowFromTop 0 is the top visual row,
  // which is engine row (ROWS-1). Engine row = ROWS-1-rowFromTop.
  for (let rt = 0; rt < ROWS; rt++) {
    for (let c = 0; c < COLS; c++) {
      const ccx = pad + c * cell + cell / 2;
      const ccy = pad + rt * cell + cell / 2;
      const disc = grid[rt][c];

      // Cell circle: the disc colour, or the "empty hole" dark fill.
      cx.beginPath();
      cx.arc(ccx, ccy, radius, 0, Math.PI * 2);
      if (disc === 'R') {
        cx.fillStyle = COLORS.red;
      } else if (disc === 'Y') {
        cx.fillStyle = COLORS.yellow;
      } else {
        cx.fillStyle = COLORS.hole;
      }
      cx.fill();

      // Disc edge stroke for legibility.
      if (disc) {
        cx.lineWidth = Math.max(1, cell * 0.04);
        cx.strokeStyle = disc === 'R' ? COLORS.redEdge : COLORS.yellowEdge;
        cx.stroke();
      }

      // Winning-line ring. Engine row for this visual cell:
      const engineRow = ROWS - 1 - rt;
      if (disc && winSet.has(`${c},${engineRow}`)) {
        cx.lineWidth = Math.max(2, cell * 0.10);
        cx.strokeStyle = COLORS.winRing;
        cx.beginPath();
        cx.arc(ccx, ccy, radius * 0.78, 0, Math.PI * 2);
        cx.stroke();
      }
    }
  }
}

function drawHud(ui, state, _ctx) {
  if (!ui.hud) return;
  const over = isGameOver(state.status);
  if (ui.hud.turn) {
    const turn = state.toMove === 'yellow' ? 'Yellow' : 'Red';
    ui.hud.turn.textContent = over ? '' : `Turn: ${turn}`;
  }
  if (ui.hud.status) {
    ui.hud.status.textContent = ui.banner
      ? ui.banner
      : statusLine(state.status, state.toMove);
    ui.hud.status.classList && ui.hud.status.classList.toggle('over', over);
  }
  // The reset button is always enabled (you can restart a finished OR live game).
}

function flashError(ui, reason) {
  if (ui.hud && ui.hud.status) {
    ui.hud.status.textContent = `Can't drop there${reason ? ` (${reason})` : ''}`;
  }
}

export const _winnerOf = winnerOf;
export default createConnectFourGame;
