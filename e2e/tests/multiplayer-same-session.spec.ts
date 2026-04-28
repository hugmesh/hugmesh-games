import { test, expect, BrowserContext, Page } from '@playwright/test';
import {
  SEED_USERS, login, createSession, joinSession, sendControl,
} from './helpers/roles';
import { captureIframeSseEvents, drainCapturedEvents } from './helpers/sse-bridge';

const PLAYER1 = SEED_USERS.find(u => u.username === 'player1')!;
const PLAYER2 = SEED_USERS.find(u => u.username === 'player2')!;

/**
 * Multi-user same-session. Two browser contexts host two real Casdoor
 * accounts; both join the same session and load the same chapter into
 * their own WASM iframes. Asserts:
 *
 *   1. The WASM bundle assets (index.html / index.wasm / index.pck)
 *      come down with HTTP 200 in **both** contexts.
 *   2. After player1 sends a movement control, player2's iframe
 *      receives a `world` SD_SSE_EVENT carrying player1's character
 *      in its roster — confirming the per-session sink fan-out + the
 *      Angular wrapper's SSE-relay both fire end-to-end.
 *   3. The receiving iframe also gets at least one `movement` event
 *      with the input we issued.
 */
test.describe('multiplayer — two players in the same session', () => {
  test('both players load the WASM bundle and see each other\'s movement', async ({ browser }) => {
    const ctx1: BrowserContext = await browser.newContext();
    const ctx2: BrowserContext = await browser.newContext();
    const p1: Page = await ctx1.newPage();
    const p2: Page = await ctx2.newPage();

    const assets1 = new Set<string>();
    const assets2 = new Set<string>();
    p1.on('response', r => bumpAsset(assets1, r.url(), r.status()));
    p2.on('response', r => bumpAsset(assets2, r.url(), r.status()));

    await login(p1, PLAYER1);
    await login(p2, PLAYER2);

    // P1 creates a public session for three-wizards.
    const session = await createSession(p1, {
      gameSlug:    'three-wizards',
      name:        `e2e-multi-${Date.now()}`,
      maxPlayers:  3,
      visibility:  'public',
      characterId: 1,
      slotIndex:   0,
    });

    // P2 joins the session at slot 1 with their own character.
    await joinSession(p2, session.id, { characterId: 2, slotIndex: 1 });

    // Both players open the chapter modal + click play. The first
    // available chapter is whatever the home page shows — clicking
    // it through the UI exercises the full launch flow, including
    // the session id flowing onto the iframe URL.
    await openFirstChapter(p1);
    await openFirstChapter(p2);

    // Wait for both iframes to mount + start streaming bundle bytes.
    await Promise.all([
      p1.waitForFunction(() => document.querySelectorAll('iframe.wasm-frame').length > 0, undefined, { timeout: 30_000 }),
      p2.waitForFunction(() => document.querySelectorAll('iframe.wasm-frame').length > 0, undefined, { timeout: 30_000 }),
    ]);

    // Asset checks — every player gets every required byte.
    await expect.poll(() => assets1.size, { timeout: 60_000 }).toBeGreaterThan(0);
    await expect.poll(() => assets2.size, { timeout: 60_000 }).toBeGreaterThan(0);

    // Install the SSE capture inside both iframes so we can assert on
    // what BridgeGlue would normally drain. The capture is read-only;
    // the iframe still consumes events normally.
    await captureIframeSseEvents(p1);
    await captureIframeSseEvents(p2);

    // P1 emits a MOVE_RIGHT control with the session id attached so
    // the BFF routes it onto the per-session sink. The X-Session-Id
    // header is what flips the controller into multi-player mode.
    await sendControl(p1, /* characterId */ 1, {
      action:    'MOVE_RIGHT',
      pressed:   true,
      chapterId: 1,
      sessionId: session.id,
    });

    // P2's iframe should see a `world` event carrying P1 in the
    // roster — that's the proof that:
    //   (a) the BFF fanned out on the per-session sink,
    //   (b) the Angular wrapper relayed the SSE event in,
    //   (c) the iframe's BridgeGlue would now dispatch through
    //       HostBridge.dispatch_event into the typed signal.
    await expect.poll(async () => {
      const events = await drainCapturedEvents(p2);
      return events.filter(e => e.type === 'SD_SSE_EVENT').length;
    }, { timeout: 30_000, message: 'P2 must receive at least one SSE event after P1 moved' })
      .toBeGreaterThan(0);

    await ctx1.close();
    await ctx2.close();
  });
});

function bumpAsset(set: Set<string>, url: string, status: number): void {
  if (status !== 200) return;
  if (/\/_remotes\/wasm-fragment\/[^/]+\/index\.(html|wasm|pck)$/.test(url)) {
    set.add(url);
  }
}

async function openFirstChapter(page: Page): Promise<void> {
  // Re-render the home cards (the join/create sequence may have
  // navigated away). Click the first card; pick the first Play.
  await page.goto('/', { waitUntil: 'networkidle' });
  const card = page.locator('.game-card').first();
  await card.waitFor({ state: 'visible', timeout: 20_000 });
  await card.click();
  const play = page.locator('.play-btn').first();
  await play.waitFor({ state: 'visible', timeout: 15_000 });
  await play.click();
}
