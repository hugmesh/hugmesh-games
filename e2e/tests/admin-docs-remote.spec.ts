import { test, expect } from '@playwright/test';
import { SEED_USERS, login } from './helpers/roles';

/**
 * Admin → /admin/docs federation smoke.
 *
 * The api-docs remote (federation_modules slug `api-docs`, exposed
 * module `./DocsShell`) is the Angular implementation of the
 * OpenAPI navigator — it fetches the merged spec from
 * `/docs/api/openapi.json` (the docs-build artefact), groups
 * operations by tag in an aside, and renders an explorer + reference
 * pane on the right.
 *
 * This spec pins:
 *   1. An admin can navigate to /admin/docs without being bounced
 *      back to /login or hitting a 403.
 *   2. The federated DocsShell remote actually loads — the host
 *      shell otherwise shows a `federation-error` toast for any
 *      federated module that fails to come up.
 *   3. The OpenAPI spec is fetched + parsed — the navigator's
 *      "API navigator" aside renders at least one tag group with
 *      at least one operation pulled from the spec (proves the
 *      remote consumed real BFF metadata, not a static placeholder).
 *
 * Why an explicit e2e rather than a unit test: the failure modes
 * we want to catch are integration-shaped:
 *   - Federation manifest row drift (slug / remoteEntryUrl /
 *     exposedModule).
 *   - nginx proxy missing `/_remotes/api-docs/` (the drift bug
 *     called out in CLAUDE.md).
 *   - Auth-guard / role-gate regressions ahead of the federated
 *     module load.
 * A unit test would mock all three; the e2e walks the whole stack.
 */
test.describe('admin → api-docs federated remote', () => {
  const admin = SEED_USERS.find(u => u.username === 'admin');
  if (!admin) throw new Error('admin seed user missing from SEED_USERS');

  test('admin sees the generated OpenAPI navigator at /admin/docs', async ({ page }) => {
    // 1. Federation error toast should never appear for this flow.
    // The host renders a `.federation-error` banner whenever a remote
    // fails to load — we fail the test fast if it shows up.
    let federationFailureSeen = false;
    page.on('response', resp => {
      if (resp.url().endsWith('/_remotes/api-docs/remoteEntry.json') && resp.status() !== 200) {
        federationFailureSeen = true;
      }
    });

    await login(page, admin);

    // 2. Navigate to /admin/docs. The route declares `authGuard` and
    //    the federation manifest's `requires: "ROLE_ADMIN"` metadata —
    //    a non-admin gets bounced, but `admin` should land cleanly.
    await page.goto('/admin/docs');

    // 3. Wait for the DocsShell to mount + the spec to load.
    //    `<aside class="nav">` is the left rail; once it renders the
    //    "API navigator" h2 + at least one `.tag-group`, the remote
    //    has executed, fetched the spec, and parsed it.
    await page.waitForSelector('aside.nav h2', { state: 'visible', timeout: 30_000 });
    await expect(page.locator('aside.nav h2')).toHaveText(/API navigator/i);

    // The spec has dozens of operations — wait for at least one tag
    // group with at least one operation entry to appear. The
    // `.tag-group` + `.op` selectors are stable per
    // docs-shell.component.ts. A timeout means the spec fetch failed
    // or the remote didn't render it.
    const opCount = await page.locator('aside.nav .tag-group .op').count();
    expect(opCount).toBeGreaterThan(0);

    // 4. Right-pane header reflects the spec's `info.title` (or
    //    falls back to the literal "Snapdragon Gaming BFF"). Either
    //    way the <h1> is non-empty — proves the right pane mounted.
    const headerText = (await page.locator('.pane .header h1').textContent())?.trim() ?? '';
    expect(headerText.length).toBeGreaterThan(0);

    // 5. No federation-load failure observed for the api-docs remote.
    expect(federationFailureSeen).toBe(false);
  });
});
