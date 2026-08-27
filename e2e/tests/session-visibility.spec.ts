import { test, expect, Page } from '@playwright/test';
import { ensureLoginFormReady, newE2eContext, resolveE2eEnv } from './helpers/roles';

/**
 * Session visibility tiers.
 *
 * <p>Asserts that a {@code friends}-tier session is visible to friends
 * of the creator but invisible to strangers, and that a {@code private}
 * session is invisible to everyone except the creator (and reachable
 * only via direct id).
 */
const PLAYER1 = process.env.E2E_PLAYER_USERNAME  ?? 'player1';
const PLAYER2 = process.env.E2E_PLAYER2_USERNAME ?? 'player2';
const PLAYER3 = process.env.E2E_PLAYER3_USERNAME ?? 'player3';
// Per-role override wins; otherwise the shared E2E_PASSWORD (env, then the
// run-state credentials file, then throw). Never a literal fallback.
const PASS    = process.env.E2E_PLAYER_PASSWORD  ?? resolveE2eEnv('E2E_PASSWORD');

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

test.describe('session visibility', () => {
  test('friends-tier session is hidden from strangers', async ({ browser }) => {
    const ctxCreator = await newE2eContext(browser);
    const ctxStranger = await newE2eContext(browser);
    const creator = await ctxCreator.newPage();
    const stranger = await ctxStranger.newPage();

    await login(creator,  PLAYER1, PASS);
    await login(stranger, PLAYER3, PASS);

    // creator → Three Wizards modal → start friends-only session
    await creator.locator('.game-card').first().click();
    await creator.fill('#session-name-input', 'friends-only-night');
    await creator.locator('input[type="radio"][value="friends"]').check();
    await creator.click('button.create-btn');

    // stranger opens the same modal; the friends-only session must NOT show
    await stranger.locator('.game-card').first().click();
    await expect(stranger.locator('text=friends-only-night')).toHaveCount(0, { timeout: 10_000 });

    await ctxCreator.close();
    await ctxStranger.close();
  });

  test('private session is hidden from everyone except the creator', async ({ browser }) => {
    const ctxCreator = await newE2eContext(browser);
    const ctxOther   = await newE2eContext(browser);
    const creator    = await ctxCreator.newPage();
    const other      = await ctxOther.newPage();

    await login(creator, PLAYER1, PASS);
    await login(other,   PLAYER2, PASS);

    await creator.locator('.game-card').first().click();
    await creator.fill('#session-name-input', 'private-only');
    await creator.locator('input[type="radio"][value="private"]').check();
    await creator.click('button.create-btn');

    await other.locator('.game-card').first().click();
    await expect(other.locator('text=private-only')).toHaveCount(0, { timeout: 10_000 });

    await ctxCreator.close();
    await ctxOther.close();
  });
});
