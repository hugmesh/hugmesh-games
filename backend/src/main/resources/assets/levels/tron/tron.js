// games/tron/tron.js
//
// The Tron light-cycles GAME implementation for the shared web-native game shell
// (games/_shared/{wasm-host,sse-host-client,game-frame}.js). It is a single
// `GAME` object that:
//
//   • init(host, container, ctx)  — seeds the engine (tron_new), builds the
//                                    canvas + HUD, STARTS the requestAnimationFrame
//                                    tick loop, and returns the initial snapshot.
//   • render(stateJson, ctx)      — paints the grid + both trails + both heads
//                                    from the engine snapshot, plus the HUD
//                                    (tick counter, who's alive, win/draw banner).
//   • onLocalInput(evt, ctx)      — maps an arrow / WASD keydown into a direction
//                                    for the LOCAL cycle, applies it through the
//                                    wasm engine (set_dir), and relays the chosen
//                                    direction on the `input` channel.
//   • onRemoteEvent(channel,json) — host: re-applies the guest's relayed `input`
//                                    direction (set_dir). guest: re-seats render
//                                    from the host's authoritative `world` snapshot.
//
// ── CATEGORY: REALTIME DUEL — host-authoritative TICK LOOP ────────────────────
// Tron is NOT click-to-move like chess/reversi: it is a fixed-step real-time
// simulation. Per docs/multiplayer-chess-design.md, the HOST browser is the only
// authority that advances the clock:
//
//   • Host: a requestAnimationFrame loop fires tron_tick() at the engine's tick
//     cadence (TICK_MS). After each tick the host re-renders AND broadcasts the
//     authoritative snapshot on the `world` channel (so the guest re-seats even
//     if it dropped an intermediate input).
//   • Guest (slot_index > 0): does NOT tick its own clock — it renders the host's
//     `world` snapshots verbatim (prediction-free, kept simple per the task). Its
//     own arrow/WASD input is relayed to the host on the `input` channel; the host
//     applies it to the authoritative engine and the result comes back in `world`.
//   • Single-player (no session_id ⇒ no characterId): a LOCAL TWO-KEYSET HOT-SEAT.
//     Arrow keys steer P1 (cycle One), WASD steers P2 (cycle Two); both run on the
//     host's one engine, no network.
//
// HOST-AUTHORITATIVE: render() never mutates the engine; every state transition
// flows through tron_set_dir / tron_tick so the rendered board can never drift
// from the rules engine. The guest's render of a `world` snapshot is a re-seat,
// not a fork.
//
// We drive the verified snapdragon_tron_wasm.wasm through the shared WasmHost with
// the EXACT call pattern proven in games/_shared/test/tron-wasm.test.mjs:
//   host.callStatus('tron_new', null, [seed])              -> 1
//   host.callStatus('tron_new_sized', null, [seed, w, h])  -> 1
//   host.callStatus('tron_set_dir', null, [player, dir])   -> 1 | 0  (rejected)
//   host.callPacked('tron_tick')                           -> { type:'tron.tick', tick, oneCrashed, twoCrashed, status }
//   host.callPacked('tron_snapshot')                       -> { type:'tron.snapshot', width, height, tick, board, one, two, status }
//   host.callPacked('tron_status')                         -> { status }
//
// Vanilla ES module. No build step, no dependencies.

// Direction codes (must match the shim: 0=up 1=down 2=left 3=right).
export const DIR = { up: 0, down: 1, left: 2, right: 3 };
export const DIR_NAME = ['up', 'down', 'left', 'right'];
// Player codes (0=One, 1=Two).
export const PLAYER = { one: 0, two: 1 };

// The fixed real-time cadence (ms per engine tick). The engine itself is
// clockless (a pure fixed timestep); the FRONTEND chooses the wall-clock rate.
export const TICK_MS = 110;

// Default grid the engine seeds on tron_new (DEFAULT_W x DEFAULT_H in the core).
const DEFAULT_W = 20;
const DEFAULT_H = 20;

// Board palette (clears the WCAG-audited non-text contrast bar at 3:1).
const COLORS = {
  arena: '#0b0a14', // the dark arena floor
  grid: '#241f38', // faint grid lines
  oneTrail: '#19c3ff', // cycle One — cyan
  oneHead: '#aef2ff',
  twoTrail: '#ff8c1a', // cycle Two — orange
  twoHead: '#ffd9a8',
  crashed: '#5a5566', // a crashed cycle's trail dims
};

// Arrow keys steer P1 (cycle One); WASD steers P2 (cycle Two). The map is from a
// keydown `key`/`code` to a [player, dir] pair.
const KEYMAP = {
  ArrowUp: [PLAYER.one, DIR.up],
  ArrowDown: [PLAYER.one, DIR.down],
  ArrowLeft: [PLAYER.one, DIR.left],
  ArrowRight: [PLAYER.one, DIR.right],
  w: [PLAYER.two, DIR.up],
  s: [PLAYER.two, DIR.down],
  a: [PLAYER.two, DIR.left],
  d: [PLAYER.two, DIR.right],
  W: [PLAYER.two, DIR.up],
  S: [PLAYER.two, DIR.down],
  A: [PLAYER.two, DIR.left],
  D: [PLAYER.two, DIR.right],
};

/** True when the status string is a terminal one (a win or a draw). */
export function isGameOver(status) {
  return typeof status === 'string' && (status.startsWith('win:') || status === 'draw');
}

/** Human-readable status line from the engine status string. */
export function statusLine(status) {
  switch (status) {
    case 'playing':
      return 'Racing';
    case 'win:one':
      return 'Cyan wins';
    case 'win:two':
      return 'Orange wins';
    case 'draw':
      return 'Draw — both crashed';
    default:
      return status || 'Racing';
  }
}

// ── the GAME object ──────────────────────────────────────────────────────────

export function createTronGame() {
  // Per-instance UI state.
  const ui = {
    canvas: null,
    cx: null,
    cell: 0,
    width: DEFAULT_W,
    height: DEFAULT_H,
    hud: null, // { tick, status }
    banner: null, // transient HUD message (sync warnings)
    onResize: null,
    onKey: null,
    rafId: null,
    lastTickAt: 0,
  };

  // The local slot's player. In a session this is derived from slot_index
  // (slot 0 = host = cycle One; slot > 0 = guest = cycle Two). In single-player
  // BOTH cycles are driven locally (the hot-seat keymap), so localPlayer is null.
  let localPlayer = null;
  let isHost = true;
  let driveTick = true; // does THIS slot advance the clock? (host yes, guest no)

  // Latest authoritative snapshot we rendered.
  let state = {
    type: 'tron.snapshot',
    width: DEFAULT_W,
    height: DEFAULT_H,
    tick: 0,
    board: '.'.repeat(DEFAULT_W * DEFAULT_H),
    one: { x: 0, y: 0, dir: 'right', alive: true },
    two: { x: 0, y: 0, dir: 'left', alive: true },
    status: 'playing',
  };

  /** Pull the full current position out of the engine into our `state` shape. */
  function readEngine(host) {
    const snap = host.callPacked('tron_snapshot');
    return snap && snap.type === 'tron.snapshot' ? snap : state;
  }

  const GAME = {
    slug: 'tron',

    async init(host, container, ctx) {
      isHost = ctx.isHost !== false;
      // Slot 0 / single-player ⇒ host (drives the clock); slot > 0 ⇒ guest.
      driveTick = isHost;
      // In a networked session the local slot drives exactly ONE cycle:
      //   host (slot 0) → cycle One; guest (slot > 0) → cycle Two.
      // No characterId ⇒ single-player hot-seat ⇒ both cycles local (null).
      const networked = ctx.params && ctx.params.characterId !== null && ctx.params.characterId !== undefined;
      localPlayer = networked ? (isHost ? PLAYER.one : PLAYER.two) : null;

      // Seed the engine to a fresh match. The host owns the seed; a deterministic
      // launch_uid-derived seed keeps host + guest in lockstep when present. The
      // engine seed is a u64 → the wasm export takes an i64, so it MUST be passed
      // as a BigInt (a plain Number throws "Cannot convert to a BigInt").
      const seed = seedFrom(ctx.params);
      host.callStatus('tron_new', null, [BigInt(seed)]);

      buildDom(container, ui, ctx);
      state = readEngine(host);
      ui.width = state.width;
      ui.height = state.height;
      layout(ui);

      // Start the host-authoritative tick loop (host only). The guest renders
      // `world` snapshots instead of ticking its own clock.
      if (driveTick) {
        startTickLoop(host, ctx);
      }
      return state;
    },

    render(stateJson, ctx) {
      if (stateJson && typeof stateJson === 'object' && stateJson.board) {
        state = {
          type: 'tron.snapshot',
          width: stateJson.width || state.width,
          height: stateJson.height || state.height,
          tick: typeof stateJson.tick === 'number' ? stateJson.tick : state.tick,
          board: stateJson.board || state.board,
          one: stateJson.one || state.one,
          two: stateJson.two || state.two,
          status: stateJson.status || 'playing',
        };
        if (state.width !== ui.width || state.height !== ui.height) {
          ui.width = state.width;
          ui.height = state.height;
          layout(ui);
        }
      }
      drawArena(ui, state);
      drawHud(ui, state);
    },

    // A captured keydown. Map it to a [player, dir] and apply it AUTHORITATIVELY
    // (host) or queue + relay it (guest). Returns the relay payload + state.
    onLocalInput(evt, ctx) {
      const host = ctx.host;
      if (!evt || evt.type !== 'keydown') return null;
      if (isGameOver(state.status)) return null;

      const key = evt.key !== undefined ? evt.key : evt.code;
      const mapped = KEYMAP[key];
      if (!mapped) return null;
      let [player, dir] = mapped;

      // In a networked session the local slot may only steer ITS OWN cycle. Map
      // the player's preferred keyset (arrows) onto whichever cycle they drive.
      if (localPlayer !== null) {
        // Accept arrow keys (the canonical "my cycle" keyset) AND the WASD keys,
        // but always steer the LOCAL cycle regardless of which keyset was used.
        player = localPlayer;
      }

      // HOST applies directly to the authoritative engine. GUEST does not own the
      // engine clock; it relays the intended direction and lets the host apply it
      // — but it ALSO applies locally so its own re-validation stays in lockstep.
      const accepted = host.callStatus('tron_set_dir', null, [player, dir]) === 1;
      ui.banner = null;

      // Relay the chosen direction on the `input` channel (guest→host and, for a
      // host with a watching guest, host→guest world covers it via the tick loop).
      const payload = { player, dir, dirName: DIR_NAME[dir] };
      // We render the CURRENT snapshot (the direction takes effect on the next
      // tick); no immediate board change, but the HUD heading hint updates.
      const snap = readEngine(host);
      state = snap;
      return {
        input: { kind: 'control', type: 'dir', payload },
        state: accepted ? state : state, // render either way (rejected = no-op)
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
        return state;
      }

      const payload = json.payload || json;

      // GUEST path: the host's authoritative `world` snapshot. Re-seat the render
      // from it verbatim (prediction-free). A guest never ticks its own clock.
      if (channel === 'world') {
        if (payload && payload.type === 'tron.snapshot' && isBoardShaped(payload.board, payload.width, payload.height)) {
          ui.banner = null;
          state = {
            type: 'tron.snapshot',
            width: payload.width,
            height: payload.height,
            tick: payload.tick,
            board: payload.board,
            one: payload.one,
            two: payload.two,
            status: payload.status,
          };
          return state;
        }
        return null;
      }

      // HOST path: the guest's relayed `input` direction. Apply it to the
      // authoritative engine; it takes effect on the next host tick. Validate the
      // shape so a malformed relayed code can't drive a bad call.
      if (channel === 'input') {
        const p = payload && payload.payload ? payload.payload : payload;
        const player = p && typeof p.player === 'number' ? p.player : null;
        const dir = p && typeof p.dir === 'number' ? p.dir : null;
        if (isHost && player !== null && dir !== null && (player === 0 || player === 1) && dir >= 0 && dir <= 3) {
          host.callStatus('tron_set_dir', null, [player, dir]);
          // No immediate render change — the tick loop broadcasts the result.
          return state;
        }
        return null;
      }

      return null;
    },

    // Release game-owned listeners + stop the tick loop. Called by the frame on
    // iframe unload.
    destroy() {
      stopTickLoop(ui);
      if (ui.onKey && typeof window !== 'undefined' && typeof window.removeEventListener === 'function') {
        window.removeEventListener('keydown', ui.onKey);
        ui.onKey = null;
      }
      if (ui.onResize && typeof window !== 'undefined' && typeof window.removeEventListener === 'function') {
        window.removeEventListener('resize', ui.onResize);
        ui.onResize = null;
      }
    },

    // Exposed for tests + the manual restart button.
    _tickOnce(host, ctx) {
      return hostTick(host, ctx, GAME, ui);
    },
    _restart(host, ctx) {
      const seed = (seedFrom(ctx && ctx.params) ^ (Date.now() & 0xffff)) >>> 0;
      host.callStatus('tron_new', null, [BigInt(seed)]);
      state = readEngine(host);
      ui.banner = null;
      if (driveTick && ui.rafId === null) startTickLoop(host, ctx);
      GAME.render(state, ctx);
      return state;
    },
    _ui: ui,
    _getState: () => state,
    _setLocalPlayer: (p) => { localPlayer = p; },
  };

  return GAME;

  // ── the host-authoritative tick loop ──────────────────────────────────────

  function startTickLoop(host, ctx) {
    if (typeof requestAnimationFrame !== 'function') return; // node / headless: no raf
    ui.lastTickAt = now();
    const step = () => {
      ui.rafId = requestAnimationFrame(step);
      const t = now();
      if (t - ui.lastTickAt < TICK_MS) return;
      ui.lastTickAt = t;
      if (isGameOver(state.status)) return; // freeze on terminal; engine ticks are inert anyway
      hostTick(host, ctx, GAME, ui);
    };
    ui.rafId = requestAnimationFrame(step);
  }
}

// ── one authoritative tick (host) ─────────────────────────────────────────────

/** Advance the engine one tick, re-render, and broadcast the world snapshot. */
function hostTick(host, ctx, GAME, ui) {
  const outcome = host.callPacked('tron_tick'); // { type:'tron.tick', ... } | {}
  const snap = host.callPacked('tron_snapshot');
  if (!snap || snap.type !== 'tron.snapshot') return null;
  GAME.render(snap, ctx);
  // Broadcast the authoritative full snapshot so a guest re-seats exactly.
  if (ctx && ctx.params && ctx.params.characterId !== null && ctx.params.characterId !== undefined &&
      ctx.client && typeof ctx.client.sendWorld === 'function') {
    ctx.client.sendWorld(ctx.params.characterId, GAME.slug, snap);
  }
  return { outcome, snap };
}

function stopTickLoop(ui) {
  if (ui.rafId !== null && typeof cancelAnimationFrame === 'function') {
    cancelAnimationFrame(ui.rafId);
  }
  ui.rafId = null;
}

function now() {
  return (typeof performance !== 'undefined' && typeof performance.now === 'function')
    ? performance.now()
    : Date.now();
}

/** A deterministic seed from the launch params (launch_uid / chapter_id), so a
 *  host + guest that share the launch share the start layout. Falls back to a
 *  fixed seed when nothing is present (single-player). */
function seedFrom(params) {
  if (!params) return 1;
  const uid = params.launchUid;
  if (typeof uid === 'string' && uid.length) {
    // FNV-1a over the launch uid → a stable 32-bit seed shared by both slots.
    let h = 0x811c9dc5;
    for (let i = 0; i < uid.length; i++) {
      h ^= uid.charCodeAt(i);
      h = (h * 0x01000193) >>> 0;
    }
    return h || 1;
  }
  if (typeof params.chapterId === 'number') return params.chapterId || 1;
  return 1;
}

// ── shape guards (protect the engine from malformed relayed snapshots) ────────

/** A plausible flat board string: width*height cells of '.'/'1'/'2'. */
function isBoardShaped(s, w, h) {
  if (typeof s !== 'string') return false;
  if (typeof w !== 'number' || typeof h !== 'number') return false;
  if (s.length !== w * h) return false;
  return /^[.12]+$/.test(s);
}

// ── DOM / canvas plumbing ─────────────────────────────────────────────────────

function buildDom(container, ui, ctx) {
  const canvas = container.querySelector('#arena') || makeCanvas(container);
  ui.canvas = canvas;
  ui.cx = canvas.getContext('2d');

  // HUD wiring (tick / status / restart) — built by index.html if present.
  ui.hud = {
    tick: container.querySelector('#tick'),
    status: container.querySelector('#status'),
    restart: container.querySelector('#restart'),
  };
  if (ui.hud.restart && ctx) {
    ui.hud.restart.addEventListener('click', () => {
      ctx.GAME._restart(ctx.host, ctx);
    });
  }

  // Keyboard input → the host-authoritative loop. Captured at the window so a
  // freshly-mounted iframe steers without a prior click (the host shell focuses
  // the iframe on load).
  if (typeof window !== 'undefined' && typeof window.addEventListener === 'function') {
    ui.onKey = (e) => {
      const key = e.key !== undefined ? e.key : e.code;
      if (KEYMAP[key]) {
        if (typeof e.preventDefault === 'function') e.preventDefault();
        if (ctx && typeof ctx.handleLocalInput === 'function') {
          ctx.handleLocalInput(e);
        }
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

function makeCanvas(container) {
  const c = typeof document !== 'undefined' ? document.createElement('canvas') : null;
  if (c) {
    c.id = 'arena';
    container.appendChild(c);
  }
  return c;
}

function layout(ui) {
  if (!ui.canvas) return;
  const cols = ui.width || DEFAULT_W;
  const rows = ui.height || DEFAULT_H;
  let target = 480;
  if (typeof window !== 'undefined' && window.innerWidth) {
    target = Math.min(window.innerWidth, window.innerHeight || window.innerWidth) - 24;
    target = Math.max(240, Math.min(target, 640));
  }
  // Square cells; the arena is cols x rows of them.
  ui.cell = Math.max(4, Math.floor(target / Math.max(cols, rows)));
  const pw = ui.cell * cols;
  const ph = ui.cell * rows;
  ui.canvas.width = pw;
  ui.canvas.height = ph;
  if (ui.canvas.style) {
    ui.canvas.style.width = pw + 'px';
    ui.canvas.style.height = ph + 'px';
  }
}

// ── drawing ───────────────────────────────────────────────────────────────────

function drawArena(ui, state) {
  const cx = ui.cx;
  if (!cx) return;
  const cell = ui.cell;
  const cols = state.width;
  const rows = state.height;
  const over = isGameOver(state.status);

  // Arena floor.
  cx.fillStyle = COLORS.arena;
  cx.fillRect(0, 0, cols * cell, rows * cell);

  // Faint grid lines.
  cx.strokeStyle = COLORS.grid;
  cx.lineWidth = Math.max(1, cell * 0.04);
  for (let c = 0; c <= cols; c++) {
    cx.strokeRect(c * cell, 0, 0, rows * cell);
  }
  for (let r = 0; r <= rows; r++) {
    cx.strokeRect(0, r * cell, cols * cell, 0);
  }

  // Trails — one fillRect per solid cell. The board string is row-major, top row
  // first, '.'=empty '1'=One '2'=Two (heads not overlaid in the flat board).
  const board = state.board || '';
  for (let y = 0; y < rows; y++) {
    for (let x = 0; x < cols; x++) {
      const cellChar = board[y * cols + x];
      if (cellChar === '1') {
        cx.fillStyle = over && !state.one.alive ? COLORS.crashed : COLORS.oneTrail;
        cx.fillRect(x * cell + 1, y * cell + 1, cell - 2, cell - 2);
      } else if (cellChar === '2') {
        cx.fillStyle = over && !state.two.alive ? COLORS.crashed : COLORS.twoTrail;
        cx.fillRect(x * cell + 1, y * cell + 1, cell - 2, cell - 2);
      }
    }
  }

  // Heads — a brighter square on top of each live cycle's current head cell.
  if (state.one && state.one.alive) {
    cx.fillStyle = COLORS.oneHead;
    cx.fillRect(state.one.x * cell + 1, state.one.y * cell + 1, cell - 2, cell - 2);
  }
  if (state.two && state.two.alive) {
    cx.fillStyle = COLORS.twoHead;
    cx.fillRect(state.two.x * cell + 1, state.two.y * cell + 1, cell - 2, cell - 2);
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
  if (ui.hud.restart) {
    ui.hud.restart.disabled = !over;
  }
}

export default createTronGame;
