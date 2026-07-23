// games/minesweeper/minesweeper.js
//
// The Minesweeper GAME implementation for the shared web-native game shell
// (games/_shared/{wasm-host,sse-host-client,game-frame}.js). It is a single
// `GAME` object that mirrors the proven chess / reversi foundation:
//
//   • init(host, container, ctx)  — seeds the engine (mine_new with a seeded
//                                    config read from the URL params or the
//                                    classic beginner default), builds the
//                                    canvas + HUD, returns the initial state.
//   • render(stateJson, ctx)      — paints the W×H grid from the engine's OWN
//                                    minesweeper.snapshot board string (`#`
//                                    hidden, `F` flag, `.` revealed-0, `1..8`
//                                    counts, `*` mine-on-loss), plus the mine/flag
//                                    counter + the elapsed timer in the HUD.
//                                    Canvas + Unicode only — NO image assets.
//   • onLocalInput(evt, ctx)      — maps a canvas click → reveal that cell, a
//                                    right-click (contextmenu) → toggle a flag,
//                                    applies it AUTHORITATIVELY through the wasm
//                                    engine, and returns ONLY { state }.
//
// SINGLE-PLAYER PUZZLE, LOCAL ONLY: there is NO SSE relay and NO SessionClient
// send. Minesweeper is seed-based — the same (width,height,mines,seed) always
// yields the same board (the engine's seeded PRNG), and mines are placed
// first-click-safe on the first reveal. The game-frame's session_id-absent path
// drives it purely locally against the wasm engine; onLocalInput returns no
// input/world payload so the frame relays nothing. render() never mutates the
// engine; every state transition flows through mine_reveal / mine_flag so the
// board can never drift from the rules engine. The server runs none of this.
//
// The engine is the verified hugmesh_minesweeper_wasm.wasm; we drive it
// through the shared WasmHost with the EXACT call pattern proven in
// games/_shared/test/minesweeper-wasm.test.mjs:
//   host.callStatus('mine_new', '{"width":..,"height":..,"mines":..,"seed":..}') -> 1
//   host.callPacked('mine_state')              -> { type:'minesweeper.snapshot', board, status, ... }
//   host.callPacked('mine_status')             -> { status }
//   host.call('mine_reveal', '3,4')            -> { type:'minesweeper.snapshot', ... } | { type:'minesweeper.error', ... }
//   host.call('mine_flag',   '3,4')            -> { type:'minesweeper.snapshot', ... } | { type:'minesweeper.error', ... }
//
// Vanilla ES module. No build step, no dependencies.

// Difficulty presets. The DEFAULT is the classic beginner board. The seed makes
// each new board deterministic for a given preset (re-seeding "New game"
// advances the seed so you get a fresh layout while staying reproducible).
export const PRESETS = {
  beginner: { width: 9, height: 9, mines: 10 },
  intermediate: { width: 16, height: 16, mines: 40 },
  expert: { width: 30, height: 16, mines: 99 },
};
const DEFAULT_PRESET = 'beginner';

// Palette — backgrounds/borders keep the brand purple (WCAG 1.4.11 non-text at
// 3:1); the per-count digit colours follow the classic Minesweeper convention,
// brightened to read on the dark board.
const COLORS = {
  boardBg: '#14121c',
  grid: '#3a3450',
  hidden: '#2a2440', // un-revealed cell face
  hiddenEdge: '#4b4470',
  revealed: '#1b1828', // opened cell face
  flag: '#ff5c5c',
  mine: '#f2eefb',
  mineBoom: '#ff5c5c', // the detonated mine's cell wash on a loss
  winWash: 'rgba(95, 224, 138, 0.10)',
  loseWash: 'rgba(255, 92, 92, 0.12)',
  // adjacency 1..8 digit colours
  counts: [
    '', // 0 unused (blank)
    '#5ab0ff', // 1 blue
    '#5fe08a', // 2 green
    '#ff7676', // 3 red
    '#b388ff', // 4 purple
    '#f2c53d', // 5 amber
    '#5fe0d6', // 6 teal
    '#f2eefb', // 7 near-white
    '#a8acc4', // 8 grey
  ],
};

const EMPTY_CELL = '#';

/**
 * Parse the engine's `board` string (W chars per row, rows joined by '/') into a
 * grid indexed [y][x]. Each cell is one of the engine's render chars:
 *   '#' hidden | 'F' flagged | '.' revealed-0 | '1'..'8' count | '*' mine (loss).
 */
export function parseBoard(boardStr, width, height) {
  const rows = (boardStr || '').split('/');
  const grid = [];
  for (let y = 0; y < height; y++) {
    const line = rows[y] || EMPTY_CELL.repeat(width);
    const cells = [];
    for (let x = 0; x < width; x++) {
      cells.push(line[x] || EMPTY_CELL);
    }
    grid.push(cells);
  }
  return grid;
}

/** True if the game has ended (won or lost) — no further actions are legal. */
export function isGameOver(status) {
  return status === 'won' || status === 'lost';
}

/** Human-readable HUD status line from an engine status string. */
export function statusLine(status) {
  switch (status) {
    case 'won':
      return 'You win! Board cleared.';
    case 'lost':
      return 'Boom! You hit a mine.';
    case 'playing':
    default:
      return 'Find all the mines';
  }
}

/**
 * A 3-digit zero-padded counter (the classic Minesweeper 7-seg displays), with a
 * negative branch for the over-flagged mine counter. Clamped to [-99, 999].
 */
export function formatCounter(n) {
  const v = Math.max(-99, Math.min(999, Math.trunc(n || 0)));
  if (v < 0) return '-' + String(-v).padStart(2, '0');
  return String(v).padStart(3, '0');
}

/** Read the per-puzzle config (preset / explicit dims + seed) from URL params. */
export function readConfig(params) {
  const presetName =
    params && typeof params.preset === 'string' && PRESETS[params.preset]
      ? params.preset
      : DEFAULT_PRESET;
  const base = PRESETS[presetName];
  const cfg = { width: base.width, height: base.height, mines: base.mines, seed: 0 };
  if (params && typeof params === 'object') {
    if (Number.isFinite(params.width)) cfg.width = params.width;
    if (Number.isFinite(params.height)) cfg.height = params.height;
    if (Number.isFinite(params.mines)) cfg.mines = params.mines;
    if (Number.isFinite(params.seed)) cfg.seed = params.seed >>> 0;
  }
  return cfg;
}

// A clock indirection so the smoke test can drive elapsed time deterministically.
let CLOCK = () =>
  typeof Date !== 'undefined' && typeof Date.now === 'function' ? Date.now() : 0;
/** Override the clock (tests only). Pass a function returning ms. */
export function _setClock(fn) {
  CLOCK = typeof fn === 'function' ? fn : CLOCK;
}

// ── the GAME object ──────────────────────────────────────────────────────────

export function createMinesweeperGame(options = {}) {
  // Per-instance UI + engine-mirror state.
  const ui = {
    canvas: null,
    cx: null,
    cell: 0, // one cell in pixels (square)
    pad: 0, // outer board padding in pixels
    hud: null, // { mines, timer, status, reset } elements
    onResize: null,
    onContext: null,
    timerId: null,
    startedAt: null, // wall-clock ms at the first reveal
    frozenElapsed: 0, // seconds frozen at game over
  };

  // The starting config: options override, else the beginner default.
  let config = readConfig(options);
  let seed = config.seed >>> 0;

  // Latest authoritative snapshot we rendered.
  let state = {
    type: 'minesweeper.snapshot',
    width: config.width,
    height: config.height,
    mines: config.mines,
    seed,
    started: false,
    status: 'playing',
    flags: 0,
    safeRevealed: 0,
    safeCells: config.width * config.height - config.mines,
    board: '',
  };

  /** Build the seeded-config JSON the shim's mine_new parses. */
  function configJson(nextSeed) {
    return JSON.stringify({
      width: config.width,
      height: config.height,
      mines: config.mines,
      seed: nextSeed,
    });
  }

  /** Pull the current snapshot out of the engine into our `state`. */
  function readEngine(host) {
    const s = host.callPacked('mine_state') || {};
    return adopt(s);
  }

  /** Adopt an engine minesweeper.snapshot payload as the new authoritative state. */
  function adopt(s) {
    if (!s || typeof s !== 'object') return state;
    state = {
      type: 'minesweeper.snapshot',
      width: s.width || state.width,
      height: s.height || state.height,
      mines: typeof s.mines === 'number' ? s.mines : state.mines,
      seed: typeof s.seed === 'number' ? s.seed : state.seed,
      started: !!s.started,
      status: s.status || 'playing',
      flags: typeof s.flags === 'number' ? s.flags : 0,
      safeRevealed: typeof s.safeRevealed === 'number' ? s.safeRevealed : 0,
      safeCells: typeof s.safeCells === 'number' ? s.safeCells : state.safeCells,
      board: typeof s.board === 'string' ? s.board : state.board,
    };
    return state;
  }

  /** Re-seed the engine to a fresh board (advancing the deterministic seed). */
  function seedNew(host, nextSeed) {
    seed = (nextSeed >>> 0) || seed;
    host.callStatus('mine_new', configJson(seed));
    const s = readEngine(host);
    ui.startedAt = null;
    ui.frozenElapsed = 0;
    return s;
  }

  /** Elapsed seconds: 0 until the first reveal, live while playing, frozen at
   *  game over. */
  function elapsedSeconds() {
    if (isGameOver(state.status)) return ui.frozenElapsed;
    if (ui.startedAt === null) return 0;
    return Math.floor((CLOCK() - ui.startedAt) / 1000);
  }

  const GAME = {
    slug: 'minesweeper',

    async init(host, container, ctx) {
      // Honour URL params (seed-based puzzle) over the constructor options.
      const fromParams = readConfig(ctx && ctx.params);
      // Only adopt param dims/seed when they were actually present; readConfig
      // already merged the beginner default, so just take its result.
      config = fromParams;
      seed = config.seed >>> 0;
      host.callStatus('mine_new', configJson(seed));
      buildDom(container, ui, ctx);
      state = readEngine(host);
      ui.startedAt = null;
      ui.frozenElapsed = 0;
      layoutIfNeeded(ui, state);
      startTimer(ui, GAME, ctx);
      return state;
    },

    render(stateJson, ctx) {
      if (stateJson && typeof stateJson === 'object' && stateJson.type === 'minesweeper.snapshot') {
        adopt(stateJson);
      }
      // Freeze the timer the moment the game ends.
      if (isGameOver(state.status) && ui.timerId !== null) {
        ui.frozenElapsed = elapsedSeconds();
        stopTimer(ui);
      }
      layoutIfNeeded(ui, state);
      drawBoard(ui, state);
      drawHud(ui, state, elapsedSeconds());
    },

    onLocalInput(evt, ctx) {
      const host = ctx.host;
      if (!evt) return null;

      // Reset / new game — advance the seed for a fresh deterministic layout.
      if (evt.type === 'reset') {
        state = seedNew(host, (seed + 0x9e37_79b1) >>> 0);
        ui.cell = 0; // re-layout (dims may differ if a preset changed)
        layoutIfNeeded(ui, state);
        stopTimer(ui);
        startTimer(ui, GAME, ctx);
        return { state };
      }

      if (isGameOver(state.status)) return null;

      const cell = cellFromEvent(ui, evt, state);
      if (!cell) return null;
      const coord = `${cell.x},${cell.y}`;

      // Right-click (contextmenu) or an explicit 'flag' event → toggle a flag.
      // Anything else (a left click) → reveal.
      const wantFlag = evt.type === 'contextmenu' || evt.type === 'flag' || evt.button === 2;
      const fn = wantFlag ? 'mine_flag' : 'mine_reveal';

      const wasStarted = state.started;
      // Apply authoritatively through the engine. The engine returns its OWN
      // full-board minesweeper.snapshot payload (the post-move world snapshot).
      const outcome = host.call(fn, coord);
      if (!outcome || outcome.type === 'minesweeper.error') {
        // Out-of-bounds / game-over / bad-coord / no-op — surface, no change.
        return { state };
      }
      adopt(outcome);
      // The timer starts on the first action that actually opens the board.
      if (!wasStarted && state.started && ui.startedAt === null) {
        ui.startedAt = CLOCK();
      }
      // LOCAL ONLY — return ONLY the state. No input / world relay.
      return { state };
    },

    // No onRemoteEvent: single-player puzzle, nothing is relayed. The frame's
    // remote wiring simply never fires (no session_id).

    destroy() {
      stopTimer(ui);
      if (typeof window !== 'undefined' && typeof window.removeEventListener === 'function') {
        if (ui.onResize) window.removeEventListener('resize', ui.onResize);
      }
      if (ui.canvas && ui.onContext && typeof ui.canvas.removeEventListener === 'function') {
        ui.canvas.removeEventListener('contextmenu', ui.onContext);
      }
      ui.onResize = null;
      ui.onContext = null;
    },

    // Exposed for the HUD reset button + tests.
    _reset(host) {
      state = seedNew(host, (seed + 0x9e37_79b1) >>> 0);
      return state;
    },
    _ui: ui,
    _getState: () => state,
    _getConfig: () => config,
    _elapsed: () => elapsedSeconds(),
  };

  return GAME;
}

// ── timer (a 1 Hz HUD repaint; the board is untouched between actions) ────────

function startTimer(ui, GAME, ctx) {
  stopTimer(ui);
  if (typeof setInterval !== 'function') return;
  ui.timerId = setInterval(() => {
    drawHud(ui, GAME._getState(), GAME._elapsed());
  }, 1000);
  // Don't keep the Node event loop alive in tests (harmless in the browser).
  if (ui.timerId && typeof ui.timerId.unref === 'function') ui.timerId.unref();
}

function stopTimer(ui) {
  if (ui.timerId !== null && typeof clearInterval === 'function') {
    clearInterval(ui.timerId);
  }
  ui.timerId = null;
}

// ── DOM / canvas plumbing ─────────────────────────────────────────────────────

function buildDom(container, ui, ctx) {
  const canvas = container.querySelector('#board') || makeCanvas(container);
  ui.canvas = canvas;
  ui.cx = canvas.getContext('2d');

  // Left-click → reveal.
  canvas.addEventListener('click', (e) => {
    if (ctx && typeof ctx.handleLocalInput === 'function') {
      ctx.handleLocalInput({ type: 'click', offsetX: e.offsetX, offsetY: e.offsetY });
    }
  });

  // Right-click → flag (suppress the browser context menu).
  ui.onContext = (e) => {
    if (typeof e.preventDefault === 'function') e.preventDefault();
    if (ctx && typeof ctx.handleLocalInput === 'function') {
      ctx.handleLocalInput({ type: 'contextmenu', offsetX: e.offsetX, offsetY: e.offsetY });
    }
    return false;
  };
  canvas.addEventListener('contextmenu', ui.onContext);

  // HUD wiring (mine counter / timer / status / reset) — built by index.html.
  const minesEl = container.querySelector('#mines');
  const timerEl = container.querySelector('#timer');
  const statusEl = container.querySelector('#status');
  const resetBtn = container.querySelector('#reset');
  ui.hud = { mines: minesEl, timer: timerEl, status: statusEl, reset: resetBtn };
  if (resetBtn && ctx) {
    resetBtn.addEventListener('click', () => {
      if (typeof ctx.handleLocalInput === 'function') {
        ctx.handleLocalInput({ type: 'reset' });
      } else {
        const next = ctx.GAME._reset(ctx.host);
        ctx.GAME.render(next, ctx);
      }
    });
  }

  // Re-layout on resize so the board stays sized to the viewport.
  if (typeof window !== 'undefined' && typeof window.addEventListener === 'function') {
    ui.onResize = () => {
      ui.cell = 0; // force a re-layout next render
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

/** Size the canvas to fit the W×H grid in the viewport (square cells). */
function layoutIfNeeded(ui, state) {
  if (!ui.canvas) return;
  if (ui.cell > 0) return; // already laid out for this size
  const W = state.width;
  const H = state.height;
  let cell = 32;
  if (typeof window !== 'undefined' && window.innerWidth) {
    const w = window.innerWidth;
    const h = window.innerHeight || window.innerWidth;
    const byWidth = (w - 24) / W;
    const byHeight = (h - 96) / H;
    cell = Math.max(16, Math.min(byWidth, byHeight, 44));
  }
  ui.cell = Math.floor(cell);
  ui.pad = Math.max(4, Math.floor(ui.cell * 0.18));
  const wpx = ui.cell * W + ui.pad * 2;
  const hpx = ui.cell * H + ui.pad * 2;
  ui.canvas.width = wpx;
  ui.canvas.height = hpx;
  if (ui.canvas.style) {
    ui.canvas.style.width = wpx + 'px';
    ui.canvas.style.height = hpx + 'px';
  }
}

/** Which cell (x,y) an event falls into, or null if outside the grid. */
function cellFromEvent(ui, evt, state) {
  if (!ui.canvas || ui.cell <= 0) return null;
  const rect =
    typeof ui.canvas.getBoundingClientRect === 'function'
      ? ui.canvas.getBoundingClientRect()
      : { left: 0, top: 0, width: ui.canvas.width, height: ui.canvas.height };
  let ox = evt.offsetX;
  let oy = evt.offsetY;
  if (ox === undefined) ox = (evt.clientX || 0) - rect.left;
  if (oy === undefined) oy = (evt.clientY || 0) - rect.top;
  const scaleX = ui.canvas.width / (rect.width || ui.canvas.width);
  const scaleY = ui.canvas.height / (rect.height || ui.canvas.height);
  const px = ox * scaleX - ui.pad;
  const py = oy * scaleY - ui.pad;
  if (px < 0 || py < 0) return null;
  const x = Math.floor(px / ui.cell);
  const y = Math.floor(py / ui.cell);
  if (x < 0 || x >= state.width || y < 0 || y >= state.height) return null;
  return { x, y };
}

// ── drawing ───────────────────────────────────────────────────────────────────

function drawBoard(ui, state) {
  const cx = ui.cx;
  if (!cx) return;
  const cell = ui.cell;
  const pad = ui.pad;
  const W = state.width;
  const H = state.height;
  const grid = parseBoard(state.board, W, H);
  const wpx = cell * W + pad * 2;
  const hpx = cell * H + pad * 2;

  // Board background.
  cx.fillStyle = COLORS.boardBg;
  cx.fillRect(0, 0, wpx, hpx);

  const over = isGameOver(state.status);
  cx.textAlign = 'center';
  cx.textBaseline = 'middle';
  cx.font = `bold ${Math.floor(cell * 0.6)}px system-ui, sans-serif`;

  for (let y = 0; y < H; y++) {
    for (let x = 0; x < W; x++) {
      const ch = grid[y][x];
      const cellX = pad + x * cell;
      const cellY = pad + y * cell;
      const midX = cellX + cell / 2;
      const midY = cellY + cell / 2;

      // Cell face: hidden/flagged faces are raised; revealed cells are sunken.
      const revealed = ch === '.' || (ch >= '1' && ch <= '8') || ch === '*';
      cx.fillStyle = ch === '*' ? COLORS.mineBoom : revealed ? COLORS.revealed : COLORS.hidden;
      cx.fillRect(cellX + 1, cellY + 1, cell - 2, cell - 2);

      // Cell grid border.
      cx.lineWidth = 1;
      cx.strokeStyle = revealed ? COLORS.grid : COLORS.hiddenEdge;
      cx.strokeRect(cellX + 0.5, cellY + 0.5, cell - 1, cell - 1);

      // Cell glyph (canvas/Unicode only — no image assets).
      if (ch >= '1' && ch <= '8') {
        cx.fillStyle = COLORS.counts[ch.charCodeAt(0) - 48] || COLORS.mine;
        cx.fillText(ch, midX, midY + 1);
      } else if (ch === 'F') {
        cx.fillStyle = COLORS.flag;
        cx.fillText('⚑', midX, midY + 1); // ⚑ flag
      } else if (ch === '*') {
        cx.fillStyle = COLORS.mine;
        cx.fillText('✸', midX, midY + 1); // ✸ mine burst
      }
      // '.' (revealed 0) and '#' (hidden) draw no glyph.
    }
  }

  // A faint full-board wash on win / loss.
  if (over) {
    cx.fillStyle = state.status === 'won' ? COLORS.winWash : COLORS.loseWash;
    cx.fillRect(pad, pad, cell * W, cell * H);
  }
}

function drawHud(ui, state, elapsed) {
  if (!ui.hud) return;
  const over = isGameOver(state.status);
  // Mine counter = total mines minus flags planted (the classic display, which
  // can go negative if you over-flag).
  if (ui.hud.mines) {
    ui.hud.mines.textContent = formatCounter((state.mines || 0) - (state.flags || 0));
  }
  if (ui.hud.timer) {
    ui.hud.timer.textContent = formatCounter(elapsed);
  }
  if (ui.hud.status) {
    ui.hud.status.textContent = statusLine(state.status);
    if (ui.hud.status.classList) {
      ui.hud.status.classList.toggle('over', over);
      ui.hud.status.classList.toggle('won', state.status === 'won');
      ui.hud.status.classList.toggle('lost', state.status === 'lost');
    }
  }
  if (ui.hud.reset) {
    // The reset button face reacts to the game state (classic smiley).
    ui.hud.reset.textContent = over ? (state.status === 'won' ? '😎 New game' : '💀 New game') : '🙂 New game';
  }
}

export default createMinesweeperGame;
