// games/bomberman/bomberman.js
//
// The Bomberman GAME implementation for the shared web-native game shell
// (games/_shared/{wasm-host,sse-host-client,game-frame}.js). It is a single
// `GAME` object that:
//
//   • init(host, container, ctx)  — seeds the engine (bomberman_new(seed)), builds
//                                    the canvas + HUD, starts the host-authoritative
//                                    requestAnimationFrame TICK LOOP, returns the
//                                    initial snapshot.
//   • render(stateJson, ctx)      — paints the grid from the engine snapshot:
//                                    indestructible walls, destructible blocks,
//                                    floor, bombs, flames, and the two players,
//                                    plus the HUD (tick + status).
//   • onLocalInput(evt, ctx)      — maps ONE keydown (arrows/space for P1, WASD +
//                                    shift for P2 in single-player hot-seat) into a
//                                    per-tick INPUT, records it AUTHORITATIVELY into
//                                    the wasm engine, and returns the relay payload
//                                    (the host streams inputs on the 'input'
//                                    channel; the authoritative 'world' snapshot is
//                                    broadcast by the tick loop, not here).
//   • onRemoteEvent(channel, json, ctx) — host: records the guest's relayed input
//                                    into the engine; guest: applies the host's
//                                    authoritative 'world' snapshot directly (the
//                                    guest does NOT re-simulate — it renders the
//                                    host's snapshot, the simplest correct model for
//                                    a fixed-step duel).
//
// CATEGORY: REALTIME DUEL (host-authoritative TICK LOOP). Per
// docs/multiplayer-chess-design.md, the local browser running this engine is the
// source of truth: only the HOST advances bomberman_tick() on a clock and
// broadcasts the resulting snapshot on the 'world' channel; the guest re-applies
// those snapshots. Local input flows through bomberman_input() into the host's
// engine and is relayed on the 'input' channel. Single-player (no session_id) is a
// local two-keyset hot-seat — P1 on the arrows, P2 on WASD.
//
// We drive the verified snapdragon_bomberman_wasm.wasm through the shared WasmHost
// with the EXACT call pattern proven in games/_shared/test/bomberman-wasm.test.mjs:
//   host.callStatus('bomberman_new', null, [BigInt(seed)])  -> 1
//   host.callPacked('bomberman_snapshot')                   -> { type:'bomberman.snapshot', width, height, board, players, bombs, flames, tick, status }
//   host.callPacked('bomberman_status')                     -> { status }
//   host.callRaw('bomberman_input', 'p1:up')                -> 1 | 0
//   host.callPacked('bomberman_tick')                       -> { ...snapshot after the tick }
//
// Vanilla ES module. No build step, no dependencies.

// Board geometry mirrors snapdragon-bomberman/src/lib.rs (WIDTH×HEIGHT cells).
const WIDTH = 13;
const HEIGHT = 11;

// Wall-clock cadence of the authoritative tick loop (ms per fixed step). The
// engine is timeless; this is how fast the host advances it. ~6 ticks/second keeps
// a Bomberman duel readable (one cell of movement per tick).
const TICK_MS = 160;

// Glyphs in the engine's flat board string.
const TILE = { FLOOR: '.', WALL: '#', BLOCK: '+' };

// Palette (clears the WCAG-audited non-text contrast bar at 3:1).
const COLORS = {
  floor: '#1b2230',
  floorAlt: '#202838',
  wall: '#4a5570',
  wallEdge: '#2a3145',
  block: '#8a6d3b',
  blockEdge: '#5c4824',
  grid: '#10131c',
  p1: '#52c4ff', // host / arrows
  p2: '#ff6d9c', // guest / WASD
  p1Edge: '#0d3a55',
  p2Edge: '#5a1730',
  bomb: '#1a1a1a',
  bombFuse: '#ffd740',
  flame: 'rgba(255, 120, 40, 0.85)',
  flameCore: 'rgba(255, 215, 64, 0.95)',
  dead: '#555a6e',
};

// The two local keysets for single-player hot-seat. Each maps a key to an action
// token consumed by bomberman_input. P1 = arrows + space; P2 = WASD + shift.
const KEYMAP = {
  ArrowUp: ['p1', 'up'],
  ArrowDown: ['p1', 'down'],
  ArrowLeft: ['p1', 'left'],
  ArrowRight: ['p1', 'right'],
  ' ': ['p1', 'bomb'],
  Spacebar: ['p1', 'bomb'],
  w: ['p2', 'up'],
  s: ['p2', 'down'],
  a: ['p2', 'left'],
  d: ['p2', 'right'],
  // Uppercase twins: P2's bomb key is Shift, so a player holding Shift while
  // pressing a movement key gets the UPPER-CASE `key` from the browser (and Caps
  // Lock does the same). Without these, P2 movement silently dies whenever Shift
  // is down — exactly the hot-seat case where you want to move-then-bomb. (Mirrors
  // the same guard in games/tron/tron.js.)
  W: ['p2', 'up'],
  S: ['p2', 'down'],
  A: ['p2', 'left'],
  D: ['p2', 'right'],
  Shift: ['p2', 'bomb'],
};

/** Pick a deterministic seed: prefer an explicit URL/launch token, else fixed. */
export function pickSeed(params) {
  // chapter_id (if present) gives a stable per-chapter board; fall back to a fixed
  // seed so single-player dev is reproducible. (launch_uid is a string token, not a
  // numeric seed, so it is NOT used here — determinism trumps per-launch variety.)
  if (params && typeof params.chapterId === 'number' && !Number.isNaN(params.chapterId)) {
    return params.chapterId >>> 0;
  }
  return 0x5eed;
}

/** True if the match has ended (a terminal status). */
export function isGameOver(status) {
  return typeof status === 'string' && (status.startsWith('win:') || status === 'draw');
}

/** Human-readable status line from an engine status string. */
export function statusLine(status) {
  switch (status) {
    case 'playing':
      return 'Fight!';
    case 'win:p1':
      return 'Player 1 wins';
    case 'win:p2':
      return 'Player 2 wins';
    case 'draw':
      return 'Draw';
    default:
      return status || 'playing';
  }
}

/** The character at (x,y) in the flat board string, or WALL out of bounds. */
function tileAt(board, x, y) {
  if (x < 0 || y < 0 || x >= WIDTH || y >= HEIGHT) return TILE.WALL;
  const c = board && board[y * WIDTH + x];
  return c === TILE.WALL || c === TILE.BLOCK ? c : TILE.FLOOR;
}

// ── the GAME object ──────────────────────────────────────────────────────────

export function createBombermanGame() {
  // Per-instance UI state.
  const ui = {
    canvas: null,
    cx: null,
    cell: 0,
    hud: null, // { status, tick } elements
    banner: null, // transient HUD message
    onResize: null,
    onKeyDown: null,
  };

  // Per-instance loop state.
  const loop = {
    running: false,
    rafId: null,
    timerId: null,
    last: 0,
    acc: 0,
  };

  // Latest snapshot we rendered.
  let state = emptyState();

  function emptyState() {
    return {
      type: 'bomberman.snapshot',
      width: WIDTH,
      height: HEIGHT,
      board: '#'.repeat(WIDTH * HEIGHT),
      players: [
        { id: 'p1', x: 1, y: 1, alive: true, bombs: 0 },
        { id: 'p2', x: WIDTH - 2, y: HEIGHT - 2, alive: true, bombs: 0 },
      ],
      bombs: [],
      flames: [],
      tick: 0,
      status: 'playing',
    };
  }

  /** Read the engine's current snapshot into our `state` shape. */
  function readEngine(host) {
    const snap = host.callPacked('bomberman_snapshot');
    return snap && snap.type === 'bomberman.snapshot' ? snap : emptyState();
  }

  const GAME = {
    slug: 'bomberman',

    async init(host, container, ctx) {
      const seed = pickSeed(ctx && ctx.params);
      // Seed the engine (verified: returns 1). Seed is the LEADING scalar arg.
      host.callStatus('bomberman_new', null, [BigInt(seed >>> 0)]);
      buildDom(container, ui, ctx);
      state = readEngine(host);

      // The HOST owns the authoritative clock; the guest only renders relayed
      // 'world' snapshots. Single-player (isHost true, no relay) ticks locally.
      if (ctx && ctx.isHost) {
        startLoop(ctx);
      }
      return state;
    },

    render(stateJson, ctx) {
      if (stateJson && typeof stateJson === 'object' && stateJson.board) {
        state = stateJson;
      }
      layout(ui, state);
      drawGrid(ui, state);
      drawHud(ui, state);
    },

    onLocalInput(evt, ctx) {
      const host = ctx.host;
      if (!evt || evt.type !== 'keydown') return null;
      if (isGameOver(state.status)) return null;

      const key = evt.key;
      const mapping = KEYMAP[key];
      if (!mapping) return null;
      const [player, action] = mapping;

      // In a multiplayer session this slot only drives ITS OWN player. Slot 0
      // (host) is P1; any guest slot is P2. Single-player (characterId null) lets
      // BOTH keysets through for hot-seat.
      const cid = ctx.params && ctx.params.characterId;
      if (cid !== null && cid !== undefined) {
        const mine = ctx.isHost ? 'p1' : 'p2';
        if (player !== mine) return null;
      }

      // Record the intent AUTHORITATIVELY into the host's engine. (Returns 1/0;
      // a rejected input — dead player / game over — is simply ignored.)
      const token = `${player}:${action}`;
      const accepted = host.callRaw('bomberman_input', token);
      if (!(Number(accepted) | 0)) return null;

      // Relay the input on the 'input' channel. The authoritative 'world' snapshot
      // is broadcast by the tick loop (broadcastWorld), NOT per keystroke — this is
      // the host-authoritative tick model: inputs stream, world snapshots stream.
      return {
        input: { kind: 'control', type: 'move', payload: { player, action } },
        // No `state` here: rendering happens on the tick, not the keystroke, so the
        // simulation stays the single source of truth for what's drawn.
      };
    },

    onRemoteEvent(channel, json, ctx) {
      const host = ctx.host;
      if (!json) return null;

      // session-control: pause/resume/close — surface on the HUD, no engine change.
      if (channel === 'session-control') {
        if (json.action && String(json.action).includes('CLOSED')) {
          ui.banner = 'Session closed';
          stopLoop();
        }
        return state;
      }

      const payload = json.payload || json;

      // HOST path: a guest's relayed INPUT — record it into the authoritative
      // engine so the next tick resolves it. The host's tick loop will broadcast
      // the resulting world; we do NOT re-render here (the loop owns the frame).
      if (channel === 'input' && ctx && ctx.isHost) {
        const player = payload && payload.player;
        const action = payload && payload.action;
        if (isActionShaped(player, action) && !isGameOver(state.status)) {
          try {
            host.callRaw('bomberman_input', `${player}:${action}`);
          } catch (_e) {
            /* a malformed token must not trap the host loop */
          }
        }
        return null;
      }

      // GUEST path: the host's authoritative WORLD snapshot — render it directly.
      // The guest does NOT re-simulate; it trusts the host's snapshot (the simplest
      // correct model for a deterministic fixed-step duel).
      if (channel === 'world' && payload && isSnapshotShaped(payload)) {
        ui.banner = null;
        state = normalizeSnapshot(payload);
        return state;
      }

      return null;
    },

    // Release game-owned listeners + stop the loop. Called by the frame on unload.
    destroy() {
      stopLoop();
      if (ui.onResize && typeof window !== 'undefined' && typeof window.removeEventListener === 'function') {
        window.removeEventListener('resize', ui.onResize);
        ui.onResize = null;
      }
      if (ui.onKeyDown && typeof window !== 'undefined' && typeof window.removeEventListener === 'function') {
        window.removeEventListener('keydown', ui.onKeyDown);
        ui.onKeyDown = null;
      }
    },

    // ── exposed for tests + buttons ───────────────────────────────────────────

    // Advance exactly one authoritative tick, render, and broadcast the world.
    // The rAF loop calls this on its fixed-step cadence; tests call it directly.
    _tickOnce(ctx) {
      const host = ctx.host;
      let snap;
      try {
        snap = host.callPacked('bomberman_tick');
      } catch (_e) {
        stopLoop();
        return state;
      }
      if (snap && snap.type === 'bomberman.snapshot') {
        state = snap;
      }
      ctx.GAME.render(state, ctx);
      broadcastWorld(ctx, state);
      if (isGameOver(state.status)) {
        stopLoop();
      }
      return state;
    },
    _ui: ui,
    _loop: loop,
    _getState: () => state,
    _startLoop: (ctx) => startLoop(ctx),
    _stopLoop: () => stopLoop(),
  };

  // ── the host-authoritative tick loop ─────────────────────────────────────────

  function startLoop(ctx) {
    if (loop.running) return;
    loop.running = true;
    loop.last = now();
    loop.acc = 0;

    const hasRaf = typeof requestAnimationFrame === 'function';
    if (hasRaf) {
      const step = () => {
        if (!loop.running) return;
        const t = now();
        loop.acc += t - loop.last;
        loop.last = t;
        // Fixed-step accumulator: advance the sim in whole ticks so it stays
        // deterministic regardless of frame jitter (cap the catch-up to avoid a
        // spiral of death after a tab is backgrounded).
        let steps = 0;
        while (loop.acc >= TICK_MS && steps < 8) {
          loop.acc -= TICK_MS;
          steps++;
          GAME._tickOnce(ctx);
          if (!loop.running) break;
        }
        if (loop.running) loop.rafId = requestAnimationFrame(step);
      };
      loop.rafId = requestAnimationFrame(step);
    } else if (typeof setInterval === 'function') {
      // Headless / no-rAF fallback (kept for completeness; tests drive _tickOnce).
      loop.timerId = setInterval(() => {
        if (loop.running) GAME._tickOnce(ctx);
      }, TICK_MS);
    }
  }

  function stopLoop() {
    loop.running = false;
    if (loop.rafId !== null && typeof cancelAnimationFrame === 'function') {
      cancelAnimationFrame(loop.rafId);
    }
    loop.rafId = null;
    if (loop.timerId !== null && typeof clearInterval === 'function') {
      clearInterval(loop.timerId);
    }
    loop.timerId = null;
  }

  /** Host broadcasts its authoritative snapshot on the 'world' channel. */
  function broadcastWorld(ctx, snap) {
    if (!ctx || !ctx.isHost) return;
    const cid = ctx.params && ctx.params.characterId;
    if (cid === null || cid === undefined) return; // single-player → nothing to relay
    if (ctx.client && typeof ctx.client.sendWorld === 'function') {
      ctx.client.sendWorld(cid, GAME.slug, snap);
    }
  }

  return GAME;
}

// ── shape guards (protect the engine + render from malformed relayed payloads) ──

function isActionShaped(player, action) {
  return (
    (player === 'p1' || player === 'p2') &&
    ['up', 'down', 'left', 'right', 'bomb', 'wait'].includes(action)
  );
}

function isSnapshotShaped(s) {
  return (
    s &&
    typeof s === 'object' &&
    typeof s.board === 'string' &&
    s.board.length === WIDTH * HEIGHT &&
    Array.isArray(s.players)
  );
}

/** Defensive fill so render never NPEs on a partial relayed snapshot. */
function normalizeSnapshot(s) {
  return {
    type: 'bomberman.snapshot',
    width: WIDTH,
    height: HEIGHT,
    board: s.board,
    players: Array.isArray(s.players) ? s.players : [],
    bombs: Array.isArray(s.bombs) ? s.bombs : [],
    flames: Array.isArray(s.flames) ? s.flames : [],
    tick: typeof s.tick === 'number' ? s.tick : 0,
    status: s.status || 'playing',
  };
}

// ── timing primitive ───────────────────────────────────────────────────────────

function now() {
  if (typeof performance !== 'undefined' && typeof performance.now === 'function') {
    return performance.now();
  }
  return Date.now();
}

// ── DOM / canvas plumbing ─────────────────────────────────────────────────────

function buildDom(container, ui, ctx) {
  const canvas = container.querySelector('#board') || makeCanvas(container);
  ui.canvas = canvas;
  ui.cx = canvas.getContext('2d');

  // HUD wiring (status + tick) — built by index.html if present.
  const statusEl = container.querySelector('#status');
  const tickEl = container.querySelector('#tick');
  ui.hud = { status: statusEl, tick: tickEl };

  // Capture keyboard at the window so a freshly-mounted iframe needs no click. The
  // controls are forwarded into the host-authoritative loop via handleLocalInput.
  if (typeof window !== 'undefined' && typeof window.addEventListener === 'function') {
    ui.onKeyDown = (e) => {
      if (KEYMAP[e.key]) {
        if (typeof e.preventDefault === 'function') e.preventDefault();
        if (ctx && typeof ctx.handleLocalInput === 'function') ctx.handleLocalInput(e);
      }
    };
    window.addEventListener('keydown', ui.onKeyDown);

    ui.onResize = () => {
      layout(ui, ctx.GAME._getState());
      ctx.GAME.render(ctx.GAME._getState(), ctx);
    };
    window.addEventListener('resize', ui.onResize);
    if (ctx && typeof ctx.onTeardown === 'function') {
      ctx.onTeardown(() => {
        if (ui.onKeyDown) {
          window.removeEventListener('keydown', ui.onKeyDown);
          ui.onKeyDown = null;
        }
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

function layout(ui, state) {
  if (!ui.canvas) return;
  let target = 520;
  if (typeof window !== 'undefined' && window.innerWidth) {
    target = Math.min(window.innerWidth, window.innerHeight || window.innerWidth) - 24;
    target = Math.max(260, Math.min(target, 720));
  }
  // Square cells; the board is wider than tall (13×11), so size cells off the
  // limiting dimension and keep the aspect.
  const cellW = Math.floor(target / WIDTH);
  const cellH = Math.floor(target / HEIGHT);
  ui.cell = Math.max(8, Math.min(cellW, cellH));
  const pxW = ui.cell * WIDTH;
  const pxH = ui.cell * HEIGHT;
  ui.canvas.width = pxW;
  ui.canvas.height = pxH;
  if (ui.canvas.style) {
    ui.canvas.style.width = pxW + 'px';
    ui.canvas.style.height = pxH + 'px';
  }
}

// ── drawing ───────────────────────────────────────────────────────────────────

function drawGrid(ui, state) {
  const cx = ui.cx;
  if (!cx) return;
  const cell = ui.cell;
  const board = state.board || '';

  // Terrain.
  for (let y = 0; y < HEIGHT; y++) {
    for (let x = 0; x < WIDTH; x++) {
      const px = x * cell;
      const py = y * cell;
      const t = tileAt(board, x, y);
      if (t === TILE.WALL) {
        cx.fillStyle = COLORS.wall;
        cx.fillRect(px, py, cell, cell);
        cx.strokeStyle = COLORS.wallEdge;
        cx.lineWidth = Math.max(1, cell * 0.06);
        cx.strokeRect(px + 1, py + 1, cell - 2, cell - 2);
      } else if (t === TILE.BLOCK) {
        cx.fillStyle = COLORS.block;
        cx.fillRect(px, py, cell, cell);
        cx.strokeStyle = COLORS.blockEdge;
        cx.lineWidth = Math.max(1, cell * 0.06);
        cx.strokeRect(px + 1, py + 1, cell - 2, cell - 2);
      } else {
        cx.fillStyle = (x + y) % 2 === 0 ? COLORS.floor : COLORS.floorAlt;
        cx.fillRect(px, py, cell, cell);
      }
      // Grid line.
      cx.strokeStyle = COLORS.grid;
      cx.lineWidth = Math.max(1, cell * 0.02);
      cx.strokeRect(px, py, cell, cell);
    }
  }

  // Bombs (with a fuse pip).
  const bombs = Array.isArray(state.bombs) ? state.bombs : [];
  for (const b of bombs) {
    const px = b.x * cell;
    const py = b.y * cell;
    cx.beginPath();
    cx.arc(px + cell / 2, py + cell / 2, cell * 0.32, 0, Math.PI * 2);
    cx.fillStyle = COLORS.bomb;
    cx.fill();
    // Fuse highlight.
    cx.beginPath();
    cx.arc(px + cell * 0.66, py + cell * 0.30, cell * 0.08, 0, Math.PI * 2);
    cx.fillStyle = COLORS.bombFuse;
    cx.fill();
  }

  // Flames (drawn over terrain + bombs so the blast reads as the top layer below
  // players).
  const flames = Array.isArray(state.flames) ? state.flames : [];
  for (const f of flames) {
    const x = Array.isArray(f) ? f[0] : f.x;
    const y = Array.isArray(f) ? f[1] : f.y;
    const px = x * cell;
    const py = y * cell;
    cx.fillStyle = COLORS.flame;
    cx.fillRect(px, py, cell, cell);
    cx.beginPath();
    cx.arc(px + cell / 2, py + cell / 2, cell * 0.30, 0, Math.PI * 2);
    cx.fillStyle = COLORS.flameCore;
    cx.fill();
  }

  // Players (top layer). Dead players are dimmed.
  const players = Array.isArray(state.players) ? state.players : [];
  for (const p of players) {
    const px = p.x * cell;
    const py = p.y * cell;
    cx.beginPath();
    cx.arc(px + cell / 2, py + cell / 2, cell * 0.36, 0, Math.PI * 2);
    if (!p.alive) {
      cx.fillStyle = COLORS.dead;
    } else {
      cx.fillStyle = p.id === 'p1' ? COLORS.p1 : COLORS.p2;
    }
    cx.fill();
    cx.lineWidth = Math.max(1, cell * 0.04);
    cx.strokeStyle = p.id === 'p1' ? COLORS.p1Edge : COLORS.p2Edge;
    cx.stroke();
  }
}

function drawHud(ui, state) {
  if (!ui.hud) return;
  const over = isGameOver(state.status);
  if (ui.hud.tick) {
    ui.hud.tick.textContent = `Tick ${state.tick}`;
  }
  if (ui.hud.status) {
    ui.hud.status.textContent = ui.banner ? ui.banner : statusLine(state.status);
    if (ui.hud.status.classList && typeof ui.hud.status.classList.toggle === 'function') {
      ui.hud.status.classList.toggle('over', over);
    }
  }
}

export default createBombermanGame;
