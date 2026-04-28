import { BrowserContext, Page } from '@playwright/test';

/**
 * Init script that every page + iframe gets BEFORE its own scripts
 * run. Installing the capture listener at init-time eliminates the
 * race where the wrapper's first SD_SSE_EVENT lands before our
 * page.evaluate-based capture has a chance to attach.
 *
 * The capture is read-only — both this listener AND BridgeGlue's
 * own listener push to their respective queues. BridgeGlue's
 * _twSseEventQueue gets drained by Rust per frame; our
 * __captureSseEvents only resets when the spec calls
 * drainCapturedEvents.
 */
const CAPTURE_INIT_SCRIPT = `
(function() {
  try {
    if (window.__captureSseInstalled) return;
    window.__captureSseInstalled = true;
    window.__captureSseEvents = [];
    window.addEventListener('message', function(ev) {
      var d = ev && ev.data;
      if (!d) return;
      if (d.type === 'SD_SSE_EVENT' || d.type === 'SD_API_RESPONSE') {
        window.__captureSseEvents.push({ at: Date.now(), type: d.type, payload: d.payload });
      }
    });
  } catch (e) { /* defensive */ }
})();
`;

/**
 * Install the capture init-script on every page + frame in the
 * given context. Call this once per BrowserContext (right after
 * creation) so every iframe that mounts later gets the listener
 * before its own scripts run.
 */
export async function installSseCapture(context: BrowserContext): Promise<void> {
  await context.addInitScript({ content: CAPTURE_INIT_SCRIPT });
}

/**
 * Legacy entry point. Kept for back-compat; prefer
 * {@link installSseCapture} on the BrowserContext.
 *
 * Bridge into the iframe's window scope so the spec can capture the
 * SSE events the wasm-side consumes. The Angular wrapper postMessages
 * each SSE event into the iframe as `SD_SSE_EVENT`; we install a
 * matching `window.message` listener inside the iframe and bucket the
 * payloads on a global the spec can poll.
 *
 * The capture is read-only — the iframe still drains the events
 * normally via `BridgeGlue::drain_sse_events`, so movement / world
 * updates still reach the Godot scene unchanged.
 */
export async function captureIframeSseEvents(page: Page): Promise<void> {
  // Wait for the iframe to attach. Both wizards + dodecahedron mount
  // a tagged element that wraps it.
  const iframeEl = page.locator('iframe.wasm-frame, app-three-wizards-remote iframe, app-dodecahedron-remote iframe').first();
  await iframeEl.waitFor({ state: 'attached', timeout: 30_000 });
  // Inject the listener inside the iframe's content window. We wrap
  // in setTimeout(..., 0) so the script runs after the iframe's own
  // onload — Godot's bootstrap is async and can race the postMessage
  // listener registration if we attach too eagerly.
  await page.evaluate(() => {
    const frames = Array.from(document.querySelectorAll('iframe.wasm-frame'));
    for (const f of frames) {
      const fr = f as HTMLIFrameElement;
      const w = fr.contentWindow;
      if (!w) continue;
      // eslint-disable-next-line @typescript-eslint/no-explicit-any
      if ((w as any).__captureSseInstalled) continue;
      // eslint-disable-next-line @typescript-eslint/no-explicit-any
      (w as any).__captureSseInstalled = true;
      // eslint-disable-next-line @typescript-eslint/no-explicit-any
      (w as any).__captureSseEvents = [];
      w.addEventListener('message', (ev: MessageEvent) => {
        const d = ev.data as { type?: string; payload?: unknown } | null;
        if (!d) return;
        if (d.type === 'SD_SSE_EVENT' || d.type === 'SD_API_RESPONSE') {
          // eslint-disable-next-line @typescript-eslint/no-explicit-any
          (w as any).__captureSseEvents.push({ at: Date.now(), ...d });
        }
      });
    }
  });
}

/** Drain the captured events. Returns every postMessage we've seen
 *  since the last `drain` (the in-iframe array is reset). Falls back
 *  to draining the BridgeGlue's own JS-side queue when our capture
 *  listener missed the message — useful when the capture install
 *  raced with the wrapper's first postMessage. */
export async function drainCapturedEvents(page: Page): Promise<Array<{
  type: string;
  payload: unknown;
  at: number;
}>> {
  return await page.evaluate(() => {
    const frames = Array.from(document.querySelectorAll('iframe.wasm-frame')) as HTMLIFrameElement[];
    const out: Array<{ type: string; payload: unknown; at: number }> = [];
    for (const f of frames) {
      const w = f.contentWindow;
      if (!w) continue;
      const wAny = w as unknown as Record<string, unknown>;
      // Primary path: our own capture listener.
      if (Array.isArray(wAny.__captureSseEvents)) {
        const buf = wAny.__captureSseEvents as Array<{ type: string; payload: unknown; at: number }>;
        out.push(...buf);
        wAny.__captureSseEvents = [];
      }
      // Fallback: BridgeGlue's own queue. When the wrapper postMessages
      // SD_SSE_EVENT, both our capture listener AND the BridgeGlue's
      // listener push to their respective queues. Read the BridgeGlue
      // queue too so we still see deliveries our capture missed.
      if (Array.isArray(wAny._twSseEventQueue)) {
        const queue = wAny._twSseEventQueue as Array<{ type?: string; payload?: unknown }>;
        for (const e of queue) {
          if (e && typeof e === 'object') {
            out.push({ type: 'SD_SSE_EVENT', payload: e, at: Date.now() });
          }
        }
        wAny._twSseEventQueue = [];
      }
    }
    return out;
  });
}
