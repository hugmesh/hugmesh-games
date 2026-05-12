// Steps for features/makers/pause-aware-content.feature.
//
// Talks to the rpg-builder + vn-builder side services via the page's
// authenticated fetch helper (same pattern as
// admin-oversight-pause-steps.ts) — the side services share the
// host's Casdoor JWT.
//
// The screenshots harvested by `make screenshots-from-e2e` feed
// figures into the maker chapter of the architecture PDF; this is
// why we drive through a real browser page even though the
// assertions are on JSON bodies.

import { expect, Browser, Page } from '@playwright/test';
import { Given, When, Then } from '../support/fixtures';
import { login, newE2eContext, SeedUser } from '../../tests/helpers/roles';
import { ctxRegistry, pageRegistry } from './_registry';

interface MakerState {
  rpgProjectId: string | null;
  rpgAreaId:    string | null;
  rpgRoomId:    string | null;
  rpgScriptId:  string | null;
  vnProjectId:  string | null;
  vnActId:      string | null;
  vnSceneId:    string | null;
  vnLineId:     string | null;
  lastResponse: { status: number; body: unknown };
}
const m: MakerState = {
  rpgProjectId: null, rpgAreaId: null, rpgRoomId: null, rpgScriptId: null,
  vnProjectId:  null, vnActId:  null, vnSceneId: null, vnLineId:    null,
  lastResponse: { status: 0, body: null },
};

async function ensurePublisher(browser: Browser): Promise<Page> {
  const existing = pageRegistry.get('publisher');
  if (existing) return existing;
  const ctx = await newE2eContext(browser);
  const page = await ctx.newPage();
  const seedUser: SeedUser = {
    username:    'publisher',
    passwordEnv: 'E2E_PUBLISHER_PASSWORD',
    kind:        'publisher',
  };
  await login(page, seedUser);
  ctxRegistry.set('publisher', ctx);
  pageRegistry.set('publisher', page);
  return page;
}

async function callApi(
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
      headers: { 'Content-Type': 'application/json', Authorization: `Bearer ${token}` },
    };
    if (body != null) init.body = JSON.stringify(body);
    const r = await fetch(path, init);
    const text = await r.text();
    let parsed: unknown = null;
    try { parsed = text ? JSON.parse(text) : null; } catch { parsed = text; }
    return { status: r.status, body: parsed };
  }, { method, path, body });
}

// ── RPG ───────────────────────────────────────────────────────────────────

Given('the publisher is signed in', async ({ browser }) => { await ensurePublisher(browser); });

Given('the publisher has a fresh RPG project named {string}', async ({ browser }, name: string) => {
  const page = await ensurePublisher(browser);
  const resp = await callApi(page, 'POST', '/api/rpg/projects',
    { slug: name.toLowerCase().replace(/[^a-z0-9]+/g, '-') + '-' + Date.now().toString(36),
      displayName: name });
  expect(resp.status, `create project body=${JSON.stringify(resp.body)}`).toBeLessThan(400);
  m.rpgProjectId = (resp.body as { id: string }).id;
});

Given('the project has a default area', async () => {
  // RPG rooms must reference an area (createRoom rejects with 400
  // otherwise). Create one with a per-run unique slug so re-runs
  // against a non-fresh DB don't 409.
  const page = pageRegistry.get('publisher')!;
  if (!m.rpgProjectId) throw new Error('no rpg project');
  const uniqueSlug = 'main-' + Date.now().toString(36);
  const resp = await callApi(page, 'POST',
    `/api/rpg/projects/${m.rpgProjectId}/areas`,
    { slug: uniqueSlug, name: 'Main area' });
  expect(resp.status, JSON.stringify(resp.body)).toBeLessThan(400);
  m.rpgAreaId = (resp.body as { id: string }).id;
});

When(/^the publisher creates a room with slug "(.+)", name "(.+)", pause_on_enter (true|false|omitted)$/,
  async ({}, slug: string, name: string, pauseFlag: string) => {
    const page = pageRegistry.get('publisher')!;
    if (!m.rpgProjectId) throw new Error('project not set');
    // Slugs are project-scoped unique, so add a per-run suffix to
    // avoid the 409 on repeat runs.
    const uniqueSlug = `${slug}-${Date.now().toString(36)}`;
    const body: Record<string, unknown> = { slug: uniqueSlug, name };
    if (m.rpgAreaId) body.areaId = m.rpgAreaId;
    if (pauseFlag === 'true')  body.pauseOnEnter = true;
    if (pauseFlag === 'false') body.pauseOnEnter = false;
    const resp = await callApi(page, 'POST',
      `/api/rpg/projects/${m.rpgProjectId}/rooms`,
      body);
    expect(resp.status, JSON.stringify(resp.body)).toBeLessThan(400);
    m.rpgRoomId = (resp.body as { id: string }).id;
    // Stash the POST response body as "read back" — the rpg-builder
    // returns the persisted row in the create response, and no GET-
    // by-id endpoint exists.
    m.lastResponse = resp;
  });

When('the publisher reads the room back', async () => {
  // Already captured at create time (no GET-by-id endpoint exists);
  // re-listing rooms and finding by id would also work but is
  // wasteful for the assertion shape.
  expect(m.lastResponse.status).toBeLessThan(400);
});

Then('the room\'s pauseOnEnter field is true', async () => {
  const r = m.lastResponse.body as { pauseOnEnter: boolean };
  expect(r.pauseOnEnter).toBe(true);
});

Then('the room\'s pauseOnEnter field is false', async () => {
  const r = m.lastResponse.body as { pauseOnEnter: boolean };
  expect(r.pauseOnEnter).toBe(false);
});

When(/^the publisher creates a script with slug "(.+)", name "(.+)", kind "(.+)" and source "(.+)"$/,
  async ({}, slug: string, name: string, kind: string, source: string) => {
    const page = pageRegistry.get('publisher')!;
    const uniqueSlug = `${slug}-${Date.now().toString(36)}`;
    const resp = await callApi(page, 'POST',
      `/api/rpg/projects/${m.rpgProjectId}/scripts`,
      { slug: uniqueSlug, name, kind, source });
    expect(resp.status, JSON.stringify(resp.body)).toBeLessThan(400);
    m.rpgScriptId = (resp.body as { id: string }).id;
    m.lastResponse = resp;
  });

When('the publisher reads the script back', async () => {
  // No GET-by-id; create response body is the persisted row.
  expect(m.lastResponse.status).toBeLessThan(400);
});

Then('the script\'s kind field is {string}', async ({}, expected: string) => {
  const r = m.lastResponse.body as { kind: string };
  expect(r.kind).toBe(expected);
});

// ── VN ────────────────────────────────────────────────────────────────────

Given('the publisher has a fresh VN project named {string}', async ({ browser }, name: string) => {
  const page = await ensurePublisher(browser);
  const resp = await callApi(page, 'POST', '/api/vn/projects',
    { slug: name.toLowerCase().replace(/[^a-z0-9]+/g, '-') + '-' + Date.now().toString(36),
      displayName: name });
  expect(resp.status, JSON.stringify(resp.body)).toBeLessThan(400);
  m.vnProjectId = (resp.body as { id: string }).id;
});

Given('the project has a default act', async () => {
  const page = pageRegistry.get('publisher')!;
  // Slugs are project-scoped unique, so suffix per-run to avoid 409
  // when the test re-runs against a non-fresh DB.
  const uniqueSlug = 'act-1-' + Date.now().toString(36);
  const resp = await callApi(page, 'POST',
    `/api/vn/projects/${m.vnProjectId}/acts`,
    { slug: uniqueSlug, name: 'Act 1' });
  expect(resp.status, JSON.stringify(resp.body)).toBeLessThan(400);
  m.vnActId = (resp.body as { id: string }).id;
});

When(/^the publisher creates a scene with slug "(.+)", name "(.+)", pauses_on_enter (true|false)$/,
  async ({}, slug: string, name: string, pauseFlag: string) => {
    const page = pageRegistry.get('publisher')!;
    const uniqueSlug = `${slug}-${Date.now().toString(36)}`;
    const body: Record<string, unknown> = {
      slug: uniqueSlug, name, pausesOnEnter: pauseFlag === 'true',
    };
    if (m.vnActId) body.actId = m.vnActId;
    const resp = await callApi(page, 'POST',
      `/api/vn/projects/${m.vnProjectId}/scenes`,
      body);
    expect(resp.status, JSON.stringify(resp.body)).toBeLessThan(400);
    m.vnSceneId = (resp.body as { id: string }).id;
    m.lastResponse = resp;
  });

When('the publisher reads the scene back', async () => {
  expect(m.lastResponse.status).toBeLessThan(400);
});

Then('the scene\'s pausesOnEnter field is true', async () => {
  const r = m.lastResponse.body as { pausesOnEnter: boolean };
  expect(r.pausesOnEnter).toBe(true);
});

Given('the act has a scene named {string}', async ({}, name: string) => {
  const page = pageRegistry.get('publisher')!;
  const body: Record<string, unknown> = {
    slug: name.toLowerCase().replace(/[^a-z0-9]+/g, '-') + '-' + Date.now().toString(36),
    name,
  };
  if (m.vnActId) body.actId = m.vnActId;
  const resp = await callApi(page, 'POST',
    `/api/vn/projects/${m.vnProjectId}/scenes`,
    body);
  expect(resp.status, JSON.stringify(resp.body)).toBeLessThan(400);
  m.vnSceneId = (resp.body as { id: string }).id;
});

When(/^the publisher adds a line of kind "(.+)" with text "(.+)"$/,
  async ({}, kind: string, text: string) => {
    const page = pageRegistry.get('publisher')!;
    const resp = await callApi(page, 'POST',
      `/api/vn/projects/${m.vnProjectId}/scenes/${m.vnSceneId}/lines`,
      { lineIndex: 0, text, kind });
    expect(resp.status, JSON.stringify(resp.body)).toBeLessThan(400);
    m.vnLineId = (resp.body as { id: string }).id;
    m.lastResponse = resp;
  });

When(/^the publisher adds a line without specifying a kind, text "(.+)"$/,
  async ({}, text: string) => {
    const page = pageRegistry.get('publisher')!;
    const resp = await callApi(page, 'POST',
      `/api/vn/projects/${m.vnProjectId}/scenes/${m.vnSceneId}/lines`,
      { lineIndex: 0, text });
    expect(resp.status, JSON.stringify(resp.body)).toBeLessThan(400);
    m.vnLineId = (resp.body as { id: string }).id;
    m.lastResponse = resp;
  });

When('the publisher reads the line back', async () => {
  // No GET-by-id; create response body is the persisted row.
  expect(m.lastResponse.status).toBeLessThan(400);
});

Then('the line\'s kind field is {string}', async ({}, expected: string) => {
  const r = m.lastResponse.body as { kind: string };
  expect(r.kind).toBe(expected);
});
