// Step definitions backing features/multiplayer/*.feature.
//
// The lobby + control + cross-player SSE flow mirrors the handwritten
// Playwright spec at tests/multiplayer-same-session.spec.ts; the steps
// here are thin wrappers so the BDD presentation layer reuses the same
// helpers and the contract stays in lockstep.
//
// Why drive the lobby UI (not the /api/sessions REST surface)? The home
// component reads activeSessionId from the lobby state to thread
// ?session_id= onto the iframe URL. A direct API call skips that wire
// and the wrapper subscribes to the unscoped /api/game/stream — the
// cross-player isolation assertion would then false-positive every
// session pair.

import { expect, Page } from '@playwright/test';
import { Given, When, Then } from '../support/fixtures';
import { pageRegistry, sessionByName } from './_registry';
import { sendControl } from '../../tests/helpers/roles';
import { captureIframeSseEvents, drainCapturedEvents } from '../../tests/helpers/sse-bridge';

function pageFor(username: string): Page {
  const page = pageRegistry.get(username);
  if (!page) throw new Error(`No page registered for "${username}". Did the scenario stage a browser context first?`);
  return page;
}

/**
 * Track each user's `/api/game/stream?sessionId=` responses. The
 * `page.waitForResponse` predicate only matches FUTURE responses, so
 * if the iframe opens its EventSource BEFORE the predicate is set
 * (e.g. the WASM compile finishes early in a warm-cache run), the
 * subsequent "is subscribed" step would time out spuriously. We
 * install a long-lived `page.on('response')` listener as soon as the
 * iframes mount and poll this set instead.
 */
const sseOpenSeen = new Map<string, boolean>();

function watchSseOpens(username: string, page: Page): void {
  sseOpenSeen.set(username, false);
  page.on('response', resp => {
    if (resp.url().includes('/api/game/stream?sessionId=')) {
      sseOpenSeen.set(username, true);
    }
  });
}

// ── Lobby create / join (drives the modal so activeSessionId is set) ─────

When('{string} creates a public lobby session named {string} for game {string} with character {int}',
     async ({}, username: string, sessionName: string, gameSlug: string, characterId: number) => {
  const page = pageFor(username);
  await page.goto('/?characterId=' + characterId, { waitUntil: 'domcontentloaded' });

  // The catalog renders one .game-card per discoverable game. Pick the
  // one with the matching slug so the scenario isn't coupled to card
  // ordering.
  const card = page.locator(`.game-card[data-game-slug="${gameSlug}"], .game-card:has-text("${gameSlug}")`).first();
  await card.waitFor({ state: 'visible', timeout: 20_000 });
  await card.click();

  await page.locator('#session-name-input').fill(sessionName);
  await page.locator('input[type="radio"][value="public"]').check();
  await page.locator('button.create-btn').click();
  await page.locator('.session-picker-active').waitFor({ state: 'visible', timeout: 15_000 });

  // Resolve the freshly-created session id by asking the BFF — the
  // lobby DOM doesn't surface ids directly. Stash for downstream
  // steps that need to call /api/controls with X-Session-Id.
  const sessionId = await page.evaluate(async ({ name, g }) => {
    const raw = window.sessionStorage.getItem('tw_auth');
    const token = raw ? (JSON.parse(raw).accessToken ?? '') : '';
    const r = await fetch(`/api/sessions?gameSlug=${encodeURIComponent(g)}`, {
      headers: { Authorization: `Bearer ${token}` },
    });
    const list = (await r.json()) as Array<{ id: string; name: string }>;
    return (list.find(s => s.name === name) ?? list[0]).id;
  }, { name: sessionName, g: gameSlug });
  sessionByName.set(sessionName, { id: sessionId, gameSlug });
});

When('{string} joins the lobby session named {string} for game {string} with character {int}',
     async ({}, username: string, sessionName: string, gameSlug: string, characterId: number) => {
  const page = pageFor(username);
  await page.goto('/?characterId=' + characterId, { waitUntil: 'domcontentloaded' });

  const card = page.locator(`.game-card[data-game-slug="${gameSlug}"], .game-card:has-text("${gameSlug}")`).first();
  await card.waitFor({ state: 'visible', timeout: 20_000 });
  await card.click();

  // .session-row carries the session name + a Join button. Filter to
  // the row whose name matches and click its Join.
  const row = page.locator('.session-row', {
    has: page.locator('.session-name', { hasText: sessionName }),
  }).first();
  await row.waitFor({ state: 'visible', timeout: 15_000 });
  await row.locator('button.join-btn').click();
  await page.locator('.session-picker-active').waitFor({ state: 'visible', timeout: 15_000 });
});

When('{string} launches the first chapter from the lobby', async ({}, username: string) => {
  const page = pageFor(username);
  const play = page.locator('.modal-backdrop button.play-btn:not([disabled])').first();
  await play.waitFor({ state: 'visible', timeout: 15_000 });
  await play.click();
});

Then('both {string} and {string} have an iframe mounted',
     async ({}, a: string, b: string) => {
  const pa = pageFor(a);
  const pb = pageFor(b);
  // Watch the network for the eventual EventSource open BEFORE we
  // wait for it — `page.waitForResponse` only matches future
  // responses, so a fast warm-cache run can race past it.
  watchSseOpens(a, pa);
  watchSseOpens(b, pb);
  await Promise.all([
    pa.waitForFunction(() => document.querySelectorAll('iframe.wasm-frame').length > 0, undefined, { timeout: 60_000 }),
    pb.waitForFunction(() => document.querySelectorAll('iframe.wasm-frame').length > 0, undefined, { timeout: 60_000 }),
  ]);
  // Install the iframe-scope capture once both iframes have attached.
  // The context-init script (installSseCapture in auth-steps) handles
  // the top-level window; the iframe's contentWindow needs its own
  // listener — same pattern as multiplayer-same-session.spec.ts.
  await captureIframeSseEvents(pa);
  await captureIframeSseEvents(pb);
});

Then('{string} is subscribed to its session SSE stream', async ({}, username: string) => {
  const page = pageFor(username);
  // Poll the network-event flag set by `watchSseOpens`. This catches
  // both the past-arrival case (already fired) and the future-arrival
  // case (still pending) without missing either. 180 s is empirical
  // headroom for two concurrent cold-image WASM compiles to finish.
  const deadline = Date.now() + 180_000;
  while (Date.now() < deadline) {
    if (sseOpenSeen.get(username)) return;
    await page.waitForTimeout(500);
  }
  throw new Error(`"${username}" did not open its /api/game/stream?sessionId= within 180 s`);
});

// ── Controls + cross-player SSE assertion ────────────────────────────────

When('{string} sends a {word} control for character {int} in session {string}',
     async ({}, username: string, action: string, characterId: number, sessionName: string) => {
  const page = pageFor(username);
  const session = sessionByName.get(sessionName);
  if (!session) throw new Error(`No session named "${sessionName}" — Did the create/join step run?`);
  await sendControl(page, characterId, {
    action,
    pressed:   true,
    chapterId: 1,
    sessionId: session.id,
  });
});

Then('{string} receives at least one multiplayer SSE event within {int} seconds',
     async ({}, username: string, seconds: number) => {
  const page = pageFor(username);
  const deadline = Date.now() + seconds * 1_000;
  let total = 0;
  while (Date.now() < deadline) {
    const events = await drainCapturedEvents(page);
    total += events.filter(e => e.type === 'SD_SSE_EVENT').length;
    if (total > 0) break;
    await page.waitForTimeout(500);
  }
  expect(
    total,
    `"${username}" must receive at least one SD_SSE_EVENT within ${seconds}s — none observed`,
  ).toBeGreaterThan(0);
});
