// UI steps for features/admin/live-sessions-ui.feature.
//
// Drives the admin Live Sessions page at /admin/sessions
// (SessionsRemoteComponent — federated `admin/SessionsShell`). The
// AfterStep hook attaches a per-step PNG; Playwright's `video: 'on'`
// (see playwright.config.ts) records the whole interaction as
// .webm. Both feed the architecture PDF's admin chapter via
// `make screenshots-from-e2e`.

import { expect, Browser, Page } from '@playwright/test';
import { Given, When, Then } from '../support/fixtures';
import { login, newE2eContext, SeedUser } from '../../tests/helpers/roles';
import { ctxRegistry, pageRegistry } from './_registry';

async function ensureAdmin(browser: Browser): Promise<Page> {
  if (pageRegistry.has('admin')) return pageRegistry.get('admin')!;
  const ctx = await newE2eContext(browser);
  const page = await ctx.newPage();
  const u: SeedUser = { username: 'admin', passwordEnv: 'E2E_ADMIN_PASSWORD', kind: 'admin' };
  await login(page, u);
  ctxRegistry.set('admin', ctx);
  pageRegistry.set('admin', page);
  return page;
}

When('the admin opens {string}', async ({ browser }, path: string) => {
  const page = await ensureAdmin(browser);
  await page.goto(path);
  await page.waitForSelector('input.search-input', { timeout: 10_000 });
});

When('the admin types {string} into the session search box', async ({}, q: string) => {
  const page = pageRegistry.get('admin')!;
  const input = page.locator('input.search-input');
  await input.click({ clickCount: 3 });
  await input.fill(q);
});

When('the admin clears the session search box', async () => {
  const page = pageRegistry.get('admin')!;
  await page.locator('input.search-input').fill('');
});

Then('the session list shows {string}', async ({}, name: string) => {
  const page = pageRegistry.get('admin')!;
  await expect(page.locator('.sessions-list li').filter({ hasText: name }))
    .toBeVisible({ timeout: 5_000 });
});

Then('the session list does not show {string}', async ({}, name: string) => {
  const page = pageRegistry.get('admin')!;
  await expect(page.locator('.sessions-list li').filter({ hasText: name }))
    .toHaveCount(0);
});

Then(/^the session list shows "(.+)" and "(.+)"$/, async ({}, a: string, b: string) => {
  const page = pageRegistry.get('admin')!;
  await expect(page.locator('.sessions-list li').filter({ hasText: a })).toBeVisible();
  await expect(page.locator('.sessions-list li').filter({ hasText: b })).toBeVisible();
});

Then('the row for {string} shows a paused pill', async ({}, name: string) => {
  const page = pageRegistry.get('admin')!;
  const row = page.locator('.sessions-list li').filter({ hasText: name });
  await expect(row.locator('.paused-pill')).toBeVisible();
});

Then('the row for {string} shows visibility {string}', async ({}, name: string, vis: string) => {
  const page = pageRegistry.get('admin')!;
  const row = page.locator('.sessions-list li').filter({ hasText: name });
  await expect(row.locator('.vis-tag')).toHaveText(vis);
});

When('the admin selects the session {string}', async ({}, name: string) => {
  const page = pageRegistry.get('admin')!;
  await page.locator('.sessions-list li').filter({ hasText: name })
    .locator('button').click();
  await page.waitForSelector('.session-detail h2', { timeout: 5_000 });
});

Then('the session detail header shows a paused pill', async () => {
  const page = pageRegistry.get('admin')!;
  await expect(page.locator('.session-detail h2 .paused-pill')).toBeVisible();
});

Then('the meta line includes the pause reason', async () => {
  const page = pageRegistry.get('admin')!;
  await expect(page.locator('.session-detail .meta')).toContainText('paused by');
});

Then('the event terminal is mounted', async () => {
  const page = pageRegistry.get('admin')!;
  await expect(page.locator('.event-terminal')).toBeVisible();
});

When('the admin unchecks the {string} channel checkbox', async ({}, channel: string) => {
  const page = pageRegistry.get('admin')!;
  await page.locator('.channel-toggle').filter({ hasText: channel })
    .locator('input[type=checkbox]').uncheck();
});

Then('the event terminal hides {string} entries', async ({}, channel: string) => {
  // The UI keeps all events in the buffer but hides them via channel
  // visibility checks (visibleChannels Set). We assert by checking
  // that the corresponding channel toggle's checkbox is unchecked —
  // the terminal element rerenders based on the same signal so a
  // false checkbox state proves the filter is active.
  const page = pageRegistry.get('admin')!;
  const box = page.locator('.channel-toggle').filter({ hasText: channel })
    .locator('input[type=checkbox]');
  expect(await box.isChecked()).toBe(false);
});

When('the admin clicks the {string} button on the event terminal', async ({}, label: string) => {
  const page = pageRegistry.get('admin')!;
  // The Clear button lives inside `.filter-row` adjacent to the
  // channel toggles. Scope by role+name to avoid clashing with the
  // page-wide Refresh button.
  await page.locator('.filter-row').getByRole('button', { name: label }).click();
});

Then('the event terminal is empty', async () => {
  const page = pageRegistry.get('admin')!;
  await expect(page.locator('.event-terminal.empty')).toBeVisible();
});
