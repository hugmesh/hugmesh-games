import { test, expect, Page } from '@playwright/test';
import { ensureLoginFormReady } from './helpers/roles';

/**
 * Session participation log per role.
 *
 * <p>Three roles, three call sites:
 *   <ul>
 *     <li>player        — /sessions/log shows their own history</li>
 *     <li>admin         — admin user-detail shows anyone's history</li>
 *     <li>mod/publisher — scoped to games they have scope for</li>
 *   </ul>
 */
// Defaults match the integration-test profile's deterministic seed —
// every role uses ${E2E_PASSWORD:-e2e-pass}. Override individual
// passwords via E2E_*_PASSWORD env if you need to.
const SHARED     = process.env.E2E_PASSWORD ?? 'e2e-pass';
const PLAYER     = process.env.E2E_PLAYER_USERNAME    ?? 'player1';
const ADMIN      = process.env.E2E_ADMIN_USERNAME     ?? 'admin';
const PASS       = process.env.E2E_PLAYER_PASSWORD    ?? SHARED;
const ADMIN_PASS = process.env.E2E_ADMIN_PASSWORD     ?? SHARED;

async function login(page: Page, user: string, pass: string): Promise<void> {
  await page.goto('/login', { waitUntil: 'domcontentloaded' });
  await ensureLoginFormReady(page);
  await page.fill('input[formControlName="username"]', user);
  await page.fill('input[formControlName="password"]', pass);
  await page.click('button.login-btn');
  await page.waitForURL((url) => !url.pathname.startsWith('/login'), { timeout: 30_000 });
  // Wait for the AuthService to actually persist the JWT — without
  // this guard, an immediate page.evaluate(fetch(..., Authorization))
  // can race the storage write and send `Bearer ` (empty token) → 0.
  await page.waitForFunction(() => {
    const raw = window.sessionStorage.getItem('tw_auth');
    if (!raw) return false;
    try { return Boolean(JSON.parse(raw).accessToken); } catch { return false; }
  }, undefined, { timeout: 15_000 });
}

test.describe('session participation log', () => {
  test('player sees their own session history at /sessions/log', async ({ page }) => {
    await login(page, PLAYER, PASS);
    // Create a session so the log is non-empty. The modal opens on
    // the game-card click; the chapter modal sits on top so we
    // close it before navigating to /sessions/log.
    await page.locator('.game-card').first().click();
    await page.fill('#session-name-input', 'log-smoke-' + Date.now());
    await page.click('button.create-btn');
    // Esc closes the modal — its backdrop intercepts pointer events
    // on the top nav otherwise.
    await page.keyboard.press('Escape');
    // Navigate via the URL directly instead of clicking the nav link
    // to avoid backdrop/race conditions on the click.
    await page.goto('/sessions/log', { waitUntil: 'domcontentloaded' });
    await expect(page.locator('app-session-log table.session-log-table')).toBeVisible({ timeout: 15_000 });
    const rows = page.locator('app-session-log tbody tr');
    expect(await rows.count()).toBeGreaterThan(0);
  });

  test('admin can request anyone\'s session history via /api/session-logs/admin/{id}', async ({ page }) => {
    await login(page, ADMIN, ADMIN_PASS);
    const status = await page.evaluate(async () => {
      const raw = window.sessionStorage.getItem('tw_auth');
      if (!raw) return 0;
      const { accessToken } = JSON.parse(raw);
      const r = await fetch('/api/session-logs/admin/player1?limit=10', {
        headers: { Authorization: `Bearer ${accessToken}` },
      });
      return r.status;
    });
    expect(status).toBe(200);
  });

  test('non-admin player gets 403 from the admin-scoped endpoint', async ({ page }) => {
    await login(page, PLAYER, PASS);
    const status = await page.evaluate(async () => {
      const raw = window.sessionStorage.getItem('tw_auth');
      if (!raw) return 0;
      const { accessToken } = JSON.parse(raw);
      const r = await fetch('/api/session-logs/admin/player2?limit=10', {
        headers: { Authorization: `Bearer ${accessToken}` },
      });
      return r.status;
    });
    expect(status).toBe(403);
  });
});
