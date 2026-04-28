import { test, expect, Page } from '@playwright/test';

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
const PLAYER     = process.env.E2E_PLAYER_USERNAME    ?? 'player1';
const ADMIN      = process.env.E2E_ADMIN_USERNAME     ?? 'admin';
const PASS       = process.env.E2E_PLAYER_PASSWORD    ?? 'e2e-player-pass';
const ADMIN_PASS = process.env.E2E_ADMIN_PASSWORD     ?? 'e2e-admin-pass';

async function login(page: Page, user: string, pass: string): Promise<void> {
  await page.goto('/login', { waitUntil: 'networkidle' });
  await page.fill('input[formControlName="username"]', user);
  await page.fill('input[formControlName="password"]', pass);
  await page.click('button.login-btn');
  await page.waitForURL('**/', { timeout: 30_000 });
}

test.describe('session participation log', () => {
  test('player sees their own session history at /sessions/log', async ({ page }) => {
    await login(page, PLAYER, PASS);
    // Create a session so the log is non-empty.
    await page.click('text=Three Wizards');
    await page.fill('#session-name-input', 'log-smoke-' + Date.now());
    await page.click('button.create-btn');
    // Navigate to the log
    await page.click('a[routerLink="/sessions/log"]');
    await expect(page.locator('app-session-log table.session-log-table')).toBeVisible({ timeout: 15_000 });
    // At least one row should be visible
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
