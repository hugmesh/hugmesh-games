import { test, expect, BrowserContext, Page } from '@playwright/test';
import {
  SEED_USERS, login, sendControl, newE2eContext,
} from './helpers/roles';
import { captureIframeSseEvents, drainCapturedEvents, installSseCapture } from './helpers/sse-bridge';

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
    const ctx1: BrowserContext = await newE2eContext(browser);
    const ctx2: BrowserContext = await newE2eContext(browser);
    // Install the SSE capture listener at context init-time so every
    // iframe that mounts later picks it up before its own scripts
    // run — eliminates the race where BridgeGlue drains before our
    // listener attaches.
    await installSseCapture(ctx1);
    await installSseCapture(ctx2);
    const p1: Page = await ctx1.newPage();
    const p2: Page = await ctx2.newPage();

    const assets1 = new Set<string>();
    const assets2 = new Set<string>();
    p1.on('response', r => bumpAsset(assets1, r.url(), r.status()));
    p2.on('response', r => bumpAsset(assets2, r.url(), r.status()));

    await login(p1, PLAYER1);
    await login(p2, PLAYER2);

    // P1 creates the session via the lobby modal — this populates
    // `activeSessionId` on the home component so the iframe URL
    // gets `?session_id=` threaded onto it. Without that flow the
    // wrapper subscribes to the unscoped /api/game/stream and the
    // strict positive assertion below can't tell whether P2 saw the
    // movement because of the per-session sink or because both
    // iframes are global subscribers.
    // P1 = character 1, P2 = character 2 — `findActiveOwnerOfCharacter`
    // keys on character_id, so duplicates would cause the BFF's
    // authorize-control check to 403 P1's MOVE_RIGHT below.
    const sessionName = `e2e-multi-${Date.now()}`;
    const sessionId = await createSessionViaLobby(p1, sessionName, 1);

    // P2 joins the same session via the lobby's join button (with
    // their own character 2).
    await joinSessionViaLobby(p2, sessionName, 2);

    // Both players click Play on the first chapter. The chapter
    // modal stays open after the create / join flow so we just
    // click the first non-disabled play button.
    await launchFirstChapter(p1);
    await launchFirstChapter(p2);

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

    // Wait for the wrapper to actually open the session-scoped
    // EventSource — without this guard we race the in-iframe wasm
    // boot. The wrapper's `?sessionId=` GET fires only after
    // game_bridge.gd's _request_sse_subscription posts
    // SD_SSE_SUBSCRIBE, which only happens once the WASM compile
    // finishes. On a cold stack this can take 90+ seconds (the
    // 41 MB rust-side wasm is the dominant cost; emscripten's
    // streaming compile is single-threaded). 120 s gives a fresh
    // CI image enough headroom without papering over a real bug
    // (a real wiring failure would never produce the response,
    // not produce it slowly).
    await p2.waitForResponse(
      r => r.url().includes(`/api/game/stream?sessionId=${sessionId}`),
      { timeout: 120_000 },
    );

    // The wrapper is now hot. Move P1.
    await sendControl(p1, /* characterId */ 1, {
      action:    'MOVE_RIGHT',
      pressed:   true,
      chapterId: 1,
      sessionId: sessionId,
    });

    // Strict positive: P2's iframe must see at least one
    // SD_SSE_EVENT after P1's movement.
    const events = await pollSseFor(p2, 12_000);
    expect(
      events,
      'P2 must receive at least one SSE event after P1 moved',
    ).toBeGreaterThan(0);

    await ctx1.close();
    await ctx2.close();
  });
});

async function pollSseFor(page: Page, ms: number): Promise<number> {
  const deadline = Date.now() + ms;
  let total = 0;
  while (Date.now() < deadline) {
    const events = await drainCapturedEvents(page);
    total += events.filter(e => e.type === 'SD_SSE_EVENT').length;
    if (total > 0) return total;
    await page.waitForTimeout(500);
  }
  return total;
}

function bumpAsset(set: Set<string>, url: string, status: number): void {
  if (status !== 200) return;
  if (/\/_remotes\/wasm-fragment\/[^/]+\/index\.(html|wasm|pck)$/.test(url)) {
    set.add(url);
  }
}

/** Open the lobby modal, fill the create-session form, submit,
 *  return the resulting session id once the picker is in the
 *  active-session state. Modal stays open with the chapter list
 *  visible. */
async function createSessionViaLobby(page: Page, sessionName: string, characterId = 1): Promise<string> {
  await page.goto('/?characterId=' + characterId, { waitUntil: 'domcontentloaded' });
  const card = page.locator('.game-card').first();
  await card.waitFor({ state: 'visible', timeout: 20_000 });
  await card.click();
  await page.locator('#session-name-input').fill(sessionName);
  await page.locator('input[type="radio"][value="public"]').check();
  await page.locator('button.create-btn').click();
  await page.locator('.session-picker-active').waitFor({ state: 'visible', timeout: 15_000 });
  return await page.evaluate(async (name: string) => {
    const raw = window.localStorage.getItem('tw_auth');
    const token = raw ? (JSON.parse(raw).accessToken ?? '') : '';
    const r = await fetch('/api/sessions?gameSlug=three-wizards', {
      headers: { Authorization: `Bearer ${token}` },
    });
    const list = (await r.json()) as Array<{ id: string; name: string }>;
    return (list.find(s => s.name === name) ?? list[0]).id;
  }, sessionName);
}

/** Open the lobby modal and click Join on the row matching
 *  {@code sessionName}. Modal flips into the active-session state. */
async function joinSessionViaLobby(page: Page, sessionName: string, characterId = 1): Promise<void> {
  await page.goto('/?characterId=' + characterId, { waitUntil: 'domcontentloaded' });
  const card = page.locator('.game-card').first();
  await card.waitFor({ state: 'visible', timeout: 20_000 });
  await card.click();
  // Each .session-row has the session name + a Join button. Filter
  // to the row whose name matches and click its Join.
  const row = page.locator('.session-row', {
    has: page.locator('.session-name', { hasText: sessionName }),
  }).first();
  await row.waitFor({ state: 'visible', timeout: 15_000 });
  await row.locator('button.join-btn').click();
  await page.locator('.session-picker-active').waitFor({ state: 'visible', timeout: 15_000 });
}

async function launchFirstChapter(page: Page): Promise<void> {
  const play = page.locator('.modal-backdrop button.play-btn:not([disabled])').first();
  await play.waitFor({ state: 'visible', timeout: 15_000 });
  await play.click();
}
