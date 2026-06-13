// games/tetris/tetris.js
//
// The Tetris GAME implementation for the shared web-native game shell
// (games/_shared/{wasm-host,sse-host-client,game-frame}.js). It is a single
// `GAME` object that:
//
//   • init(host, container, ctx)  — seeds the engine (tetris_new(seed)), builds
//                                    the canvas + HUD + next/hold panels, starts
//                                    the gravity tick loop, returns initial state.
//   • render(stateJson, ctx)      — paints the 10x20 well (locked stack + the
//                                    falling active piece), the next + held piece
//                                    previews, and the score / lines / level HUD,
//                                    plus the game-over banner.
//   • onLocalInput(evt, ctx)      — maps ONE keyboard action (left / right /
//                                    rotate / soft / hard drop / hold / a gravity
//                                    tick / restart) into the matching wasm op,
//                                    applies it AUTHORITATIVELY, and returns the
//                                    new state to render.
//
// SINGLE-PLAYER PUZZLE — LOCAL ONLY. There is NO SSE relay, NO SessionClient
// sends, NO opponent, NO server. onLocalInput returns ONLY `{ state }` (never
// `input`/`world`/`control`), so the shared frame's relay branches are no-ops.
// Determinism comes entirely from the seed (URL `?seed=` param, else a default):
// the engine takes `tetris_new(seed)` and the same seed + same inputs reproduce
// the identical game.
//
// HOST-AUTHORITATIVE in the trivial single-player sense: render() never mutates
// the engine; every state transition flows through a wasm op (tetris_left,
// tetris_hard_drop, tetris_tick, …) so the well can never drift from the rules
// engine. Blocks are canvas-drawn squares (no image assets). The engine is the
// verified snapdragon_tetris_wasm.wasm; we drive it through the shared WasmHost
// with the EXACT call pattern proven in games/_shared/test/tetris-wasm.test.mjs:
//   host.callStatus('tetris_new', null, [seedBigInt]) -> 1
//   host.callPacked('tetris_snapshot')  -> { type:'tetris.snapshot', width, height, board, active, held, canHold, next, score, lines, level, status }
//   host.callPacked('tetris_left' | 'tetris_right' | 'tetris_rotate_cw' | 'tetris_rotate_ccw' | 'tetris_soft_drop' | 'tetris_hard_drop' | 'tetris_hold' | 'tetris_tick') -> snapshot
//   host.callPacked('tetris_status')    -> { status }
//
// Vanilla ES module. No build step, no dependencies.

export const WIDTH = 10;
export const HEIGHT = 20;

// Per-piece block colours (canvas-drawn; no image assets). Keyed by the engine's
// lower-case wire glyph AND the upper-case board glyph (the engine packs the
// locked board with upper-case piece glyphs, the active piece with lower-case
// wire tokens). All clear the WCAG non-text 3:1 contrast bar against the well.
const PIECE_COLORS = {
  i: '#3fd0e0',
  o: '#ffd23f',
  t: '#b388ff',
  s: '#5fd07a',
  z: '#ff6b6b',
  j: '#5b8def',
  l: '#ff9f43',
};

const COLORS = {
  well: '#14121c',
  wellAlt: '#1a1726', // faint checker so the empty grid reads
  grid: '#2a2440',
  ghost: 'rgba(179, 136, 255, 0.18)', // landing-shadow hint of the active piece
  blockStroke: 'rgba(0, 0, 0, 0.45)',
  panel: '#1f1b2e',
};

/** Colour for a board glyph ('I'..'L' locked) or wire token ('i'..'l' active). */
function colorFor(glyph) {
  if (!glyph) return null;
  const c = PIECE_COLORS[glyph.toLowerCase()];
  return c || '#9aa0bf';
}

/** True when the engine status string is terminal ("gameover:score:lines"). */
export function isGameOver(status) {
  return typeof status === 'string' && status.startsWith('gameover');
}

/** Parse the seed from a URL `?seed=` param (decimal). Defaults to 1 when absent
 *  or unparseable, so the game always boots deterministically. */
export function parseSeed(search) {
  const s = search !== undefined
    ? search
    : (typeof window !== 'undefined' ? window.location.search : '');
  const v = new URLSearchParams(s).get('seed');
  if (v === null || v === '') return 1;
  // Decimal seed; the engine accepts any u64. Strip non-digits defensively.
  const n = v.replace(/[^0-9]/g, '');
  return n.length ? n : 1;
}

/**
 * Compute the active piece's absolute (col,row) cells from a snapshot's active
 * descriptor. Mirrors snapdragon_tetris::Piece::cells — the SAME geometry the
 * engine locks, so the on-screen falling piece matches the engine exactly.
 * row grows downward (row 0 top), col grows rightward.
 */
export function activeCells(active) {
  if (!active || typeof active.piece !== 'string') return [];
  const r = ((active.rot | 0) & 3);
  const p = active.piece.toLowerCase();
  let offs;
  switch (p) {
    case 'i':
      offs = r % 2 === 0 ? [[0, 1], [1, 1], [2, 1], [3, 1]] : [[2, 0], [2, 1], [2, 2], [2, 3]];
      break;
    case 'o':
      offs = [[1, 0], [2, 0], [1, 1], [2, 1]];
      break;
    case 't':
      offs = r === 0 ? [[1, 0], [0, 1], [1, 1], [2, 1]]
        : r === 1 ? [[1, 0], [1, 1], [2, 1], [1, 2]]
          : r === 2 ? [[0, 1], [1, 1], [2, 1], [1, 2]]
            : [[1, 0], [0, 1], [1, 1], [1, 2]];
      break;
    case 's':
      offs = r % 2 === 0 ? [[1, 0], [2, 0], [0, 1], [1, 1]] : [[1, 0], [1, 1], [2, 1], [2, 2]];
      break;
    case 'z':
      offs = r % 2 === 0 ? [[0, 0], [1, 0], [1, 1], [2, 1]] : [[2, 0], [1, 1], [2, 1], [1, 2]];
      break;
    case 'j':
      offs = r === 0 ? [[0, 0], [0, 1], [1, 1], [2, 1]]
        : r === 1 ? [[1, 0], [2, 0], [1, 1], [1, 2]]
          : r === 2 ? [[0, 1], [1, 1], [2, 1], [2, 2]]
            : [[1, 0], [1, 1], [0, 2], [1, 2]];
      break;
    case 'l':
      offs = r === 0 ? [[2, 0], [0, 1], [1, 1], [2, 1]]
        : r === 1 ? [[1, 0], [1, 1], [1, 2], [2, 2]]
          : r === 2 ? [[0, 1], [1, 1], [2, 1], [0, 2]]
            : [[0, 0], [1, 0], [1, 1], [1, 2]];
      break;
    default:
      return [];
  }
  return offs.map(([cx, cy]) => [active.x + cx, active.y + cy]);
}

/**
 * The four relative cells of a piece in its spawn (rot 0) orientation, used to
 * draw the next/hold preview boxes. Returns cells in a 4-wide box.
 */
function previewCells(glyph) {
  return activeCells({ piece: glyph, rot: 0, x: 0, y: 0 });
}

// ── the GAME object ──────────────────────────────────────────────────────────

export function createTetrisGame() {
  // Per-instance UI state.
  const ui = {
    canvas: null,
    cx: null,
    cell: 0,
    next: null, // <canvas> preview
    nextCx: null,
    hold: null, // <canvas> preview
    holdCx: null,
    hud: null, // { score, lines, level, status, restart }
    raf: null, // gravity loop handle
    lastTick: 0, // ms timestamp of the last gravity step
    seed: 1,
  };

  // Latest authoritative state we rendered.
  let state = emptyState();

  function emptyState() {
    return {
      width: WIDTH,
      height: HEIGHT,
      board: '.'.repeat(WIDTH * HEIGHT),
      active: { piece: 't', rot: 0, x: 3, y: 0 },
      held: null,
      canHold: true,
      next: 's',
      score: 0,
      lines: 0,
      level: 0,
      status: 'playing',
    };
  }

  /** Coerce a wasm snapshot (or null) into our `state` shape. */
  function adopt(snap) {
    if (!snap || typeof snap !== 'object') return state;
    state = {
      width: snap.width || WIDTH,
      height: snap.height || HEIGHT,
      board: typeof snap.board === 'string' ? snap.board : state.board,
      active: snap.active || state.active,
      held: snap.held !== undefined ? snap.held : state.held,
      canHold: snap.canHold !== undefined ? snap.canHold : state.canHold,
      next: snap.next !== undefined ? snap.next : state.next,
      score: typeof snap.score === 'number' ? snap.score : state.score,
      lines: typeof snap.lines === 'number' ? snap.lines : state.lines,
      level: typeof snap.level === 'number' ? snap.level : state.level,
      status: snap.status || state.status,
    };
    return state;
  }

  // Action → wasm export. Every op returns the authoritative snapshot.
  const OP = {
    left: 'tetris_left',
    right: 'tetris_right',
    'rotate-cw': 'tetris_rotate_cw',
    'rotate-ccw': 'tetris_rotate_ccw',
    soft: 'tetris_soft_drop',
    hard: 'tetris_hard_drop',
    hold: 'tetris_hold',
    tick: 'tetris_tick',
  };

  const GAME = {
    slug: 'tetris',

    async init(host, container, ctx) {
      // Seed from the URL `?seed=` param (else default). A test can pre-seed via
      // ctx.params.search; the browser falls through to window.location.search.
      ui.seed = parseSeed(ctx && ctx.params ? ctx.params.search : undefined);
      // Seed the engine deterministically. The seed is a u64 leading arg (NOT a
      // ptr/len buffer) — pass it through the WasmHost's leadingArgs as a BigInt.
      host.callStatus('tetris_new', null, [BigInt(ui.seed)]);
      buildDom(container, ui, ctx);
      adopt(host.callPacked('tetris_snapshot'));
      startGravity(host, ui, ctx);
      return state;
    },

    render(stateJson, ctx) {
      adopt(stateJson);
      drawWell(ui, state);
      drawPreview(ui.nextCx, ui.next, state.next);
      drawPreview(ui.holdCx, ui.hold, state.held);
      drawHud(ui, state);
    },

    onLocalInput(evt, ctx) {
      const host = ctx.host;
      if (!evt) return null;

      // Restart with the same seed (deterministic replay) — local only.
      if (evt.type === 'restart') {
        host.callStatus('tetris_new', null, [BigInt(ui.seed)]);
        adopt(host.callPacked('tetris_snapshot'));
        ui.lastTick = now();
        return { state };
      }

      if (evt.type !== 'action') return null;
      const op = OP[evt.action];
      if (!op) return null;

      // A gravity tick is allowed even at game-over (it's a no-op the engine
      // freezes); player actions after game-over are simply ignored here so the
      // well stops responding to input the moment the stack tops out.
      if (isGameOver(state.status) && evt.action !== 'tick') {
        return null;
      }

      adopt(host.callPacked(op));
      // A player action that isn't a tick resets the gravity clock so the piece
      // doesn't immediately drop on top of an intentional move (standard feel).
      if (evt.action !== 'tick') ui.lastTick = now();
      // SINGLE-PLAYER: return ONLY the state to render. No input/world/control —
      // the shared frame's relay branches stay no-ops.
      return { state };
    },

    // No remote events in a single-player puzzle — present for interface
    // completeness; always ignores.
    onRemoteEvent() {
      return null;
    },

    // Release the gravity loop + game-owned listeners on iframe teardown.
    destroy() {
      stopGravity(ui);
      if (ui.onKey && typeof window !== 'undefined' &&
          typeof window.removeEventListener === 'function') {
        window.removeEventListener('keydown', ui.onKey);
        ui.onKey = null;
      }
      if (ui.onResize && typeof window !== 'undefined' &&
          typeof window.removeEventListener === 'function') {
        window.removeEventListener('resize', ui.onResize);
        ui.onResize = null;
      }
    },

    // Exposed for the tick loop + tests.
    _ui: ui,
    _getState: () => state,
    _activeCells: () => activeCells(state.active),
  };

  return GAME;
}

// ── gravity tick loop (requestAnimationFrame, speeds with level) ──────────────

/** ms timestamp helper (monotonic-ish; falls back when performance is absent). */
function now() {
  if (typeof performance !== 'undefined' && typeof performance.now === 'function') {
    return performance.now();
  }
  return Date.now();
}

/**
 * Gravity period in milliseconds for the current level. Mirrors the engine's
 * gravity_period() curve (ticks shorten as level rises) translated to real time:
 * level 0 ≈ 800 ms/cell, tightening ~70 ms per level, floored at 80 ms so even a
 * level-9+ game stays playable in the browser.
 */
export function gravityMs(level) {
  const l = Math.max(0, level | 0);
  return Math.max(80, 800 - l * 70);
}

function startGravity(host, ui, ctx) {
  if (typeof requestAnimationFrame !== 'function') return; // headless / tests
  ui.lastTick = now();
  const step = (t) => {
    ui.raf = requestAnimationFrame(step);
    if (isGameOver(ctx.GAME._getState().status)) return; // freeze on game-over
    const period = gravityMs(ctx.GAME._getState().level);
    if (t - ui.lastTick >= period) {
      ui.lastTick = t;
      // Route the tick through the SAME authoritative input path so render +
      // (no-op) relay sequencing matches every other action.
      if (typeof ctx.handleLocalInput === 'function') {
        ctx.handleLocalInput({ type: 'action', action: 'tick' });
      }
    }
  };
  ui.raf = requestAnimationFrame(step);
}

function stopGravity(ui) {
  if (ui.raf !== null && typeof cancelAnimationFrame === 'function') {
    cancelAnimationFrame(ui.raf);
  }
  ui.raf = null;
}

// ── DOM / canvas plumbing ─────────────────────────────────────────────────────

function buildDom(container, ui, ctx) {
  ui.canvas = (container.querySelector && container.querySelector('#well')) || makeCanvas(container, 'well');
  ui.cx = ui.canvas.getContext('2d');
  ui.next = (container.querySelector && container.querySelector('#next')) || null;
  ui.nextCx = ui.next ? ui.next.getContext('2d') : null;
  ui.hold = (container.querySelector && container.querySelector('#hold')) || null;
  ui.holdCx = ui.hold ? ui.hold.getContext('2d') : null;
  layout(ui);

  ui.hud = {
    score: container.querySelector && container.querySelector('#score'),
    lines: container.querySelector && container.querySelector('#lines'),
    level: container.querySelector && container.querySelector('#level'),
    status: container.querySelector && container.querySelector('#status'),
    restart: container.querySelector && container.querySelector('#restart'),
  };
  if (ui.hud.restart && ctx) {
    ui.hud.restart.addEventListener('click', () => {
      if (typeof ctx.handleLocalInput === 'function') ctx.handleLocalInput({ type: 'restart' });
    });
  }

  // Keyboard → action mapping. Arrows + WASD + space + shift/c (hold).
  if (typeof window !== 'undefined' && typeof window.addEventListener === 'function') {
    ui.onKey = (e) => {
      const action = keyToAction(e);
      if (!action) return;
      if (typeof e.preventDefault === 'function') e.preventDefault();
      if (action === 'restart') {
        if (typeof ctx.handleLocalInput === 'function') ctx.handleLocalInput({ type: 'restart' });
        return;
      }
      if (typeof ctx.handleLocalInput === 'function') {
        ctx.handleLocalInput({ type: 'action', action });
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
        if (ui.onKey) window.removeEventListener('keydown', ui.onKey);
        if (ui.onResize) window.removeEventListener('resize', ui.onResize);
        ui.onKey = null;
        ui.onResize = null;
      });
    }
  }
}

/** Map a keydown event to a game action token (or null to ignore). */
export function keyToAction(e) {
  const k = (e && (e.key || e.code)) || '';
  switch (k) {
    case 'ArrowLeft':
    case 'a':
    case 'A':
    case 'KeyA':
      return 'left';
    case 'ArrowRight':
    case 'd':
    case 'D':
    case 'KeyD':
      return 'right';
    case 'ArrowUp':
    case 'x':
    case 'X':
    case 'KeyX':
      return 'rotate-cw';
    case 'z':
    case 'Z':
    case 'KeyZ':
    case 'Control':
      return 'rotate-ccw';
    case 'ArrowDown':
    case 's':
    case 'S':
    case 'KeyS':
      return 'soft';
    case ' ':
    case 'Spacebar':
    case 'Space':
      return 'hard';
    case 'Shift':
    case 'c':
    case 'C':
    case 'KeyC':
      return 'hold';
    case 'r':
    case 'R':
    case 'KeyR':
      return 'restart';
    default:
      return null;
  }
}

function makeCanvas(container, id) {
  const c = typeof document !== 'undefined' ? document.createElement('canvas') : null;
  if (c) {
    c.id = id;
    if (container.appendChild) container.appendChild(c);
  }
  return c;
}

function layout(ui) {
  if (!ui.canvas) return;
  let cell = 24;
  if (typeof window !== 'undefined' && window.innerWidth) {
    // Fit the 10-wide well into the viewport with room for the side panel.
    const maxByW = Math.floor((Math.min(window.innerWidth, 520) - 160) / WIDTH);
    const maxByH = Math.floor((window.innerHeight - 40) / HEIGHT);
    cell = Math.max(14, Math.min(28, Math.min(maxByW, maxByH)));
  }
  ui.cell = cell;
  const w = cell * WIDTH;
  const h = cell * HEIGHT;
  ui.canvas.width = w;
  ui.canvas.height = h;
  if (ui.canvas.style) {
    ui.canvas.style.width = w + 'px';
    ui.canvas.style.height = h + 'px';
  }
  // Preview boxes are a fixed 4x4 of a smaller cell.
  const pcell = Math.max(10, Math.floor(cell * 0.7));
  for (const c of [ui.next, ui.hold]) {
    if (!c) continue;
    c.width = pcell * 4;
    c.height = pcell * 4;
    c._pcell = pcell;
    if (c.style) {
      c.style.width = c.width + 'px';
      c.style.height = c.height + 'px';
    }
  }
}

// ── drawing ───────────────────────────────────────────────────────────────────

function drawWell(ui, state) {
  const cx = ui.cx;
  if (!cx) return;
  const cell = ui.cell;
  const board = state.board || '';

  // Empty well with a faint checker.
  for (let row = 0; row < HEIGHT; row++) {
    for (let col = 0; col < WIDTH; col++) {
      const x = col * cell;
      const y = row * cell;
      cx.fillStyle = (row + col) % 2 === 0 ? COLORS.well : COLORS.wellAlt;
      cx.fillRect(x, y, cell, cell);
      cx.strokeStyle = COLORS.grid;
      cx.lineWidth = 1;
      cx.strokeRect(x, y, cell, cell);
    }
  }

  // Locked stack (board glyphs, upper-case piece letters; '.' is empty).
  for (let row = 0; row < HEIGHT; row++) {
    for (let col = 0; col < WIDTH; col++) {
      const g = board[row * WIDTH + col];
      if (g && g !== '.') drawBlock(cx, col, row, cell, colorFor(g));
    }
  }

  // The falling active piece (only while playing; on game-over the stack is the
  // whole story). Cells above the ceiling (row < 0) are clipped naturally.
  if (!isGameOver(state.status)) {
    const cells = activeCells(state.active);
    const color = colorFor(state.active && state.active.piece);
    for (const [col, row] of cells) {
      if (row < 0 || row >= HEIGHT || col < 0 || col >= WIDTH) continue;
      drawBlock(cx, col, row, cell, color);
    }
  }
}

function drawBlock(cx, col, row, cell, color) {
  const x = col * cell;
  const y = row * cell;
  cx.fillStyle = color || '#9aa0bf';
  cx.fillRect(x + 1, y + 1, cell - 2, cell - 2);
  cx.strokeStyle = COLORS.blockStroke;
  cx.lineWidth = Math.max(1, cell * 0.06);
  cx.strokeRect(x + 1, y + 1, cell - 2, cell - 2);
}

/** Draw a single piece centred in a 4x4 preview box. `glyph` may be null. */
function drawPreview(cx, canvas, glyph) {
  if (!cx || !canvas) return;
  const pcell = canvas._pcell || Math.floor((canvas.width || 40) / 4);
  cx.fillStyle = COLORS.panel;
  cx.fillRect(0, 0, canvas.width, canvas.height);
  if (!glyph) return;
  const cells = previewCells(glyph);
  const color = colorFor(glyph);
  for (const [col, row] of cells) {
    const x = col * pcell;
    const y = row * pcell;
    cx.fillStyle = color;
    cx.fillRect(x + 1, y + 1, pcell - 2, pcell - 2);
    cx.strokeStyle = COLORS.blockStroke;
    cx.lineWidth = Math.max(1, pcell * 0.06);
    cx.strokeRect(x + 1, y + 1, pcell - 2, pcell - 2);
  }
}

function drawHud(ui, state) {
  if (!ui.hud) return;
  const over = isGameOver(state.status);
  if (ui.hud.score) ui.hud.score.textContent = String(state.score);
  if (ui.hud.lines) ui.hud.lines.textContent = String(state.lines);
  if (ui.hud.level) ui.hud.level.textContent = String(state.level);
  if (ui.hud.status) {
    ui.hud.status.textContent = over ? `Game over — score ${state.score}` : 'Playing';
    if (ui.hud.status.classList) ui.hud.status.classList.toggle('over', over);
  }
  if (ui.hud.restart) ui.hud.restart.disabled = false;
}

export default createTetrisGame;
