// Step definitions for auth/new-tab-navigation.feature.
//
// Verifies that the AuthService's localStorage-backed token survives
// `window.open(...)` to a same-origin route — every signed-in role
// should be able to right-click → "Open in new tab" on a menu link
// and land on the target route, NOT be bounced to /login.
//
// The previous sessionStorage-backed token made fresh tabs read an
// empty store, so the AuthGuard redirected every menu-link new-tab
// open to /login. PR auth-localstorage-admin-views swapped the
// storage backend; these scenarios pin that fix from regressing.

import { expect, Browser, Page } from '@playwright/test';
import { Given, When, Then } from '../support/fixtures';
import { login, newE2eContext, SeedUser } from '../../tests/helpers/roles';
import { installSseCapture } from '../../tests/helpers/sse-bridge';

interface NewTabState {
  user:    SeedUser | null;
  primary: Page | null;
  /** The most recently opened "new tab" page handle. */
  opened:  Page | null;
}

// Per-scenario bag — playwright-bdd creates one fixture instance per
// scenario, so we hang state off a `World`-like object via a Map
// keyed on the fixture's browser instance. Simpler than touching the
// playwright-bdd internals; the registry stays scoped because each
// scenario uses a fresh browser context anyway.
const stateByBrowser = new WeakMap<Browser, NewTabState>();
function state(browser: Browser): NewTabState {
  let s = stateByBrowser.get(browser);
  if (!s) {
    s = { user: null, primary: null, opened: null };
    stateByBrowser.set(browser, s);
  }
  return s;
}

async function signIn(browser: Browser, username: string): Promise<NewTabState> {
  const s = state(browser);
  const ctx = await newE2eContext(browser);
  await installSseCapture(ctx);
  const page = await ctx.newPage();
  // SeedUser entries we use here are listed in helpers/roles.ts.
  const lookup: Record<string, string> = {
    admin:     'admin',
    player:    'player',
    publisher: 'publisher',
    mod:       'mod',
  };
  const seedName = lookup[username] ?? username;
  // `user(...)` fixture would be cleaner but we don't have it scoped
  // in here — falling back to a direct SEED_USERS lookup via
  // helpers/roles' `login` helper, which takes the username and
  // pulls the password env var on its own.
  const seedUser: SeedUser = {
    username:    seedName,
    passwordEnv: `E2E_${seedName.toUpperCase().replace(/-/g, '_')}_PASSWORD`,
    kind:        username === 'admin' ? 'admin' :
                 username === 'mod'   ? 'mod'   :
                 username === 'publisher' ? 'publisher' :
                 'player',
  };
  await login(page, seedUser);
  s.user    = seedUser;
  s.primary = page;
  s.opened  = null;
  return s;
}

Given('a signed-in admin',     async ({ browser }) => { await signIn(browser, 'admin'); });
Given('a signed-in player',    async ({ browser }) => { await signIn(browser, 'player'); });
Given('a signed-in publisher', async ({ browser }) => { await signIn(browser, 'publisher'); });
Given('a signed-in mod',       async ({ browser }) => { await signIn(browser, 'mod'); });

// {role} is one of admin / player / publisher / mod. The Gherkin
// wording uses the natural-language role name; the step matcher
// accepts it as a free string and we just record the request.
When('the {word} opens {string} in a new tab',
  async ({ browser, baseURL }, _role: string, path: string) => {
    const s = state(browser);
    expect(s.primary).not.toBeNull();
    const primary = s.primary!;
    // Open the route via `window.open` so it travels the same path
    // a middle-click / Ctrl-click would: a fresh top-level browsing
    // context that inherits localStorage by origin policy.
    const [opened] = await Promise.all([
      primary.context().waitForEvent('page'),
      primary.evaluate((url) => window.open(url, '_blank', 'noopener'),
                       `${baseURL ?? ''}${path}`),
    ]);
    await opened.waitForLoadState('domcontentloaded');
    s.opened = opened;
  });

Then(/^the new tab lands on "(.+)" without redirecting to \/login$/,
  async ({ browser }, expectedPath: string) => {
    const s = state(browser);
    expect(s.opened).not.toBeNull();
    const opened = s.opened!;
    // Allow a short settle so the AuthGuard runs and federated
    // remotes resolve. We poll the URL because client-side routes
    // commit asynchronously.
    await opened.waitForURL((u) =>
      u.pathname === expectedPath || u.pathname === '/login',
      { timeout: 10_000 });
    const finalUrl = new URL(opened.url());
    expect(finalUrl.pathname,
      `new tab redirected away from ${expectedPath} to ${finalUrl.pathname}`)
      .toBe(expectedPath);
    // Belt + braces — also assert the AuthService token is still in
    // localStorage (the actual bug was that it lived in
    // sessionStorage, which a fresh tab can't see).
    const hasToken = await opened.evaluate(
      () => !!window.localStorage.getItem('tw_auth'));
    expect(hasToken, 'tw_auth missing from localStorage').toBe(true);
  });
