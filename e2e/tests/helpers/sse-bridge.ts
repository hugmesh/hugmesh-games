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
      if (d.type === 'SD_SSE_EVENT' || d.type === 'SD_API_RESPONSE' || d.type === 'SD_SHOP_EVENT' || d.type === 'SD_DIALOGUE_EVENT') {
        // SD_SHOP_EVENT / SD_DIALOGUE_EVENT are posted FLAT by the
        // generated game_bridge ({type, action, item, ...} /
        // {type, npc, portraitLoaded}) — there is no nested payload.
        // Spread the whole message so those fields survive capture;
        // envelope messages (SD_SSE_EVENT) keep their payload key.
        window.__captureSseEvents.push(Object.assign({ at: Date.now(), payload: d.payload }, d));
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
  // Wait for the iframe to attach. The federated wizards + dodecahedron
  // remotes mount `iframe.wasm-frame`; maker-built games ('rpg'/'vn'
  // scene types) take the host game-canvas legacy path whose iframe is
  // `iframe.game-frame` — both must be covered or maker-game specs
  // silently drain zero events forever.
  const iframeEl = page.locator('iframe.wasm-frame, iframe.game-frame, app-three-wizards-remote iframe, app-dodecahedron-remote iframe').first();
  await iframeEl.waitFor({ state: 'attached', timeout: 30_000 });
  // Inject the listener inside the iframe's content window. We wrap
  // in setTimeout(..., 0) so the script runs after the iframe's own
  // onload — Godot's bootstrap is async and can race the postMessage
  // listener registration if we attach too eagerly.
  await page.evaluate(() => {
    const frames = Array.from(document.querySelectorAll('iframe.wasm-frame, iframe.game-frame'));
    for (const f of frames) {
      const fr = f as HTMLIFrameElement;
      const w = fr.contentWindow;
      if (!w) continue;
      // A frame that is (transiently) cross-origin — e.g. mid-navigation —
      // throws on ANY property access. Skip it instead of failing the
      // whole install; the context-level installSseCapture init-script
      // still covers such frames from the inside.
      try {
        void (w as unknown as { __captureSseInstalled?: boolean }).__captureSseInstalled;
      } catch {
        continue;
      }
      // eslint-disable-next-line @typescript-eslint/no-explicit-any
      if ((w as any).__captureSseInstalled) continue;
      // eslint-disable-next-line @typescript-eslint/no-explicit-any
      (w as any).__captureSseInstalled = true;
      // eslint-disable-next-line @typescript-eslint/no-explicit-any
      (w as any).__captureSseEvents = [];
      w.addEventListener('message', (ev: MessageEvent) => {
        const d = ev.data as { type?: string; payload?: unknown } | null;
        if (!d) return;
        if (d.type === 'SD_SSE_EVENT' || d.type === 'SD_API_RESPONSE' || d.type === 'SD_SHOP_EVENT' || d.type === 'SD_DIALOGUE_EVENT') {
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
 *  raced with the wrapper's first postMessage. Also drains the TOP
 *  window's own buffer: SD_SHOP_EVENT is posted by the game to its
 *  PARENT, so it lands on the page window, not the iframe's. */
export async function drainCapturedEvents(page: Page): Promise<Array<{
  type: string;
  payload: unknown;
  at: number;
  /** Flat fields of parent-posted messages (SD_SHOP_EVENT /
   *  SD_DIALOGUE_EVENT are flat objects, not envelopes) — e.g.
   *  `action`, `item`, `npc`, `portraitLoaded`. */
  [flat: string]: unknown;
}>> {
  return await page.evaluate(() => {
    const frames = Array.from(document.querySelectorAll('iframe.wasm-frame, iframe.game-frame')) as HTMLIFrameElement[];
    const out: Array<{ type: string; payload: unknown; at: number }> = [];
    // Top-level window buffer (context init-script installs it on the
    // page too). Messages targeted at the parent — e.g. SD_SHOP_EVENT
    // and SD_DIALOGUE_EVENT — only ever appear here.
    const topAny = window as unknown as Record<string, unknown>;
    if (Array.isArray(topAny.__captureSseEvents)) {
      const buf = topAny.__captureSseEvents as Array<{ type: string; payload: unknown; at: number }>;
      out.push(...buf);
      topAny.__captureSseEvents = [];
    }
    for (const f of frames) {
      const w = f.contentWindow;
      if (!w) continue;
      const wAny = w as unknown as Record<string, unknown>;
      // Skip frames that are (transiently) cross-origin — property
      // access on such a window throws and would abort the whole drain.
      try {
        void wAny.__captureSseEvents;
      } catch {
        continue;
      }
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
