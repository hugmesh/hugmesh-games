import { test, expect, Page } from '@playwright/test';
import { ensureLoginFormReady, newE2eContext } from './helpers/roles';

/**
 * Friend graph + blocklist flow.
 *
 * <p>Logs two players in via parallel browser contexts, has player1
 * send a friend request to player2, has player2 accept, and asserts
 * the relationship surfaces in both UIs. Then exercises block /
 * unblock and asserts that a blocked user's session is hidden from
 * the lobby list.
 */
const PLAYER1 = process.env.E2E_PLAYER_USERNAME  ?? 'player1';
const PLAYER2 = process.env.E2E_PLAYER2_USERNAME ?? 'player2';
const PASS    = process.env.E2E_PLAYER_PASSWORD  ?? (process.env.E2E_PASSWORD ?? 'e2e-pass');

async function login(page: Page, user: string, pass: string): Promise<void> {
  await page.goto('/login', { waitUntil: 'domcontentloaded' });
  await ensureLoginFormReady(page);
  await page.fill('input[formControlName="username"]', user);
  await page.fill('input[formControlName="password"]', pass);
  await page.click('button.login-btn');
  await page.waitForURL((url) => !url.pathname.startsWith('/login'), { timeout: 30_000 });
  await page.waitForFunction(() => {
    const raw = window.localStorage.getItem('tw_auth');
    if (!raw) return false;
    try { return Boolean(JSON.parse(raw).accessToken); } catch { return false; }
  }, undefined, { timeout: 15_000 });
}

test.describe('friends + blocking', () => {
  test('player1 requests, player2 accepts, both see the friendship', async ({ browser }) => {
    const ctx1 = await newE2eContext(browser);
    const ctx2 = await newE2eContext(browser);
    const p1 = await ctx1.newPage();
    const p2 = await ctx2.newPage();

    await login(p1, PLAYER1, PASS);
    await login(p2, PLAYER2, PASS);

    // p1 → friends page → request player2
    await p1.click('a[routerLink="/friends"]');
    await p1.fill('#friends-target-input', PLAYER2);
    await p1.click('button[type="submit"]');

    // p2 → friends page sees the incoming request and accepts
    await p2.click('a[routerLink="/friends"]');
    await p2.waitForSelector(`text=${PLAYER1}`, { timeout: 10_000 });
    await p2.locator('button.accept-btn').first().click();

    // Refresh both — both should see the other in the friends list
    await p1.reload({ waitUntil: 'domcontentloaded' });
    await p2.reload({ waitUntil: 'domcontentloaded' });

    await expect(p1.locator('section[aria-label="Your friends"]')).toContainText(PLAYER2);
    await expect(p2.locator('section[aria-label="Your friends"]')).toContainText(PLAYER1);

    await ctx1.close();
    await ctx2.close();
  });

  test('blocking a user hides their public sessions from the blocker\'s lobby', async ({ browser }) => {
    const ctxBlocker = await newE2eContext(browser);
    const ctxBlocked = await newE2eContext(browser);
    const blocker = await ctxBlocker.newPage();
    const blocked = await ctxBlocked.newPage();

    await login(blocker, PLAYER1, PASS);
    await login(blocked, PLAYER2, PASS);

    // blocked creates a public session
    await blocked.locator('.game-card').first().click();
    await blocked.fill('#session-name-input', 'block-test-session');
    await blocked.locator('input[type="radio"][value="public"]').check();
    await blocked.click('button.create-btn');

    // blocker blocks player2 from /friends
    await blocker.click('a[routerLink="/friends"]');
    await blocker.fill('#friends-target-input', PLAYER2);
    // First send a request, then block from the friends row — or block
    // directly via a direct API call against /api/relationships/blocks/add.
    await blocker.evaluate(async (target) => {
      const raw = window.localStorage.getItem('tw_auth');
      if (!raw) return;
      const { accessToken } = JSON.parse(raw);
      await fetch('/api/relationships/blocks/add', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json', Authorization: `Bearer ${accessToken}` },
        body: JSON.stringify({ other: target }),
      });
    }, PLAYER2);

    // blocker opens Three Wizards lobby — blocked's session should NOT be listed
    await blocker.goto('/');
    await blocker.locator('.game-card').first().click();
    await expect(blocker.locator('text=block-test-session')).toHaveCount(0, { timeout: 10_000 });

    await ctxBlocker.close();
    await ctxBlocked.close();
  });
});
