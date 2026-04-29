// Step definitions backing features/auth/*.feature. Each step wraps
// the plain Playwright helpers from tests/helpers/roles.ts — the BDD
// surface stays a thin presentation layer so the existing
// `.spec.ts` infrastructure (login retries, federation init, JWT
// stash) keeps being the single source of truth.

import { expect, Browser, Page } from '@playwright/test';
import { Given, When, Then } from '../support/fixtures';
import { login, newE2eContext, SeedUser } from '../../tests/helpers/roles';
import { installSseCapture } from '../../tests/helpers/sse-bridge';
import { ctxRegistry, pageRegistry } from './_registry';

async function loginInto(browser: Browser, user: SeedUser): Promise<Page> {
  const ctx = await newE2eContext(browser);
  await installSseCapture(ctx);
  const page = await ctx.newPage();
  await login(page, user);
  ctxRegistry.set(user.username, ctx);
  pageRegistry.set(user.username, page);
  return page;
}

Given('a fresh browser context', async ({ context }) => {
  // The Playwright fixture already gives us a clean per-scenario
  // context; this step exists so feature files read naturally and
  // gives us an obvious hook for future state-clearing logic
  // (cookies, sessionStorage) without touching every scenario.
  await context.clearCookies();
});

Given('a fresh browser context for {string}', async ({ browser, user }, username: string) => {
  await loginInto(browser, user(username));
});

Given('a separate browser context for {string}', async ({ browser, user }, username: string) => {
  await loginInto(browser, user(username));
});

When('{string} logs in', async ({ page, user }, username: string) => {
  // Single-page variant for the per-role-login feature — uses the
  // default page fixture so we don't proliferate browser contexts
  // for the simple login matrix.
  const u = user(username);
  await login(page, u);
  pageRegistry.set(u.username, page);
});

Then('the catalog renders at least one game card', async ({ page }) => {
  await expect(page.locator('.game-card').first()).toBeVisible({ timeout: 20_000 });
});

Then('the {string} nav link is visible', async ({ page }, route: string) => {
  // The host shell renders `<a routerLink="/admin">`/`/mod`/`/publisher`
  // only when AuthService.is{Admin,Mod,Publisher} returns true, so
  // the existence of the link IS the role-conditional check.
  await expect(page.locator(`a[routerLink="${route}"]`)).toBeVisible({ timeout: 10_000 });
});

Then('no role-specific console nav is visible', async ({ page }) => {
  // Plain players should see neither admin / mod / publisher links.
  // Wait briefly so a slow render doesn't false-positive — the host
  // is fully populated within ~1s of catalog render.
  await page.waitForTimeout(500);
  for (const route of ['/admin', '/mod', '/publisher']) {
    await expect(page.locator(`a[routerLink="${route}"]`)).toHaveCount(0);
  }
});
