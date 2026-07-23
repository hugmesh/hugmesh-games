// games/reversi/reversi.js
//
// The Reversi/Othello GAME implementation for the shared web-native game shell
// (games/_shared/{wasm-host,sse-host-client,game-frame}.js). It is a single
// `GAME` object that:
//
//   • init(host, container, ctx)  — seeds the engine (reversi_new), builds the
//                                    canvas + HUD, returns the initial state.
//   • render(stateJson, ctx)      — paints the 8x8 board + discs from the engine
//                                    snapshot, plus legal-move hints, last-move /
//                                    flipped decoration, score + game-over text.
//   • onLocalInput(evt, ctx)      — maps ONE canvas click (placing a disc) into
//                                    an algebraic square, applies it
//                                    AUTHORITATIVELY through the wasm engine, and
//                                    returns the relay payload + new state. Also
//                                    handles the Pass action (forced pass).
//   • onRemoteEvent(channel, json, ctx) — re-applies the opponent's relayed
//                                    placement/pass/world against the same wasm
//                                    engine (the guest re-validates).
//
// HOST-AUTHORITATIVE: the local browser running this engine is the source of
// truth (docs/multiplayer-chess-design.md). render() never mutates the engine;
// every state transition flows through reversi_apply / reversi_pass so the board
// can never drift from the rules engine.
//
// Discs are canvas-drawn circles (no image assets). The engine is the verified
// hugmesh_reversi_wasm.wasm; we drive it through the shared WasmHost with the
// EXACT call pattern proven in games/_shared/test/reversi-wasm.test.mjs:
//   host.callStatus('reversi_new')                 -> 1
//   host.callPacked('reversi_state')               -> { type:'reversi.snapshot', board, side, black, white, legal, status }
//   host.callPacked('reversi_legal')               -> ["c4","d3", ...]
//   host.callPacked('reversi_status')              -> { status }
//   host.call('reversi_apply', 'd3')               -> { type:'reversi.state', ... } | { type:'reversi.error', ... }
//   host.callPacked('reversi_pass')                -> { type:'reversi.state', lastMove:'pass', ... } | { type:'reversi.error', ... }
//   host.callRaw('reversi_from_board', null, ...)  -> (board+side, used only on remote resync)
//
// Vanilla ES module. No build step, no dependencies.

const FILES = ['a', 'b', 'c', 'd', 'e', 'f', 'g', 'h'];

// The standard Othello opening board (square 0 = a1 first), Black to move.
// d5,e4 = Black; d4,e5 = White (see hugmesh-reversi/src/lib.rs).
const START_BOARD = (() => {
  const cells = new Array(64).fill('.');
  // index = fileIdx + rankIdx*8, rankIdx 0 == rank 1.
  cells[idxOf('d5')] = 'B';
  cells[idxOf('e4')] = 'B';
  cells[idxOf('d4')] = 'W';
  cells[idxOf('e5')] = 'W';
  return cells.join('');
})();

// Board palette (clears the WCAG-audited non-text contrast bar at 3:1).
const COLORS = {
  felt: '#1f6f43', // the green felt squares
  feltAlt: '#23794a', // subtle checker so the grid reads
  grid: '#0d3a23',
  blackDisc: '#1a1a1a',
  whiteDisc: '#fbfbfb',
  discStroke: '#0d3a23',
  legal: 'rgba(124, 77, 255, 0.55)', // legal-move hint dots
  lastMove: 'rgba(255, 215, 64, 0.55)', // last-placed square tint
  flipped: 'rgba(255, 215, 64, 0.30)', // squares whose discs just flipped
};

/** algebraic square ("d3") → engine square index (fileIdx + rankIdx*8). */
function idxOf(sq) {
  const f = FILES.indexOf(sq[0]);
  const r = parseInt(sq[1], 10) - 1;
  return f + r * 8;
}

/** engine square index → algebraic square ("d3"). */
export function squareName(fileIdx, rankIdx) {
  return FILES[fileIdx] + String(rankIdx + 1);
}

/**
 * Parse the engine's 64-char board string (square 0 = a1 first, rank 0 == rank 1)
 * into a render grid indexed [rowFromTop][fileIdx], where rowFromTop 0 is rank 8
 * (the top of the board) so it draws like a board faced by Black at the bottom.
 * Cells are 'B' | 'W' | null.
 */
export function parseBoard(boardStr) {
  const s = (boardStr && boardStr.length === 64) ? boardStr : START_BOARD;
  const grid = [];
  for (let row = 0; row < 8; row++) {
    const cells = [];
    const rankIdx = 7 - row; // top row is rank 8 → rankIdx 7
    for (let f = 0; f < 8; f++) {
      const c = s[f + rankIdx * 8];
      cells.push(c === 'B' || c === 'W' ? c : null);
    }
    grid.push(cells);
  }
  return grid;
}

/** Count discs of a colour ('B'|'W') in the engine board string. */
export function countDiscs(boardStr, who) {
  if (!boardStr) return 0;
  let n = 0;
  for (const c of boardStr) if (c === who) n++;
  return n;
}

/** True if the game has ended (a terminal status). */
export function isGameOver(status) {
  return typeof status === 'string' && (status.startsWith('win:') || status === 'draw');
}

/** Human-readable status line from an engine status string + side to move. */
export function statusLine(status, side, mustPass) {
  switch (true) {
    case status === 'ongoing': {
      const who = side === 'white' ? 'White' : 'Black';
      return mustPass ? `${who} must pass` : `${who} to move`;
    }
    case status === 'win:black':
      return 'Black wins';
    case status === 'win:white':
      return 'White wins';
    case status === 'draw':
      return 'Draw';
    default:
      return status || 'ongoing';
  }
}

// ── the GAME object ──────────────────────────────────────────────────────────

export function createReversiGame() {
  // Per-instance UI state.
  const ui = {
    canvas: null,
    cx: null,
    size: 0,
    cell: 0,
    hud: null, // { turn, status, score, pass } elements
    banner: null, // transient HUD message (sync warnings)
    onResize: null,
  };

  // Latest authoritative state we rendered.
  let state = {
    board: START_BOARD,
    side: 'black',
    black: 2,
    white: 2,
    legal: ['c4', 'd3', 'e6', 'f5'],
    status: 'ongoing',
    lastMove: null,
    flipped: [],
  };

  /** Pull the full current position out of the engine into our `state` shape. */
  function readEngine(host) {
    const snap = host.callPacked('reversi_state') || {};
    return {
      board: snap.board || START_BOARD,
      side: snap.side || 'black',
      black: typeof snap.black === 'number' ? snap.black : countDiscs(snap.board, 'B'),
      white: typeof snap.white === 'number' ? snap.white : countDiscs(snap.board, 'W'),
      legal: Array.isArray(snap.legal) ? snap.legal : [],
      status: snap.status || 'ongoing',
      lastMove: state.lastMove || null,
      flipped: state.flipped || [],
    };
  }

  /** Merge an Outcome (reversi.state) into `state`, then read the fresh board. */
  function applyOutcome(host, outcome, lastMove) {
    const snap = readEngine(host);
    state = {
      board: snap.board,
      side: outcome.next || snap.side,
      black: typeof outcome.black === 'number' ? outcome.black : snap.black,
      white: typeof outcome.white === 'number' ? outcome.white : snap.white,
      legal: snap.legal,
      status: outcome.status || snap.status,
      lastMove: outcome.lastMove !== undefined ? outcome.lastMove : lastMove,
      flipped: Array.isArray(outcome.flipped) ? outcome.flipped : [],
    };
    return state;
  }

  const GAME = {
    slug: 'reversi',

    async init(host, container, ctx) {
      // Seed the engine to the standard opening (verified: returns 1).
      host.callStatus('reversi_new');
      buildDom(container, ui, ctx);
      state = readEngine(host);
      state.lastMove = null;
      state.flipped = [];
      return state;
    },

    render(stateJson, ctx) {
      if (stateJson && typeof stateJson === 'object') {
        state = {
          board: stateJson.board || state.board,
          side: stateJson.side || state.side,
          black: typeof stateJson.black === 'number' ? stateJson.black : state.black,
          white: typeof stateJson.white === 'number' ? stateJson.white : state.white,
          legal: Array.isArray(stateJson.legal) ? stateJson.legal : state.legal,
          status: stateJson.status || 'ongoing',
          lastMove: stateJson.lastMove !== undefined ? stateJson.lastMove : state.lastMove,
          flipped: Array.isArray(stateJson.flipped) ? stateJson.flipped : (state.flipped || []),
        };
      }
      drawBoard(ui, state);
      drawHud(ui, state, ctx);
    },

    onLocalInput(evt, ctx) {
      const host = ctx.host;
      if (!evt) return null;

      // Pass action (only legal when the side to move has no placement). The
      // pass is RELAYED so the opponent's board advances the turn too.
      if (evt.type === 'pass') {
        if (isGameOver(state.status)) return null;
        const outcome = host.callPacked('reversi_pass');
        if (!outcome || outcome.type === 'reversi.error') {
          flashError(ui, outcome && outcome.error);
          return { state };
        }
        ui.banner = null;
        applyOutcome(host, outcome, 'pass');
        const world = worldFrom(state);
        return {
          input: { kind: 'control', type: 'move', payload: { pass: true } },
          world,
          state,
        };
      }

      if (evt.type !== 'click') return null;
      if (isGameOver(state.status)) return null;

      const sq = canvasToSquare(ui, evt);
      if (!sq) return null;
      const clicked = squareName(sq.fileIdx, sq.rankIdx);

      // Only a legal placement does anything (single-click; no selection step).
      if (!state.legal.includes(clicked)) {
        return null;
      }
      return applyMove(clicked);

      // Apply an algebraic square through the engine, refresh derived state, and
      // build the relay payloads (the guest re-applies, AND the host broadcasts).
      function applyMove(square) {
        const outcome = host.call('reversi_apply', square);
        if (!outcome || outcome.type === 'reversi.error') {
          flashError(ui, outcome && outcome.error);
          return { state };
        }
        ui.banner = null;
        applyOutcome(host, outcome, square);
        const world = worldFrom(state);
        return {
          input: { kind: 'control', type: 'move', payload: { square } },
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

      // The opponent's intended placement/pass (guest→host `input`) OR the host's
      // authoritative `world` broadcast (host→guest). Both re-apply against the
      // engine; the guest re-validates by letting the engine accept/reject.
      const payload = json.payload || json;
      let square = null;
      let pass = false;
      let worldBoard = null;
      let worldSide = null;
      if (payload && typeof payload === 'object') {
        square = payload.square || (payload.payload && payload.payload.square) || null;
        pass = Boolean(payload.pass || (payload.payload && payload.payload.pass));
        worldBoard = payload.board || null;
        worldSide = payload.side || null;
      }

      // A relayed pass — advance the turn locally.
      if (pass) {
        let outcome = null;
        try {
          outcome = host.callPacked('reversi_pass');
        } catch (_e) {
          outcome = { type: 'reversi.error', error: 'engine-trap' };
        }
        if (outcome && outcome.type !== 'reversi.error') {
          ui.banner = null;
          applyOutcome(host, outcome, 'pass');
          return state;
        }
        // Engine rejected the pass; fall through to a world resync if present.
      }

      // A relayed placement — validate the shape before handing it to the engine.
      // A malformed (multi-byte / emoji) token can TRAP the wasm instance; reject
      // it as a desync instead of letting it throw and silently lose the move.
      if (square) {
        let outcome = null;
        if (isSquareShaped(square)) {
          try {
            outcome = host.call('reversi_apply', square);
          } catch (_e) {
            outcome = { type: 'reversi.error', error: 'engine-trap' };
          }
        } else {
          outcome = { type: 'reversi.error', error: 'bad-square' };
        }
        if (outcome && outcome.type !== 'reversi.error') {
          ui.banner = null;
          applyOutcome(host, outcome, square);
          return state;
        }
        // Engine rejected the relayed move. If the host also sent a world board,
        // trust the authoritative board (host is the source of truth).
      }

      // Authoritative world resync: re-seat the engine at the host's board.
      if (worldBoard && isBoardShaped(worldBoard)) {
        const side = worldSide === 'white' ? 'white' : 'black';
        const ok = loadBoard(host, worldBoard, side);
        if (ok === 1) {
          ui.banner = null;
          const snap = readEngine(host);
          state = {
            ...snap,
            lastMove: (payload && payload.lastMove) || state.lastMove,
            flipped: (payload && Array.isArray(payload.flipped)) ? payload.flipped : [],
          };
          return state;
        }
      }

      // We received a relayed move/world we could neither apply nor recover from
      // — the guest's board has diverged from the authoritative host. Surface it
      // rather than silently dropping it.
      if (square || pass || worldBoard) {
        ui.banner = 'Host out of sync';
        return { ...state };
      }
      return null;
    },

    // Release game-owned listeners. Called by the frame on iframe unload.
    destroy() {
      if (ui.onResize && typeof window !== 'undefined' &&
          typeof window.removeEventListener === 'function') {
        window.removeEventListener('resize', ui.onResize);
        ui.onResize = null;
      }
    },

    // Exposed for the pass button + tests.
    _pass(host) {
      const outcome = host.callPacked('reversi_pass');
      if (outcome && outcome.type !== 'reversi.error') {
        applyOutcome(host, outcome, 'pass');
      }
      return state;
    },
    _ui: ui,
    _getState: () => state,
  };

  return GAME;
}

// ── world broadcast shape (host-authoritative) ────────────────────────────────

/** The host-authoritative `world` payload — the full board so a guest can
 *  re-seat its engine exactly even if it dropped an intermediate move. */
function worldFrom(s) {
  return {
    board: s.board,
    side: s.side,
    black: s.black,
    white: s.white,
    status: s.status,
    lastMove: s.lastMove,
    flipped: s.flipped || [],
  };
}

// ── shape guards (protect the engine from malformed relayed tokens) ───────────

/** A well-formed algebraic square: file a-h + rank 1-8 (e.g. "d3"). */
function isSquareShaped(s) {
  return typeof s === 'string' && /^[a-h][1-8]$/.test(s);
}

/** A plausible board string: 64 cells of 'B'/'W'/'.'. */
function isBoardShaped(s) {
  return typeof s === 'string' && s.length === 64 && /^[BW.]{64}$/.test(s);
}

/** Re-seat the engine from a board string + side via reversi_from_board. */
function loadBoard(host, boardStr, side) {
  // reversi_from_board(boardPtr, boardLen, sidePtr, sideLen). The shared
  // WasmHost has no 4-arg-string helper, so marshal both buffers by hand using
  // its low-level primitives — identical to the contract callRaw uses.
  const ex = host.exports;
  const enc = new TextEncoder();
  const bbuf = enc.encode(boardStr);
  const sbuf = enc.encode(side);
  const bptr = Number(ex.alloc(bbuf.length));
  new Uint8Array(host.memory.buffer).set(bbuf, bptr);
  const sptr = Number(ex.alloc(sbuf.length));
  new Uint8Array(host.memory.buffer).set(sbuf, sptr);
  try {
    return Number(ex.reversi_from_board(bptr, bbuf.length, sptr, sbuf.length)) | 0;
  } finally {
    ex.dealloc(bptr, bbuf.length);
    ex.dealloc(sptr, sbuf.length);
  }
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

  // HUD wiring (turn / status / score / pass) — built by index.html if present.
  const turnEl = container.querySelector('#turn');
  const statusEl = container.querySelector('#status');
  const scoreEl = container.querySelector('#score');
  const passBtn = container.querySelector('#pass');
  ui.hud = { turn: turnEl, status: statusEl, score: scoreEl, pass: passBtn };
  if (passBtn && ctx) {
    passBtn.addEventListener('click', () => {
      // Pass is routed through handleLocalInput so it RELAYS to the opponent.
      if (typeof ctx.handleLocalInput === 'function') {
        ctx.handleLocalInput({ type: 'pass' });
      } else {
        const next = ctx.GAME._pass(ctx.host);
        ctx.GAME.render(next, ctx);
      }
    });
  }

  // Re-layout on resize so the board stays square + mobile-friendly. Tracked so
  // GAME.destroy() can remove it on iframe teardown.
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

function layout(ui) {
  if (!ui.canvas) return;
  let target = 480;
  if (typeof window !== 'undefined' && window.innerWidth) {
    target = Math.min(window.innerWidth, window.innerHeight || window.innerWidth) - 24;
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
  let x = evt.offsetX;
  let y = evt.offsetY;
  if (x === undefined || y === undefined) {
    x = (evt.clientX || 0) - rect.left;
    y = (evt.clientY || 0) - rect.top;
  }
  const scaleX = (ui.cell * 8) / (rect.width || ui.cell * 8);
  const scaleY = (ui.cell * 8) / (rect.height || ui.cell * 8);
  const col = Math.floor((x * scaleX) / ui.cell);
  const rowFromTop = Math.floor((y * scaleY) / ui.cell);
  if (col < 0 || col > 7 || rowFromTop < 0 || rowFromTop > 7) return null;
  // rowFromTop 0 is rank 8 → rankIdx 7. Engine fileIdx == col.
  return { fileIdx: col, rankIdx: 7 - rowFromTop };
}

// ── drawing ───────────────────────────────────────────────────────────────────

function drawBoard(ui, state) {
  const cx = ui.cx;
  if (!cx) return;
  const cell = ui.cell;
  const grid = parseBoard(state.board);

  const lastSq = (state.lastMove && state.lastMove !== 'pass') ? state.lastMove : null;
  const flippedSet = new Set(Array.isArray(state.flipped) ? state.flipped : []);

  for (let row = 0; row < 8; row++) {
    for (let col = 0; col < 8; col++) {
      const x = col * cell;
      const y = row * cell;
      // Felt squares (subtle checker so the 8x8 grid is legible).
      cx.fillStyle = (row + col) % 2 === 0 ? COLORS.felt : COLORS.feltAlt;
      cx.fillRect(x, y, cell, cell);

      const rankIdx = 7 - row;
      const sqName = FILES[col] + String(rankIdx + 1);

      // Flipped-square tint (the discs that just changed colour).
      if (flippedSet.has(sqName)) {
        cx.fillStyle = COLORS.flipped;
        cx.fillRect(x, y, cell, cell);
      }
      // Last-move tint (the square the disc was just placed on).
      if (lastSq === sqName) {
        cx.fillStyle = COLORS.lastMove;
        cx.fillRect(x, y, cell, cell);
      }

      // Grid line.
      cx.strokeStyle = COLORS.grid;
      cx.lineWidth = Math.max(1, cell * 0.02);
      cx.strokeRect(x, y, cell, cell);

      // Disc, if present.
      const disc = grid[row][col];
      if (disc) {
        cx.beginPath();
        cx.arc(x + cell / 2, y + cell / 2, cell * 0.38, 0, Math.PI * 2);
        cx.fillStyle = disc === 'B' ? COLORS.blackDisc : COLORS.whiteDisc;
        cx.fill();
        cx.lineWidth = Math.max(1, cell * 0.03);
        cx.strokeStyle = COLORS.discStroke;
        cx.stroke();
      }
    }
  }

  // Legal-move hint dots for the side to move (single-click placement).
  if (!isGameOver(state.status) && Array.isArray(state.legal)) {
    cx.fillStyle = COLORS.legal;
    for (const sq of state.legal) {
      const f = FILES.indexOf(sq[0]);
      const rankIdx = parseInt(sq[1], 10) - 1;
      if (f < 0 || Number.isNaN(rankIdx)) continue;
      const col = f;
      const row = 7 - rankIdx;
      const cxp = col * cell + cell / 2;
      const cyp = row * cell + cell / 2;
      cx.beginPath();
      cx.arc(cxp, cyp, cell * 0.14, 0, Math.PI * 2);
      cx.fill();
    }
  }
}

function drawHud(ui, state, ctx) {
  if (!ui.hud) return;
  const over = isGameOver(state.status);
  const mustPass = !over && Array.isArray(state.legal) && state.legal.length === 0;
  if (ui.hud.turn) {
    const turn = state.side === 'white' ? 'White' : 'Black';
    ui.hud.turn.textContent = over ? '' : `Turn: ${turn}`;
  }
  if (ui.hud.score) {
    ui.hud.score.textContent = `Black ${state.black} – ${state.white} White`;
  }
  if (ui.hud.status) {
    ui.hud.status.textContent = ui.banner
      ? ui.banner
      : statusLine(state.status, state.side, mustPass);
    ui.hud.status.classList && ui.hud.status.classList.toggle('over', over);
  }
  if (ui.hud.pass) {
    // Pass is only enabled when the side to move genuinely has no placement.
    ui.hud.pass.disabled = over || !mustPass;
  }
}

function flashError(ui, reason) {
  if (ui.hud && ui.hud.status) {
    ui.hud.status.textContent = `Illegal move${reason ? ` (${reason})` : ''}`;
  }
}

export default createReversiGame;
