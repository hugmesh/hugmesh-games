// games/sudoku/sudoku.js
//
// The Sudoku GAME implementation for the shared web-native game shell
// (games/_shared/{wasm-host,sse-host-client,game-frame}.js). It is a single
// `GAME` object that mirrors the proven chess / connect-four foundation:
//
//   • init(host, container, ctx)  — seeds the engine (sudoku_new with a seed),
//                                    builds the canvas + HUD, returns the
//                                    initial sudoku.snapshot state.
//   • render(stateJson, ctx)      — paints the 9x9 grid + 3x3 box rules + the
//                                    given/player digits + the selected cell
//                                    highlight + conflict shading, all canvas
//                                    primitives (NO image assets, Unicode-free
//                                    numerals drawn with fillText).
//   • onLocalInput(evt, ctx)      — maps a canvas click (pick a cell) or a digit
//                                    keypress (place) / erase into a wasm op,
//                                    applied AUTHORITATIVELY through the engine,
//                                    and returns the new state.
//   • onRemoteEvent(...)          — A NO-OP. Sudoku is SINGLE-PLAYER: it runs
//                                    purely locally against the wasm engine,
//                                    never opens a session, never relays.
//
// SINGLE-PLAYER / LOCAL-ONLY: unlike chess / connect-four, Sudoku has no
// host/guest, no relay, no world broadcast. onLocalInput returns ONLY `{ state }`
// (no input/world/control), so the game-frame's relay path is never exercised
// even if a session_id were present. The engine is the single source of truth;
// render() never mutates it — every transition flows through sudoku_place /
// sudoku_clear so the board can never drift from the rules engine.
//
// The engine is the verified hugmesh_sudoku_wasm.wasm; we drive it through the
// shared WasmHost with the EXACT call pattern proven in
// games/_shared/test/sudoku-wasm.test.mjs:
//   host.callStatus('sudoku_new', null, [seedLo, seedHi, clues]) -> 1
//   host.callPacked('sudoku_state')                              -> { type:'sudoku.snapshot', board, givens, status, ... }
//   host.callRaw('sudoku_place', null, [row, col, n])            -> packed snapshot | sudoku.error
//   host.callRaw('sudoku_clear', null, [row, col])               -> packed snapshot | sudoku.error
//   host.callStatus('sudoku_is_given', null, [row, col])         -> 0|1
//
// Vanilla ES module. No build step, no dependencies.

export const N = 9; // grid side
export const BOX = 3; // 3x3 box side
const CELLS = N * N;

// A fully-empty 81-char board (render fallback before the engine has spoken).
const EMPTY_BOARD = '.'.repeat(CELLS);

// Palette — brand purple frame/lines (WCAG 1.4.11 non-text at 3:1); given vs.
// player digits read distinctly, the selection + conflict washes are translucent.
const COLORS = {
  boardFill: '#1f1b2e',
  gridThin: '#4a4470',
  gridThick: '#7c4dff',
  given: '#f2eefb', // immutable clue digits — bright
  entry: '#7cc4ff', // player entries — cool blue
  badEntry: '#ff8a8a', // a player entry the engine rejected (transient flash text)
  selectFill: 'rgba(124, 77, 255, 0.28)', // selected cell wash
  peerFill: 'rgba(124, 77, 255, 0.10)', // same row/col/box as selection
  sameDigitFill: 'rgba(124, 196, 255, 0.16)', // cells sharing the selected digit
  solvedFill: 'rgba(108, 207, 127, 0.18)', // a faint green wash when solved
};

/** Is the puzzle finished? */
export function isSolved(status) {
  return status === 'solved';
}

/** Human-readable HUD status line from an engine snapshot. */
export function statusLine(snap) {
  if (!snap) return '';
  if (snap.status === 'solved') return 'Solved! Well done.';
  const filled = typeof snap.filled === 'number' ? snap.filled : 0;
  const remaining = CELLS - filled;
  return `${remaining} cell${remaining === 1 ? '' : 's'} to go`;
}

/**
 * Parse a sudoku snapshot's 81-char board string into a flat array of digits
 * (0 == empty). Tolerates '.'/'0' for empties. Always returns 81 entries.
 */
export function parseBoard(boardStr) {
  const s = (typeof boardStr === 'string' ? boardStr : EMPTY_BOARD).slice(0, CELLS);
  const out = new Array(CELLS).fill(0);
  for (let i = 0; i < CELLS; i++) {
    const ch = s.charAt(i);
    out[i] = ch >= '1' && ch <= '9' ? ch.charCodeAt(0) - 48 : 0;
  }
  return out;
}

/** Parse the givens mask string ('.'/digit) into a boolean[81]. */
export function parseGivens(givensStr) {
  const s = (typeof givensStr === 'string' ? givensStr : EMPTY_BOARD).slice(0, CELLS);
  const out = new Array(CELLS).fill(false);
  for (let i = 0; i < CELLS; i++) {
    const ch = s.charAt(i);
    out[i] = ch >= '1' && ch <= '9';
  }
  return out;
}

// ── the GAME object ──────────────────────────────────────────────────────────

export function createSudokuGame(options) {
  const opts = options || {};
  // Default seed/clues: a deterministic, medium puzzle. A URL ?seed= could
  // override this in the future; for now it's reproducible per boot config.
  const seed = Number.isFinite(opts.seed) ? opts.seed >>> 0 : 20260613;
  const clues = Number.isFinite(opts.clues) ? opts.clues | 0 : 32;

  // Per-instance UI state.
  const ui = {
    canvas: null,
    cx: null,
    cell: 0, // one cell in pixels (square)
    padding: 0, // outer board padding
    selRow: -1, // selected cell row, or -1
    selCol: -1,
    flash: null, // a transient error string for the HUD
    hud: null, // { status, reset, hint } elements
    onResize: null,
    onKey: null,
  };

  // Latest authoritative snapshot we rendered.
  let snap = {
    type: 'sudoku.snapshot',
    givens: EMPTY_BOARD,
    board: EMPTY_BOARD,
    givenCount: 0,
    filled: 0,
    status: 'playing',
  };

  /** Pull the current snapshot out of the engine into `snap`. */
  function readEngine(host) {
    const s = host.callPacked('sudoku_state') || {};
    if (s && s.type === 'sudoku.snapshot') return adopt(s);
    return snap;
  }

  /** Adopt an engine sudoku.snapshot payload as the new authoritative state. */
  function adopt(s) {
    snap = {
      type: 'sudoku.snapshot',
      givens: typeof s.givens === 'string' ? s.givens : snap.givens,
      board: typeof s.board === 'string' ? s.board : snap.board,
      givenCount: typeof s.givenCount === 'number' ? s.givenCount : snap.givenCount,
      filled: typeof s.filled === 'number' ? s.filled : snap.filled,
      status: typeof s.status === 'string' ? s.status : snap.status,
    };
    return snap;
  }

  /** Seed a fresh puzzle. The u64 seed is split into two u32 halves for the ABI. */
  function newGame(host) {
    const lo = seed >>> 0;
    const hi = 0; // seeds here fit in 32 bits; high half is 0
    host.callStatus('sudoku_new', null, [lo, hi, clues]);
    ui.selRow = -1;
    ui.selCol = -1;
    ui.flash = null;
    return readEngine(host);
  }

  const GAME = {
    slug: 'sudoku',

    async init(host, container, ctx) {
      buildDom(container, ui, ctx);
      newGame(host);
      return snap;
    },

    render(stateJson, ctx) {
      if (stateJson && typeof stateJson === 'object' && stateJson.type === 'sudoku.snapshot') {
        adopt(stateJson);
      }
      drawBoard(ui, snap);
      drawHud(ui, snap, ctx);
    },

    onLocalInput(evt, ctx) {
      const host = ctx.host;
      if (!evt) return null;

      // New game / reset — re-seed the SAME deterministic puzzle.
      if (evt.type === 'reset') {
        newGame(host);
        return { state: snap };
      }

      // Reveal a single correct digit for the first empty cell (the hint button).
      if (evt.type === 'hint') {
        const h = host.callPacked('sudoku_hint');
        if (!h || h.type !== 'sudoku.hint') {
          ui.flash = 'No hint available';
          return { state: snap };
        }
        const out = host.callPacked('sudoku_place', null, [h.row, h.col, h.digit]);
        if (out && out.type === 'sudoku.snapshot') {
          ui.flash = null;
          ui.selRow = h.row;
          ui.selCol = h.col;
          adopt(out);
        }
        return { state: snap };
      }

      // Pick a cell (canvas click → row/col).
      if (evt.type === 'click' || evt.type === 'cellpick') {
        const { row, col } =
          evt.type === 'cellpick'
            ? { row: evt.row, col: evt.col }
            : cellFromEvent(ui, evt);
        if (row < 0 || col < 0) return { state: snap };
        ui.selRow = row;
        ui.selCol = col;
        ui.flash = null;
        return { state: snap };
      }

      // Erase the selected (non-given) cell.
      if (evt.type === 'erase') {
        if (ui.selRow < 0 || ui.selCol < 0) return { state: snap };
        if (host.callStatus('sudoku_is_given', null, [ui.selRow, ui.selCol]) === 1) {
          ui.flash = "Can't change a clue";
          return { state: snap };
        }
        const out = host.callPacked('sudoku_clear', null, [ui.selRow, ui.selCol]);
        if (out && out.type === 'sudoku.snapshot') {
          ui.flash = null;
          adopt(out);
        } else if (out && out.type === 'sudoku.error') {
          ui.flash = errorText(out.error);
        }
        return { state: snap };
      }

      // Place a digit into the selected cell.
      if (evt.type === 'digit') {
        const d = evt.digit | 0;
        if (d < 1 || d > 9) return { state: snap };
        if (ui.selRow < 0 || ui.selCol < 0) {
          ui.flash = 'Select a cell first';
          return { state: snap };
        }
        const out = host.callPacked('sudoku_place', null, [ui.selRow, ui.selCol, d]);
        if (out && out.type === 'sudoku.snapshot') {
          ui.flash = null;
          adopt(out);
        } else if (out && out.type === 'sudoku.error') {
          ui.flash = errorText(out.error);
        }
        return { state: snap };
      }

      return null;
    },

    // SINGLE-PLAYER: there is no relay. A no-op (the frame never wires a session
    // for a game that returns no input/world payloads, but we implement it
    // defensively so a stray relayed event is harmlessly ignored).
    onRemoteEvent(_channel, _json, _ctx) {
      return null;
    },

    destroy() {
      if (typeof window !== 'undefined' && typeof window.removeEventListener === 'function') {
        if (ui.onResize) {
          window.removeEventListener('resize', ui.onResize);
          ui.onResize = null;
        }
        if (ui.onKey) {
          window.removeEventListener('keydown', ui.onKey);
          ui.onKey = null;
        }
      }
    },

    // Exposed for the HUD buttons + tests.
    _newGame: (host) => newGame(host),
    _ui: ui,
    _getState: () => snap,
  };

  return GAME;
}

// ── error text ────────────────────────────────────────────────────────────────

function errorText(reason) {
  switch (reason) {
    case 'conflict':
      return 'Conflicts with row, column, or box';
    case 'given-cell':
      return "Can't change a clue";
    case 'bad-digit':
      return 'Pick a digit 1–9';
    case 'out-of-range':
      return 'Cell out of range';
    case 'no-game':
      return 'No puzzle loaded';
    default:
      return reason ? `Rejected (${reason})` : 'Rejected';
  }
}

// ── DOM / canvas plumbing ──────────────────────────────────────────────────────

function buildDom(container, ui, ctx) {
  const canvas = container.querySelector('#board') || makeCanvas(container);
  ui.canvas = canvas;
  ui.cx = canvas.getContext('2d');
  layout(ui);

  // Click → pick a cell.
  canvas.addEventListener('click', (e) => {
    if (ctx && typeof ctx.handleLocalInput === 'function') {
      ctx.handleLocalInput(e);
    }
  });

  // HUD wiring (status / reset / hint) — built by index.html if present.
  const statusEl = container.querySelector('#status');
  const resetBtn = container.querySelector('#reset');
  const hintBtn = container.querySelector('#hint');
  ui.hud = { status: statusEl, reset: resetBtn, hint: hintBtn };
  if (resetBtn) {
    resetBtn.addEventListener('click', () => {
      if (typeof ctx.handleLocalInput === 'function') ctx.handleLocalInput({ type: 'reset' });
    });
  }
  if (hintBtn) {
    hintBtn.addEventListener('click', () => {
      if (typeof ctx.handleLocalInput === 'function') ctx.handleLocalInput({ type: 'hint' });
    });
  }

  // Keyboard: digits 1–9 place, 0/Backspace/Delete erase, arrows move selection.
  if (typeof window !== 'undefined' && typeof window.addEventListener === 'function') {
    ui.onKey = (e) => {
      if (typeof ctx.handleLocalInput !== 'function') return;
      const k = e.key;
      if (k >= '1' && k <= '9') {
        ctx.handleLocalInput({ type: 'digit', digit: k.charCodeAt(0) - 48 });
        e.preventDefault();
      } else if (k === '0' || k === 'Backspace' || k === 'Delete') {
        ctx.handleLocalInput({ type: 'erase' });
        e.preventDefault();
      } else if (k === 'ArrowUp' || k === 'ArrowDown' || k === 'ArrowLeft' || k === 'ArrowRight') {
        moveSelection(ui, ctx, k);
        e.preventDefault();
      }
    };
    window.addEventListener('keydown', ui.onKey);

    ui.onResize = () => {
      layout(ui);
      ctx.GAME.render(ctx.GAME._getState(), ctx);
    };
    window.addEventListener('resize', ui.onResize);
    if (ctx && typeof ctx.onTeardown === 'function') {
      ctx.onTeardown(() => {
        if (ui.onResize) window.removeEventListener('resize', ui.onResize);
        if (ui.onKey) window.removeEventListener('keydown', ui.onKey);
        ui.onResize = null;
        ui.onKey = null;
      });
    }
  }
}

function moveSelection(ui, ctx, key) {
  let r = ui.selRow < 0 ? 0 : ui.selRow;
  let c = ui.selCol < 0 ? 0 : ui.selCol;
  if (ui.selRow >= 0 && ui.selCol >= 0) {
    if (key === 'ArrowUp') r = Math.max(0, r - 1);
    else if (key === 'ArrowDown') r = Math.min(N - 1, r + 1);
    else if (key === 'ArrowLeft') c = Math.max(0, c - 1);
    else if (key === 'ArrowRight') c = Math.min(N - 1, c + 1);
  }
  ctx.handleLocalInput({ type: 'cellpick', row: r, col: c });
}

function makeCanvas(container) {
  const c = typeof document !== 'undefined' ? document.createElement('canvas') : null;
  if (c) {
    c.id = 'board';
    container.appendChild(c);
  }
  return c;
}

function layout(ui) {
  if (!ui.canvas) return;
  let target = 540;
  if (typeof window !== 'undefined' && window.innerWidth) {
    const w = window.innerWidth;
    const h = window.innerHeight || window.innerWidth;
    const byWidth = w - 24;
    const byHeight = h - 110; // leave room for the HUD
    target = Math.max(270, Math.min(byWidth, byHeight, 612));
  }
  // Make the board side a multiple of 9 so cells are integral.
  ui.cell = Math.max(30, Math.floor(target / N));
  ui.padding = Math.max(6, Math.floor(ui.cell * 0.18));
  const side = ui.cell * N + ui.padding * 2;
  ui.canvas.width = side;
  ui.canvas.height = side;
  if (ui.canvas.style) {
    ui.canvas.style.width = side + 'px';
    ui.canvas.style.height = side + 'px';
  }
}

/** Which (row,col) an event's (x,y) falls into, or {row:-1,col:-1} if outside. */
function cellFromEvent(ui, evt) {
  if (!ui.canvas) return { row: -1, col: -1 };
  const rect =
    typeof ui.canvas.getBoundingClientRect === 'function'
      ? ui.canvas.getBoundingClientRect()
      : { left: 0, top: 0, width: ui.canvas.width, height: ui.canvas.height };
  let x = evt.offsetX;
  let y = evt.offsetY;
  if (x === undefined) x = (evt.clientX || 0) - rect.left;
  if (y === undefined) y = (evt.clientY || 0) - rect.top;
  const scaleX = ui.canvas.width / (rect.width || ui.canvas.width);
  const scaleY = ui.canvas.height / (rect.height || ui.canvas.height);
  const px = x * scaleX - ui.padding;
  const py = y * scaleY - ui.padding;
  if (px < 0 || py < 0) return { row: -1, col: -1 };
  const col = Math.floor(px / ui.cell);
  const row = Math.floor(py / ui.cell);
  if (row < 0 || row >= N || col < 0 || col >= N) return { row: -1, col: -1 };
  return { row, col };
}

// ── drawing ─────────────────────────────────────────────────────────────────

function drawBoard(ui, snap) {
  const cx = ui.cx;
  if (!cx) return;
  const cell = ui.cell;
  const pad = ui.padding;
  const side = cell * N + pad * 2;

  const board = parseBoard(snap.board);
  const givens = parseGivens(snap.givens);
  const solved = isSolved(snap.status);

  // Background.
  cx.fillStyle = COLORS.boardFill;
  cx.fillRect(0, 0, side, side);

  // A faint green wash over the whole grid when solved.
  if (solved) {
    cx.fillStyle = COLORS.solvedFill;
    cx.fillRect(pad, pad, cell * N, cell * N);
  }

  // Selection-related washes (peers, same-digit, selected) — only while playing.
  const sr = ui.selRow;
  const sc = ui.selCol;
  const haveSel = sr >= 0 && sc >= 0;
  const selDigit = haveSel ? board[sr * N + sc] : 0;

  for (let r = 0; r < N; r++) {
    for (let c = 0; c < N; c++) {
      const x = pad + c * cell;
      const y = pad + r * cell;
      const idx = r * N + c;

      if (haveSel && !solved) {
        const samePeer =
          r === sr ||
          c === sc ||
          (Math.floor(r / BOX) === Math.floor(sr / BOX) &&
            Math.floor(c / BOX) === Math.floor(sc / BOX));
        const sameDigit = selDigit > 0 && board[idx] === selDigit;
        if (r === sr && c === sc) {
          cx.fillStyle = COLORS.selectFill;
          cx.fillRect(x, y, cell, cell);
        } else if (sameDigit) {
          cx.fillStyle = COLORS.sameDigitFill;
          cx.fillRect(x, y, cell, cell);
        } else if (samePeer) {
          cx.fillStyle = COLORS.peerFill;
          cx.fillRect(x, y, cell, cell);
        }
      }
    }
  }

  // Grid lines: thin between cells, thick between 3x3 boxes + the outer frame.
  for (let i = 0; i <= N; i++) {
    const thick = i % BOX === 0;
    cx.strokeStyle = thick ? COLORS.gridThick : COLORS.gridThin;
    cx.lineWidth = thick ? Math.max(2, cell * 0.06) : Math.max(1, cell * 0.02);
    const p = pad + i * cell;
    // vertical
    cx.beginPath();
    cx.moveTo(p, pad);
    cx.lineTo(p, pad + cell * N);
    cx.stroke();
    // horizontal
    cx.beginPath();
    cx.moveTo(pad, p);
    cx.lineTo(pad + cell * N, p);
    cx.stroke();
  }

  // Digits. Givens bright, player entries cool blue. Canvas fillText numerals —
  // NO image assets, NO Unicode glyphs beyond the ASCII digits.
  cx.textAlign = 'center';
  cx.textBaseline = 'middle';
  cx.font = `${Math.floor(cell * 0.62)}px system-ui, -apple-system, "Segoe UI", Roboto, sans-serif`;
  for (let r = 0; r < N; r++) {
    for (let c = 0; c < N; c++) {
      const idx = r * N + c;
      const v = board[idx];
      if (v === 0) continue;
      const cxp = pad + c * cell + cell / 2;
      const cyp = pad + r * cell + cell / 2;
      cx.fillStyle = givens[idx] ? COLORS.given : COLORS.entry;
      cx.fillText(String(v), cxp, cyp);
    }
  }
}

function drawHud(ui, snap, _ctx) {
  if (!ui.hud) return;
  if (ui.hud.status) {
    ui.hud.status.textContent = ui.flash ? ui.flash : statusLine(snap);
    if (ui.hud.status.classList) {
      ui.hud.status.classList.toggle('over', isSolved(snap.status));
      ui.hud.status.classList.toggle('warn', !!ui.flash && !isSolved(snap.status));
    }
  }
  if (ui.hud.hint) {
    // Disable the hint button once solved.
    ui.hud.hint.disabled = isSolved(snap.status);
  }
}

export default createSudokuGame;
