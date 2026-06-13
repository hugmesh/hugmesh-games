// Step definitions backing features/makers/*.feature. The AfterStep
// hook in support/fixtures.ts attaches a PNG at every transition, so
// the HTML report shows the inline form-error banners and the build
// progress bar at every step.

import { expect } from '@playwright/test';
import { Given, When, Then } from '../support/fixtures';
import { login } from '../../tests/helpers/roles';
import { pageRegistry } from './_registry';

// Use the default Playwright `page` fixture (not a fresh context) so the
// AfterStep hook in fixtures.ts photographs the same page the step is
// driving. A separate-context approach silently photographs the empty
// default tab, producing white-screen step PNGs.

Given('{string} is logged in', async ({ page, context, user }, username: string) => {
  const u = user(username);
  // Clear browser cookies so a previously-active session doesn't
  // poison this one. login() itself navigates to /login; pre-clearing
  // sessionStorage too eagerly races the Angular shell's half-
  // bootstrapped state. Match the per-role-login feature's pattern
  // exactly so we behave like a known-working step.
  await context.clearCookies();
  await login(page, u);
  pageRegistry.set(u.username, page);
});

Given('the publisher console is open', async ({ page }) => {
  await page.goto('/publisher');
  // Match on the heading containing the diamond + "Publisher console";
  // hasText is a substring match so the leading glyph is fine.
  await expect(page.getByRole('heading', { name: /Publisher console/ }))
      .toBeVisible({ timeout: 20_000 });
  pageRegistry.set('publisher', page);
});

When('the publisher clicks {string}', async ({}, label: string) => {
  const page = pageRegistry.get('publisher')!;
  // window.open intercept — capture the would-be popup URL since
  // tabs in headless contexts behave inconsistently.
  await page.evaluate(() => {
    (window as unknown as { __openedUrls?: string[] }).__openedUrls = [];
    const origOpen = window.open;
    window.open = ((url?: string | URL) => {
      const s = typeof url === 'string' ? url : url?.toString() ?? '';
      ((window as unknown as { __openedUrls: string[] }).__openedUrls).push(s);
      return null;
    }) as typeof window.open;
    void origOpen; // keep tsc happy
  });
  await page.getByRole('button', { name: label }).click();
});

Then('a new tab opens at the RPG maker', async () => {
  const page = pageRegistry.get('publisher')!;
  const opened = await page.evaluate(() =>
    ((window as unknown as { __openedUrls?: string[] }).__openedUrls) ?? []);
  expect(opened.some(u => u.includes('/rpg-maker'))).toBeTruthy();
});

Then('a new tab opens at the VN maker', async () => {
  const page = pageRegistry.get('publisher')!;
  const opened = await page.evaluate(() =>
    ((window as unknown as { __openedUrls?: string[] }).__openedUrls) ?? []);
  expect(opened.some(u => u.includes('/vn-maker'))).toBeTruthy();
});

When('the publisher opens the RPG maker', async ({ page }) => {
  // Use the default page fixture so the step works whether the
  // caller logged in via maker-steps ("publisher console is open"
  // registers as 'publisher') OR via auth-steps' bare
  // `When "..." logs in` (which only registers under the username).
  // Side-effect: re-register under 'publisher' for the steps below.
  await page.goto('/rpg-maker');
  await expect(page.locator('h1', { hasText: 'RPG Maker' })).toBeVisible({ timeout: 15_000 });
  pageRegistry.set('publisher', page);
});

When('the publisher opens the VN maker', async ({ page }) => {
  await page.goto('/vn-maker');
  await expect(page.locator('h1', { hasText: 'Visual Novel Maker' })).toBeVisible({ timeout: 15_000 });
  pageRegistry.set('publisher', page);
});

// ── Form fills with field-error assertions ────────────────────────────────

When('the publisher fills the new-project slug with {string}', async ({}, slug: string) => {
  const page = pageRegistry.get('publisher')!;
  await page.locator('input[name="newProjSlug"]').fill(slug);
});

When('the publisher leaves the new-project display name blank', async () => {
  const page = pageRegistry.get('publisher')!;
  await page.locator('input[name="newProjName"]').fill('');
});

When('the publisher submits the new-project form', async () => {
  const page = pageRegistry.get('publisher')!;
  // Soft-click: the Create button stays disabled until both slug
  // and displayName are filled, so for the "blank display name"
  // scenario the button is unclickable. The eager inline error
  // (rendered as soon as slug is valid + name is empty) already
  // shows the validation message, so the subsequent `Then` step
  // still finds it. If the button is enabled, we click as usual.
  const btn = page.getByRole('button', { name: 'Create' });
  if (await btn.isEnabled().catch(() => false)) {
    await btn.click();
  }
});

When('the publisher fills the new-character slug with {string}', async ({}, slug: string) => {
  await pageRegistry.get('publisher')!.locator('input[name="newCharSlug"]').fill(slug);
});

When('the publisher fills the new-character display name with {string}', async ({}, n: string) => {
  await pageRegistry.get('publisher')!.locator('input[name="newCharName"]').fill(n);
});

When('the publisher fills the new-character colour with {string}', async ({}, c: string) => {
  await pageRegistry.get('publisher')!.locator('input[name="newCharColor"]').fill(c);
});

When('the publisher fills the new-choice label with {string}', async ({}, l: string) => {
  await pageRegistry.get('publisher')!.locator('input[name="newChoiceLabel"]').fill(l);
});

When('the publisher leaves the new-choice target blank', async () => {
  await pageRegistry.get('publisher')!.locator('select[name="newChoiceTarget"]').selectOption('');
});

When('the publisher submits the new-choice form', async () => {
  // Soft-click — see "submits the new-project form" for the rationale.
  const page = pageRegistry.get('publisher')!;
  const btn = page.getByRole('button', { name: 'Add choice' });
  if (await btn.isEnabled().catch(() => false)) {
    await btn.click();
  }
});

Then('an inline error reads {string}', async ({}, fragment: string) => {
  const page = pageRegistry.get('publisher')!;
  await expect(page.locator('.field-error', { hasText: fragment })).toBeVisible({ timeout: 5_000 });
});

// ── Happy-path entity creation ────────────────────────────────────────────

When('the publisher creates a project {string} called {string}', async ({}, slug: string, name: string) => {
  const page = pageRegistry.get('publisher')!;
  await page.locator('input[name="newProjSlug"]').fill(slug);
  await page.locator('input[name="newProjName"]').fill(name);
  await page.getByRole('button', { name: 'Create' }).click();
  // Selecting the new project drives the rest of the editor; click
  // its row in the projects pane.
  await page.locator('.link-btn', { hasText: name }).first().click({ timeout: 10_000 });
});

When('the publisher creates an area {string} called {string}', async ({}, slug: string, name: string) => {
  const page = pageRegistry.get('publisher')!;
  await page.locator('input[name="newAreaSlug"]').fill(slug);
  await page.locator('input[name="newAreaName"]').fill(name);
  await page.getByRole('button', { name: 'Add area' }).click();
});

When('the publisher selects the area {string}', async ({}, slug: string) => {
  const page = pageRegistry.get('publisher')!;
  await page.locator('.link-btn', { hasText: `(${slug})` }).first().click();
});

When('the publisher creates a room {string} called {string}', async ({}, slug: string, name: string) => {
  const page = pageRegistry.get('publisher')!;
  await page.locator('input[name="newRoomSlug"]').fill(slug);
  await page.locator('input[name="newRoomName"]').fill(name);
  await page.getByRole('button', { name: 'Add room' }).click();
});

When('the publisher creates an act {string} called {string}', async ({}, slug: string, name: string) => {
  const page = pageRegistry.get('publisher')!;
  await page.locator('input[name="newActSlug"]').fill(slug);
  await page.locator('input[name="newActName"]').fill(name);
  await page.getByRole('button', { name: 'Add act' }).click();
});

When('the publisher selects the act {string}', async ({}, slug: string) => {
  const page = pageRegistry.get('publisher')!;
  await page.locator('.link-btn', { hasText: `(${slug})` }).first().click();
});

When('the publisher creates a scene {string} called {string} as the entry point',
     async ({}, slug: string, name: string) => {
  const page = pageRegistry.get('publisher')!;
  await page.locator('input[name="newSceneSlug"]').fill(slug);
  await page.locator('input[name="newSceneName"]').fill(name);
  await page.locator('input[name="newSceneEntry"]').check();
  await page.getByRole('button', { name: 'Add scene' }).click();
});

When('the publisher selects the scene {string}', async ({}, slug: string) => {
  const page = pageRegistry.get('publisher')!;
  await page.locator('.link-btn', { hasText: `(${slug}` }).first().click();
});

When('the publisher creates a line {string}', async ({}, text: string) => {
  const page = pageRegistry.get('publisher')!;
  await page.locator('textarea[name="newLineText"]').fill(text);
  await page.getByRole('button', { name: 'Add line' }).click();
});

When('the publisher creates an item {string} called {string}', async ({}, slug: string, name: string) => {
  const page = pageRegistry.get('publisher')!;
  await page.locator('input[name="newItemSlug"]').fill(slug);
  await page.locator('input[name="newItemName"]').fill(name);
  await page.getByRole('button', { name: 'Add item' }).click();
});

When('the publisher creates an NPC {string} called {string}', async ({}, slug: string, name: string) => {
  const page = pageRegistry.get('publisher')!;
  await page.locator('input[name="newNpcSlug"]').fill(slug);
  await page.locator('input[name="newNpcName"]').fill(name);
  await page.getByRole('button', { name: 'Add NPC' }).click();
});

When('the publisher uploads a {string} art asset {string} from a sample PNG',
     async ({}, kind: string, slug: string) => {
  const page = pageRegistry.get('publisher')!;
  await page.locator('input[name="newAssetSlug"]').fill(slug);
  await page.locator('select[name="newAssetKind"]').selectOption(kind);
  // 1x1 transparent PNG — small enough to clear the 4 MB cap and a valid
  // image/png so the gallery renders a thumbnail. setInputFiles with a
  // buffer means the docker e2e needs no fixture file on disk.
  const png = Buffer.from(
    'iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNkYPhfDwAChwGA60e6kgAAAABJRU5ErkJggg==',
    'base64',
  );
  await page.locator('input[name="newAssetFile"]').setInputFiles({
    name: `${slug}.png`,
    mimeType: 'image/png',
    buffer: png,
  });
  await page.getByRole('button', { name: 'Upload asset' }).click();
});

When('the publisher enqueues a build with target slug {string}', async ({}, slug: string) => {
  const page = pageRegistry.get('publisher')!;
  const targetInput = page.locator('input[name="targetSlug"], input[name="vnTarget"]').first();
  await targetInput.fill(slug);
  await page.getByRole('button', { name: /Build & upload/ }).click();
});

Then('the build progress reaches {string} or {string}', async ({}, doneStatus: string, failStatus: string) => {
  const page = pageRegistry.get('publisher')!;
  // Watch for terminal state. Generous timeout — the worker tick is
  // 5 s and the stub bundle round-trips through BFF upload + register.
  await expect(page.locator('.build-progress strong'))
      .toHaveText(new RegExp(`^(${doneStatus}|${failStatus})$`), { timeout: 60_000 });
});

Then('the build progress reaches {string}', async ({}, terminalStatus: string) => {
  const page = pageRegistry.get('publisher')!;
  await expect(page.locator('.build-progress strong'))
      .toHaveText(new RegExp(`^${terminalStatus}$`), { timeout: 90_000 });
});

// ── Post-build catalog + asset verification ──────────────────────────
// Once the maker reports DONE, the BFF should have a games row + chapter
// rows + the on-disk bundle. We verify both surfaces in-page so the
// publisher's bearer is forwarded automatically.

Then('the BFF catalog lists the game {string}', async ({}, slug: string) => {
  const page = pageRegistry.get('publisher')!;
  await expect.poll(async () => {
    return page.evaluate(async (s) => {
      const raw = window.sessionStorage.getItem('tw_auth');
      const token = raw ? (JSON.parse(raw).accessToken ?? '') : '';
      const r = await fetch('/api/games', {
        headers: { Authorization: `Bearer ${token}` },
      });
      if (!r.ok) return [];
      const games = (await r.json()) as Array<{ slug: string }>;
      return games.map(g => g.slug);
    }, slug);
  }, { timeout: 15_000, intervals: [500, 1000, 2000] }).toContain(slug);
});

// ── Item / NPC / art-asset listing assertions ─────────────────────────
// The Items and NPCs panes both render a `.row-list` with the entity name
// in a <strong>, so scope each assertion to its own `section.pane` (keyed
// on the pane's <h2>) to avoid matching the wrong pane. The gallery has
// its own `.asset-grid` class so it needs no extra scoping.

Then('the items list contains {string}', async ({}, name: string) => {
  const page = pageRegistry.get('publisher')!;
  const pane = page.locator('section.pane', { has: page.getByRole('heading', { name: 'Items' }) });
  await expect(pane.locator('.row-list strong', { hasText: name }).first())
      .toBeVisible({ timeout: 10_000 });
});

Then('the NPCs list contains {string}', async ({}, name: string) => {
  const page = pageRegistry.get('publisher')!;
  const pane = page.locator('section.pane', { has: page.getByRole('heading', { name: 'NPCs' }) });
  await expect(pane.locator('.row-list strong', { hasText: name }).first())
      .toBeVisible({ timeout: 10_000 });
});

Then('the asset gallery shows {string}', async ({}, slug: string) => {
  const page = pageRegistry.get('publisher')!;
  await expect(page.locator('.asset-grid .asset-card strong', { hasText: slug }).first())
      .toBeVisible({ timeout: 10_000 });
});

Then('the projects list contains {string}', async ({ page }, displayName: string) => {
  // Polls the maker's project pane for the seeded fixture name. The
  // fixture is materialised by Liquibase 004 in each builder service,
  // owned by tw-pub-flat-001, so logging in as publisher-flatland
  // surfaces it in the GET /api/{rpg|vn}/projects response.
  await expect(page.locator('.row-list', { hasText: displayName }).first())
      .toBeVisible({ timeout: 10_000 });
});

Then('the BFF chapter {string} of {string} returns 200 with the bundle index.html',
     async ({}, chapterSlug: string, gameSlug: string) => {
  const page = pageRegistry.get('publisher')!;
  // Hot-uploaded bundles are served by AssetController under
  // /api/assets/levels/<slug>/<chapter>/<file>. Try a small list of
  // candidate URLs so the assertion stays robust if the path settles
  // a layer up or down (e.g. wasm-fragment vs api).
  const result = await page.evaluate(async ({ g, c }) => {
    const raw = window.sessionStorage.getItem('tw_auth');
    const token = raw ? (JSON.parse(raw).accessToken ?? '') : '';
    const candidates = [
      `/api/assets/levels/${g}/${c}/index.html`,
      `/api/assets/levels/${g}/index.html`,
      `/_remotes/wasm-fragment/${g}/${c}/index.html`,
      `/_remotes/wasm-fragment/${g}/index.html`,
    ];
    const tried: Array<{ url: string; status: number }> = [];
    for (const url of candidates) {
      try {
        const r = await fetch(url, { headers: { Authorization: `Bearer ${token}` } });
        tried.push({ url, status: r.status });
        if (r.ok) {
          const text = await r.text();
          return { ok: true, url, status: r.status, len: text.length, tried };
        }
      } catch { /* try next */ }
    }
    return { ok: false, tried };
  }, { g: gameSlug, c: chapterSlug });
  expect(
    result.ok,
    `bundle for ${gameSlug}/${chapterSlug} did not respond 200 on any candidate URL: ${JSON.stringify(result)}`,
  ).toBe(true);
});
