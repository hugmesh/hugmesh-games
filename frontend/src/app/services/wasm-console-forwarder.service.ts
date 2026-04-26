import { Injectable, NgZone, inject } from '@angular/core';

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
  private started = false;

  start(): void {
    if (this.started || typeof window === 'undefined') return;
    this.started = true;
    // The listener stays OUTSIDE Angular's NgZone — incoming WASM
    // crashes shouldn't trigger change detection on every line.
    this.zone.runOutsideAngular(() => {
      window.addEventListener('message', this.onMessage);
    });
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
    // Forward to BFF — fire-and-forget; never block the UI.
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
