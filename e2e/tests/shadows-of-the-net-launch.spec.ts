import { test, expect } from '@playwright/test';
import { SEED_USERS, login } from './helpers/roles';

/**
 * shadows-of-the-net — committed-bundle Godot LAUNCH + BOOT.
 *
 * The XR chat chamber (migration 056) is a pure-GDScript Godot 4 WASM
 * bundle registered with the standard kind='wasm-game' federation row,
 * so the host launches it exactly like the 14 web-native games: the
 * chapter's asset_url (`/_remotes/wasm-fragment/shadows-of-the-net/
 * index.html`) mounts through app-game-canvas as `iframe.game-frame`.
 * Unlike the web-native canvas games it has NO `window.__<game>Ready`
 * boot flag and no `wasm/<engine>.wasm` — it is a stock Godot export
 * (index.js / index.wasm / index.pck, threads+extensions OFF), so the
 * boot signal is the Godot HTML template's own contract:
 * `engine.startGame().then(() => setStatusMode('hidden'))` REMOVES the
 * `#status` overlay from the iframe DOM on successful boot (failure
 * keeps it and shows `#status-notice`). Mirrors the
 * web-games-launch.spec.ts flow (login → card by slug chip → modal
 * Play → iframe mounts → engine bytes 200 → render proof), swapping
 * the bootFlag/canvas step for the Godot status-overlay contract.
 *
 * The iframe is same-origin (wasm-fragment is proxied under the host
 * origin at /_remotes/wasm-fragment/), so the host page can reach the
 * iframe's contentDocument — same trick web-games-launch uses for its
 * contentWindow boot-flag poll.
 */

const PLAYER = SEED_USERS.find(u => u.username === 'player')!;
const SLUG = 'shadows-of-the-net';
const BUNDLE = `/_remotes/wasm-fragment/${SLUG}/`;

test.describe('shadows-of-the-net — Godot bundle launch + boot', () => {
  test('launches at the chapter asset_url and the Godot engine boots', async ({ page }, testInfo) => {
    // Track the exact bundle bytes the Godot loader requests — the
    // engine fetches index.wasm + index.pck relative to index.html,
    // which resolves under the host's wasm-fragment proxy. Recording
    // every status (not just 200s) lets us assert "no 404" rather
    // than merely "some 200" (guards the nginx-fallthrough case).
    const wasmStatuses: number[] = [];
    const pckStatuses:  number[] = [];
    const consoleLines: string[] = [];
    page.on('console', msg => consoleLines.push(`[${msg.type()}] ${msg.text()}`));
    page.on('response', r => {
      const url = r.url();
      if (url.includes(`${BUNDLE}index.wasm`)) wasmStatuses.push(r.status());
      if (url.includes(`${BUNDLE}index.pck`))  pckStatuses.push(r.status());
    });

    await login(page, PLAYER);

    // ── open the shadows-of-the-net card from the grid ──────────────
    // Filter by the exact slug chip (web-games-launch convention) —
    // robust against display-name drift / re-ordering.
    const card = page.locator('.game-card', {
      has: page.locator('.chip--game-slug', { hasText: /^shadows-of-the-net$/ }),
    }).first();
    await card.waitFor({ state: 'visible', timeout: 25_000 });
    await card.click();

    // ── chapter modal → first unlocked Play ─────────────────────────
    // One chapter ('The Chamber', slug == game slug). Scope to the
    // modal's `button.play-btn` — the grid card's play affordance is a
    // `<span class="play-btn">`.
    const playBtn = page.locator('.modal-backdrop button.play-btn:not([disabled])').first();
    await playBtn.waitFor({ state: 'visible', timeout: 15_000 });
    await playBtn.click();

    // ── iframe mounts at the chapter asset_url ──────────────────────
    await page.waitForFunction(
      () => document.querySelectorAll('iframe.game-frame').length > 0,
      undefined,
      { timeout: 30_000 },
    );
    const src = await page.locator('iframe.game-frame').first().getAttribute('src');
    expect(
      src,
      'iframe src must be the chapter asset_url through the wasm-fragment proxy',
    ).toContain(`${BUNDLE}index.html`);

    // ── bundle bytes come down clean ────────────────────────────────
    // Generous window — first-load on a cold image streams + compiles
    // the ~35 MB Godot wasm, which dominates boot.
    await expect
      .poll(() => wasmStatuses.length, {
        timeout: 90_000,
        message: `expected a fetch of ${BUNDLE}index.wasm`,
      })
      .toBeGreaterThan(0);
    expect(wasmStatuses, `index.wasm fetches must not 404 (got ${wasmStatuses.join(',')})`).not.toContain(404);
    expect(wasmStatuses.some(s => s === 200), 'at least one 200 for index.wasm').toBe(true);

    await expect
      .poll(() => pckStatuses.length, {
        timeout: 90_000,
        message: `expected a fetch of ${BUNDLE}index.pck`,
      })
      .toBeGreaterThan(0);
    expect(pckStatuses, `index.pck fetches must not 404 (got ${pckStatuses.join(',')})`).not.toContain(404);
    expect(pckStatuses.some(s => s === 200), 'at least one 200 for index.pck').toBe(true);

    // ── Godot engine boots ──────────────────────────────────────────
    // Success contract of the exported index.html: startGame() resolve
    // calls setStatusMode('hidden') which REMOVES #status; a boot
    // failure keeps #status and fills #status-notice instead. So
    // "canvas present AND #status gone" is a strict boot-succeeded
    // signal — a blank/failed document can never satisfy it. 120 s sits
    // inside the repo's 180 s per-test budget with the earlier waits'
    // slack; wasm compile + first frame is the long pole.
    try {
      await page.waitForFunction(
        () => {
          const fr = document.querySelector('iframe.game-frame') as HTMLIFrameElement | null;
          const doc = fr?.contentDocument ?? null;
          if (!doc) return false;
          return Boolean(doc.getElementById('canvas')) && !doc.getElementById('status');
        },
        undefined,
        { timeout: 120_000, polling: 500 },
      );
    } catch (err) {
      // Surface the Godot failure notice (if any) + the console log so
      // a boot failure is diagnosable from the report alone.
      const notice = await page.evaluate(() => {
        const fr = document.querySelector('iframe.game-frame') as HTMLIFrameElement | null;
        return fr?.contentDocument?.getElementById('status-notice')?.textContent
          ?? '(no #status-notice — engine never finished booting)';
      }).catch(() => '(iframe unreachable)');
      await testInfo.attach('console-log.txt', {
        body: consoleLines.join('\n'),
        contentType: 'text/plain',
      });
      throw new Error(`Godot boot did not complete: ${notice}\n${String(err)}`);
    }

    // The render surface is live after boot.
    const frame = page.frameLocator('iframe.game-frame');
    await expect(
      frame.locator('#canvas'),
      'the Godot canvas must be visible in the iframe after boot',
    ).toBeVisible({ timeout: 15_000 });

    await testInfo.attach('console-log.txt', {
      body: consoleLines.join('\n'),
      contentType: 'text/plain',
    });
  });
});
