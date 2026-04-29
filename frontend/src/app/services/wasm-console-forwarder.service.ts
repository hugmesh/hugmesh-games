import { Injectable, NgZone, inject } from '@angular/core';
import { AuthService } from './auth.service';

/**
 * Forwards `SD_WASM_CONSOLE` postMessage payloads from the WASM
 * iframe up to the BFF's diagnostics endpoint, AND mirrors them to
 * the host's own console with a `[wasm]` prefix so a developer
 * watching DevTools sees crashes from the game without having to
 * dive into the iframe context.
 *
 * <p>The wasm-fragment Godot bundle's index.html (see
 * wasm-fragment/nginx.conf + the patched index.html) installs a tiny
 * shim that wraps:
 *   <ul>
 *     <li>{@code console.error}</li>
 *     <li>{@code console.warn}</li>
 *     <li>{@code window.onerror}</li>
 *     <li>{@code window.addEventListener('unhandledrejection')}</li>
 *   </ul>
 * and posts each captured message to its parent window with
 *   {@code postMessage({ type: 'SD_WASM_CONSOLE', level, message,
 *                        source, line, column, stack, gameSlug, chapterSlug }, '*')}.
 *
 * <p>This service listens for that envelope, mirrors locally, and
 * fires a fire-and-forget POST to /api/diagnostics/wasm-console so
 * the line lands on the SSE wasm-console channel for the admin
 * devtools view.
 *
 * <p>Inject and call {@link start} once at host bootstrap.
 */
@Injectable({ providedIn: 'root' })
export class WasmConsoleForwarderService {
  private readonly zone = inject(NgZone);
  private readonly auth = inject(AuthService);
  private started = false;
  /**
   * Whether the BFF's wasm-console capture is currently enabled. The
   * forwarder still mirrors locally regardless (helpful for any
   * authenticated dev), but the POST to the BFF is suppressed when
   * either:
   *   • the caller isn't admin, OR
   *   • the BFF's wasm_console_debug_enabled flag is false.
   *
   * Refreshed on start() and on every {@link refreshDebugFlag} call;
   * the admin UI can call refreshDebugFlag after toggling the gate.
   */
  private debugEnabled = false;

  start(): void {
    if (this.started || typeof window === 'undefined') return;
    this.started = true;
    // The listener stays OUTSIDE Angular's NgZone — incoming WASM
    // crashes shouldn't trigger change detection on every line.
    this.zone.runOutsideAngular(() => {
      window.addEventListener('message', this.onMessage);
    });
    this.refreshDebugFlag();
  }

  /**
   * Re-fetch the debug flag from the BFF. The endpoint is admin-only,
   * so non-admin callers always end up with debugEnabled=false (which
   * suppresses the POST). The admin UI calls this after flipping the
   * toggle so the local mirror catches up immediately.
   */
  refreshDebugFlag(): void {
    if (!this.auth.isAdmin()) {
      this.debugEnabled = false;
      return;
    }
    fetch('/api/admin/debug-mode', { credentials: 'same-origin' })
      .then(r => r.ok ? r.json() : Promise.reject(r))
      .then(d => { this.debugEnabled = !!d?.wasmConsoleEnabled; })
      .catch(() => { this.debugEnabled = false; });
  }

  private readonly onMessage = (e: MessageEvent) => {
    const data = e.data;
    if (!data || data.type !== 'SD_WASM_CONSOLE') return;
    const { level, message, source, line, column, stack,
            gameSlug, chapterSlug } = data;
    // Mirror to host console so devtools shows the line.
    const tag = `[wasm:${level || 'log'}]${gameSlug ? ' ' + gameSlug : ''}`;
    const args = [tag, message, source, line, column, stack].filter(x => x !== undefined);
    switch (level) {
      case 'error': console.error(...args); break;
      case 'warn':  console.warn(...args);  break;
      case 'info':  console.info(...args);  break;
      default:      console.log(...args);
    }
    // Forward to BFF — fire-and-forget; never block the UI. Suppressed
    // when the caller isn't admin OR the BFF debug-mode flag is off:
    // the BFF would refuse the POST with 403 anyway, so we save the
    // round-trip and avoid noise in DevTools' Network panel for
    // non-admin players.
    if (!this.debugEnabled) return;
    fetch('/api/diagnostics/wasm-console', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({
        level: typeof level === 'string' ? level : 'log',
        gameSlug: gameSlug ?? null,
        chapterSlug: chapterSlug ?? null,
        message: typeof message === 'string' ? message : String(message ?? ''),
        source: typeof source === 'string' ? source : null,
        line:   typeof line   === 'number' ? line   : null,
        column: typeof column === 'number' ? column : null,
        stack:  typeof stack  === 'string' ? stack  : null,
      }),
      keepalive: true,
    }).catch(() => {/* swallow */});
  };
}
