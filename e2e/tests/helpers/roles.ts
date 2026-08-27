import { Browser, BrowserContext, Page } from '@playwright/test';
import * as fs from 'fs';
import * as path from 'path';

/**
 * Catalogue of Casdoor seed users available under the
 * `integration-test` Spring profile. Every entry's password defaults
 * to `resolveE2eEnv('E2E_PASSWORD')` (env, then the run-state
 * credentials file, then throw — never a literal) unless a per-user
 * override env var is exported. Specs iterate this list to drive a
 * per-role test matrix without hard-coding strings inside the
 * `test.describe` body.
 *
 * The `kind` tag drives which role-specific assertions run:
 *  - `player`     can play any game; sees no admin / mod / publisher routes.
 *  - `admin`      sees /admin, /admin/games, /admin/sessions, /admin/docs.
 *  - `mod`        sees /mod scoped to mod_game_scopes.
 *  - `publisher`  sees /publisher scoped to publisher_game_scopes.
 */
export interface SeedUser {
  username:     string;
  passwordEnv:  string;       // env var the docker-compose overlay routes the cred through
  kind:         'player' | 'admin' | 'mod' | 'publisher';
  scopedGame?:  string;       // present on per-game mod / publisher users
}

export const SEED_USERS: SeedUser[] = [
  // Plain players — the multiplayer specs spread three concurrent users
  // across `player1`/`player2`/`player3` so each session has unique slots.
  { username: 'player',  passwordEnv: 'E2E_PLAYER_PASSWORD',  kind: 'player' },
  { username: 'player1', passwordEnv: 'E2E_PLAYER1_PASSWORD', kind: 'player' },
  { username: 'player2', passwordEnv: 'E2E_PLAYER2_PASSWORD', kind: 'player' },
  { username: 'player3', passwordEnv: 'E2E_PLAYER3_PASSWORD', kind: 'player' },

  // Admin — full /admin surface. The session-log spec uses this user
  // to call /api/session-logs/admin/{id} and assert it returns 200
  // for any target.
  { username: 'admin', passwordEnv: 'E2E_ADMIN_PASSWORD', kind: 'admin' },

  // Mods — the generic 'mod' user has no game scope, so /mod loads but
  // shows zero-scope. The per-game variants exercise the
  // mod_game_scopes filter.
  { username: 'mod',                passwordEnv: 'E2E_MOD_PASSWORD',       kind: 'mod' },
  { username: 'mod-three-wizards',  passwordEnv: 'E2E_MOD_TWIAT_PASSWORD', kind: 'mod', scopedGame: 'three-wizards' },
  { username: 'mod-dodecahedron',   passwordEnv: 'E2E_MOD_DODEC_PASSWORD', kind: 'mod', scopedGame: 'dodecahedron'  },

  // Publishers — same shape as mods.
  { username: 'publisher',                passwordEnv: 'E2E_PUBLISHER_PASSWORD', kind: 'publisher' },
  { username: 'publisher-three-wizards',  passwordEnv: 'E2E_PUB_TWIAT_PASSWORD', kind: 'publisher', scopedGame: 'three-wizards' },
  { username: 'publisher-dodecahedron',   passwordEnv: 'E2E_PUB_DODEC_PASSWORD', kind: 'publisher', scopedGame: 'dodecahedron'  },
  // Dev fixture owner — this Casdoor seed user holds the
  // pre-populated Flatland projects in the rpg-builder + vn-builder
  // DBs (Liquibase 004 in each, keyed on the username: the Casdoor
  // sub claim is the username, not the seed id), and the BFF
  // (Liquibase 038, re-keyed by 055-2) grants publisher scope on both
  // flatland-rpg + flatland-vn slugs ahead of any actual upload. Lets
  // a maker e2e scenario log in and find projects ready to build.
  { username: 'publisher-flatland',       passwordEnv: 'E2E_PUB_FLAT_PASSWORD',  kind: 'publisher' },
];

/**
 * Create a fresh browser context that trusts the e2e self-signed cert
 * AND uses the same baseURL as the playwright config. The default
 * `browser.newContext()` does NOT inherit project-level `use` options
 * (Playwright design choice — `newPage`'s fixture does, but
 * `newContext` does not), so a spec that opens a manual context
 * misses `ignoreHTTPSErrors: true` and every HTTPS request fails
 * the cert check. The federation manifest fetch is the first
 * casualty: the host shows the "Couldn't reach Login — the
 * federated module didn't load" banner even though the chunks are
 * served fine.
 *
 * Specs that need multiple users in parallel (friends, multiplayer,
 * isolation) MUST call this instead of `browser.newContext()`.
 *
 * Same non-inheritance pitfall applies to `video: 'on'` — manual
 * contexts record NOTHING unless `recordVideo` is passed explicitly.
 * Scenarios that want webm evidence for manually-created contexts
 * (e.g. the two-player biome scenario) pass
 * `{ recordVideo: { dir: testInfo.outputDir } }` and must close the
 * context (videos are only finalised on context close — register it
 * in ctxRegistry so the After hook does that).
 */
export interface E2eContextOptions {
  recordVideo?: { dir: string; size?: { width: number; height: number } };
}

export async function newE2eContext(
  browser: Browser,
  opts: E2eContextOptions = {},
): Promise<BrowserContext> {
  return await browser.newContext({
    ignoreHTTPSErrors: true,
    baseURL: process.env.E2E_BASE_URL ?? 'https://frontend:8443',
    ...opts,
  });
}

/**
 * Run-state credentials file the Makefile's generator
 * (`tests/stack/e2e-password.sh`, invoked by `make e2e` / `e2e-bdd` /
 * `e2e-godot`) writes `E2E_PASSWORD` into. Format: plain `KEY=value`
 * lines (see CONTRACT.md rule 3).
 *
 * Inside the containerised `e2e` Playwright run this path won't exist
 * (the repo root isn't mounted into the image) — that's fine, because
 * `docker-compose.e2e.yml` already requires `E2E_PASSWORD` to be set on
 * the host before `docker compose` will even start the container, so
 * `process.env` always resolves first there. This fallback matters when
 * running Playwright directly against an already-up stack from the host
 * (`cd e2e && npx playwright test`), where `process.env` may not have
 * been threaded through by hand.
 */
function credentialsFilePath(): string {
  return process.env.E2E_CREDENTIALS_FILE
      ?? path.resolve(__dirname, '../../../.e2e-credentials');
}

let credentialsCache: Record<string, string> | undefined;

function readCredentialsFile(): Record<string, string> {
  if (credentialsCache) return credentialsCache;
  const out: Record<string, string> = {};
  try {
    const raw = fs.readFileSync(credentialsFilePath(), 'utf8');
    for (const line of raw.split('\n')) {
      const m = /^([A-Za-z_][A-Za-z0-9_]*)=(.*)$/.exec(line.trim());
      if (m) out[m[1]] = m[2];
    }
  } catch {
    // Not found / not readable — resolveE2eEnv falls through to throwing.
  }
  credentialsCache = out;
  return out;
}

/**
 * Resolve an e2e credential env var: explicit `process.env` first, then
 * the run-state credentials file the Makefile generator writes, then
 * THROW. Never a literal fallback (CONTRACT.md rule 4) — a silent
 * fallback is the exact bug this replaces: it would let a
 * misconfigured run authenticate with a guessable password and still
 * go green.
 */
export function resolveE2eEnv(name: string): string {
  const fromEnv = process.env[name];
  if (fromEnv) return fromEnv;
  const fromFile = readCredentialsFile()[name];
  if (fromFile) return fromFile;
  throw new Error(
    `${name} is not set and not found in ${credentialsFilePath()}. ` +
    'Run `make e2e` (or e2e-bdd / e2e-godot) first — it generates and ' +
    `writes the shared e2e credentials — or export ${name} yourself.`,
  );
}

/** Resolve a seed user's password — a per-role env override (if
 *  exported) wins; otherwise the shared `E2E_PASSWORD` (env, then the
 *  run-state credentials file, then throw). Never a literal fallback. */
export function passwordFor(user: SeedUser): string {
  const perRole = process.env[user.passwordEnv];
  if (perRole) return perRole;
  return resolveE2eEnv('E2E_PASSWORD');
}

/**
 * Wait for the federated login form to be interactable. Two
 * fragility surfaces:
 *
 *   1. `domcontentloaded` fires before the federation runtime has
 *      finished initialising — `__HUGMESH_LOAD_REMOTE_MODULE__`
 *      isn't on `globalThis` yet, so any route trying to lazy-load
 *      a federated remote rejects → NavigationError → "Couldn't
 *      reach Login" banner.
 *   2. Even after init, the manifest fetch for the login bundle is
 *      sometimes flaky on a cold image. The host already exposes
 *      a Retry button on that banner; we click it up to 3 times.
 */
export async function ensureLoginFormReady(page: Page): Promise<void> {
  // Best-effort wait for federation init. The retry loop below
  // recovers if this races; the wait just gives the common path a
  // smoother start.
  await page.waitForFunction(
    () => Boolean((globalThis as unknown as Record<string, unknown>)
        .__HUGMESH_FEDERATION_MANIFEST__),
    undefined,
    { timeout: 5_000 },
  ).catch(() => { /* fall through to the retry loop */ });

  for (let attempt = 1; attempt <= 4; attempt++) {
    const input = page.locator('input[formControlName="username"]');
    try {
      await input.waitFor({ state: 'visible', timeout: 8_000 });
      return;
    } catch {
      // Federation banner — click Retry, then loop.
      const retry = page.locator('button.federation-error-retry');
      if ((await retry.count()) > 0) {
        await retry.click();
      } else {
        // Hard reload — last resort.
        await page.reload({ waitUntil: 'domcontentloaded' });
      }
    }
  }
  // Final wait — long enough that we get a clear failure if the
  // remote really never came up.
  await page.locator('input[formControlName="username"]')
    .waitFor({ state: 'visible', timeout: 15_000 });
}

/** Drive the federated login form and wait for the home route + a
 *  populated `tw_auth` in localStorage. The form inputs hang off
 *  `formControlName` because the login bundle is its own federated
 *  remote and that's the public selector contract. The
 *  `waitForFunction` after the URL transition guards against a race
 *  where the spec's first API call lands before AuthService has
 *  written the token. */
export async function login(page: Page, user: SeedUser): Promise<void> {
  await page.goto('/login', { waitUntil: 'domcontentloaded' });
  await ensureLoginFormReady(page);
  await page.fill('input[formControlName="username"]', user.username);
  await page.fill('input[formControlName="password"]', passwordFor(user));
  await page.click('button.login-btn');
  // 60 s budget here is intentional — federated `login` remote +
  // Casdoor /api/auth/token + the host's federation init can stack
  // up to ~30 s on a cold worker, and Live Sessions UI scenarios
  // sign in 3 users in sequence so per-login slack is needed.
  await page.waitForURL((url) => !url.pathname.startsWith('/login'), { timeout: 60_000 });
  await page.waitForFunction(
    () => {
      const raw = window.localStorage.getItem('tw_auth');
      if (!raw) return false;
      try {
        return Boolean(JSON.parse(raw).accessToken);
      } catch {
        return false;
      }
    },
    undefined,
    { timeout: 15_000 },
  );
}

export async function logout(page: Page): Promise<void> {
  // Best-effort — the sign-out button is exposed on the top nav for
  // every authenticated route. Tests use this between role swaps so
  // we don't carry a stale JWT into the next login.
  const btn = page.locator('button.logout-btn');
  if ((await btn.count()) > 0) {
    await btn.first().click();
    await page.waitForURL('**/login', { timeout: 15_000 });
  }
}

/** Read the access token off `localStorage['tw_auth']` so tests can
 *  hit the BFF directly via `page.evaluate(fetch)` without going
 *  through the iframe's host-bridged path. */
export async function authToken(page: Page): Promise<string | null> {
  return await page.evaluate(() => {
    const raw = window.localStorage.getItem('tw_auth');
    if (!raw) return null;
    try {
      return (JSON.parse(raw) as { accessToken?: string }).accessToken ?? null;
    } catch {
      return null;
    }
  });
}

/** POST /api/sessions with the caller's JWT. Returns the created
 *  GameSession DTO — `{id,name,gameSlug,visibility,...}`. */
export async function createSession(
  page: Page,
  body: {
    gameSlug:    string;
    name:        string;
    maxPlayers?: number;
    visibility?: 'public' | 'friends' | 'private';
    characterId?: number;
    slotIndex?:   number;
  },
): Promise<{ id: string; name: string; gameSlug: string; visibility: string; maxPlayers: number; }> {
  const result = await page.evaluate(async (req) => {
    const raw = window.localStorage.getItem('tw_auth');
    const token = raw ? (JSON.parse(raw).accessToken ?? '') : '';
    const r = await fetch('/api/sessions', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json', Authorization: `Bearer ${token}` },
      body: JSON.stringify(req),
    });
    const text = await r.text();
    let json: unknown = null;
    try { json = text ? JSON.parse(text) : null; } catch { json = text; }
    return { status: r.status, body: json };
  }, body);
  if (result.status < 200 || result.status >= 300) {
    throw new Error(`createSession failed: status ${result.status} body=${JSON.stringify(result.body)}`);
  }
  return result.body as { id: string; name: string; gameSlug: string; visibility: string; maxPlayers: number; };
}

/** Join a session by id. Returns the updated GameSession DTO. */
export async function joinSession(
  page: Page,
  sessionId: string,
  body: { characterId?: number; slotIndex?: number } = {},
): Promise<unknown> {
  return await page.evaluate(async ({ sid, payload }) => {
    const raw = window.localStorage.getItem('tw_auth');
    const token = raw ? (JSON.parse(raw).accessToken ?? '') : '';
    const r = await fetch(`/api/sessions/${sid}/join`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json', Authorization: `Bearer ${token}` },
      body: JSON.stringify(payload),
    });
    if (!r.ok) throw new Error(`joinSession failed: status ${r.status}`);
    return await r.json();
  }, { sid: sessionId, payload: body });
}

/** Issue a control event — the iframe's keyboard handler does the same
 *  POST. Used by the multiplayer spec to drive a remote slot's avatar
 *  without typing into the iframe (which would require focus). */
export async function sendControl(
  page: Page,
  characterId: number,
  body: { action: string; pressed: boolean; chapterId?: number; sessionId?: string },
): Promise<void> {
  await page.evaluate(async ({ cid, payload, sid }) => {
    const raw = window.localStorage.getItem('tw_auth');
    const token = raw ? (JSON.parse(raw).accessToken ?? '') : '';
    const headers: Record<string, string> = {
      'Content-Type': 'application/json',
      Authorization: `Bearer ${token}`,
    };
    if (sid) headers['X-Session-Id'] = sid;
    await fetch(`/api/controls/${cid}`, {
      method: 'POST',
      headers,
      body: JSON.stringify({ ...payload, timestamp: Date.now() }),
    });
  }, { cid: characterId, payload: body, sid: body.sessionId });
}
