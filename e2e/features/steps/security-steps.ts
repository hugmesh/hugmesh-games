// Step definitions for features/security/*.feature.
//
// Pattern: a Given "X is logged in" step (re-used from maker-steps.ts)
// stages a real Casdoor login + token in sessionStorage. The When step
// fires the API call via `page.evaluate(fetch(...))` so the request is
// same-origin through the host nginx — exercising the actual auth
// chain (limit_req → @PreAuthorize → service-level scope check). The
// captured status code lands in the module-global responseState so the
// Then assertion doesn't have to thread it.
//
// Why page.evaluate + fetch instead of Playwright's APIRequestContext?
// The fetch call inherits the same sessionStorage'd JWT the SPA uses,
// so the auth assertion matches what a real browser exchange would
// trigger (cookies, CORS, edge rate limits). APIRequestContext lives
// outside the page context and would bypass nginx's per-origin paths.

import { expect } from '@playwright/test';
import { Given, When, Then } from '../support/fixtures';
import { pageRegistry, responseState } from './_registry';
import { authToken } from '../../tests/helpers/roles';

/** Resolve the page registered for `username`, falling back to the
 *  default fixture if the user-level entry hasn't been set. The
 *  existing `Given "X" is logged in` step calls
 *  `pageRegistry.set(username, page)` so most scenarios hit the cached
 *  case. */
async function pageFor(username: string, fallback: import('@playwright/test').Page) {
  return pageRegistry.get(username) ?? fallback;
}

// ── Generic API-call steps ────────────────────────────────────────────────

When('the unauthenticated client POSTs to {string}', async ({ page }, path: string) => {
  // Anonymous request — no Authorization header, no sessionStorage
  // priming. We still go through the SPA shell so the request lands
  // at the same origin via fetch.
  await page.goto('/');
  responseState.lastStatus = await page.evaluate(async (p) => {
    const r = await fetch(p, { method: 'POST', body: '{}', headers: { 'Content-Type': 'application/json' } });
    return r.status;
  }, path);
});

When('the unauthenticated client GETs {string}', async ({ page }, path: string) => {
  await page.goto('/');
  responseState.lastStatus = await page.evaluate(async (p) => {
    const r = await fetch(p);
    return r.status;
  }, path);
});

When('{string} POSTs an empty body to {string}', async ({ page }, username: string, path: string) => {
  const p = await pageFor(username, page);
  const token = await authToken(p);
  responseState.lastStatus = await p.evaluate(async ({ url, t }) => {
    const r = await fetch(url, {
      method: 'POST',
      headers: t ? { Authorization: `Bearer ${t}` } : {},
    });
    return r.status;
  }, { url: path, t: token });
});

When('{string} POSTs grant-mod JSON to {string} targeting {string} on game {string}',
     async ({ page }, username: string, path: string, targetCasdoorId: string, gameSlug: string) => {
  // Synthesise the GrantRequest body inline so the .feature line
  // reads as a sentence — playwright-bdd's docstring support hasn't
  // been wired here yet, so we keep step args as plain {string}s.
  const p = await pageFor(username, page);
  const token = await authToken(p);
  const body = JSON.stringify({ targetCasdoorId, gameSlug });
  responseState.lastStatus = await p.evaluate(async ({ url, t, b }) => {
    const r = await fetch(url, {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
        ...(t ? { Authorization: `Bearer ${t}` } : {}),
      },
      body: b,
    });
    return r.status;
  }, { url: path, t: token, b: body });
});

When('{string} GETs {string}', async ({ page }, username: string, path: string) => {
  const p = await pageFor(username, page);
  const token = await authToken(p);
  responseState.lastStatus = await p.evaluate(async ({ url, t }) => {
    const r = await fetch(url, {
      headers: t ? { Authorization: `Bearer ${t}` } : {},
    });
    return r.status;
  }, { url: path, t: token });
});

When('{string} attempts to upload an empty bundle for game {string}',
     async ({ page }, username: string, gameSlug: string) => {
  // Mimics the publisher console's POST shape — multipart/form-data
  // with a `game` slug field + a `bundle` file. The body is one byte
  // so the auth gate's decision is observable before the body parser
  // can chew through it.
  const p = await pageFor(username, page);
  const token = await authToken(p);
  responseState.lastStatus = await p.evaluate(async ({ slug, t }) => {
    const fd = new FormData();
    fd.append('gameSlug', slug);
    fd.append('bundle', new Blob([new Uint8Array([0])], { type: 'application/zip' }), 'fake.zip');
    const r = await fetch('/api/admin/games/upload', {
      method: 'POST',
      headers: t ? { Authorization: `Bearer ${t}` } : {},
      body: fd,
    });
    return r.status;
  }, { slug: gameSlug, t: token });
});

// ── Response-status assertions ────────────────────────────────────────────
// `Then the response status is {int}` lives in session-steps.ts — we
// reuse it. The "one of" variant is new because the existing one only
// handles a single expected code.

Then('the response status is one of {int} or {int}', async ({}, a: number, b: number) => {
  expect([a, b]).toContain(responseState.lastStatus);
});

// ── Maker run-after-publish verification ──────────────────────────────────
// Used by the "build then a player can run" scenarios in the maker
// feature files. We don't switch Casdoor sessions mid-scenario (which
// races Casdoor's login tracking when admin + player both run in
// quick succession); instead we mint a player token inline from inside
// the page via POST /api/auth/token and use it to exercise the same
// catalog + bundle paths a player browser session would.

Then('a player can launch chapter {string} of game {string}',
     async ({}, chapterSlug: string, gameSlug: string) => {
  const page = pageRegistry.get('publisher')!;
  const playerPassword = process.env.E2E_PLAYER_PASSWORD || process.env.E2E_PASSWORD || 'e2e-pass';

  const result = await page.evaluate(async ({ pw, g, c }) => {
    // The publisher's bearer (the page that ran the maker) is in
    // sessionStorage. We use it for the admin-side grant call below,
    // then mint a separate player JWT through the public /api/auth
    // endpoint and use it for the actual launch — so the assertion
    // proves "after the game was published, a PLAYER (not the
    // publisher) can launch it" rather than re-using the publisher's
    // own access.
    const rawAdmin = window.localStorage.getItem("tw_auth");
    const adminToken = rawAdmin ? (JSON.parse(rawAdmin).accessToken ?? '') : '';
    if (!adminToken) {
      return { ok: false, stage: 'admin-token-missing', status: 0 };
    }

    // Mint a fresh player JWT through nginx + the BFF auth path. Same
    // surface a /login round-trip would take, just from inside the
    // page so we don't lose the publisher's admin session in
    // Casdoor's session tracker.
    const tokenResp = await fetch('/api/auth/token', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ username: 'player', password: pw }),
    });
    if (!tokenResp.ok) {
      return { ok: false, stage: 'token', status: tokenResp.status };
    }
    const tokenJson = (await tokenResp.json()) as { access_token?: string; accessToken?: string };
    const playerToken = tokenJson.access_token ?? tokenJson.accessToken ?? '';
    if (!playerToken) {
      return { ok: false, stage: 'token-shape', status: 200 };
    }

    const headers = { Authorization: `Bearer ${playerToken}` };

    // Maker-built games land in the catalog with NO game_access rows
    // (the upload pipeline registers the game + chapters but does
    // not grant per-role access). Built-in games get seeded grants
    // via Liquibase, but freshly-published games need an admin
    // grant. POST {"scope":"all"} so any authenticated user
    // (including this scenario's player) can launch. Idempotent —
    // the unique constraint absorbs duplicates if the scenario re-runs.
    const grantResp = await fetch(`/api/admin/games/${g}/access`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json', Authorization: `Bearer ${adminToken}` },
      body: JSON.stringify({ scope: 'all' }),
    });
    if (!grantResp.ok && grantResp.status !== 409) {
      // 409 (Conflict) means an "all" row already exists — that's fine.
      return { ok: false, stage: 'grant-access', status: grantResp.status };
    }

    // Catalog: the new game must appear in /api/games for the player.
    const catResp = await fetch('/api/games', { headers });
    if (!catResp.ok) {
      return { ok: false, stage: 'catalog', status: catResp.status };
    }
    const games = (await catResp.json()) as Array<{ id: number; slug: string }>;
    const game = games.find(x => x.slug === g);
    if (!game) {
      return { ok: false, stage: 'catalog-listing', status: 200, listed: games.map(x => x.slug) };
    }

    // Discover the freshly-built chapters via the canonical
    // /api/games/{id}/levels endpoint. Each chapter row carries its
    // own `assetUrl` — which is the bundle path the maker produced
    // (e.g. "bundle/square-row/index.html"), not the slug. The
    // hand-rolled candidate list in the earlier version of this step
    // got this wrong (constructed paths from the slug like
    // "abbott-village/abbott-village-square-row/index.html") and
    // collected 404s.
    const chaptersResp = await fetch(`/api/games/${game.id}/levels`, { headers });
    if (!chaptersResp.ok) {
      return { ok: false, stage: 'chapters', status: chaptersResp.status };
    }
    const chapters = (await chaptersResp.json()) as Array<{ slug: string; assetUrl?: string }>;
    if (chapters.length === 0) {
      return { ok: false, stage: 'chapters-empty', status: 200 };
    }
    // Caller pinned a chapter slug — find it; otherwise just use the
    // first chapter. Either way we exercise the same fetch path.
    const target = chapters.find(ch => ch.slug === c) ?? chapters[0];
    const assetUrl = target.assetUrl || 'index.html';

    // Mint a launch uid — the federated client's canonical pre-fetch
    // flow. This is critical: without the uid path, Spring's
    // route-matching picks the more-specific 3-segment uid pattern
    // for any 3+ segment asset URL, sees an invalid uid, and returns
    // 404 (defense-in-depth response to launch-uid forgery). With
    // the uid in hand, the same path resolves to the legacy serve
    // logic and the file lands.
    const launchResp = await fetch(`/api/games/${g}/launch`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json', ...headers },
    });
    if (!launchResp.ok) {
      return { ok: false, stage: 'launch', status: launchResp.status };
    }
    const launchJson = (await launchResp.json()) as { uid?: string };
    const uid = launchJson.uid ?? '';

    const tried: Array<{ url: string; status: number }> = [];
    // Primary: uid-gated asset path. Same predicate the federated
    // remote uses in production after POST /api/games/{slug}/launch.
    // Also probe the sibling WASM payload — a 200 on index.html alone passed even
    // for the zero-byte STUB bundle, so assert the actual game payload (index.wasm /
    // index.pck) is served too, and capture its byte length so a godot-enabled stack
    // can assert it's a real (non-empty) export, not a placeholder.
    const probeSiblings = async (htmlUrl: string) => {
      const sib = async (name: string) => {
        const u = htmlUrl.replace(/index\.html$/, name);
        try {
          const r = await fetch(u, { headers });
          const len = r.ok ? (await r.arrayBuffer()).byteLength : 0;
          return { url: u, status: r.status, len };
        } catch {
          return { url: u, status: 0, len: 0 };
        }
      };
      return { wasm: await sib('index.wasm'), pck: await sib('index.pck') };
    };

    if (uid) {
      const uidUrl = `/api/assets/levels/${g}/${uid}/${assetUrl}`;
      try {
        const r = await fetch(uidUrl, { headers });
        tried.push({ url: uidUrl, status: r.status });
        if (r.ok) {
          return { ok: true, stage: 'done', status: 200, bundleUrl: uidUrl, tried,
                   assets: await probeSiblings(uidUrl) };
        }
      } catch { /* fall through */ }
    }

    // Fallback: legacy non-uid path. Spring's route matcher prefers
    // the 3-segment uid pattern for any multi-slash filename, so this
    // only resolves when the assetUrl has no embedded slash (e.g. a
    // classpath-backed bundle without the bundle/<area>/ prefix).
    const legacy = `/api/assets/levels/${g}/${assetUrl}`;
    try {
      const r = await fetch(legacy, { headers });
      tried.push({ url: legacy, status: r.status });
      if (r.ok) return { ok: true, stage: 'done', status: 200, bundleUrl: legacy, tried };
    } catch { /* end */ }

    return { ok: false, stage: 'bundle', status: 0, tried, assetUrl, uid, chapterCount: chapters.length };
  }, { pw: playerPassword, g: gameSlug, c: chapterSlug });

  expect(
    result.ok,
    `player launch of ${gameSlug}/${chapterSlug} failed at stage=${result.stage}: ${JSON.stringify(result)}`,
  ).toBe(true);

  // The bundle must serve its WASM payload, not just the HTML shell (the old check
  // passed on a zero-byte stub).
  const assets = (result as { assets?: { wasm: { status: number; len: number }; pck: { status: number; len: number } } }).assets;
  if (assets) {
    expect(assets.wasm.status, `index.wasm not served for ${gameSlug}/${chapterSlug}`).toBe(200);
    expect(assets.pck.status, `index.pck not served for ${gameSlug}/${chapterSlug}`).toBe(200);
    // On a godot-enabled stack the export is real — assert it carries actual bytes.
    if (process.env.E2E_GODOT_ENABLED === 'true') {
      expect(assets.wasm.len, `index.wasm is empty (stub?) for ${gameSlug}/${chapterSlug}`).toBeGreaterThan(0);
      expect(assets.pck.len, `index.pck is empty (stub?) for ${gameSlug}/${chapterSlug}`).toBeGreaterThan(0);
    }
  }
});
