// games/_shared/sse-host-client.js
//
// SessionClient — the transport seam every web-native Snapdragon game frontend
// reuses. It connects a game iframe to the BFF's per-session SSE relay and
// sends controls/inputs back, OR degrades to pure-local single-player when
// there is no session.
//
// HOST-AUTHORITATIVE MODEL (docs/multiplayer-chess-design.md):
//   The HOST browser runs the wasm engine and is authoritative. The server is
//   a DUMB RELAY (GameStateService.reemitToSession) — it never interprets a
//   move. The guest sends its intended input on the `input` channel; the host
//   applies it in wasm and emits the authoritative world on the `world`
//   channel; the guest re-applies + re-validates. The same channel names the
//   BFF fans out (GameStreamController) are used here.
//
// TWO TRANSPORTS, auto-selected:
//   1. Parent-bridge (default when embedded in the Angular host shell). The
//      host shell's ApiBridgeService owns ONE EventSource per iframe; the
//      iframe asks for it by posting `SD_SSE_SUBSCRIBE` and receives every
//      typed event back as `SD_SSE_EVENT` ({type, payload:{type:<channel>,
//      payload:<json>}}). Controls go out as `SD_API_REQUEST` so the host
//      attaches the JWT + X-Session-Id uniformly. This matches the existing
//      snapdragon-shim.js / game_bridge.gd transport exactly.
//   2. Direct (fallback when opened standalone, i.e. window.parent === window,
//      e.g. a local dev page or a test). The client opens its own
//      EventSource('/api/game/stream?sessionId=…') and POSTs controls itself.
//
// SINGLE-PLAYER / HOT-SEAT: no session_id ⇒ no network at all. on(...) handlers
// simply never fire from the network; the game loop drives both sides locally.
//
// Vanilla ES module. No build step, no dependencies. Served as a static file.

/** The per-session SSE channels the BFF relays (GameStreamController.stream). */
export const CHANNELS = Object.freeze([
  'movement',
  'world',
  'character',
  'time',
  'scene',
  'input',
  'audio',
  'wasm-console',
  'session-control',
]);

/** session-control actions the BFF emits (mirror game_bridge.gd._on_session_control). */
export const SESSION_CONTROL = Object.freeze({
  PAUSED: 'SESSION_PAUSED',
  RESUMED: 'SESSION_RESUMED',
  CLOSED: 'SESSION_CLOSED',
  PLAYER_PAUSED: 'PLAYER_PAUSED',
  PLAYER_RESUMED: 'PLAYER_RESUMED',
});

export class SessionClient {
  /**
   * @param {string|null} sessionId multiplayer session id from the iframe URL
   *        (`?session_id=`). null/empty ⇒ single-player, no network.
   * @param {object} [opts]
   * @param {Window}  [opts.parent] parent window for the bridge transport
   *        (defaults to window.parent). When it equals the current window the
   *        client uses the DIRECT transport instead.
   * @param {string}  [opts.apiBase] base URL for direct HTTP (defaults to '').
   * @param {function():(string|null)} [opts.tokenProvider] returns a bearer
   *        token for the DIRECT transport (the bridge transport gets the token
   *        from the host shell's interceptor, so this is only needed standalone).
   */
  constructor(sessionId, opts = {}) {
    this.sessionId = sessionId && String(sessionId).length ? String(sessionId) : null;
    this.multiplayer = this.sessionId !== null;
    this._parent = opts.parent || (typeof window !== 'undefined' ? window.parent : null);
    this._apiBase = opts.apiBase || '';
    this._tokenProvider = opts.tokenProvider || (() => null);

    // Embedded under the host shell iff there is a DISTINCT parent window.
    this._embedded =
      typeof window !== 'undefined' && this._parent && this._parent !== window;

    /** @type {Map<string, Set<Function>>} channel → handlers */
    this._handlers = new Map();
    /** @type {EventSource|null} direct-transport stream */
    this._source = null;
    this._reqId = 0;
    this._messageListener = null;
    this._connected = false;
  }

  /**
   * Register a handler for a relay channel. Channels are the SSE event names in
   * CHANNELS (`input`, `world`, `session-control`, …). Returns an unsubscribe fn.
   * @param {string} channel
   * @param {(payload:any)=>void} handler
   */
  on(channel, handler) {
    let set = this._handlers.get(channel);
    if (!set) {
      set = new Set();
      this._handlers.set(channel, set);
    }
    set.add(handler);
    return () => set.delete(handler);
  }

  _emit(channel, payload) {
    const set = this._handlers.get(channel);
    if (!set) return;
    for (const h of set) {
      try {
        h(payload);
      } catch (e) {
        // A handler throwing must not kill the stream pump.
        // eslint-disable-next-line no-console
        console.error(`[SessionClient] handler for '${channel}' threw`, e);
      }
    }
  }

  /**
   * Open the relay. No-op (returns immediately) in single-player. Picks the
   * bridge or direct transport based on embedding. Idempotent.
   */
  connect() {
    if (!this.multiplayer || this._connected) return;
    this._connected = true;
    if (this._embedded) {
      this._connectViaBridge();
    } else {
      this._connectDirect();
    }
  }

  // ── transport 1: parent host-shell bridge ──────────────────────────────────

  _connectViaBridge() {
    this._messageListener = (e) => {
      const data = e && e.data;
      if (!data || typeof data !== 'object') return;
      if (data.type === 'SD_SSE_EVENT' && data.payload) {
        // Envelope: { type:'SD_SSE_EVENT', payload:{ type:<channel>, payload:<json> } }
        this._emit(data.payload.type, data.payload.payload);
      } else if (data.type === 'SD_GAME_PAUSE') {
        this._emit('session-control', { action: SESSION_CONTROL.PLAYER_PAUSED });
      } else if (data.type === 'SD_GAME_RESUME') {
        this._emit('session-control', { action: SESSION_CONTROL.PLAYER_RESUMED });
      } else if (data.type === 'SD_SESSION_EXPIRED') {
        this._emit('session-control', { action: SESSION_CONTROL.CLOSED, reason: 'expired' });
      }
    };
    window.addEventListener('message', this._messageListener);
    // Ask the host shell to open the EventSource for this session and pipe
    // events back. Mirrors game_bridge.gd._request_sse_subscription.
    this._postParent({ type: 'SD_SSE_SUBSCRIBE', payload: { sessionId: this.sessionId } });
  }

  _postParent(msg) {
    try {
      this._parent.postMessage(msg, '*');
    } catch (_e) {
      /* parent gone — drop silently */
    }
  }

  // ── transport 2: direct EventSource (standalone / test) ────────────────────

  _connectDirect() {
    if (typeof EventSource === 'undefined') return;
    const url = `${this._apiBase}/api/game/stream?sessionId=${encodeURIComponent(this.sessionId)}`;
    const source = new EventSource(url);
    for (const channel of CHANNELS) {
      source.addEventListener(channel, (ev) => {
        let parsed = null;
        try {
          parsed = ev.data ? JSON.parse(ev.data) : null;
        } catch (_e) {
          parsed = ev.data;
        }
        this._emit(channel, parsed);
      });
    }
    source.onerror = () => {
      // EventSource auto-reconnects; only log so a transient blip is quiet.
      // eslint-disable-next-line no-console
      console.warn('[SessionClient] SSE error — auto-reconnecting');
    };
    this._source = source;
  }

  // ── outbound ───────────────────────────────────────────────────────────────

  /**
   * Send a CONTROL event (the JRPG-shaped verb path: MOVE_FORWARD, JUMP,
   * CAST_INSTANT_1, …) to `POST /api/controls/{characterId}` scoped by
   * `X-Session-Id`. In single-player this is a no-op (the local engine already
   * applied it). Returns true if anything was sent.
   *
   * @param {number|string} characterId the slot's character id
   * @param {{action:string,pressed?:boolean,timestamp?:number,chapterId?:number}} payload
   */
  sendControl(characterId, payload) {
    if (!this.multiplayer) return false;
    const body = {
      action: payload.action,
      pressed: payload.pressed !== undefined ? payload.pressed : true,
      timestamp: payload.timestamp || Date.now(),
    };
    if (payload.chapterId !== undefined && payload.chapterId !== null) {
      body.chapterId = payload.chapterId;
    }
    this._send(`/api/controls/${characterId}`, body);
    return true;
  }

  /**
   * Send a generalised INPUT event to `POST /api/input/{characterId}`. This is
   * the host-authoritative RELAY path the chess design uses: the guest emits
   * `{kind:'control', type:'move', payload:{uci}}` on the `input` channel; the
   * BFF reemits it to the session and the host's wasm engine applies it. The
   * host then emits the authoritative board back on `world` (see
   * sendWorld()). No-op in single-player.
   *
   * @param {number|string} characterId
   * @param {{gameSlug:string,kind:string,type:string,payload:object}} ev
   */
  sendInput(characterId, ev) {
    if (!this.multiplayer) return false;
    const body = {
      gameSlug: ev.gameSlug,
      sessionId: this.sessionId,
      kind: ev.kind,
      type: ev.type,
      payload: ev.payload || {},
    };
    this._send(`/api/input/${characterId}`, body);
    return true;
  }

  /**
   * HOST authoritative world emit. The host browser, after applying a move in
   * its wasm engine, broadcasts the new world `{fen,san,status,lastMove,...}`
   * for the guest to re-apply + re-validate. It rides the same `input` ingestion
   * endpoint with `kind:'world'` so the BFF reemits it on the session's `world`
   * channel (no server chess logic — pure relay). No-op in single-player.
   *
   * @param {number|string} characterId the host's character id
   * @param {string} gameSlug
   * @param {object} world the authoritative state to broadcast
   */
  sendWorld(characterId, gameSlug, world) {
    return this.sendInput(characterId, {
      gameSlug,
      kind: 'world',
      type: 'state',
      payload: world,
    });
  }

  /**
   * POST a body either through the host-shell bridge (so the JWT + X-Session-Id
   * are attached uniformly by ApiBridgeService) or directly (standalone).
   */
  _send(path, body) {
    if (this._embedded) {
      this._postParent({
        type: 'SD_API_REQUEST',
        payload: {
          requestId: ++this._reqId,
          method: 'POST',
          path,
          sessionId: this.sessionId,
          body,
        },
      });
      return;
    }
    // Direct transport: attach the bearer + session header ourselves.
    const headers = { 'Content-Type': 'application/json' };
    const token = this._tokenProvider();
    if (token) headers['Authorization'] = `Bearer ${token}`;
    if (this.sessionId) headers['X-Session-Id'] = this.sessionId;
    if (typeof fetch === 'function') {
      fetch(`${this._apiBase}${path}`, {
        method: 'POST',
        headers,
        body: JSON.stringify(body),
      }).catch(() => {
        /* best-effort — the relay tolerates dropped sends */
      });
    }
  }

  /** Tear down the transport. Safe to call multiple times. */
  close() {
    if (this._source) {
      this._source.close();
      this._source = null;
    }
    if (this._messageListener) {
      window.removeEventListener('message', this._messageListener);
      this._postParent({ type: 'SD_SSE_UNSUBSCRIBE', payload: { sessionId: this.sessionId } });
      this._messageListener = null;
    }
    this._connected = false;
  }
}

export default SessionClient;
