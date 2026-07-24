// Step definitions for features/admin/session-log-scoped.feature.
//
// session-log.feature covers /me (self) and /admin/{id} (admin-any + non-
// admin 403) but never GET /api/session-logs/scoped/{casdoorId} — the
// mod/publisher-scope-filtered read (SessionLogController.scopedFor filters
// history to games in the caller's mod+publisher scope union). A cross-user
// leak (a mod reading history for a game OUTSIDE their scope) would go
// uncaught. This asserts the ALLOW+filter legs (each scoped mod sees only
// its own game's rows) and the role DENY (a plain player 403s).

import { expect, Page } from '@playwright/test';
import { When, Then } from '../support/fixtures';
import { pageRegistry, responseState } from './_registry';
import { authToken } from '../../tests/helpers/roles';

function pageFor(username: string): Page {
  const p = pageRegistry.get(username);
  if (!p) throw new Error(`No logged-in page for "${username}". Stage a browser context first.`);
  return p;
}

async function casdoorIdOf(username: string): Promise<string> {
  const token = await authToken(pageFor(username));
  if (!token) throw new Error(`No token for "${username}"`);
  const seg = token.split('.')[1];
  const b64 = seg.replace(/-/g, '+').replace(/_/g, '/');
  const padded = b64 + '='.repeat((4 - (b64.length % 4)) % 4);
  const sub = JSON.parse(Buffer.from(padded, 'base64').toString('utf8')).sub;
  if (!sub) throw new Error(`Token for "${username}" has no sub claim`);
  return sub as string;
}

/** Scenario-scoped result of the last scoped-log read. Re-set by each
 *  "reads the scoped session log" When before its Then, so serial
 *  scenarios don't cross-contaminate. */
let scopedSlugs: string[] = [];

When('{string} reads the scoped session log for {string}',
  async ({}, reader: string, target: string) => {
    const targetSub = await casdoorIdOf(target);
    const page = pageFor(reader);
    const token = await authToken(page);
    const res = await page.evaluate(async ({ sub, t }) => {
      const r = await fetch(`/api/session-logs/scoped/${sub}?limit=100`, {
        headers: t ? { Authorization: `Bearer ${t}` } : {},
      });
      let slugs: string[] = [];
      if (r.ok) {
        const rows = (await r.json()) as Array<{ gameSlug: string }>;
        slugs = rows.map(x => x.gameSlug);
      }
      return { status: r.status, slugs };
    }, { sub: targetSub, t: token });
    responseState.lastStatus = res.status;
    scopedSlugs = res.slugs;
  });

Then('the scoped log contains only {string} rows', async ({}, gameSlug: string) => {
  expect(responseState.lastStatus, 'scoped read must be 200 for a scoped mod').toBe(200);
  expect(scopedSlugs.length, `scoped log must be non-empty for ${gameSlug}`).toBeGreaterThan(0);
  expect(
    scopedSlugs.every(s => s === gameSlug),
    `scoped log must contain ONLY ${gameSlug} rows (got: ${scopedSlugs.join(',')})`,
  ).toBe(true);
});

Then('the scoped log request is denied', async () => {
  expect(responseState.lastStatus, 'a plain player must be 403 on the scoped path').toBe(403);
});
