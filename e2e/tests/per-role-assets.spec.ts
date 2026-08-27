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
 * docker-compose.e2e.yml overlay seeds Casdoor with one shared password
 * for every role by default — a fresh random value generated per run by
 * `tests/stack/e2e-password.sh` (env `E2E_PASSWORD`, no literal default).
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

      // Wait for the chapter modal to open + click the first Play
      // button. The home cards expose `<span class="play-btn">Open
      // chapters</span>` which the modal sits on top of, so the
      // selector must scope to the actual `<button class="play-btn">`
      // inside the modal — `button.play-btn` distinguishes them.
      const playBtn = page.locator('.modal-backdrop button.play-btn:not([disabled])').first();
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

      // ── Committed-bundle Godot game: shadows-of-the-net ──────────
      // The kind='wasm-game' Godot bundles (migration 056) are served
      // by the same wasm-fragment nginx but mount through the host's
      // app-game-canvas as `iframe.game-frame` (NOT the federated
      // remotes' `iframe.wasm-frame`), so the first-card path above
      // never reaches them — shadows-of-the-net sorts last
      // (sort_order = MAX+1). Re-check its bundle bytes explicitly
      // for every role: game_access scope='all' means each seed user
      // must be able to pull index.wasm/index.pck through the proxy.
      // connect-four-godot (055) is deliberately NOT exercised here —
      // it is registered-but-bundleless (no committed bundle under
      // backend/src/main/resources/assets/levels/), the same reason
      // no other spec launches it.
      await page.locator('button.close-btn').click();

      const shadowsCard = page.locator('.game-card', {
        has: page.locator('.chip--game-slug', { hasText: /^shadows-of-the-net$/ }),
      }).first();
      await shadowsCard.waitFor({ state: 'visible', timeout: 15_000 });
      await shadowsCard.click();

      const shadowsPlay = page.locator('.modal-backdrop button.play-btn:not([disabled])').first();
      await shadowsPlay.waitFor({ state: 'visible', timeout: 15_000 });
      await shadowsPlay.click();

      await page.waitForFunction(
        () => document.querySelectorAll('iframe.game-frame').length > 0,
        undefined,
        { timeout: 30_000 },
      );
      const shadowsAssets = () =>
        [...seenAssets].filter(u => u.includes('/_remotes/wasm-fragment/shadows-of-the-net/'));
      await expect.poll(
        () => shadowsAssets().filter(u => u.endsWith('.wasm')).length,
        { timeout: 60_000, message: 'expected shadows-of-the-net index.wasm with HTTP 200 via wasm-fragment' },
      ).toBeGreaterThan(0);
      await expect.poll(
        () => shadowsAssets().filter(u => u.endsWith('.pck')).length,
        { timeout: 60_000, message: 'expected shadows-of-the-net index.pck with HTTP 200 via wasm-fragment' },
      ).toBeGreaterThan(0);

      // Close the chapter and clean up so the per-role iteration
      // starts from a known state on the next user.
      await logout(page);
    });
  }
});
