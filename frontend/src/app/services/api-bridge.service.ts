import { Injectable, NgZone, inject } from '@angular/core';
import { HttpClient, HttpHeaders, HttpResponse } from '@angular/common/http';
import { firstValueFrom, of } from 'rxjs';
import { catchError, switchMap } from 'rxjs/operators';
import { SessionService } from './session.service';

interface ApiRequestPayload {
  requestId: number;
  method:    string;
  path:      string;
  sessionId: string;
  body:      unknown;
}

interface ViewEgressPayload {
  slot: number;
  characterId: number;
  sessionId: string;
  x: number; y: number; z: number;
  yaw: number; pitch: number; fov: number;
}

/** Channels the SSE relay forwards into the iframe via SD_SSE_EVENT. */
const SSE_CHANNELS = ['movement', 'world', 'character', 'time', 'scene'] as const;
type SseChannel = typeof SSE_CHANNELS[number];

interface SseSubscribeRequest {
  /** Optional session id — when set, scopes the stream to per-session sinks. */
  sessionId?: string;
}

/**
 * Host-shell side of the Snapdragon bridge's API egress channel.
 *
 * <p>Listens for `SD_API_REQUEST` and `SD_VIEW_EGRESS` postMessages
 * from any Godot iframe, performs the matching HTTP call via the
 * host's {@link HttpClient} (so the auth interceptor + any future
 * shared headers apply uniformly), and posts the response back to the
 * originating iframe via {@code event.source.postMessage} as
 * `SD_API_RESPONSE`.
 *
 * <p>Why this exists:
 * <ul>
 *   <li>Single point of network egress for all Godot games — auth +
 *       session context attached consistently.</li>
 *   <li>Iframe code never needs to know about JWT or session ids —
 *       the host shell sets the headers based on
 *       {@code request.sessionId} and the active OAuth2 token.</li>
 *   <li>Mocking / replaying / testing all happens at this seam.</li>
 *   <li>No rogue `HTTPRequest` instances inside the WASM bundle making
 *       cross-origin calls outside the host shell's awareness.</li>
 * </ul>
 *
 * <p>Inject and call {@link start} once at host bootstrap — the
 * AppComponent already does this for the gamepad + WASM-console
 * forwarders.
 */
@Injectable({ providedIn: 'root' })
export class ApiBridgeService {
  private readonly zone = inject(NgZone);
  private readonly http = inject(HttpClient);
  private readonly session = inject(SessionService);
  private started = false;

  /**
   * One EventSource per origin Window. The iframe sends
   * `SD_SSE_SUBSCRIBE` once on boot; we open the EventSource and pipe
   * each typed event back via `SD_SSE_EVENT` so the iframe's Rust
   * BridgeGlue can dispatch it through HostBridge into typed signals.
   * Tearing down the EventSource is keyed by the source Window so
   * remounting an iframe (cave → field) closes the prior stream.
   */
  private readonly sseStreams = new WeakMap<Window, EventSource>();

  start(): void {
    if (this.started || typeof window === 'undefined') return;
    this.started = true;
    this.zone.runOutsideAngular(() => {
      window.addEventListener('message', this.onMessage);
    });
  }

  private readonly onMessage = (e: MessageEvent) => {
    const data = e.data as { type?: string; payload?: unknown } | null;
    if (!data) return;
    if (data.type === 'SD_API_REQUEST') {
      void this.handleApiRequest(e, data.payload as ApiRequestPayload);
      return;
    }
    if (data.type === 'SD_VIEW_EGRESS') {
      void this.handleViewEgress(data.payload as ViewEgressPayload);
      return;
    }
    if (data.type === 'SD_SSE_SUBSCRIBE') {
      this.handleSseSubscribe(e, (data.payload as SseSubscribeRequest) ?? {});
      return;
    }
    if (data.type === 'SD_SSE_UNSUBSCRIBE') {
      this.handleSseUnsubscribe(e);
      return;
    }
    if (data.type === 'SD_SESSION_EXPIRED') {
      this.handleSessionExpired();
      return;
    }
  };

  private handleSessionExpired(): void {
    this.zone.run(() => {
      this.session.connect().pipe(
        switchMap(r => r?.isController ? of(r) : this.session.claimControl()),
        catchError(() => of(null)),
      ).subscribe();
    });
  }

  private async handleApiRequest(e: MessageEvent, req: ApiRequestPayload): Promise<void> {
    if (!req || typeof req.requestId !== 'number' || typeof req.path !== 'string') return;
    let headers = new HttpHeaders({ Accept: 'application/json' });
    // X-Session-Id is what flips the BFF's ControlController into
    // multi-player mode (per-session SSE sink routing). The bridge
    // attaches it whenever the iframe is in a session; in single-
    // player mode the field is empty and the BFF takes the legacy
    // global-controller path.
    if (req.sessionId && req.sessionId !== '') {
      headers = headers.set('X-Session-Id', req.sessionId);
    }

    const method = (req.method || 'GET').toUpperCase();
    const body = req.body ?? null;

    try {
      const resp = await firstValueFrom(this.http.request<unknown>(method, req.path, {
        body: method === 'GET' || method === 'DELETE' ? undefined : body,
        headers,
        observe: 'response',
      }));
      this.respond(e, req.requestId, resp.status, resp.body ?? null);
    } catch (err: unknown) {
      const status = (err as { status?: number })?.status ?? 0;
      const body = (err as { error?: unknown })?.error ?? null;
      this.respond(e, req.requestId, status, body);
    }
  }

  /**
   * View samples are fire-and-forget — POST /api/view, ignore the
   * response. The bridge doesn't expose a request_id for view egress
   * because the BFF's view channel is best-effort by design.
   */
  private async handleViewEgress(payload: ViewEgressPayload): Promise<void> {
    if (!payload || !payload.sessionId) return;
    let headers = new HttpHeaders({ 'Content-Type': 'application/json' });
    headers = headers.set('X-Session-Id', payload.sessionId);
    try {
      await firstValueFrom(this.http.post('/api/view', payload, { headers }));
    } catch {
      // View samples are throttled at ~10 Hz; one failure is fine.
    }
  }

  private respond(e: MessageEvent, requestId: number, status: number, body: unknown): void {
    const target = e.source as Window | null;
    if (!target || typeof (target as Window).postMessage !== 'function') return;
    target.postMessage({
      type: 'SD_API_RESPONSE',
      payload: { requestId, status, body },
    }, '*');
  }

  /**
   * Open an EventSource on the iframe's behalf and pipe typed events
   * back as `SD_SSE_EVENT`. The iframe runs as a passive consumer —
   * Rust handles the data once it lands.
   *
   * One stream per iframe Window; remounting (chapter swap) replaces
   * the prior stream so we don't leak connections.
   */
  private handleSseSubscribe(e: MessageEvent, req: SseSubscribeRequest): void {
    const target = e.source as Window | null;
    if (!target) return;
    this.handleSseUnsubscribe(e);

    const url = req.sessionId
      ? `/api/game/stream?sessionId=${encodeURIComponent(req.sessionId)}`
      : '/api/game/stream';
    const source = new EventSource(url);
    for (const channel of SSE_CHANNELS) {
      source.addEventListener(channel, (ev: MessageEvent) => {
        let parsed: unknown = null;
        try {
          parsed = ev.data ? JSON.parse(ev.data as string) : null;
        } catch {
          parsed = ev.data;
        }
        target.postMessage({
          type: 'SD_SSE_EVENT',
          payload: { type: channel, payload: parsed },
        }, '*');
      });
    }
    source.onerror = () => {
      // EventSource auto-reconnects; only log so a transient blip
      // doesn't drown the console.
      // eslint-disable-next-line no-console
      console.warn('[api-bridge] SSE stream error — auto-reconnecting');
    };
    this.sseStreams.set(target, source);
  }

  private handleSseUnsubscribe(e: MessageEvent): void {
    const target = e.source as Window | null;
    if (!target) return;
    const existing = this.sseStreams.get(target);
    if (existing) {
      existing.close();
      this.sseStreams.delete(target);
    }
  }
}
