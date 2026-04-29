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

When('{string} opens the Three Wizards lobby', async ({}, username: string) => {
  const page = pageFor(username);
  await page.goto('/');
  await page.locator('.game-card').first().click();
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
    await page.locator('.game-card').first().click();
    await expect(page.locator(`text=${name}`).first()).toBeVisible({ timeout: 10_000 });
  });

When('{string} requests their own session log', async ({}, username: string) => {
  const page = pageFor(username);
  responseState.lastStatus = await page.evaluate(async () => {
    const raw = window.sessionStorage.getItem('tw_auth');
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
      const raw = window.sessionStorage.getItem('tw_auth');
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
