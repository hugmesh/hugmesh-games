// games/asteroids-duel/asteroids-duel.js
//
// The Asteroids-Duel GAME implementation for the shared web-native game shell
// (games/_shared/{wasm-host,sse-host-client,game-frame}.js). It is a single
// `GAME` object that:
//
//   • init(host, container, ctx)  — seeds the engine (asteroids_new), builds the
//                                    canvas + HUD, starts the requestAnimationFrame
//                                    TICK LOOP, returns the initial snapshot.
//   • render(stateJson, ctx)      — paints the wrapping field: ship triangles,
//                                    asteroid polygons, bullets — as VECTOR
//                                    graphics on the canvas, plus the score / status
//                                    HUD. render() never mutates the engine.
//   • onLocalInput(evt, ctx)      — captures arrows/space keydown/keyup into a held
//                                    key-state map. The TICK LOOP (not this method)
//                                    applies the held input to the host's engine
//                                    each tick and relays it on the `input` channel.
//   • onRemoteEvent(channel, json, ctx) — the guest re-applies the host's
//                                    authoritative `world` snapshot; the host
//                                    folds a relayed guest `input` into the guest
//                                    ship's per-tick input.
//
// CATEGORY: REALTIME DUEL (host-authoritative TICK LOOP). This is the FIRST
// real-time web-native game in the portal, so it establishes the tick-loop
// pattern the other realtime engines (the tick-loop cores) will mirror:
//
//   ┌─ HOST (slot 0 / single-player) ──────────────────────────────────────────┐
//   │  requestAnimationFrame loop, fixed ~30 Hz accumulator:                    │
//   │    1. asteroids_input(0, …local p1 keys…)  set p1's per-tick input        │
//   │    2. asteroids_input(1, …guest p2 keys…)  set p2 from the last relayed   │
//   │       guest input (duel) — survival has no p2                             │
//   │    3. asteroids_tick()                      advance the authoritative sim │
//   │    4. asteroids_snapshot()                  read state, render()          │
//   │    5. sendWorld(snapshot)                   broadcast on the `world` chan │
//   │  Local keys are ALSO relayed on the `input` channel so the guest's host   │
//   │  copy stays warm, but the snapshot is the source of truth.                │
//   └───────────────────────────────────────────────────────────────────────────┘
//   ┌─ GUEST (slot > 0) ───────────────────────────────────────────────────────┐
//   │  NO local tick loop. It RENDERS host `world` snapshots as they arrive and │
//   │  RELAYS its own keys on `input` so the host drives p2. (Prediction is     │
//   │  intentionally omitted — the engine is deterministic, the host is close,  │
//   │  and a guest re-applying snapshots is the simplest correct model.)        │
//   └───────────────────────────────────────────────────────────────────────────┘
//
// Single-player (no session_id) = the host loop with no p2 input source: a
// solo SURVIVAL run against the seeded field (players=1), OR a local hot-seat duel
// (players=2) where a second keyset drives p2 — see KEYMAP below.
//
// HOST-AUTHORITATIVE: the local browser running this engine is the source of
// truth (docs/multiplayer-chess-design.md). render() never mutates the engine;
// every state transition flows through asteroids_tick so the field can never
// drift from the simulation.
//
// The engine is the verified hugmesh_asteroids_duel_wasm.wasm; we drive it
// through the shared WasmHost with the EXACT call pattern proven in
// games/_shared/test/asteroids-duel-wasm.test.mjs:
//   host.callStatus('asteroids_new', null, [seedLo, seedHi, players]) -> 1
//   host.callStatus('asteroids_input', null, [ship, thrust, turn, fire]) -> 0|1
//   host.callStatus('asteroids_tick') -> 0|1
//   host.callPacked('asteroids_snapshot') -> { type:'asteroids.snapshot', fp,width,height,players,tick,ships,asteroids,bullets,status }
//   host.callPacked('asteroids_status') -> { status }
//
// Vanilla ES module. No build step, no dependencies.

// Engine tick rate — the host advances the fixed-step sim at this many ticks/sec.
// The engine's tuning (thrust/friction/bullet life) is balanced for ~30 Hz.
const TICK_HZ = 30;
const TICK_MS = 1000 / TICK_HZ;
// Cap the catch-up so a backgrounded tab that wakes after seconds doesn't
// spiral-of-death replaying thousands of ticks in one frame.
const MAX_CATCHUP_TICKS = 5;

// Field palette (clears the WCAG-audited non-text contrast bar at 3:1 against the
// near-black space background).
const COLORS = {
  space: '#0a0a14',
  star: '#2a2a44',
  p1: '#b388ff', // player one ship (the brand accent)
  p2: '#5fd0ff', // player two ship
  ship: '#f2eefb', // single-ship survival
  thrust: '#ffb347', // thrust flame
  asteroid: '#c8ccdd',
  bullet: '#ffd740',
  dead: '#6a6a82', // a destroyed ship wreck
};

// Held-key → ship input. Arrows + space drive p1 (the local/host ship). A second
// keyset (WASD + F) drives p2 for a LOCAL hot-seat duel; in a networked duel the
// guest's keys arrive over the relay instead.
const KEYMAP = {
  // player one
  ArrowLeft: { ship: 0, act: 'left' },
  ArrowRight: { ship: 0, act: 'right' },
  ArrowUp: { ship: 0, act: 'thrust' },
  ' ': { ship: 0, act: 'fire' },
  Spacebar: { ship: 0, act: 'fire' }, // legacy key name
  // player two (local hot-seat)
  a: { ship: 1, act: 'left' },
  d: { ship: 1, act: 'right' },
  w: { ship: 1, act: 'thrust' },
  f: { ship: 1, act: 'fire' },
  A: { ship: 1, act: 'left' },
  D: { ship: 1, act: 'right' },
  W: { ship: 1, act: 'thrust' },
  F: { ship: 1, act: 'fire' },
};

// ── pure helpers (exported for tests) ─────────────────────────────────────────

/** True once the game has reached a terminal status. */
export function isGameOver(status) {
  return typeof status === 'string' && (status.startsWith('win:') || status.startsWith('gameover:'));
}

/** Human-readable status line from an engine status string. */
export function statusLine(status, players) {
  if (typeof status !== 'string' || status === 'playing') {
    return players === 2 ? 'Duel — destroy the other ship' : 'Survive the field';
  }
  if (status.startsWith('win:')) {
    const who = status.slice(4) === 'p2' ? 'Player 2' : 'Player 1';
    return `${who} wins`;
  }
  if (status.startsWith('gameover:')) {
    const score = status.slice('gameover:'.length);
    return players === 2 ? 'Draw — both ships destroyed' : `Game over — score ${score}`;
  }
  return status;
}

/**
 * Fold a held key-state set for one ship into the four engine input scalars
 * `[thrust, turn, fire]` the shim's asteroids_input expects:
 *   thrust : 0|1   turn : -1 (left) | 0 | +1 (right)   fire : 0|1
 * `left` + `right` held at once cancel to no turn.
 */
export function inputScalars(keys) {
  const thrust = keys.thrust ? 1 : 0;
  const fire = keys.fire ? 1 : 0;
  let turn = 0;
  if (keys.left) turn -= 1;
  if (keys.right) turn += 1;
  return [thrust, turn, fire];
}

/** A polygon (8 verts) for an asteroid of a given size class, in world units. */
function asteroidRadius(size) {
  // mirrors the engine's Size::radius in *world* units (radius_fp / FP).
  switch (size) {
    case 'L':
      return 36;
    case 'M':
      return 20;
    default:
      return 11;
  }
}

// ── the GAME object ────────────────────────────────────────────────────────────

export function createAsteroidsGame() {
  // Per-instance UI + loop state.
  const ui = {
    canvas: null,
    cx: null,
    w: 1024,
    h: 768,
    scale: 1, // canvas px per world unit
    fp: 256, // fixed-point scale (from the snapshot)
    hud: null, // { score, status }
    banner: null,
    stars: null, // a fixed star-field (decorative, deterministic per mount)
    onResize: null,
  };

  // Held keys, per ship, the loop reads each tick.
  const held = [
    { left: false, right: false, thrust: false, fire: false },
    { left: false, right: false, thrust: false, fire: false },
  ];
  // The most-recent relayed guest input (networked duel): the host folds this
  // into ship 1 each tick.
  let relayedGuest = { left: false, right: false, thrust: false, fire: false };

  // Loop bookkeeping.
  let rafId = null;
  let lastTs = 0;
  let acc = 0;
  let running = false;

  // The latest snapshot we rendered (engine truth on the host; relayed on guest).
  let state = null;
  let players = 1;
  let isHostLoop = true; // host (or single-player) runs the tick loop

  // Cached ctx for the loop (set in init).
  let CTX = null;

  /** Pull the current snapshot out of the engine. */
  function readEngine(host) {
    return host.callPacked('asteroids_snapshot');
  }

  /** Seed dimensions/scale from a snapshot (first one, or on resize). */
  function adoptDims(snap) {
    if (!snap) return;
    ui.w = snap.width || ui.w;
    ui.h = snap.height || ui.h;
    ui.fp = snap.fp || ui.fp;
    players = snap.players || players;
  }

  // ── the host tick loop ──────────────────────────────────────────────────────
  function startLoop() {
    if (running || !isHostLoop) return;
    running = true;
    lastTs = nowMs();
    acc = 0;
    const step = () => {
      if (!running) return;
      const t = nowMs();
      let frame = t - lastTs;
      lastTs = t;
      if (frame > 250) frame = 250; // clamp a huge gap (tab was hidden)
      acc += frame;
      let ticked = 0;
      while (acc >= TICK_MS && ticked < MAX_CATCHUP_TICKS) {
        acc -= TICK_MS;
        hostTickOnce();
        ticked++;
      }
      if (ticked === 0 && acc >= TICK_MS) acc = 0; // drained the catch-up budget
      rafId = requestFrame(step);
    };
    rafId = requestFrame(step);
  }

  function stopLoop() {
    running = false;
    if (rafId !== null) {
      cancelFrame(rafId);
      rafId = null;
    }
  }

  /** One authoritative tick: set inputs, advance, read, render, broadcast. */
  function hostTickOnce() {
    const host = CTX.host;
    if (!host) return;

    // p1 = the local arrows/space keys.
    const [t1, turn1, f1] = inputScalars(held[0]);
    host.callStatus('asteroids_input', null, [0, t1, turn1, f1]);

    // p2 (duel only) = the local hot-seat keyset OR the relayed guest input.
    if (players === 2) {
      const guest = CTX.params.sessionId ? relayedGuest : held[1];
      const [t2, turn2, f2] = inputScalars(guest);
      host.callStatus('asteroids_input', null, [1, t2, turn2, f2]);
    }

    host.callStatus('asteroids_tick');
    state = readEngine(host);
    CTX.GAME.render(state, CTX);

    // Broadcast the authoritative snapshot to any guest (no-op single-player).
    if (CTX.params.characterId !== null && CTX.client && state) {
      CTX.client.sendWorld(CTX.params.characterId, CTX.GAME.slug, state);
    }

    // Stop ticking once terminal (the engine freezes anyway; this saves CPU).
    if (state && isGameOver(state.status)) {
      stopLoop();
    }
  }

  const GAME = {
    slug: 'asteroids-duel',

    async init(host, container, ctx) {
      CTX = ctx;
      // Duel when there's a networked opponent (slot present) OR an explicit
      // ?players=2; otherwise single-ship survival. The launcher passes slot_index
      // so a 2-slot session is a duel.
      const qp = readQuery(ctx);
      players = qp.players || (ctx.params.slotIndex !== null && ctx.params.sessionId ? 2 : 1);
      players = players === 2 ? 2 : 1;
      // Seed: deterministic per session so host + guest simulate the same field.
      const seed = qp.seed >>> 0;
      isHostLoop = ctx.isHost;

      host.callStatus('asteroids_new', null, [seed | 0, 0, players]);
      buildDom(container, ui, ctx);
      state = readEngine(host);
      adoptDims(state);
      layout(ui);

      if (isHostLoop) {
        startLoop();
      }
      return state;
    },

    render(stateJson, ctx) {
      if (stateJson && typeof stateJson === 'object' && stateJson.type === 'asteroids.snapshot') {
        state = stateJson;
        adoptDims(state);
      }
      drawField(ui, state);
      drawHud(ui, state, players);
    },

    // Capture held keys. The TICK LOOP (not this) applies + relays them, so a
    // keydown/keyup just flips the held-state bit and returns the relay payload
    // (on change) for the guest→host path.
    onLocalInput(evt, ctx) {
      if (!evt || (evt.type !== 'keydown' && evt.type !== 'keyup')) return null;
      const map = KEYMAP[evt.key];
      if (!map) return null;
      const down = evt.type === 'keydown';

      // The guest drives its OWN ship locally as "ship 0" of the input message,
      // but on the host that maps to p2. For a networked guest, every key is a p1
      // arrow/space (its keyset); we relay it and the host folds it into p2.
      const targetShip = ctx.params.sessionId && !ctx.isHost ? 1 : map.ship;
      if (held[targetShip]) {
        if (held[targetShip][map.act] === down) return null; // no state change
        held[targetShip][map.act] = down;
      }

      // On the host (or single-player) the loop already consumes held[]. We still
      // relay the local p1 keys so the guest's host-copy view stays warm and an
      // observer can see inputs. A networked guest relays its keys for the host
      // to fold into p2.
      const relayShip = ctx.isHost ? 0 : 1;
      const payload = {
        ship: relayShip,
        keys: { ...held[targetShip] },
      };
      return {
        input: { kind: 'control', type: 'input', payload },
        // No `world` here: the host's tick loop owns the authoritative broadcast.
        state, // re-render immediately for snappy local feedback
      };
    },

    onRemoteEvent(channel, json, ctx) {
      if (!json) return null;

      if (channel === 'session-control') {
        if (json.action && String(json.action).includes('CLOSED')) {
          ui.banner = 'Session closed';
          stopLoop();
        }
        return state;
      }

      const payload = json.payload || json;

      // GUEST path: the host's authoritative `world` snapshot — adopt + render it.
      if (channel === 'world') {
        const snap = payload && payload.type === 'asteroids.snapshot' ? payload : null;
        if (snap) {
          state = snap;
          adoptDims(state);
          if (isGameOver(state.status)) stopLoop();
          return state;
        }
        return null;
      }

      // HOST path: a relayed guest `input` — fold it into the guest ship's keys so
      // the next host tick applies it. The host's loop does the apply + broadcast.
      if (channel === 'input') {
        const keys = payload && payload.keys;
        if (keys && typeof keys === 'object') {
          relayedGuest = {
            left: Boolean(keys.left),
            right: Boolean(keys.right),
            thrust: Boolean(keys.thrust),
            fire: Boolean(keys.fire),
          };
        }
        // No immediate re-render: the next authoritative tick reflects it.
        return null;
      }

      return null;
    },

    // Release game-owned listeners + stop the loop. Called by the frame on unload.
    destroy() {
      stopLoop();
      if (ui.onResize && typeof window !== 'undefined' &&
          typeof window.removeEventListener === 'function') {
        window.removeEventListener('resize', ui.onResize);
        ui.onResize = null;
      }
      if (ui._onKeyDown && typeof window !== 'undefined') {
        window.removeEventListener('keydown', ui._onKeyDown);
        window.removeEventListener('keyup', ui._onKeyUp);
      }
    },

    // ── test seams ────────────────────────────────────────────────────────────
    _ui: ui,
    _held: held,
    _getState: () => state,
    _players: () => players,
    // Drive exactly one host tick (the loop's body) — lets a headless test step
    // the simulation deterministically without a requestAnimationFrame.
    _tickOnce: () => hostTickOnce(),
    _startLoop: startLoop,
    _stopLoop: stopLoop,
  };

  return GAME;
}

// ── query params (seed / players / mode) ──────────────────────────────────────

/** Read optional ?seed= / ?players= from the iframe URL (defaults: seed 1, auto). */
function readQuery(ctx) {
  let seed = 1;
  let players = 0;
  try {
    const search =
      (ctx && ctx.params && ctx.params._search) ||
      (typeof window !== 'undefined' && window.location ? window.location.search : '');
    const qs = new URLSearchParams(search || '');
    const s = qs.get('seed');
    const p = qs.get('players');
    if (s !== null && s !== '' && !Number.isNaN(parseInt(s, 10))) seed = parseInt(s, 10) >>> 0;
    if (p === '2') players = 2;
    if (p === '1') players = 1;
  } catch (_e) {
    /* default seed */
  }
  return { seed, players };
}

// ── timing shims (window in browser, sane fallbacks in node) ──────────────────

function nowMs() {
  if (typeof performance !== 'undefined' && typeof performance.now === 'function') {
    return performance.now();
  }
  return Date.now();
}
function requestFrame(fn) {
  if (typeof requestAnimationFrame === 'function') return requestAnimationFrame(fn);
  return setTimeout(() => fn(nowMs()), TICK_MS);
}
function cancelFrame(id) {
  if (typeof cancelAnimationFrame === 'function') return cancelAnimationFrame(id);
  return clearTimeout(id);
}

// ── DOM / canvas plumbing ──────────────────────────────────────────────────────

function buildDom(container, ui, ctx) {
  const canvas = container.querySelector('#field') || makeCanvas(container);
  ui.canvas = canvas;
  ui.cx = canvas.getContext('2d');

  // HUD wiring (score / status) — built by index.html if present.
  const scoreEl = container.querySelector('#score');
  const statusEl = container.querySelector('#status');
  ui.hud = { score: scoreEl, status: statusEl };

  // Keyboard input → the host-authoritative loop. We listen on window so the
  // iframe captures arrows/space without a prior canvas click (the host shell
  // focuses the iframe on mount).
  if (typeof window !== 'undefined' && typeof window.addEventListener === 'function') {
    ui._onKeyDown = (e) => {
      if (KEYMAP[e.key]) {
        if (typeof e.preventDefault === 'function') e.preventDefault(); // stop page scroll
        if (ctx && typeof ctx.handleLocalInput === 'function') ctx.handleLocalInput(e);
      }
    };
    ui._onKeyUp = (e) => {
      if (KEYMAP[e.key]) {
        if (ctx && typeof ctx.handleLocalInput === 'function') ctx.handleLocalInput(e);
      }
    };
    window.addEventListener('keydown', ui._onKeyDown);
    window.addEventListener('keyup', ui._onKeyUp);

    ui.onResize = () => {
      layout(ui);
      ctx.GAME.render(ctx.GAME._getState(), ctx);
    };
    window.addEventListener('resize', ui.onResize);
    if (ctx && typeof ctx.onTeardown === 'function') {
      ctx.onTeardown(() => {
        if (ui.onResize) window.removeEventListener('resize', ui.onResize);
        if (ui._onKeyDown) {
          window.removeEventListener('keydown', ui._onKeyDown);
          window.removeEventListener('keyup', ui._onKeyUp);
        }
        ui.onResize = null;
      });
    }
  }
}

function makeCanvas(container) {
  const c = typeof document !== 'undefined' ? document.createElement('canvas') : null;
  if (c) {
    c.id = 'field';
    container.appendChild(c);
  }
  return c;
}

function layout(ui) {
  if (!ui.canvas) return;
  // Fit the 4:3 field into the viewport, leaving room for the HUD.
  let availW = 1024;
  let availH = 768;
  if (typeof window !== 'undefined' && window.innerWidth) {
    availW = Math.max(320, window.innerWidth - 24);
    availH = Math.max(240, (window.innerHeight || 768) - 80);
  }
  const scale = Math.min(availW / ui.w, availH / ui.h);
  const px = Math.max(1, scale);
  ui.scale = px;
  const cw = Math.round(ui.w * px);
  const ch = Math.round(ui.h * px);
  ui.canvas.width = cw;
  ui.canvas.height = ch;
  if (ui.canvas.style) {
    ui.canvas.style.width = cw + 'px';
    ui.canvas.style.height = ch + 'px';
  }
  // A deterministic decorative star-field (computed once, in world units).
  if (!ui.stars) {
    ui.stars = [];
    let s = 0x1234abcd;
    const rnd = () => {
      s ^= s << 13; s ^= s >>> 17; s ^= s << 5; s >>>= 0;
      return s / 0xffffffff;
    };
    for (let i = 0; i < 80; i++) {
      ui.stars.push({ x: rnd() * ui.w, y: rnd() * ui.h, r: 0.5 + rnd() * 1.2 });
    }
  }
}

// ── drawing (vector field: ships, asteroids, bullets) ──────────────────────────

function drawField(ui, state) {
  const cx = ui.cx;
  if (!cx) return;
  const scale = ui.scale;
  const fp = ui.fp || 256;
  const cw = ui.canvas.width;
  const ch = ui.canvas.height;

  // backdrop
  cx.fillStyle = COLORS.space;
  cx.fillRect(0, 0, cw, ch);

  // stars
  if (ui.stars) {
    cx.fillStyle = COLORS.star;
    for (const st of ui.stars) {
      cx.beginPath();
      cx.arc(st.x * scale, st.y * scale, st.r, 0, Math.PI * 2);
      cx.fill();
    }
  }

  if (!state) return;

  // asteroids — irregular polygons (8 verts) so they read as rocks, not circles.
  if (Array.isArray(state.asteroids)) {
    cx.strokeStyle = COLORS.asteroid;
    cx.lineWidth = Math.max(1, 1.5 * scale);
    for (const a of state.asteroids) {
      const wx = (a.x / fp) * scale;
      const wy = (a.y / fp) * scale;
      const r = asteroidRadius(a.size) * scale;
      cx.beginPath();
      for (let v = 0; v < 8; v++) {
        const ang = (v / 8) * Math.PI * 2;
        // a fixed per-vertex jitter (deterministic by vertex index) for a rocky look
        const jitter = 0.78 + ((v * 37) % 5) * 0.06;
        const px = wx + Math.cos(ang) * r * jitter;
        const py = wy + Math.sin(ang) * r * jitter;
        if (v === 0) cx.moveTo(px, py);
        else cx.lineTo(px, py);
      }
      cx.closePath();
      cx.stroke();
    }
  }

  // bullets — small dots.
  if (Array.isArray(state.bullets)) {
    cx.fillStyle = COLORS.bullet;
    for (const b of state.bullets) {
      const wx = (b.x / fp) * scale;
      const wy = (b.y / fp) * scale;
      cx.beginPath();
      cx.arc(wx, wy, Math.max(1.5, 2 * scale), 0, Math.PI * 2);
      cx.fill();
    }
  }

  // ships — triangles pointing along the heading. Heading is an integer step in
  // 0..256 (0 = +X), so the screen angle is heading/256 * 2π.
  if (Array.isArray(state.ships)) {
    state.ships.forEach((s, i) => {
      const wx = (s.x / fp) * scale;
      const wy = (s.y / fp) * scale;
      const ang = (s.heading / 256) * Math.PI * 2;
      const R = 12 * scale; // ship draw radius (collision radius is 8 world units)
      const colour = !s.alive ? COLORS.dead : (state.ships.length === 1 ? COLORS.ship : i === 0 ? COLORS.p1 : COLORS.p2);

      // nose + two tail corners
      const nose = [wx + Math.cos(ang) * R, wy + Math.sin(ang) * R];
      const tailL = [
        wx + Math.cos(ang + 2.5) * R,
        wy + Math.sin(ang + 2.5) * R,
      ];
      const tailR = [
        wx + Math.cos(ang - 2.5) * R,
        wy + Math.sin(ang - 2.5) * R,
      ];
      cx.strokeStyle = colour;
      cx.lineWidth = Math.max(1.5, 2 * scale);
      cx.beginPath();
      cx.moveTo(nose[0], nose[1]);
      cx.lineTo(tailL[0], tailL[1]);
      cx.lineTo(tailR[0], tailR[1]);
      cx.closePath();
      cx.stroke();

      // thrust flame when this ship is accelerating (velocity along heading > 0).
      // We can't see the held key on the guest, so approximate from velocity.
      if (s.alive) {
        const speed = Math.hypot(s.vx, s.vy);
        if (speed > 12) {
          const back = [wx - Math.cos(ang) * R * 1.4, wy - Math.sin(ang) * R * 1.4];
          cx.strokeStyle = COLORS.thrust;
          cx.beginPath();
          cx.moveTo((tailL[0] + tailR[0]) / 2, (tailL[1] + tailR[1]) / 2);
          cx.lineTo(back[0], back[1]);
          cx.stroke();
        }
      }
    });
  }
}

function drawHud(ui, state, players) {
  if (!ui.hud || !state) return;
  if (ui.hud.score) {
    if (state.ships && state.ships.length === 2) {
      ui.hud.score.textContent = `P1 ${state.ships[0].score} – ${state.ships[1].score} P2`;
    } else if (state.ships && state.ships.length === 1) {
      ui.hud.score.textContent = `Score ${state.ships[0].score}`;
    }
  }
  if (ui.hud.status) {
    const over = isGameOver(state.status);
    ui.hud.status.textContent = ui.banner ? ui.banner : statusLine(state.status, players);
    if (ui.hud.status.classList) ui.hud.status.classList.toggle('over', over);
  }
}

export default createAsteroidsGame;
