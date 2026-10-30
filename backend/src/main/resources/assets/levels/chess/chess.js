// games/chess/chess.js
//
// The chess GAME implementation for the shared web-native game shell
// (games/_shared/{wasm-host,sse-host-client,game-frame}.js). It is a single
// `GAME` object that:
//
//   • init(host, container, ctx)  — seeds the engine (chess_new), builds the
//                                    canvas + HUD, returns the initial state.
//   • render(stateJson, ctx)      — paints the 8x8 board + pieces from a FEN,
//                                    plus check / selection / legal-move /
//                                    last-move / game-over decoration.
//   • onLocalInput(evt, ctx)      — maps two canvas clicks (from-square then
//                                    to-square) into a UCI move, applies it
//                                    AUTHORITATIVELY through the wasm engine,
//                                    and returns the relay payload + new state.
//   • onRemoteEvent(channel, json, ctx) — re-applies the opponent's relayed
//                                    move/world against the same wasm engine
//                                    (the guest re-validates).
//
// HOST-AUTHORITATIVE: the local browser running this engine is the source of
// truth (docs/multiplayer-chess-design.md). render() never mutates the engine;
// every state transition flows through chess_apply_uci / chess_resign so the
// board can never drift from the rules engine.
//
// Pieces are Unicode glyphs (no image assets). The engine is the verified
// hugmesh_chess.wasm; we drive it through the shared WasmHost with the
// EXACT call pattern proven in games/_shared/test/chess-wasm.test.mjs:
//   host.callStatus('chess_new')              -> 1
//   host.callPacked('chess_fen')              -> { fen, ... }
//   host.callPacked('chess_legal_moves')      -> ["e2e4", ...]
//   host.callPacked('chess_status')           -> { status }
//   host.call('chess_apply_uci', 'e2e4')      -> { type:'chess.state', ... } | { type:'chess.error', ... }
//   host.callStatus('chess_from_fen', fen)    -> 0|1
//   host.call('chess_resign', 'white')        -> { status:'resigned:black', ... }
//
// Vanilla ES module. No build step, no dependencies.

// Unicode chess glyphs, keyed by FEN piece letter (uppercase = white).
const GLYPH = {
  K: '♔', Q: '♕', R: '♖', B: '♗', N: '♘', P: '♙',
  k: '♚', q: '♛', r: '♜', b: '♝', n: '♞', p: '♟',
};

const FILES = ['a', 'b', 'c', 'd', 'e', 'f', 'g', 'h'];
const START_FEN = 'rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1';

// Board palette (clears the WCAG-audited non-text contrast bar at 3:1).
const COLORS = {
  light: '#ead9b5',
  dark: '#9c6b3f',
  selected: '#7c4dff',
  legal: 'rgba(124, 77, 255, 0.55)',
  legalCapture: 'rgba(255, 92, 92, 0.65)',
  lastMove: 'rgba(255, 215, 64, 0.45)',
  check: 'rgba(255, 56, 56, 0.70)',
  whitePiece: '#fbfbfb',
  blackPiece: '#1a1a1a',
  pieceStroke: '#000000',
  pieceStrokeWhite: '#444444',
};

/**
 * Parse the placement field of a FEN into an 8x8 array indexed [rank][file]
 * where rank 0 is the 8th rank (top of the board as White sees it) and file 0
 * is the a-file. Cells are the FEN piece letter or null.
 */
export function parseFenBoard(fen) {
  const placement = (fen || START_FEN).split(' ')[0];
  const rows = placement.split('/');
  const board = [];
  for (let r = 0; r < 8; r++) {
    const cells = [];
    const row = rows[r] || '8';
    for (const ch of row) {
      if (ch >= '1' && ch <= '8') {
        const n = ch.charCodeAt(0) - 48;
        for (let i = 0; i < n; i++) cells.push(null);
      } else {
        cells.push(ch);
      }
    }
    while (cells.length < 8) cells.push(null);
    board.push(cells.slice(0, 8));
  }
  while (board.length < 8) board.push(new Array(8).fill(null));
  return board;
}

/** Side to move from a FEN ('w' | 'b'); defaults to white. */
export function sideToMove(fen) {
  const parts = (fen || START_FEN).split(' ');
  return parts[1] === 'b' ? 'b' : 'w';
}

/**
 * Convert (fileIndex 0..7, rankIndex 0..7 where 0 is the 8th rank) into an
 * algebraic square like "e2".  rankIndex 0 → rank 8, rankIndex 7 → rank 1.
 */
export function squareName(fileIdx, rankIdx) {
  return FILES[fileIdx] + String(8 - rankIdx);
}

/** Locate the king of `side` ('w'|'b') in a parsed board → {fileIdx,rankIdx} | null. */
function findKing(board, side) {
  const target = side === 'w' ? 'K' : 'k';
  for (let r = 0; r < 8; r++) {
    for (let f = 0; f < 8; f++) {
      if (board[r][f] === target) return { fileIdx: f, rankIdx: r };
    }
  }
  return null;
}

/** Count placed pieces in a parsed board. */
export function countPieces(board) {
  let n = 0;
  for (let r = 0; r < 8; r++) for (let f = 0; f < 8; f++) if (board[r][f]) n++;
  return n;
}

/** Human-readable status line from an engine status string. */
export function statusLine(status, fen) {
  switch (true) {
    case status === 'ongoing': {
      const side = sideToMove(fen) === 'w' ? 'White' : 'Black';
      return `${side} to move`;
    }
    case status === 'check:white':
      return 'White is in check';
    case status === 'check:black':
      return 'Black is in check';
    case status === 'checkmate:white':
      return 'Checkmate — White wins';
    case status === 'checkmate:black':
      return 'Checkmate — Black wins';
    case status === 'stalemate':
      return 'Stalemate — draw';
    case status === 'resigned:white':
      return 'White wins by resignation';
    case status === 'resigned:black':
      return 'Black wins by resignation';
    default:
      return status || 'ongoing';
  }
}

/** True if the game has ended (no further moves are legal). */
export function isGameOver(status) {
  return (
    typeof status === 'string' &&
    (status.startsWith('checkmate') ||
      status === 'stalemate' ||
      status.startsWith('resigned'))
  );
}

/** A king is "in check" (for the highlight) when status says so. */
function sideInCheck(status) {
  if (status === 'check:white' || status === 'checkmate:white') return 'w';
  if (status === 'check:black' || status === 'checkmate:black') return 'b';
  return null;
}

// ── the GAME object ──────────────────────────────────────────────────────────

export function createChessGame() {
  // Per-instance UI state (selection + the latest engine state).
  const ui = {
    canvas: null,
    cx: null,
    size: 0, // canvas pixel size (square)
    cell: 0, // one square in pixels
    selected: null, // {fileIdx, rankIdx} of the picked-up piece, or null
    legalFromSelected: [], // ["e2e4", ...] destinations from `selected`
    legalAll: [], // all legal moves in the current position
    hud: null, // { turn, status, resign } elements
    promoEl: null, // the #promo overlay element (picker), if present
    promo: false, // true while the promotion picker is open (board clicks ignored)
    promoPending: null, // { base } UCI prefix awaiting a piece choice
    promoChoice: null, // last piece the picker chose ('q'|'r'|'b'|'n')
    onResize: null, // bound resize handler, removed on destroy
  };

  // Latest authoritative state we rendered (fen + status + lastMove).
  let state = { fen: START_FEN, status: 'ongoing', lastMove: null };

  /** Pull the current position out of the engine into our `state` shape. */
  function readEngine(host) {
    const fenObj = host.callPacked('chess_fen') || {};
    const statusObj = host.callPacked('chess_status') || {};
    return {
      fen: fenObj.fen || START_FEN,
      status: statusObj.status || 'ongoing',
      lastMove: state.lastMove || null,
    };
  }

  function refreshLegal(host) {
    ui.legalAll = host.callPacked('chess_legal_moves') || [];
  }

  const GAME = {
    slug: 'chess',

    async init(host, container, ctx) {
      // Seed the engine to the start position (verified: returns 1).
      host.callStatus('chess_new');
      refreshLegal(host);

      buildDom(container, ui, ctx);
      state = readEngine(host);
      ui.selected = null;
      ui.legalFromSelected = [];
      return state;
    },

    render(stateJson, ctx) {
      if (stateJson && typeof stateJson === 'object') {
        state = {
          fen: stateJson.fen || state.fen,
          status: stateJson.status || 'ongoing',
          lastMove:
            stateJson.lastMove !== undefined ? stateJson.lastMove : state.lastMove,
        };
      }
      drawBoard(ui, state);
      drawHud(ui, state, ctx);
    },

    onLocalInput(evt, ctx) {
      const host = ctx.host;
      if (!evt) return null;

      // Promotion choice (from the picker overlay) — re-issue the stashed
      // base move with the chosen piece suffix. This is how underpromotion
      // reaches the engine; without it a promotion always auto-queened.
      if (evt.type === 'promote') {
        if (isGameOver(state.status) || !ui.promoPending) return null;
        const piece = /^[qrbn]$/.test(evt.piece) ? evt.piece : 'q';
        const uci = ui.promoPending.base + piece;
        closePromo(ui);
        return applyMove(uci);
      }

      // Resignation. The local player resigns THEIR colour (not whoever's
      // turn it is); in single-player hot-seat there's no fixed colour so we
      // fall back to the side to move. The resignation is RELAYED so the
      // opponent learns the game ended.
      if (evt.type === 'resign') {
        if (isGameOver(state.status)) return null;
        const color =
          evt.color || (sideToMove(state.fen) === 'w' ? 'white' : 'black');
        GAME._resign(host, color);
        const world = {
          fen: state.fen,
          san: null,
          status: state.status,
          lastMove: state.lastMove,
        };
        return {
          input: { kind: 'control', type: 'move', payload: { resign: color } },
          world,
          state,
        };
      }

      if (evt.type !== 'click') return null;
      if (isGameOver(state.status)) return null;
      if (ui.promo) return null; // a picker is open; ignore board clicks

      const sq = canvasToSquare(ui, evt);
      if (!sq) return null;

      const clicked = squareName(sq.fileIdx, sq.rankIdx);
      const board = parseFenBoard(state.fen);
      const piece = board[sq.rankIdx][sq.fileIdx];
      const turn = sideToMove(state.fen);
      const isOwnPiece =
        piece &&
        ((turn === 'w' && piece === piece.toUpperCase()) ||
          (turn === 'b' && piece === piece.toLowerCase()));

      // First click (or re-pick): select one of the side-to-move's pieces.
      if (!ui.selected) {
        if (!isOwnPiece) return null;
        return select(sq, clicked);
      }

      const fromSq = squareName(ui.selected.fileIdx, ui.selected.rankIdx);

      // Clicking the same square again → deselect.
      if (clicked === fromSq) {
        ui.selected = null;
        ui.legalFromSelected = [];
        return { state };
      }

      // Clicking another own piece → switch the selection.
      if (isOwnPiece) {
        return select(sq, clicked);
      }

      // Second click → attempt a move from→to.
      const base = fromSq + clicked;
      const promo = promotionLetterFor(board, ui.selected, sq);

      // Promotion: open the picker so the player can choose ANY piece
      // (queen / rook / bishop / knight). Underpromotion is a required engine
      // feature; auto-queening made it unreachable. We stash the base move and
      // wait for a 'promote' event from the picker. The selection is kept lit
      // until the choice resolves so the player sees what they're promoting.
      if (promo) {
        ui.promoPending = { base };
        openPromo(ui);
        return { state };
      }

      // Apply authoritatively through the engine.
      return applyMove(base);

      function select(square, name) {
        ui.selected = square;
        ui.legalFromSelected = ui.legalAll.filter((m) => m.slice(0, 2) === name);
        return { state };
      }

      // Apply a fully-formed UCI through the engine, refresh derived state,
      // and build the relay payloads. Shared by the normal + promotion paths.
      function applyMove(uci) {
        const outcome = host.call('chess_apply_uci', uci);
        ui.selected = null;
        ui.legalFromSelected = [];

        if (!outcome || outcome.type === 'chess.error') {
          // Illegal — keep the board as-is, surface nothing to relay.
          flashError(ui, outcome && outcome.error);
          return { state };
        }

        // Accepted: refresh derived state from the engine + the outcome.
        refreshLegal(host);
        state = {
          fen: outcome.fen || readEngine(host).fen,
          status: outcome.status || readEngine(host).status,
          lastMove: outcome.lastMove || uci,
        };

        const world = {
          fen: state.fen,
          san: outcome.san || null,
          status: state.status,
          lastMove: state.lastMove,
        };
        return {
          // The relay payload the guest re-applies (input channel), AND the
          // host-authoritative world broadcast.
          input: { kind: 'control', type: 'move', payload: { uci } },
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
          state = { ...state, status: state.status };
          ui.banner = 'Session closed';
        }
        return state;
      }

      // The opponent's intended move (guest→host `input`) OR the host's
      // authoritative `world` broadcast (host→guest). Both re-apply against the
      // engine; the guest re-validates by letting the engine accept/reject.
      const payload = json.payload || json;
      let uci = null;
      let worldFen = null;
      let resignColor = null;
      if (payload && typeof payload === 'object') {
        uci = payload.uci || (payload.payload && payload.payload.uci) || null;
        worldFen = payload.fen || null;
        resignColor =
          payload.resign || (payload.payload && payload.payload.resign) || null;
      }

      // A relayed resignation — apply it locally so this side sees game-over.
      if (resignColor && (resignColor === 'white' || resignColor === 'black')) {
        ui.banner = null;
        host.call('chess_resign', resignColor);
        state = {
          ...state,
          status: (host.callPacked('chess_status') || {}).status || state.status,
        };
        ui.selected = null;
        ui.legalFromSelected = [];
        return state;
      }

      if (uci) {
        // Validate the relayed UCI shape before handing it to the engine. A
        // malformed (e.g. multi-byte / emoji) uci can TRAP the wasm instance
        // (RuntimeError: unreachable); we reject it as a desync instead of
        // letting it throw and silently lose the move.
        let outcome = null;
        if (isUciShaped(uci)) {
          try {
            outcome = host.call('chess_apply_uci', uci);
          } catch (_e) {
            outcome = { type: 'chess.error', error: 'engine-trap' };
          }
        } else {
          outcome = { type: 'chess.error', error: 'bad-uci' };
        }
        if (outcome && outcome.type !== 'chess.error') {
          ui.banner = null; // a good move clears any prior desync warning
          refreshLegal(host);
          state = {
            fen: outcome.fen || readEngine(host).fen,
            status: outcome.status || readEngine(host).status,
            lastMove: outcome.lastMove || uci,
          };
          ui.selected = null;
          ui.legalFromSelected = [];
          return state;
        }
        // Engine rejected the relayed move. If the host also sent a world FEN,
        // trust the authoritative FEN (host is the source of truth).
      }

      if (worldFen) {
        const ok = isFenShaped(worldFen) ? host.callStatus('chess_from_fen', worldFen) : 0;
        if (ok === 1) {
          ui.banner = null;
          refreshLegal(host);
          state = {
            fen: worldFen,
            status: (host.callPacked('chess_status') || {}).status || 'ongoing',
            lastMove: (payload && payload.lastMove) || state.lastMove,
          };
          ui.selected = null;
          ui.legalFromSelected = [];
          return state;
        }
      }

      // We received a relayed move/world we could neither apply nor recover
      // from — the guest's board has diverged from the authoritative host.
      // The design (docs/multiplayer-chess-design.md) requires surfacing this
      // rather than silently dropping it: show "host out of sync" so the
      // player knows the position is unreliable and can abort/reload.
      if (uci || worldFen) {
        ui.banner = 'Host out of sync';
        return { ...state };
      }
      return null;
    },

    // Release game-owned listeners. Called by the frame on iframe unload.
    // (The frame's ctx.onTeardown already removes the resize handler; this is
    // a defensive fallback if a game is mounted without the frame's teardown.)
    destroy() {
      if (ui.onResize && typeof window !== 'undefined' &&
          typeof window.removeEventListener === 'function') {
        window.removeEventListener('resize', ui.onResize);
        ui.onResize = null;
      }
    },

    // Exposed for the resign button + tests.
    _resign(host, color) {
      const outcome = host.call('chess_resign', color);
      if (outcome && outcome.status) {
        state = { ...state, status: outcome.status, lastMove: state.lastMove };
      }
      return state;
    },
    _ui: ui,
    _getState: () => state,
  };

  return GAME;
}

// ── promotion detection ──────────────────────────────────────────────────────

/**
 * If moving the selected piece to `to` is a pawn reaching the last rank, return
 * the side ('w'|'b') needing a promotion choice; else null.
 */
function promotionLetterFor(board, from, to) {
  const piece = board[from.rankIdx][from.fileIdx];
  if (piece === 'P' && to.rankIdx === 0) return 'w';
  if (piece === 'p' && to.rankIdx === 7) return 'b';
  return null;
}

/** Show the promotion picker overlay (board clicks are blocked while open). */
function openPromo(ui) {
  ui.promo = true;
  if (ui.promoEl) {
    if (ui.promoEl.classList) ui.promoEl.classList.add('open');
    if (typeof ui.promoEl.setAttribute === 'function') {
      ui.promoEl.setAttribute('aria-hidden', 'false');
    }
  }
}

/** Hide the promotion picker overlay and clear the pending move. */
function closePromo(ui) {
  ui.promo = false;
  ui.promoPending = null;
  if (ui.promoEl) {
    if (ui.promoEl.classList) ui.promoEl.classList.remove('open');
    if (typeof ui.promoEl.setAttribute === 'function') {
      ui.promoEl.setAttribute('aria-hidden', 'true');
    }
  }
}

/**
 * A well-formed UCI move: two squares + optional promotion piece
 * (e.g. "e2e4", "e7e8q"). Guards the engine against a malformed / multi-byte
 * relayed token that could trap the wasm instance.
 */
function isUciShaped(s) {
  return typeof s === 'string' && /^[a-h][1-8][a-h][1-8][qrbn]?$/.test(s);
}

/**
 * The local player's colour in a multiplayer session: the HOST (slot 0, the
 * creator) plays white, the guest plays black. Returns null in single-player
 * hot-seat (no session) where there is no fixed local colour and either side
 * may move — the caller then falls back to the side to move.
 */
function localColor(ctx) {
  const params = ctx && ctx.params;
  if (!params || !params.sessionId) return null; // hot-seat
  return ctx.isHost ? 'white' : 'black';
}

/** A FEN is plausible if it's an ASCII string with the 8 rank fields. */
function isFenShaped(s) {
  return (
    typeof s === 'string' &&
    s.length > 0 &&
    // eslint-disable-next-line no-control-regex
    /^[\x20-\x7e]+$/.test(s) &&
    s.split(' ')[0].split('/').length === 8
  );
}

// ── DOM / canvas plumbing ─────────────────────────────────────────────────────

function buildDom(container, ui, ctx) {
  // The canvas board.
  const canvas =
    container.querySelector('#board') || makeCanvas(container);
  ui.canvas = canvas;
  ui.cx = canvas.getContext('2d');
  layout(ui);

  // Forward clicks into the host-authoritative loop.
  canvas.addEventListener('click', (e) => {
    if (ctx && typeof ctx.handleLocalInput === 'function') {
      ctx.handleLocalInput(e);
    }
  });

  // Promotion picker wiring. The overlay markup lives in index.html; we wire
  // its buttons HERE (in the game module) so the bundle is self-contained and
  // underpromotion works without any per-page glue. Each button feeds a
  // synthetic { type:'promote', piece } event back through the same
  // host-authoritative loop so the chosen move relays like any other.
  const promoEl =
    (container.querySelector && container.querySelector('#promo')) ||
    (typeof document !== 'undefined' && document.getElementById
      ? document.getElementById('promo')
      : null);
  ui.promoEl = promoEl;
  if (promoEl && typeof promoEl.querySelectorAll === 'function' && ctx) {
    const buttons = promoEl.querySelectorAll('button[data-promo]') || [];
    buttons.forEach((btn) => {
      btn.addEventListener('click', () => {
        const piece = btn.getAttribute('data-promo');
        ui.promoChoice = piece;
        if (typeof ctx.handleLocalInput === 'function') {
          ctx.handleLocalInput({ type: 'promote', piece });
        }
      });
    });
  }

  // HUD wiring (turn / status / resign) — built by index.html if present.
  const turnEl = container.querySelector('#turn');
  const statusEl = container.querySelector('#status');
  const resignBtn = container.querySelector('#resign');
  ui.hud = { turn: turnEl, status: statusEl, resign: resignBtn };
  if (resignBtn && ctx) {
    resignBtn.addEventListener('click', () => {
      // Resign the LOCAL player's colour (host=white, guest=black) so a
      // player can't accidentally resign their opponent on the opponent's
      // turn. In single-player hot-seat there's no fixed colour — pass none
      // and onLocalInput falls back to the side to move. Routed through
      // handleLocalInput so the resignation RELAYS to the opponent.
      const color = localColor(ctx);
      if (typeof ctx.handleLocalInput === 'function') {
        ctx.handleLocalInput({ type: 'resign', color });
      } else {
        // No frame loop (e.g. a bare test harness) — apply directly.
        const next = ctx.GAME._resign(ctx.host, color || (sideToMove(ctx.GAME._getState().fen) === 'w' ? 'white' : 'black'));
        ctx.GAME.render(next, ctx);
      }
    });
  }

  // Re-layout on resize so the board stays square + mobile-friendly. The
  // handler is tracked so GAME.destroy() can remove it on iframe teardown
  // (otherwise it leaks for the life of the window).
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
  let target = 480;
  if (typeof window !== 'undefined' && window.innerWidth) {
    // Leave room for what sits above and below the board (body padding, the
    // HUD, which wraps on narrow screens, the gap). Sizing to the full
    // viewport height overflowed the frame by the HUD's height whenever the
    // board was height-limited (the in-shell player clipped chess's last rank).
    const availH = (window.innerHeight || window.innerWidth) - boardReserve(ui.canvas);
    target = Math.min(window.innerWidth - 24, availH);
    target = Math.max(240, Math.min(target, 640));
  }
  ui.size = target;
  ui.cell = Math.floor(target / 8);
  const px = ui.cell * 8;
  ui.canvas.width = px;
  ui.canvas.height = px;
  if (ui.canvas.style) {
    ui.canvas.style.width = px + 'px';
    ui.canvas.style.height = px + 'px';
  }
}

function canvasToSquare(ui, evt) {
  if (!ui.canvas) return null;
  const rect =
    typeof ui.canvas.getBoundingClientRect === 'function'
      ? ui.canvas.getBoundingClientRect()
      : { left: 0, top: 0, width: ui.cell * 8, height: ui.cell * 8 };
  // Allow tests to pass offsetX/offsetY directly.
  let x = evt.offsetX;
  let y = evt.offsetY;
  if (x === undefined || y === undefined) {
    x = (evt.clientX || 0) - rect.left;
    y = (evt.clientY || 0) - rect.top;
  }
  // Scale from CSS pixels to canvas pixels if the element was sized via style.
  const scaleX = (ui.cell * 8) / (rect.width || ui.cell * 8);
  const scaleY = (ui.cell * 8) / (rect.height || ui.cell * 8);
  const fileIdx = Math.floor((x * scaleX) / ui.cell);
  const rankIdx = Math.floor((y * scaleY) / ui.cell);
  if (fileIdx < 0 || fileIdx > 7 || rankIdx < 0 || rankIdx > 7) return null;
  return { fileIdx, rankIdx };
}

// ── drawing ───────────────────────────────────────────────────────────────────

function drawBoard(ui, state) {
  const cx = ui.cx;
  if (!cx) return;
  const cell = ui.cell;
  const board = parseFenBoard(state.fen);
  const inCheckSide = sideInCheck(state.status);
  const kingSq = inCheckSide ? findKing(board, inCheckSide) : null;

  // Last-move squares for the yellow highlight.
  let lastFrom = null;
  let lastTo = null;
  if (state.lastMove && state.lastMove.length >= 4) {
    lastFrom = uciSquareToIdx(state.lastMove.slice(0, 2));
    lastTo = uciSquareToIdx(state.lastMove.slice(2, 4));
  }

  for (let r = 0; r < 8; r++) {
    for (let f = 0; f < 8; f++) {
      const x = f * cell;
      const y = r * cell;
      // Base square.
      cx.fillStyle = (r + f) % 2 === 0 ? COLORS.light : COLORS.dark;
      cx.fillRect(x, y, cell, cell);

      // Last-move tint.
      if (
        (lastFrom && lastFrom.fileIdx === f && lastFrom.rankIdx === r) ||
        (lastTo && lastTo.fileIdx === f && lastTo.rankIdx === r)
      ) {
        cx.fillStyle = COLORS.lastMove;
        cx.fillRect(x, y, cell, cell);
      }

      // Check highlight on the king square.
      if (kingSq && kingSq.fileIdx === f && kingSq.rankIdx === r) {
        cx.fillStyle = COLORS.check;
        cx.fillRect(x, y, cell, cell);
      }

      // Selection highlight.
      if (ui.selected && ui.selected.fileIdx === f && ui.selected.rankIdx === r) {
        cx.fillStyle = COLORS.selected;
        cx.fillRect(x, y, cell, cell);
      }
    }
  }

  // Legal-move dots for the current selection.
  for (const m of ui.legalFromSelected) {
    const dest = uciSquareToIdx(m.slice(2, 4));
    if (!dest) continue;
    const board2 = board;
    const captures = board2[dest.rankIdx][dest.fileIdx] !== null;
    const cxp = dest.fileIdx * cell + cell / 2;
    const cyp = dest.rankIdx * cell + cell / 2;
    cx.fillStyle = captures ? COLORS.legalCapture : COLORS.legal;
    cx.beginPath();
    cx.arc(cxp, cyp, captures ? cell * 0.42 : cell * 0.16, 0, Math.PI * 2);
    cx.fill();
  }

  // Pieces.
  cx.textAlign = 'center';
  cx.textBaseline = 'middle';
  cx.font = `${Math.floor(cell * 0.78)}px "Segoe UI Symbol","Noto Sans Symbols2","DejaVu Sans",serif`;
  for (let r = 0; r < 8; r++) {
    for (let f = 0; f < 8; f++) {
      const p = board[r][f];
      if (!p) continue;
      const glyph = GLYPH[p];
      if (!glyph) continue;
      const isWhite = p === p.toUpperCase();
      const cxp = f * cell + cell / 2;
      const cyp = r * cell + cell / 2;
      // Outline for legibility against either square color.
      cx.lineWidth = Math.max(1, cell * 0.03);
      cx.strokeStyle = isWhite ? COLORS.pieceStroke : COLORS.pieceStrokeWhite;
      cx.strokeText(glyph, cxp, cyp);
      cx.fillStyle = isWhite ? COLORS.whitePiece : COLORS.blackPiece;
      cx.fillText(glyph, cxp, cyp);
    }
  }
}

function drawHud(ui, state, ctx) {
  if (!ui.hud) return;
  const over = isGameOver(state.status);
  if (ui.hud.turn) {
    const turn = sideToMove(state.fen) === 'w' ? 'White' : 'Black';
    ui.hud.turn.textContent = over ? '' : `Turn: ${turn}`;
  }
  if (ui.hud.status) {
    ui.hud.status.textContent = ui.banner
      ? ui.banner
      : statusLine(state.status, state.fen);
    ui.hud.status.classList && ui.hud.status.classList.toggle('over', over);
  }
  if (ui.hud.resign) {
    ui.hud.resign.disabled = over;
  }
}

function flashError(ui, reason) {
  if (ui.hud && ui.hud.status) {
    ui.hud.status.textContent = `Illegal move${reason ? ` (${reason})` : ''}`;
  }
}

function uciSquareToIdx(sq) {
  if (!sq || sq.length < 2) return null;
  const f = FILES.indexOf(sq[0]);
  const rank = parseInt(sq[1], 10);
  if (f < 0 || Number.isNaN(rank)) return null;
  return { fileIdx: f, rankIdx: 8 - rank };
}

export const _glyphs = GLYPH;
export default createChessGame;
