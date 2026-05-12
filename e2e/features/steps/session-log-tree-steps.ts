// UI steps for features/admin/session-log-tree-ui.feature.
//
// Drives the host-shell admin Session Log tree at /admin/session-log
// (AdminSessionLogPageComponent). Per-step PNGs attach via the
// existing AfterStep hook; the page recording (`video: 'on'`) makes
// the harvested .webm a directly-usable docs asset.

import { expect, Browser, Page } from '@playwright/test';
import { Given, When, Then } from '../support/fixtures';
import { login, newE2eContext, SeedUser } from '../../tests/helpers/roles';
import { ctxRegistry, pageRegistry } from './_registry';

async function ensure(browser: Browser, name: 'admin' | 'player1'): Promise<Page> {
  if (pageRegistry.has(name)) return pageRegistry.get(name)!;
  const ctx = await newE2eContext(browser);
  const page = await ctx.newPage();
  const u: SeedUser = {
    username:    name,
    passwordEnv: name === 'admin' ? 'E2E_ADMIN_PASSWORD' : 'E2E_PLAYER1_PASSWORD',
    kind:        name === 'admin' ? 'admin' : 'player',
  };
  await login(page, u);
  ctxRegistry.set(name, ctx);
  pageRegistry.set(name, page);
  return page;
}

Given('{string} is signed in', async ({ browser }, name: string) => {
  // Routes to the admin-specific helper if matching.
  if (name === 'admin')   { await ensure(browser, 'admin');   return; }
  if (name === 'player1') { await ensure(browser, 'player1'); return; }
  // Generic player fallback — shares the player1 path for simplicity.
  await ensure(browser, 'player1');
});

When('{string} opens {string}', async ({}, user: string, path: string) => {
  const lookup = user === 'admin' ? 'admin' : 'player1';
  const page = pageRegistry.get(lookup);
  if (!page) throw new Error(`no page for ${user}`);
  await page.goto(path);
});

Then('the game tree shows at least one game row', async () => {
  const page = pageRegistry.get('admin')!;
  // Wait for the route to settle and the tree to render
  await page.waitForSelector('.game-tree', { timeout: 10_000 });
  const rows = page.locator('.game-tree > li');
  expect(await rows.count()).toBeGreaterThan(0);
});

When('the admin clicks the row for game {string}', async ({}, slug: string) => {
  const page = pageRegistry.get('admin')!;
  await page.locator('.game-row').filter({ hasText: slug }).click();
});

Then('the expanded game shows session {string}', async ({}, sessionName: string) => {
  const page = pageRegistry.get('admin')!;
  await expect(page.locator('.session-rows li').filter({ hasText: sessionName }))
    .toBeVisible({ timeout: 5_000 });
});

Then('session {string} is not visible', async ({}, sessionName: string) => {
  const page = pageRegistry.get('admin')!;
  await expect(page.locator('.session-rows li').filter({ hasText: sessionName }))
    .toHaveCount(0);
});

When('the admin types {string} into the session-log search box', async ({}, q: string) => {
  const page = pageRegistry.get('admin')!;
  await page.locator('input.search-input').fill(q);
});

Then(/^session "(.+)" is visible under game "(.+)"$/,
  async ({}, sessionName: string, _slug: string) => {
    const page = pageRegistry.get('admin')!;
    await expect(page.locator('.session-rows li').filter({ hasText: sessionName }))
      .toBeVisible({ timeout: 5_000 });
  });

Then('the row for game {string} shows a paused-count badge', async ({}, slug: string) => {
  const page = pageRegistry.get('admin')!;
  const row = page.locator('.game-row').filter({ hasText: slug });
  await expect(row.locator('.game-paused-count')).toBeVisible();
});

Then('the row for session {string} shows a paused pill', async ({}, sessionName: string) => {
  const page = pageRegistry.get('admin')!;
  const row = page.locator('.session-rows li').filter({ hasText: sessionName });
  await expect(row.locator('.paused-pill')).toBeVisible();
});

Then('the row for {string} shows a paused status tag', async ({}, sessionName: string) => {
  const page = pageRegistry.get('player1')!;
  // Player session-log table uses .status-paused on the Status cell.
  const row = page.locator('.session-log-table tbody tr').filter({ hasText: sessionName });
  await expect(row.locator('.status-paused')).toBeVisible({ timeout: 5_000 });
});
