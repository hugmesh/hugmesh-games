// games/_shared/game-frame.js
//
// GameFrame — the ONE lifecycle file all 14 web-native HugMesh games share.
// A per-game `index.html` imports this, builds its GAME object, and calls
// `mountGame(GAME, { wasmUrl })`. Everything else — URL-param parsing, wasm
// boot, session wiring, the host-authoritative input→apply→render→relay loop,
// and the remote-event re-apply loop — lives here.
//
// HOST-AUTHORITATIVE FLOW (docs/multiplayer-chess-design.md):
//   • Local input → GAME.onLocalInput(evt) returns a control payload (or null).
//   • The frame applies it AUTHORITATIVELY via the wasm engine (GAME owns the
//     wasm call inside onLocalInput/render — the frame just sequences it), then
//     GAME.render(state) draws it, then the payload is RELAYED over the session.
//   • A remote event (input/world/session-control) → GAME.onRemoteEvent(channel,
//     json) re-applies it against the same wasm engine and the frame re-renders.
//   • No session_id ⇒ single-player hot-seat: the relay is a no-op, the loop is
//     purely local.
//
// THE GAME INTERFACE a per-game script implements:
//
//   const GAME = {
//     slug: 'chess',                       // catalog slug (used on the input relay)
//
//     // Boot: wasm is loaded, container is the iframe <body> mount point.
//     // Return the initial render state (or call host.call(...) to seed the engine).
//     async init(host, container, ctx) -> initialStateJson,
//
//     // Draw a state object into the container. Called after every apply.
//     render(stateJson, ctx) -> void,
//
//     // A DOM event the game captured (click/keydown/drag). Return a control
//     // payload to RELAY + the authoritative state to render, or null to ignore.
//     // Shape: { control?: {...}|null, input?: {...}|null, world?: {...}|null,
//     //          state: <stateJson> }  — `state` is what render() receives.
//     onLocalInput(evt, ctx) -> { control?, input?, world?, state } | null,
//
//     // A relayed event arrived for the guest (or host). Re-apply against wasm,
//     // return the new state to render (or null to ignore the event).
//     onRemoteEvent(channel, json, ctx) -> stateJson | null,
//   };
//
// `ctx` is the GameContext below: { host, client, params, isHost, container }.
//
// Vanilla ES module. No build step, no dependencies. Served as a static file.

import { WasmHost } from './wasm-host.js';
import { SessionClient } from './sse-host-client.js';

/**
 * Parse the iframe URL params the Explore finding confirmed:
 *   session_id, launch_uid, chapter_id, slot_index  (+ character_id used by the
 *   relay, present in game_bridge.gd). All optional; absence ⇒ single-player.
 * @param {string} [search] defaults to window.location.search
 */
export function parseFrameParams(search) {
  const qs = new URLSearchParams(
    search !== undefined ? search : (typeof window !== 'undefined' ? window.location.search : '')
  );
  const intOrNull = (k) => {
    const v = qs.get(k);
    if (v === null || v === '') return null;
    const n = parseInt(v, 10);
    return Number.isNaN(n) ? null : n;
  };
  return {
    sessionId: qs.get('session_id') || null,
    launchUid: qs.get('launch_uid') || null,
    chapterId: intOrNull('chapter_id'),
    slotIndex: intOrNull('slot_index'),
    characterId: intOrNull('character_id'),
  };
}

/**
 * Mount a GAME. Boots wasm + the session client, wires the host-authoritative
 * loop, and runs GAME.init/render. Returns a GameContext you can keep for
 * teardown (ctx.destroy()).
 *
 * @param {object} GAME the per-game interface (see file header)
 * @param {object} opts
 * @param {string} opts.wasmUrl URL to the game's `.wasm` engine
 * @param {HTMLElement} [opts.container] mount point (defaults to document.body)
 * @param {string} [opts.search] override URL search (tests)
 * @param {object} [opts.clientOpts] passed through to SessionClient
 */
export async function mountGame(GAME, opts) {
  if (!GAME || typeof GAME.init !== 'function' || typeof GAME.render !== 'function') {
    throw new Error('mountGame: GAME must implement at least init() and render()');
  }
  if (!opts || !opts.wasmUrl) {
    throw new Error('mountGame: opts.wasmUrl is required');
  }

  const params = parseFrameParams(opts.search);
  const container =
    opts.container || (typeof document !== 'undefined' ? document.body : null);

  // The HOST is the slot that CREATED the game; the launcher marks slot 0 as
  // host (slot_index === 0) — the guest re-validates. Single-player is "host".
  const isHost = params.slotIndex === null || params.slotIndex === 0;

  const host = new WasmHost(opts.wasmUrl);
  await host.load();

  const client = new SessionClient(params.sessionId, opts.clientOpts);

  /** Teardown registrations the frame owns, drained by destroy(). */
  const teardowns = [];
  let destroyed = false;

  /** @type {GameContext} */
  const ctx = {
    host,
    client,
    params,
    isHost,
    container,
    GAME,
    destroy() {
      if (destroyed) return; // idempotent — pagehide + an explicit call both fire
      destroyed = true;
      // Let the game release its own listeners (resize, etc.) first.
      if (typeof GAME.destroy === 'function') {
        try {
          GAME.destroy(ctx);
        } catch (_e) {
          /* a game teardown throwing must not block transport close */
        }
      }
      for (const off of teardowns.splice(0)) {
        try {
          off();
        } catch (_e) {
          /* best-effort */
        }
      }
      // Closes the EventSource / removes the window 'message' listener AND
      // posts SD_SSE_UNSUBSCRIBE so the host shell drops the per-session SSE
      // subscription and the server its subscriber.
      client.close();
    },
  };
  ctx.onTeardown = (off) => {
    if (typeof off === 'function') teardowns.push(off);
  };

  // Boot the game; seed initial render.
  const initialState = await GAME.init(host, container, ctx);
  if (initialState !== undefined && initialState !== null) {
    GAME.render(initialState, ctx);
  }

  // ── remote events → re-apply → render ──────────────────────────────────────
  // The guest re-applies host-authoritative `world` updates and re-validates;
  // the host applies relayed guest `input`. session-control pauses/closes.
  if (typeof GAME.onRemoteEvent === 'function') {
    for (const channel of ['input', 'world', 'movement', 'character', 'scene', 'session-control']) {
      client.on(channel, (json) => {
        const next = GAME.onRemoteEvent(channel, json, ctx);
        if (next !== undefined && next !== null) {
          GAME.render(next, ctx);
        }
      });
    }
  }
  client.connect();

  // ── local input → authoritative apply → render → relay ─────────────────────
  // The frame is transport-agnostic: GAME.onLocalInput already applied the move
  // to the wasm engine and returned both the new state to render AND the
  // payload(s) to relay. We render then relay so the local player sees an
  // instant, optimistic result while the network catches up.
  ctx.handleLocalInput = (evt) => {
    if (typeof GAME.onLocalInput !== 'function') return;
    const result = GAME.onLocalInput(evt, ctx);
    if (!result) return;
    if (result.state !== undefined && result.state !== null) {
      GAME.render(result.state, ctx);
    }
    const cid = params.characterId;
    if (cid === null) return; // single-player or no slot → nothing to relay
    if (result.control) {
      client.sendControl(cid, result.control);
    }
    if (result.input) {
      client.sendInput(cid, { gameSlug: GAME.slug, ...result.input });
    }
    if (result.world) {
      client.sendWorld(cid, GAME.slug, result.world);
    }
  };

  // ── teardown on iframe unload ───────────────────────────────────────────────
  // Without this the direct-transport EventSource, the bridge-transport
  // window 'message' listener, and the host shell's per-session SSE
  // subscription (+ the server's subscriber) all leak every time a game iframe
  // navigates away. pagehide is the reliable signal (fires on bfcache too);
  // beforeunload is a belt-and-suspenders fallback for older engines.
  if (typeof window !== 'undefined' && typeof window.addEventListener === 'function') {
    const onUnload = () => ctx.destroy();
    window.addEventListener('pagehide', onUnload);
    window.addEventListener('beforeunload', onUnload);
    ctx.onTeardown(() => {
      window.removeEventListener('pagehide', onUnload);
      window.removeEventListener('beforeunload', onUnload);
    });
  }

  return ctx;
}

/**
 * @typedef {object} GameContext
 * @property {WasmHost} host
 * @property {SessionClient} client
 * @property {object} params parsed URL params
 * @property {boolean} isHost is this slot the authoritative host?
 * @property {HTMLElement} container
 * @property {object} GAME
 * @property {(evt:any)=>void} handleLocalInput feed a captured DOM event here
 * @property {()=>void} destroy tear down the session client
 */

export { WasmHost, SessionClient };
