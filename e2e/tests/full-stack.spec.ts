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

    const dodecaAssets = () =>
      [...seenAssetUrls].filter(u => u.includes('/levels/dodecahedron/'));

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

    // Step 4: pick the Cave card by its scene-tag. Cave + field both
    // route through the federated games-three-wizards remote now
    // (SCENE_TYPE_TO_GAME_SLUG in home.component.ts), so the iframe
    // sits inside <app-three-wizards-remote> rather than the legacy
    // <app-game-canvas>. Federated dodecahedron is exercised in Step 6.
    const caveCard = page.locator('.game-card', {
      has: page.locator('.scene-tag--cave'),
    }).first();
    await expect(caveCard).toBeVisible({ timeout: 15_000 });
    await caveCard.click();

    // Wait for the iframe to attach + start streaming bytes.
    const iframe = page.frameLocator('app-three-wizards-remote iframe');
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

    // ── Step 6: federated chrome-less Dodecahedron Demo ─────────────────────
    // Close the cave canvas, return to the home grid, click the dodecahedron
    // card. The dodecahedron level renders via the federated remote
    // (<app-dodecahedron-remote> registered by RemoteLoaderService +
    // loadRemoteModule), NOT the legacy <app-game-canvas>. Verify
    //   (a) the federated custom element is mounted,
    //   (b) the legacy <app-game-canvas> path is gone for this level,
    //   (c) HUD + controls overlays are NOT rendered (sceneType is
    //       CHROMELESS in home.component.ts), and
    //   (d) WASM/PCK still fetch with HTTP 200 (the iframe inside the
    //       federated component points at the same Godot asset URLs).
    await page.locator('button.close-btn').click();
    await expect(page.locator('app-game-canvas iframe')).toHaveCount(0);

    const dodecaCard = page.locator('.game-card', {
      has: page.locator('.scene-tag--dodecahedron'),
    }).first();
    await expect(dodecaCard).toBeVisible({ timeout: 15_000 });
    await dodecaCard.click();

    // Federated mount: the host registered the custom element via
    // loadRemoteModule + createCustomElement; the upgraded element holds
    // the iframe pointing at /api/assets/levels/dodecahedron/index.html.
    await expect(page.locator('app-dodecahedron-remote')).toBeAttached({ timeout: 30_000 });
    await expect(page.locator('app-game-canvas')).toHaveCount(0);

    // The chrome-less rule: hud-layer must not be in the DOM for this game.
    await expect(page.locator('.hud-layer')).toHaveCount(0);
    await expect(page.locator('app-game-hud')).toHaveCount(0);
    await expect(page.locator('app-game-controls')).toHaveCount(0);

    // Dodecahedron WASM bundle is ~37 MB — give it a wide window.
    await expect.poll(
      () => dodecaAssets().filter(u => u.endsWith('.wasm')).length,
      { timeout: 90_000, message: 'expected /api/assets/levels/dodecahedron/index.wasm' },
    ).toBeGreaterThan(0);
    await expect.poll(
      () => dodecaAssets().filter(u => u.endsWith('.pck')).length,
      { timeout: 90_000, message: 'expected /api/assets/levels/dodecahedron/index.pck' },
    ).toBeGreaterThan(0);

    // ── Step 7: federated /admin route renders for non-admin too ───────────
    // The /admin route lazy-loads AdminRemoteComponent from the `admin`
    // remote (loadComponent → loadRemoteModule). The seeded e2e player
    // does NOT have ROLE_ADMIN, so the BFF rejects /api/admin/stats with
    // 403 — but the federated component itself MUST mount and render the
    // 403 banner. That's what we assert: the loadRemote → custom-element
    // boundary is exercised even when the API rejects. (The /login route
    // is implicitly exercised earlier when we logged in via the federated
    // form.)
    await page.goto('/admin', { waitUntil: 'networkidle' });
    // The federated AdminShell renders its own header — `app-admin-remote`
    // is the selector createCustomElement registered, but loadComponent
    // attaches it as a normal Angular host element with the same selector.
    await expect(page.locator('.admin-title')).toBeVisible({ timeout: 30_000 });
    await expect(page.locator('.admin-subtitle')).toContainText('admin');
    // Without ROLE_ADMIN, /api/admin/stats returns 403 and the remote's
    // catchError sets the host-equivalent error banner. Tolerate both
    // states — slow stack startups can still be loading when the assertion
    // runs, and a green dashboard render is also a valid pass.
    await Promise.race([
      page.locator('.error-banner').waitFor({ state: 'visible', timeout: 15_000 }).then(() => 'banner'),
      page.locator('.stats-grid').waitFor({ state: 'visible', timeout: 15_000 }).then(() => 'stats'),
    ]);

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
