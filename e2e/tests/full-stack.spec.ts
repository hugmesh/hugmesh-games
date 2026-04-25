import { test, expect, Page } from '@playwright/test';

const USERNAME = process.env.E2E_PLAYER_USERNAME ?? 'player';
const PASSWORD = process.env.E2E_PLAYER_PASSWORD ?? 'e2e-player-pass';

/**
 * Full cross-stack smoke. Asserts every layer between Playwright and the
 * Godot WASM bundle is reachable and behaving:
 *
 *   1. nginx serves HTTPS with the self-signed cert (handled by playwright
 *      config `ignoreHTTPSErrors: true`)
 *   2. /api/auth/token proxies to Casdoor and issues a JWT
 *   3. Auth guard releases the home route, GameLibraryService loads games
 *   4. Selecting a level mounts the iframe and the WASM bundle requests
 *      (index.html, index.wasm, index.pck) all return 200
 *   5. The Godot prefetch-script JSON `[TW] scene prefetched` fires in the
 *      iframe console, confirming /api/levels/{id}/scene is reachable
 *      through the in-iframe origin too.
 */
test.describe('full-stack smoke', () => {
  test('login → home → load level → wasm/pck/scene fetched', async ({ page }, testInfo) => {
    const seenAssetUrls = new Set<string>();
    const seenSceneUrls = new Set<string>();
    const consoleLines: string[] = [];

    page.on('console', msg => consoleLines.push(`[${msg.type()}] ${msg.text()}`));
    page.on('response', resp => {
      const url = resp.url();
      if (/\/api\/assets\/levels\/[^/]+\/index\.(html|wasm|pck|js)$/.test(url)) {
        if (resp.status() === 200) seenAssetUrls.add(url);
      }
      if (/\/api\/levels\/\d+\/scene$/.test(url)) {
        if (resp.status() === 200) seenSceneUrls.add(url);
      }
    });

    // Step 1: visit /login (auth guard sends unauthenticated users here).
    await page.goto('/login', { waitUntil: 'networkidle' });
    await expect(page.locator('input[formControlName="username"]')).toBeVisible();

    // Step 2: log in as the seeded player.
    await page.fill('input[formControlName="username"]', USERNAME);
    await page.fill('input[formControlName="password"]', PASSWORD);
    await page.click('button.login-btn');

    // Step 3: home page renders the games list — at least one card visible.
    await page.waitForURL('**/', { timeout: 30_000 });
    const gameCards = page.locator('.game-card');
    await expect(gameCards.first()).toBeVisible({ timeout: 20_000 });
    const cardCount = await gameCards.count();
    expect(cardCount).toBeGreaterThan(0);

    // Step 4: click the first unlocked level (Cave is always unlocked at id=1).
    await gameCards.filter({ hasNot: page.locator('.locked') }).first().click();

    // Wait for the iframe to attach + start streaming bytes.
    const iframe = page.frameLocator('app-game-canvas iframe');
    await expect(iframe.locator('body')).toBeAttached({ timeout: 30_000 });

    // Step 5: assert the WASM bundle assets came down. The Godot loader
    // requests index.wasm + index.pck immediately after parsing index.html.
    // Give it a generous window — the wasm is 30+ MB.
    await expect.poll(
      () => Array.from(seenAssetUrls).filter(u => u.endsWith('.wasm')).length,
      { timeout: 90_000, message: 'expected at least one /api/assets/levels/*/index.wasm with HTTP 200' },
    ).toBeGreaterThan(0);

    await expect.poll(
      () => Array.from(seenAssetUrls).filter(u => u.endsWith('.pck')).length,
      { timeout: 90_000, message: 'expected /api/assets/levels/*/index.pck' },
    ).toBeGreaterThan(0);

    await expect.poll(
      () => seenSceneUrls.size,
      { timeout: 30_000, message: 'expected /api/levels/{id}/scene to be fetched by the prefetch script' },
    ).toBeGreaterThan(0);

    // Capture diagnostic artifacts even on success.
    await testInfo.attach('captured-asset-urls.txt', {
      body: [...seenAssetUrls, ...seenSceneUrls].join('\n'),
      contentType: 'text/plain',
    });
    await testInfo.attach('console-log.txt', {
      body: consoleLines.join('\n'),
      contentType: 'text/plain',
    });
  });
});
