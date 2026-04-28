import { test, expect, BrowserContext, Page } from '@playwright/test';
import {
  SEED_USERS, login, createSession, sendControl,
} from './helpers/roles';
import { captureIframeSseEvents, drainCapturedEvents } from './helpers/sse-bridge';

const PLAYER1 = SEED_USERS.find(u => u.username === 'player1')!;
const PLAYER2 = SEED_USERS.find(u => u.username === 'player2')!;

/**
 * Cross-session isolation. Two players each create their own session
 * for the same game and load the same chapter; they never join each
 * other's session. After P1 moves in session A, P2 (in session B)
 * must NOT receive any movement / world / character events from
 * session A's sink.
 *
 * This is the e2e flip-side of `SessionIsolationTest` on the BFF —
 * that test pins the per-session sink contract at the service layer;
 * this test pins it through every hop (sink fan-out → SSE → Angular
 * wrapper relay → iframe consumer).
 */
test.describe('cross-session isolation', () => {
  test('two sessions for the same game don\'t cross-stream events', async ({ browser }) => {
    const ctxA: BrowserContext = await browser.newContext();
    const ctxB: BrowserContext = await browser.newContext();
    const pA: Page = await ctxA.newPage();
    const pB: Page = await ctxB.newPage();

    await login(pA, PLAYER1);
    await login(pB, PLAYER2);

    const sessionA = await createSession(pA, {
      gameSlug:    'three-wizards',
      name:        `iso-A-${Date.now()}`,
      maxPlayers:  3,
      visibility:  'public',
      characterId: 1,
      slotIndex:   0,
    });
    const sessionB = await createSession(pB, {
      gameSlug:    'three-wizards',
      name:        `iso-B-${Date.now()}`,
      maxPlayers:  3,
      visibility:  'public',
      characterId: 2,
      slotIndex:   0,
    });
    expect(sessionA.id).not.toBe(sessionB.id);

    // Both players open their own chapter — same scene, different
    // session. The iframe URL carries `?session_id=<own>` which
    // the bridge_glue sends as SD_SSE_SUBSCRIBE; the Angular wrapper
    // opens an EventSource scoped to that session.
    await openFirstChapter(pA);
    await openFirstChapter(pB);

    await Promise.all([
      pA.waitForFunction(() => document.querySelectorAll('iframe.wasm-frame').length > 0, undefined, { timeout: 30_000 }),
      pB.waitForFunction(() => document.querySelectorAll('iframe.wasm-frame').length > 0, undefined, { timeout: 30_000 }),
    ]);

    await captureIframeSseEvents(pA);
    await captureIframeSseEvents(pB);

    // Drain whatever queued during boot so the post-control assertion
    // only sees fresh deliveries.
    await drainCapturedEvents(pA);
    await drainCapturedEvents(pB);

    // P1 (session A) moves their character. The BFF should fan this
    // out on session A's sinks ONLY — session B's subscriber must
    // see nothing.
    await sendControl(pA, 1, {
      action:    'MOVE_RIGHT',
      pressed:   true,
      chapterId: 1,
      sessionId: sessionA.id,
    });

    // Wait long enough for the SSE delivery — roughly two ticks of
    // the BFF's emit pipeline. If session B got anything carrying
    // P1's character (id 1), it's a leak.
    await pA.waitForTimeout(2_500);

    const eventsA = await drainCapturedEvents(pA);
    const eventsB = await drainCapturedEvents(pB);

    // Sanity check — A heard its own movement at least once.
    const sseA = eventsA.filter(e => e.type === 'SD_SSE_EVENT');
    expect(sseA.length, 'session A subscriber must hear its own movement').toBeGreaterThan(0);

    // The contract — B saw nothing carrying session A's character.
    const sseB = eventsB.filter(e => {
      if (e.type !== 'SD_SSE_EVENT') return false;
      const blob = JSON.stringify(e.payload ?? {});
      // Session B's character is id 2; any event mentioning "characterId":1
      // before A's id is the bleed we're testing for.
      return blob.includes('"characterId":1') || blob.includes('"slot":0,"characterId":1');
    });
    expect(
      sseB,
      `session B must NOT receive session A's events (got ${sseB.length})`,
    ).toEqual([]);

    await ctxA.close();
    await ctxB.close();
  });
});

async function openFirstChapter(page: Page): Promise<void> {
  await page.goto('/', { waitUntil: 'networkidle' });
  const card = page.locator('.game-card').first();
  await card.waitFor({ state: 'visible', timeout: 20_000 });
  await card.click();
  const play = page.locator('.play-btn').first();
  await play.waitFor({ state: 'visible', timeout: 15_000 });
  await play.click();
}
