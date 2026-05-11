// Step definitions for features/multiplayer/window-controls.feature.
//
// All steps operate on the user-registered page (from auth-steps'
// `a separate browser context for "X"`). The host shell's game canvas
// (frontend/src/app/game-canvas/) renders the controls + paused
// overlay; these steps drive the user-visible buttons + read back
// observable state (data-testid attributes, document.fullscreenElement,
// captured postMessages).

import { expect, Page } from '@playwright/test';
import { When, Then } from '../support/fixtures';
import { pageRegistry } from './_registry';

function pageFor(username: string): Page {
  const page = pageRegistry.get(username);
  if (!page) throw new Error(`No page registered for "${username}".`);
  return page;
}

// ── Toolbar visibility ───────────────────────────────────────────────

Then('{string} sees the canvas toolbar with fullscreen, popout, and reload controls',
     async ({}, username: string) => {
  const page = pageFor(username);
  // The toolbar lives inside the GameCanvasComponent's wrapper. Some
  // routes mount via federated remotes that don't render this exact
  // toolbar — three-wizards / dodecahedron DO go through it for the
  // legacy iframe path. Wait for any of the three control buttons
  // (titles are stable + role=toolbar makes them findable).
  const tb = page.locator('[role="toolbar"][aria-label="Game window controls"]');
  await expect(tb).toBeVisible({ timeout: 30_000 });
  await expect(tb.getByTitle(/Reload game/)).toBeVisible();
  await expect(tb.getByTitle(/Open game in a new tab/)).toBeVisible();
  await expect(tb.getByTitle(/Open game in a separate window/)).toBeVisible();
  await expect(tb.getByTitle(/Enter fullscreen|Exit fullscreen/)).toBeVisible();
});

// ── Fullscreen ───────────────────────────────────────────────────────

When('{string} clicks the fullscreen control', async ({}, username: string) => {
  const page = pageFor(username);
  // Stub the Fullscreen API on the document — headless Chromium does
  // grant fullscreen in tests, but the wrapper.requestFullscreen()
  // call goes against the browser's user-gesture check anyway, so we
  // patch the API to record + resolve. Same trick the federated remote
  // tests already use for window.open.
  await page.evaluate(() => {
    const w = window as unknown as { __fsTarget?: Element | null };
    w.__fsTarget = null;
    const wrap = document.querySelector('.canvas-wrapper');
    if (!wrap) return;
    const origReq = (Element.prototype as unknown as { requestFullscreen?: () => Promise<void> }).requestFullscreen;
    (Element.prototype as unknown as { requestFullscreen: () => Promise<void> }).requestFullscreen = function () {
      (window as unknown as { __fsTarget: Element | null }).__fsTarget = this as Element;
      // Also reflect into the document so the component's
      // @HostListener('document:fullscreenchange') sees it. Browsers
      // would normally fire this on the FS transition; the stub does
      // it synchronously.
      Object.defineProperty(document, 'fullscreenElement', { value: this, configurable: true });
      document.dispatchEvent(new Event('fullscreenchange'));
      return Promise.resolve();
    };
    void origReq; // silence "assigned but unused" — we never restore
  });
  await page.getByRole('button', { name: /Fullscreen/ }).first().click();
});

Then("{string}'s canvas wrapper is the document's fullscreen element",
     async ({}, username: string) => {
  const page = pageFor(username);
  const matched = await page.evaluate(() => {
    const wrap = document.querySelector('.canvas-wrapper');
    return document.fullscreenElement === wrap;
  });
  expect(matched, 'document.fullscreenElement should be the .canvas-wrapper element').toBeTruthy();
});

// ── Open in new window + paused overlay ──────────────────────────────

When('{string} clicks the open-in-new-window control', async ({}, username: string) => {
  const page = pageFor(username);
  // Intercept window.open so the popup doesn't actually spawn (the
  // headless context would fight for focus). The component reads the
  // return value (truthy = popup created), so return a stub object
  // with the minimum surface its `if (handle) this.pauseGame(…)`
  // branch needs.
  await page.evaluate(() => {
    const w = window as unknown as { __openedUrls?: string[] };
    w.__openedUrls = [];
    window.open = ((url?: string | URL) => {
      const s = typeof url === 'string' ? url : url?.toString() ?? '';
      ((window as unknown as { __openedUrls: string[] }).__openedUrls).push(s);
      return { closed: false } as unknown as Window;
    }) as typeof window.open;
    // Track posted messages to the iframe so the SD_GAME_PAUSE
    // assertion below has something to read.
    const iframes = Array.from(document.querySelectorAll('iframe.game-frame'));
    for (const f of iframes) {
      const fr = f as HTMLIFrameElement;
      const win = fr.contentWindow as unknown as { __postedMessages?: unknown[] } | null;
      if (!win) continue;
      win.__postedMessages = [];
      const origPostMessage = (fr.contentWindow as Window).postMessage.bind(fr.contentWindow);
      (fr.contentWindow as unknown as { postMessage: typeof origPostMessage }).postMessage = ((msg: unknown, opts?: unknown) => {
        ((win as { __postedMessages: unknown[] }).__postedMessages).push(msg);
        return origPostMessage(msg as never, opts as never);
      }) as typeof origPostMessage;
    }
  });
  await page.getByRole('button', { name: /Open in new window/ }).first().click();
});

Then("{string}'s canvas shows the paused overlay", async ({}, username: string) => {
  const page = pageFor(username);
  await expect(page.locator('[data-testid="canvas-paused"]')).toBeVisible({ timeout: 5_000 });
});

Then('the iframe in {string}\'s window received an SD_GAME_PAUSE postMessage',
     async ({}, username: string) => {
  const page = pageFor(username);
  const seen = await page.evaluate(() => {
    const iframes = Array.from(document.querySelectorAll('iframe.game-frame'));
    for (const f of iframes) {
      const win = (f as HTMLIFrameElement).contentWindow as unknown as { __postedMessages?: Array<{ type?: string }> } | null;
      const list = win?.__postedMessages ?? [];
      if (list.some(m => m && m.type === 'SD_GAME_PAUSE')) return true;
    }
    return false;
  });
  expect(seen, 'iframe should have received an SD_GAME_PAUSE postMessage').toBeTruthy();
});

When('{string} clicks Resume on the paused overlay', async ({}, username: string) => {
  const page = pageFor(username);
  await page.locator('[data-testid="canvas-resume-btn"]').click();
});

Then("{string}'s canvas no longer shows the paused overlay", async ({}, username: string) => {
  const page = pageFor(username);
  await expect(page.locator('[data-testid="canvas-paused"]')).toHaveCount(0, { timeout: 5_000 });
});
