// games/match-three/match-three.js
//
// The Match-3 (Bejeweled-like) GAME implementation for the shared web-native game
// shell (games/_shared/{wasm-host,sse-host-client,game-frame}.js). It is a single
// `GAME` object that:
//
//   • init(host, container, ctx)  — seeds the engine (match3_new with a seed read
//                                    from the ?seed= URL param, or a default),
//                                    builds the canvas + HUD, returns the initial
//                                    state.
//   • render(stateJson, ctx)      — paints the width×height grid of coloured gems,
//                                    a selected-gem highlight, and the score /
//                                    moves / status HUD.
//   • onLocalInput(evt, ctx)      — maps a canvas click (two-click OR click-drag
//                                    swap of two adjacent gems) into a swap,
//                                    applies it AUTHORITATIVELY through the wasm
//                                    engine, and returns ONLY { state } — there is
//                                    NO relay (single-player puzzle).
//
// LOCAL-ONLY, SINGLE-PLAYER. This is a SEEDED score-attack puzzle: the engine's
// own doc-comment is explicit — "no multiplayer, no server, no relay". The GAME
// interface still applies, but onLocalInput returns NO `input`/`world`/`control`
// payloads (nothing is sent over the session). onRemoteEvent is a no-op. The board
// is fully determined by (width, height, colors, seed); two games with the same
// seed are identical move-for-move.
//
// The engine is the verified hugmesh_match_three_wasm.wasm; we drive it through
// the shared WasmHost with the EXACT call pattern proven in
// games/_shared/test/match-three-wasm.test.mjs:
//   host.callStatus('match3_new', '8,8,6,42')   -> 1
//   host.callPacked('match3_snapshot')          -> { type:'match3.snapshot', width, height, colors, score, board, legalSwaps, status }
//   host.callPacked('match3_legal')             -> [[ax,ay,bx,by], ...]
//   host.callPacked('match3_status')            -> { status }
//   host.call('match3_swap', '2,1,2,2')         -> { type:'match3.swap', cleared, cascades, score, steps, status } | { type:'match3.error', error }
//
// Vanilla ES module. No build step, no dependencies.

// Default board config (the URL `?seed=` overrides the seed; width/height/colors
// can be overridden by `?w=`/`?h=`/`?colors=`).
const DEFAULTS = { width: 8, height: 8, colors: 6, seed: 1 };

// Six distinct gem colours (index = gem value). The palette clears the WCAG-audited
// non-text contrast bar and stays distinguishable. A 7th+ colour (rare configs)
// falls back to a hashed hue so the grid still reads.
const GEM_COLORS = [
  '#e0436b', // 0 ruby
  '#3fb6e6', // 1 sapphire
  '#4cc66a', // 2 emerald
  '#f2c14e', // 3 topaz
  '#b388ff', // 4 amethyst
  '#ff8a3d', // 5 amber
  '#f2eefb', // 6 diamond (pale)
  '#7c8a9e', // 7 onyx (slate)
];

const COLORS = {
  cellA: '#211b33', // checkerboard cell A
  cellB: '#1a1528', // checkerboard cell B
  grid: '#322a4a',
  gemStroke: '#0d0a16',
  selected: 'rgba(255, 215, 64, 0.85)', // selected-gem ring
  hint: 'rgba(124, 77, 255, 0.45)', // (unused-by-default) swap hint tint
};

/** Colour for a gem value, with a stable hashed fallback past the palette. */
export function gemColor(v) {
  if (v >= 0 && v < GEM_COLORS.length) return GEM_COLORS[v];
  const hue = (v * 47) % 360;
  return `hsl(${hue}, 65%, 60%)`;
}

/** Parse the seed/dimension config from URL params (frame passes ctx.params, but
 *  this puzzle reads its OWN ?seed=/?w=/?h=/?colors= too so it's standalone). */
export function parseConfig(search) {
  const qs = new URLSearchParams(
    search !== undefined ? search : (typeof window !== 'undefined' ? window.location.search : ''),
  );
  const intOr = (k, d) => {
    const v = qs.get(k);
    if (v === null || v === '') return d;
    const n = parseInt(v, 10);
    return Number.isNaN(n) ? d : n;
  };
  // Clamp to the engine's accepted range so a hostile URL can't trap the wasm.
  const width = Math.max(3, Math.min(intOr('w', DEFAULTS.width), 12));
  const height = Math.max(3, Math.min(intOr('h', DEFAULTS.height), 12));
  const colors = Math.max(3, Math.min(intOr('colors', DEFAULTS.colors), GEM_COLORS.length));
  // Seed is any non-negative integer; default 1. (0 is valid; the engine nudges it.)
  let seed = intOr('seed', DEFAULTS.seed);
  if (seed < 0) seed = DEFAULTS.seed;
  return { width, height, colors, seed };
}

/** Two cells are orthogonally adjacent (the only legal swap geometry). */
export function isAdjacent(a, b) {
  if (!a || !b) return false;
  const dx = Math.abs(a.x - b.x);
  const dy = Math.abs(a.y - b.y);
  return dx + dy === 1;
}

// ── the GAME object ──────────────────────────────────────────────────────────

export function createMatchThreeGame() {
  // Per-instance UI state.
  const ui = {
    canvas: null,
    cx: null,
    cell: 0,
    hud: null, // { score, moves, status, new } elements
    banner: null, // transient HUD message (illegal-move flash)
    selected: null, // { x, y } of the first-clicked gem (two-click swap), or null
    moves: 0, // count of LEGAL swaps applied this game (drawHud reads this)
    onResize: null,
  };

  // The board config this instance was seeded with (so the New-game button reseeds
  // with a fresh seed but the same dimensions).
  let config = { ...DEFAULTS };

  // Latest authoritative state we rendered (mirrors the engine snapshot shape).
  let state = {
    width: DEFAULTS.width,
    height: DEFAULTS.height,
    colors: DEFAULTS.colors,
    score: 0,
    board: [],
    legalSwaps: 0,
    status: 'playing',
  };

  /** Pull the full current board out of the engine into our `state` shape. */
  function readEngine(host) {
    const snap = host.callPacked('match3_snapshot') || {};
    return {
      width: typeof snap.width === 'number' ? snap.width : config.width,
      height: typeof snap.height === 'number' ? snap.height : config.height,
      colors: typeof snap.colors === 'number' ? snap.colors : config.colors,
      score: typeof snap.score === 'number' ? snap.score : 0,
      board: Array.isArray(snap.board) ? snap.board : [],
      legalSwaps: typeof snap.legalSwaps === 'number' ? snap.legalSwaps : 0,
      status: snap.status || 'playing',
    };
  }

  /** (Re)seed the engine to a config and refresh derived state. */
  function seed(host, cfg) {
    config = { ...cfg };
    const ok = host.callStatus('match3_new', `${cfg.width},${cfg.height},${cfg.colors},${cfg.seed}`);
    if (ok !== 1) {
      // Fall back to the safe defaults if the requested config was rejected.
      config = { ...DEFAULTS };
      host.callStatus('match3_new', `${DEFAULTS.width},${DEFAULTS.height},${DEFAULTS.colors},${DEFAULTS.seed}`);
    }
    ui.moves = 0;
    ui.selected = null;
    ui.banner = null;
    state = readEngine(host);
    return state;
  }

  const GAME = {
    slug: 'match-three',

    async init(host, container, ctx) {
      // SINGLE-PLAYER puzzle: seed from the URL (?seed=/?w=/?h=/?colors=) or default.
      const cfg = parseConfig(ctx && ctx.params && ctx.params.search);
      buildDom(container, ui, ctx);
      seed(host, cfg);
      return state;
    },

    render(stateJson, ctx) {
      if (stateJson && typeof stateJson === 'object' && Array.isArray(stateJson.board)) {
        state = {
          width: typeof stateJson.width === 'number' ? stateJson.width : state.width,
          height: typeof stateJson.height === 'number' ? stateJson.height : state.height,
          colors: typeof stateJson.colors === 'number' ? stateJson.colors : state.colors,
          score: typeof stateJson.score === 'number' ? stateJson.score : state.score,
          board: stateJson.board,
          legalSwaps: typeof stateJson.legalSwaps === 'number' ? stateJson.legalSwaps : state.legalSwaps,
          status: stateJson.status || state.status,
        };
      }
      layout(ui, state);
      drawBoard(ui, state);
      drawHud(ui, state);
    },

    // LOCAL-ONLY: applies the swap through the engine and returns ONLY { state }.
    // No `input` / `world` / `control` — nothing is relayed (single-player puzzle).
    onLocalInput(evt, ctx) {
      const host = ctx.host;
      if (!evt) return null;

      // New-game action (re-seed with a fresh random seed, same dimensions).
      if (evt.type === 'new-game') {
        const fresh = { ...config, seed: nextSeed(config.seed) };
        seed(host, fresh);
        return { state };
      }

      if (evt.type !== 'click') return null;
      if (isTerminal(state.status)) return null;

      const cell = canvasToCell(ui, evt, state);
      if (!cell) return null;

      // Two-click (or click-drag) swap: first click selects; a second click on an
      // orthogonally-adjacent gem attempts the swap; a second click elsewhere
      // re-selects.
      if (!ui.selected) {
        ui.selected = cell;
        return { state };
      }

      const a = ui.selected;
      const b = cell;
      // Clicking the same gem again deselects.
      if (a.x === b.x && a.y === b.y) {
        ui.selected = null;
        return { state };
      }
      // A non-adjacent second click becomes the new selection.
      if (!isAdjacent(a, b)) {
        ui.selected = b;
        return { state };
      }

      // Adjacent pair — attempt the swap through the engine.
      ui.selected = null;
      const outcome = host.call('match3_swap', `${a.x},${a.y},${b.x},${b.y}`);
      if (!outcome || outcome.type === 'match3.error') {
        flashError(ui, outcome && outcome.error);
        return { state };
      }
      // A legal swap: count the move, read the freshly settled board.
      ui.moves += 1;
      ui.banner = null;
      state = readEngine(host);
      return { state };
    },

    // SINGLE-PLAYER: nothing is relayed, so there is nothing to re-apply. The
    // GAME interface requires the method; it is a deliberate no-op here.
    onRemoteEvent(_channel, _json, _ctx) {
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

    // Exposed for the HUD button + tests.
    _ui: ui,
    _getState: () => state,
    _getMoves: () => ui.moves,
    _getConfig: () => ({ ...config }),
  };

  return GAME;
}

// ── helpers ───────────────────────────────────────────────────────────────────

/** True when the game is over (no legal swap exists). */
export function isTerminal(status) {
  return status === 'no_moves';
}

/** Deterministic next seed for the New-game button (keeps the puzzle reproducible
 *  within a session while giving a fresh board each press). */
function nextSeed(prev) {
  // A small LCG step, mirroring the engine's own seeding ethos; stays a positive
  // 32-bit-ish integer so the CSV config the engine parses is always well-formed.
  const n = ((prev >>> 0) * 1664525 + 1013904223) >>> 0;
  return n || 1;
}

function flashError(ui, reason) {
  ui.banner = `No match${reason && reason !== 'no-match' ? ` (${reason})` : ''}`;
}

// ── DOM / canvas plumbing ─────────────────────────────────────────────────────

function buildDom(container, ui, ctx) {
  const canvas = container.querySelector('#board') || makeCanvas(container);
  ui.canvas = canvas;
  ui.cx = canvas.getContext('2d');

  // Forward clicks into the host-authoritative loop (here: local-only apply).
  canvas.addEventListener('click', (e) => {
    if (ctx && typeof ctx.handleLocalInput === 'function') {
      ctx.handleLocalInput(e);
    }
  });

  // HUD wiring (score / moves / status / new-game) — built by index.html if present.
  const scoreEl = container.querySelector('#score');
  const movesEl = container.querySelector('#moves');
  const statusEl = container.querySelector('#status');
  const newBtn = container.querySelector('#new-game');
  ui.hud = { score: scoreEl, moves: movesEl, status: statusEl, new: newBtn };
  if (newBtn && ctx) {
    newBtn.addEventListener('click', () => {
      if (typeof ctx.handleLocalInput === 'function') {
        ctx.handleLocalInput({ type: 'new-game' });
      }
    });
  }

  // Re-layout on resize so the board stays square + mobile-friendly. Tracked so
  // GAME.destroy() can remove it on iframe teardown.
  if (typeof window !== 'undefined' && typeof window.addEventListener === 'function') {
    ui.onResize = () => {
      if (ctx && ctx.GAME) ctx.GAME.render(ctx.GAME._getState(), ctx);
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

/** Size the canvas to a square that fits the viewport, with `cell` px per gem. */

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

function layout(ui, state) {
  if (!ui.canvas) return;
  const w = state.width || DEFAULTS.width;
  const h = state.height || DEFAULTS.height;
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
  // Cell size is the largest integer that fits BOTH axes inside `target`.
  ui.cell = Math.max(1, Math.floor(target / Math.max(w, h)));
  ui.canvas.width = ui.cell * w;
  ui.canvas.height = ui.cell * h;
  if (ui.canvas.style) {
    ui.canvas.style.width = ui.canvas.width + 'px';
    ui.canvas.style.height = ui.canvas.height + 'px';
  }
}

/** Map a canvas click to a board cell { x, y } (y=0 is the TOP row, matching the
 *  engine's row-major board with y increasing downward). */
function canvasToCell(ui, evt, state) {
  if (!ui.canvas || !ui.cell) return null;
  const w = state.width || DEFAULTS.width;
  const h = state.height || DEFAULTS.height;
  const rect =
    typeof ui.canvas.getBoundingClientRect === 'function'
      ? ui.canvas.getBoundingClientRect()
      : { left: 0, top: 0, width: ui.cell * w, height: ui.cell * h };
  let x = evt.offsetX;
  let y = evt.offsetY;
  if (x === undefined || y === undefined) {
    x = (evt.clientX || 0) - rect.left;
    y = (evt.clientY || 0) - rect.top;
  }
  const scaleX = (ui.cell * w) / (rect.width || ui.cell * w);
  const scaleY = (ui.cell * h) / (rect.height || ui.cell * h);
  const col = Math.floor((x * scaleX) / ui.cell);
  const row = Math.floor((y * scaleY) / ui.cell);
  if (col < 0 || col >= w || row < 0 || row >= h) return null;
  return { x: col, y: row };
}

// ── drawing ───────────────────────────────────────────────────────────────────

function drawBoard(ui, state) {
  const cx = ui.cx;
  if (!cx) return;
  const cell = ui.cell;
  const board = state.board;
  const w = state.width;
  const h = state.height;

  for (let y = 0; y < h; y++) {
    const row = board[y] || [];
    for (let x = 0; x < w; x++) {
      const px = x * cell;
      const py = y * cell;
      // Checkerboard backing so the grid is legible.
      cx.fillStyle = (x + y) % 2 === 0 ? COLORS.cellA : COLORS.cellB;
      cx.fillRect(px, py, cell, cell);
      // Grid line.
      cx.strokeStyle = COLORS.grid;
      cx.lineWidth = Math.max(1, cell * 0.02);
      cx.strokeRect(px, py, cell, cell);

      // The gem — a rounded filled circle (no image assets).
      const v = row[x];
      if (typeof v === 'number') {
        cx.beginPath();
        cx.arc(px + cell / 2, py + cell / 2, cell * 0.36, 0, Math.PI * 2);
        cx.fillStyle = gemColor(v);
        cx.fill();
        cx.lineWidth = Math.max(1, cell * 0.03);
        cx.strokeStyle = COLORS.gemStroke;
        cx.stroke();
      }
    }
  }

  // Selected-gem highlight ring (two-click swap).
  if (ui.selected) {
    const { x, y } = ui.selected;
    cx.beginPath();
    cx.arc(x * cell + cell / 2, y * cell + cell / 2, cell * 0.44, 0, Math.PI * 2);
    cx.lineWidth = Math.max(2, cell * 0.06);
    cx.strokeStyle = COLORS.selected;
    cx.stroke();
  }
}

function drawHud(ui, state) {
  if (!ui.hud) return;
  const over = isTerminal(state.status);
  if (ui.hud.score) {
    ui.hud.score.textContent = `Score: ${state.score}`;
  }
  if (ui.hud.moves) {
    ui.hud.moves.textContent = `Moves: ${ui.moves || 0}`;
  }
  if (ui.hud.status) {
    ui.hud.status.textContent = ui.banner
      ? ui.banner
      : over
        ? 'No moves left'
        : `Swap two adjacent gems (${state.legalSwaps} possible)`;
    ui.hud.status.classList && ui.hud.status.classList.toggle('over', over);
  }
}

export default createMatchThreeGame;
