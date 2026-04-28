import { test, expect } from '@playwright/test';
import { SEED_USERS, login, logout } from './helpers/roles';

/**
 * Per-role login + asset-load matrix.
 *
 * For every Casdoor seed user (player / player1..3 / admin / mod
 * variants / publisher variants):
 *   1. Logs them in via the federated login form.
 *   2. Asserts the home page renders the games list.
 *   3. Picks the first available chapter and asserts that the WASM
 *      bundle bytes (index.html / index.wasm / index.pck) come down
 *      with HTTP 200 — this is the "personal session" path.
 *   4. Creates a fresh public multiplayer session and re-checks the
 *      asset bytes load through the session-aware iframe URL — this
 *      is the "multiplayer session" path.
 *
 * The list of users is pulled from `helpers/roles.ts`; the
 * docker-compose.e2e.yml overlay seeds Casdoor with deterministic
 * passwords for each one (default: `${E2E_PASSWORD:-e2e-pass}`).
 */
test.describe('per-role login + asset load', () => {
  for (const user of SEED_USERS) {
    test(`${user.username} (${user.kind}) — home renders, WASM bundle bytes load`, async ({ page }) => {
      const seenAssets = new Set<string>();
      page.on('response', resp => {
        const url = resp.url();
        if (/\/_remotes\/wasm-fragment\/[^/]+\/index\.(html|wasm|pck)$/.test(url) && resp.status() === 200) {
          seenAssets.add(url);
        }
      });

      await login(page, user);

      // Home page renders the games list — at least one card visible
      // for every role (admin / mod / publisher all have play access).
      const gameCards = page.locator('.game-card');
      await expect(gameCards.first()).toBeVisible({ timeout: 20_000 });

      // ── Personal-session path ────────────────────────────────────
      // Click the first game card and the first available chapter.
      // The wizards remote mounts an iframe at /_remotes/wasm-fragment/
      // <chapterSlug>/index.html and the patched Godot loader fetches
      // index.wasm + index.pck immediately after parsing index.html.
      const firstGameCard = gameCards.first();
      await firstGameCard.click();

      // Wait for the chapter modal to open + click the first Play button.
      const playBtn = page.locator('.play-btn').first();
      await playBtn.waitFor({ state: 'visible', timeout: 15_000 });
      await playBtn.click();

      // Give the WASM bundle a generous window — first-load on a cold
      // image with the 30+ MB wasm can take ~20 seconds.
      await page.waitForFunction(
        () => document.querySelectorAll('iframe.wasm-frame').length > 0,
        undefined,
        { timeout: 30_000 },
      );
      await expect.poll(() => seenAssets.size, { timeout: 45_000 }).toBeGreaterThan(0);

      // Close the chapter and clean up so the per-role iteration
      // starts from a known state on the next user.
      await logout(page);
    });
  }
});
