import { Page } from '@playwright/test';

/**
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
 *  since the last `drain` (the in-iframe array is reset). */
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
      // eslint-disable-next-line @typescript-eslint/no-explicit-any
      if (!w || !(w as any).__captureSseEvents) continue;
      // eslint-disable-next-line @typescript-eslint/no-explicit-any
      const buf = (w as any).__captureSseEvents as Array<{ type: string; payload: unknown; at: number }>;
      out.push(...buf);
      // eslint-disable-next-line @typescript-eslint/no-explicit-any
      (w as any).__captureSseEvents = [];
    }
    return out;
  });
}
