// Steps backing the lobby + session-log features. Wraps helpers/roles.ts
// for plain Playwright reuse.

import { expect, Page } from '@playwright/test';
import { Given, When, Then } from '../support/fixtures';
import { createSession } from '../../tests/helpers/roles';
import { pageRegistry, sessionByName, responseState } from './_registry';

function pageFor(username: string): Page {
  const page = pageRegistry.get(username);
  if (!page) throw new Error(`No logged-in page for "${username}". Add a Given/And step to set one up.`);
  return page;
}

Given('{string} creates a public session named {string} in Three Wizards',
  async ({}, username: string, name: string) => {
    const session = await createSession(pageFor(username), {
      gameSlug: 'three-wizards', name, maxPlayers: 3, visibility: 'public', characterId: 1, slotIndex: 0,
    });
    sessionByName.set(name, { id: session.id, gameSlug: session.gameSlug });
  });

Given('{string} creates a friends-only session named {string} in Three Wizards',
  async ({}, username: string, name: string) => {
    const session = await createSession(pageFor(username), {
      gameSlug: 'three-wizards', name, maxPlayers: 3, visibility: 'friends', characterId: 1, slotIndex: 0,
    });
    sessionByName.set(name, { id: session.id, gameSlug: session.gameSlug });
  });

Given('{string} creates a private session named {string} in Three Wizards',
  async ({}, username: string, name: string) => {
    const session = await createSession(pageFor(username), {
      gameSlug: 'three-wizards', name, maxPlayers: 3, visibility: 'private', characterId: 1, slotIndex: 0,
    });
    sessionByName.set(name, { id: session.id, gameSlug: session.gameSlug });
  });

Given('{string} creates a public session named {string} in Dodecahedron',
  async ({}, username: string, name: string) => {
    // A second game so the mod-scoped session-log filter has cross-game
    // history to prove it filters (three-wizards vs dodecahedron).
    const session = await createSession(pageFor(username), {
      gameSlug: 'dodecahedron', name, maxPlayers: 3, visibility: 'public', characterId: 1, slotIndex: 0,
    });
    sessionByName.set(name, { id: session.id, gameSlug: session.gameSlug });
  });

When('{string} opens the Three Wizards lobby', async ({}, username: string) => {
  const page = pageFor(username);
  await page.goto('/');
  // The catalog is sorted by sort_order ASC NULLS FIRST, so newly-
  // uploaded games (sort_order=0) push three-wizards out of the first
  // slot. Target the card by data-game-slug so the lobby step picks
  // the right modal regardless of how many games the BDD run has
  // built into the catalog above us.
  await page.locator('.game-card[data-game-slug="three-wizards"]').click();
  await page.locator('.modal-backdrop').first().waitFor({ state: 'visible', timeout: 10_000 });
});

Then('the lobby does not show the session named {string}',
  async ({}, name: string) => {
    let asserted = 0;
    for (const [, page] of pageRegistry) {
      const backdrop = page.locator('.modal-backdrop');
      if ((await backdrop.count()) > 0) {
        await expect(page.locator(`text=${name}`)).toHaveCount(0, { timeout: 10_000 });
        asserted += 1;
      }
    }
    if (asserted === 0) {
      throw new Error('No page had the lobby modal open — did you forget the "opens the … lobby" step?');
    }
  });

Then('{string} still sees the session named {string} in their own lobby',
  async ({}, username: string, name: string) => {
    const page = pageFor(username);
    await page.goto('/');
    // Pin the card by slug — see the "opens the Three Wizards lobby"
    // step for why .first() is no longer safe.
    await page.locator('.game-card[data-game-slug="three-wizards"]').click();
    await expect(page.locator(`text=${name}`).first()).toBeVisible({ timeout: 10_000 });
  });

// ── friends-tier ALLOW leg ──────────────────────────────────────────────
// The DENY legs above assert strangers can't see friends-only / private
// sessions. These assert the positive contract: after the mutual-friend
// handshake, the friend DOES see the friends-tier session and can join it
// — so a fail-closed regression in the friend-graph filter (hiding
// friends-tier sessions from everyone) fails the suite instead of passing.

Then('{string} sees the session named {string} in the lobby',
  async ({}, username: string, name: string) => {
    // Lobby already opened by a prior "opens the Three Wizards lobby"
    // step for this user. Pin the row by its .session-name so we assert
    // the ALLOW leg precisely (not an incidental text match elsewhere).
    const page = pageFor(username);
    const row = page.locator('.session-row', {
      has: page.locator('.session-name', { hasText: name }),
    }).first();
    await expect(row).toBeVisible({ timeout: 10_000 });
  });

When('{string} joins the session named {string}',
  async ({}, username: string, name: string) => {
    const page = pageFor(username);
    const row = page.locator('.session-row', {
      has: page.locator('.session-name', { hasText: name }),
    }).first();
    await row.waitFor({ state: 'visible', timeout: 10_000 });
    await row.locator('button.join-btn').click();
  });

Then('{string} reaches the active-session slot',
  async ({}, username: string) => {
    // joinSession() flips the picker to its "you're in this session as
    // slot N" state (.session-picker-active) once the BFF returns the
    // updated session — the render-layer proof that the join landed.
    const page = pageFor(username);
    await expect(page.locator('.session-picker-active')).toBeVisible({ timeout: 15_000 });
  });

Then("{string}'s lobby does not show the session named {string}",
  async ({}, username: string, name: string) => {
    // User-scoped DENY (vs. the all-open-lobbies variant above) so a
    // stranger leg can run in the SAME scenario as a friend who legitimately
    // sees the session in their own still-open lobby.
    const page = pageFor(username);
    await expect(page.locator('.session-row', {
      has: page.locator('.session-name', { hasText: name }),
    })).toHaveCount(0, { timeout: 10_000 });
  });

When('{string} requests their own session log', async ({}, username: string) => {
  const page = pageFor(username);
  responseState.lastStatus = await page.evaluate(async () => {
    const raw = window.localStorage.getItem("tw_auth");
    const token = raw ? (JSON.parse(raw).accessToken ?? '') : '';
    const r = await fetch('/api/session-logs/me', { headers: { Authorization: `Bearer ${token}` } });
    return r.status;
  });
});

When("the caller requests {string}'s session log via the admin endpoint",
  async ({}, target: string) => {
    // The admin endpoint takes an opaque identifier — the existing spec
    // passes literal usernames as the path variable, which the BFF
    // accepts (the lookup just returns an empty list when no rows
    // match). We use the most-recently-logged-in page (last entry in
    // the registry), since the BDD scenario opens with exactly one
    // "fresh browser context for X" before this step.
    const lastPage = Array.from(pageRegistry.values()).at(-1);
    if (!lastPage) throw new Error('No logged-in page; missing Given step?');
    responseState.lastStatus = await lastPage.evaluate(async (t) => {
      const raw = window.localStorage.getItem("tw_auth");
      const token = raw ? (JSON.parse(raw).accessToken ?? '') : '';
      const r = await fetch(`/api/session-logs/admin/${t}?limit=10`, {
        headers: { Authorization: `Bearer ${token}` },
      });
      return r.status;
    }, target);
  });

Then('the response status is {int}', async ({}, expected: number) => {
  expect(responseState.lastStatus).toBe(expected);
});
