// Steps backing features/admin/oversight-and-pause.feature.
//
// API-style assertions: every step talks to the BFF via
// `page.evaluate(fetch ...)` from a signed-in browser context so the
// auth interceptor + bearer header flow is exercised end-to-end.
// We launch the browser even for API-only checks so the AfterStep
// screenshot hook can attach the admin dashboard frame as visual
// evidence — the resulting PNGs feed `make screenshots-from-e2e`
// and become figures in the architecture PDF.
//
// Multi-user scenarios use the existing `loginInto` pattern from
// auth-steps.ts via direct `login(...)` calls; per-user pages live
// in the shared `pageRegistry`.

import { expect, Browser, Page } from '@playwright/test';
import { Given, When, Then } from '../support/fixtures';
import { login, newE2eContext, SeedUser, createSession } from '../../tests/helpers/roles';
import { ctxRegistry, pageRegistry, sessionByName, responseState } from './_registry';

interface ApiState {
  lastStatus:  number;
  lastBody:    unknown;
  /** Most recent session id captured by a step that POSTed to
   *  /api/sessions/{id}/... — referenced by the next step as `{id}`. */
  lastSessionId: string | null;
  /** Sessions visible in the admin oversight baseline, captured before
   *  each scenario so "new" session creates can be asserted on. */
  baselineNames: Set<string>;
}
const api: ApiState = { lastStatus: 0, lastBody: null, lastSessionId: null, baselineNames: new Set() };

interface StreamCaptureState {
  page:    Page | null;
  events:  Array<{ action: string; reason: string | null; sessionId: string }>;
}
const stream: StreamCaptureState = { page: null, events: [] };

async function ensureSignedIn(browser: Browser, seedName: string): Promise<Page> {
  const existing = pageRegistry.get(seedName);
  if (existing) return existing;
  const ctx = await newE2eContext(browser);
  const page = await ctx.newPage();
  const seedUser: SeedUser = {
    username:    seedName,
    passwordEnv: `E2E_${seedName.toUpperCase().replace(/-/g, '_')}_PASSWORD`,
    kind:        seedName === 'admin'     ? 'admin'
              :  seedName === 'publisher' ? 'publisher'
              :  seedName === 'mod'       ? 'mod'
              :                              'player',
  };
  await login(page, seedUser);
  ctxRegistry.set(seedName, ctx);
  pageRegistry.set(seedName, page);
  return page;
}

function pageFor(username: string): Page {
  const page = pageRegistry.get(username);
  if (!page) throw new Error(`No page registered for "${username}".`);
  return page;
}

async function callBff(
  page: Page,
  method: string,
  path: string,
  body: unknown = null,
): Promise<{ status: number; body: unknown }> {
  return await page.evaluate(async ({ method, path, body }) => {
    const raw = window.localStorage.getItem('tw_auth');
    const token = raw ? (JSON.parse(raw).accessToken ?? '') : '';
    const init: RequestInit = {
      method,
      headers: {
        'Content-Type': 'application/json',
        Authorization: `Bearer ${token}`,
      },
    };
    if (body != null) init.body = JSON.stringify(body);
    const r = await fetch(path, init);
    const text = await r.text();
    let parsed: unknown = null;
    try { parsed = text ? JSON.parse(text) : null; } catch { parsed = text; }
    return { status: r.status, body: parsed };
  }, { method, path, body });
}

function resolvePath(template: string): string {
  if (!template.includes('{id}')) return template;
  if (!api.lastSessionId) throw new Error(`Path template uses {id} but no session captured: ${template}`);
  return template.replace('{id}', encodeURIComponent(api.lastSessionId));
}

// ── Given steps ───────────────────────────────────────────────────────────

Given('the admin is signed in', async ({ browser }) => { await ensureSignedIn(browser, 'admin'); });

Given('the admin has rebuilt a baseline list of open sessions', async () => {
  const adminPage = pageFor('admin');
  const resp = await callBff(adminPage, 'GET', '/api/admin/multiplayer-sessions');
  api.baselineNames.clear();
  if (Array.isArray(resp.body)) {
    for (const s of resp.body as Array<{ name: string }>) {
      if (s && typeof s.name === 'string') api.baselineNames.add(s.name);
    }
  }
});

Given(/^"(.+)" has created a (public|friends-only|private) session named "(.+)" on game "(.+)"$/,
  async ({ browser }, username: string, visibility: string, name: string, gameSlug: string) => {
    await ensureSignedIn(browser, username);
    const visEnum = visibility === 'friends-only' ? 'friends'
                  : visibility === 'private'      ? 'private'
                  :                                 'public';
    const session = await createSession(pageFor(username), {
      gameSlug, name, maxPlayers: 3,
      visibility: visEnum as 'public' | 'friends' | 'private',
      characterId: 1, slotIndex: 0,
    });
    sessionByName.set(name, { id: session.id, gameSlug: session.gameSlug });
    api.lastSessionId = session.id;
  });

Given('{string} has created and paused a session named {string} on game {string}',
  async ({ browser }, username: string, name: string, gameSlug: string) => {
    await ensureSignedIn(browser, username);
    const session = await createSession(pageFor(username), {
      gameSlug, name, maxPlayers: 3, visibility: 'public', characterId: 1, slotIndex: 0,
    });
    sessionByName.set(name, { id: session.id, gameSlug: session.gameSlug });
    api.lastSessionId = session.id;
    const resp = await callBff(pageFor(username), 'POST',
      `/api/sessions/${encodeURIComponent(session.id)}/pause`,
      { reason: 'fixture-paused' });
    if (resp.status !== 200) {
      throw new Error(`pause fixture failed: ${resp.status} ${JSON.stringify(resp.body)}`);
    }
  });

Given('{string} has joined the {string} session',
  async ({ browser }, username: string, sessionName: string) => {
    await ensureSignedIn(browser, username);
    const sess = sessionByName.get(sessionName);
    if (!sess) throw new Error(`unknown session ${sessionName}`);
    const resp = await callBff(pageFor(username), 'POST',
      `/api/sessions/${encodeURIComponent(sess.id)}/join`,
      { characterId: 2, slotIndex: 1 });
    if (resp.status !== 200) {
      throw new Error(`join fixture failed: ${resp.status} ${JSON.stringify(resp.body)}`);
    }
  });

Given(/^the admin is streaming \/api\/admin\/sessions\/\{id\}\/stream$/, async () => {
  const page = pageFor('admin');
  if (!api.lastSessionId) throw new Error('no session captured');
  stream.events = [];
  stream.page = page;
  await page.evaluate((sessionId) => {
    const w = window as unknown as {
      _twAdminCtl?: { events: Array<{action: string; reason: string|null; sessionId: string}>; src: EventSource };
    };
    const raw = window.localStorage.getItem('tw_auth');
    const token = raw ? (JSON.parse(raw).accessToken ?? '') : '';
    // EventSource doesn't support custom headers; the admin stream
    // requires the bearer via query for headless test contexts.
    // The BFF's SecurityConfig accepts both bearer and the
    // ?access_token= override for dev/test profiles.
    const src = new EventSource(
      `/api/admin/sessions/${encodeURIComponent(sessionId)}/stream?access_token=${encodeURIComponent(token)}`);
    const bag: Array<{action: string; reason: string|null; sessionId: string}> = [];
    src.addEventListener('session-control', e => {
      try {
        const data = JSON.parse((e as MessageEvent).data);
        bag.push({ action: data.action, reason: data.reason, sessionId: data.sessionId });
      } catch { /* */ }
    });
    w._twAdminCtl = { events: bag, src };
  }, api.lastSessionId);
});

// ── When steps ────────────────────────────────────────────────────────────

When('the admin GETs {string}', async ({}, path: string) => {
  const resp = await callBff(pageFor('admin'), 'GET', path);
  api.lastStatus = resp.status;
  responseState.lastStatus = resp.status;
  api.lastBody   = resp.body;
});

// Combined matcher so playwright-bdd doesn't pick between two regexps
// for the same line. Optional capture (3) holds the reason payload —
// empty when the Gherkin line omits the "with reason ..." clause.
When(/^"(.+)" POSTs to "(.+)"(?: with reason "(.+)")?$/,
  async ({}, username: string, pathTemplate: string, reason: string | undefined) => {
    const page = pageFor(username);
    const body = reason ? { reason } : {};
    const resp = await callBff(page, 'POST', resolvePath(pathTemplate), body);
    api.lastStatus = resp.status;
    responseState.lastStatus = resp.status;
    api.lastBody   = resp.body;
    if (typeof resp.body === 'object' && resp.body !== null && 'id' in (resp.body as object)) {
      api.lastSessionId = (resp.body as { id: string }).id;
    }
  });

When(/^the admin POSTs to "(.+)" with reason "(.+)"$/,
  async ({}, pathTemplate: string, reason: string) => {
    const resp = await callBff(pageFor('admin'), 'POST', resolvePath(pathTemplate), { reason });
    api.lastStatus = resp.status;
  responseState.lastStatus = resp.status;
    api.lastBody   = resp.body;
  });

When('{string} DELETEs {string}', async ({}, username: string, pathTemplate: string) => {
  const resp = await callBff(pageFor(username), 'DELETE', resolvePath(pathTemplate));
  api.lastStatus = resp.status;
  responseState.lastStatus = resp.status;
  api.lastBody   = resp.body;
});

// ── Then steps ────────────────────────────────────────────────────────────

Then('the response contains a session named {string}', async ({}, name: string) => {
  expect(Array.isArray(api.lastBody)).toBe(true);
  const arr = api.lastBody as Array<{ name: string }>;
  expect(arr.some(s => s?.name === name),
    `oversight list did not contain "${name}". Names: ${arr.map(s=>s.name).join(', ')}`).toBe(true);
});

Then('the response does not contain a session named {string}', async ({}, name: string) => {
  expect(Array.isArray(api.lastBody)).toBe(true);
  const arr = api.lastBody as Array<{ name: string }>;
  expect(arr.some(s => s?.name === name)).toBe(false);
});

// Reusing existing matcher from session-steps.ts: `Then('the response
// status is {int}', ...)` checks `responseState.lastStatus` — every
// step here mirrors `api.lastStatus → responseState.lastStatus` after
// each call so the shared matcher works without a parallel definition.

Then('the response body has a non-null pausedAt', async () => {
  const dto = api.lastBody as { pausedAt: string | null };
  expect(dto.pausedAt).toBeTruthy();
});

Then('the response body\'s pauseReason is {string}', async ({}, reason: string) => {
  const dto = api.lastBody as { pauseReason: string | null };
  expect(dto.pauseReason).toBe(reason);
});

Then('the response body\'s pauseReason is null', async () => {
  const dto = api.lastBody as { pauseReason: string | null };
  expect(dto.pauseReason).toBeNull();
});

Then('the response body\'s pausedAt is null', async () => {
  const dto = api.lastBody as { pausedAt: string | null };
  expect(dto.pausedAt).toBeNull();
});

Then('the response body\'s pausedByCasdoorId is {string}', async ({}, expected: string) => {
  const dto = api.lastBody as { pausedByCasdoorId: string | null };
  expect(dto.pausedByCasdoorId).toBe(expected);
});

Then(/^the admin sees a paused indicator on the session in \/api\/admin\/multiplayer-sessions$/,
  async () => {
    const resp = await callBff(pageFor('admin'), 'GET', '/api/admin/multiplayer-sessions');
    expect(resp.status).toBe(200);
    const list = resp.body as Array<{ id: string; pausedAt: string | null }>;
    const found = list.find(s => s.id === api.lastSessionId);
    expect(found, 'session missing from oversight list').toBeTruthy();
    expect(found!.pausedAt).toBeTruthy();
  });

Then(/^a session-control event with action "(.+)" and reason "(.+)" arrives on the admin stream within (\d+) seconds$/,
  async ({}, action: string, reason: string, seconds: number) => {
    if (!stream.page || !api.lastSessionId) throw new Error('admin stream not initialised');
    const deadline = Date.now() + seconds * 1000;
    while (Date.now() < deadline) {
      const events = await stream.page.evaluate(() => {
        const w = window as unknown as { _twAdminCtl?: { events: Array<{action: string; reason: string|null; sessionId: string}> }};
        return w._twAdminCtl?.events ?? [];
      });
      if (events.some(e => e.action === action && e.reason === reason && e.sessionId === api.lastSessionId)) {
        return;
      }
      await new Promise(r => setTimeout(r, 250));
    }
    throw new Error(`expected ${action}/${reason} on admin stream within ${seconds}s — never arrived`);
  });

Then(/^the session's closedAt is non-null in \/api\/admin\/multiplayer-sessions list response$/,
  async () => {
    // The list endpoint only returns OPEN sessions (closed_at IS NULL),
    // so a closed session disappears — that's the assertion shape.
    const resp = await callBff(pageFor('admin'), 'GET', '/api/admin/multiplayer-sessions');
    expect(resp.status).toBe(200);
    const list = resp.body as Array<{ id: string }>;
    expect(list.some(s => s.id === api.lastSessionId),
      'closed session unexpectedly still listed as open').toBe(false);
  });
