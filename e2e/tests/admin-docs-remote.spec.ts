import { test, expect } from '@playwright/test';
import { SEED_USERS, login } from './helpers/roles';

/**
 * Admin → /admin/docs federation smoke.
 *
 * The api-docs remote (federation_modules slug `api-docs`, exposed
 * module `./DocsShell`) is the Angular implementation of the
 * OpenAPI navigator. The remote itself fetches the merged spec at
 * runtime from `/docs/api/openapi.json` (the docs-build artefact —
 * absent in the e2e image, served when `make docs-bundle` has run
 * and the docs zip is mounted into the host) with a fallback to
 * the live `/v3/api-docs` springdoc endpoint (admin-gated).
 *
 * This spec pins the INTEGRATION CONCERNS — the things a unit test
 * would mock:
 *   1. An admin can navigate to /admin/docs without being bounced
 *      back to /login or hitting a 403.
 *   2. The federated `api-docs` remote is reachable — its
 *      `remoteEntry.json` returns 200 from the
 *      `/_remotes/api-docs/` nginx proxy. A drift in nginx-tls.conf
 *      (the bug called out in CLAUDE.md) would fail this.
 *   3. The DocsShell component mounts — the "API navigator" h2
 *      renders, proving the federated module loaded + the host
 *      auth-guard let the admin through.
 *
 * Specifically NOT pinned: the spec-population assertion. The e2e
 * image doesn't ship the docs-build artefact, and the live
 * /v3/api-docs is admin-gated; covering "the spec renders N ops"
 * is the docs-publishing pipeline's responsibility, not this
 * federation smoke's.
 */
test.describe('admin → api-docs federated remote', () => {
  const admin = SEED_USERS.find(u => u.username === 'admin');
  if (!admin) throw new Error('admin seed user missing from SEED_USERS');

  test('admin reaches /admin/docs and the DocsShell remote mounts', async ({ page }) => {
    // 1. Watch the federated remote's entry-point response. A 404
    //    here means nginx-tls.conf drifted and the federation can't
    //    even start loading.
    let remoteEntryStatus: number | null = null;
    page.on('response', resp => {
      if (resp.url().endsWith('/_remotes/api-docs/remoteEntry.json')) {
        remoteEntryStatus = resp.status();
      }
    });

    await login(page, admin);

    // 2. Navigate to /admin/docs. authGuard + the federation manifest's
    //    `requires: "ROLE_ADMIN"` metadata should both pass for the
    //    admin seed user.
    await page.goto('/admin/docs');

    // 3. The DocsShell mounts → `aside.nav h2` with "API navigator"
    //    text. This is the minimum proof the federated remote loaded
    //    and parsed; spec-population is a separate concern handled
    //    by the docs-publishing pipeline.
    await page.waitForSelector('aside.nav h2', { state: 'visible', timeout: 30_000 });
    await expect(page.locator('aside.nav h2')).toHaveText(/API navigator/i);

    // 4. Right-pane header is non-empty — DocsShell mounted its main
    //    pane (the title falls back to "Snapdragon Gaming BFF" when
    //    the spec hasn't loaded).
    const headerText = (await page.locator('.pane .header h1').textContent())?.trim() ?? '';
    expect(headerText.length).toBeGreaterThan(0);

    // 5. Federation manifest entry came back 200 (or wasn't fetched
    //    at all yet because the route resolver short-circuited;
    //    either way, no observed-and-failed case).
    if (remoteEntryStatus !== null) {
      expect(remoteEntryStatus).toBe(200);
    }
  });
});
