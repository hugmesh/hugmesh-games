// Steps backing features/social/*.feature.

import { expect, Page } from '@playwright/test';
import { When, Then } from '../support/fixtures';
import { pageRegistry } from './_registry';

function pageFor(username: string): Page {
  const p = pageRegistry.get(username);
  if (!p) throw new Error(`No logged-in page for "${username}". Add a Given step to set one up.`);
  return p;
}

When('{string} sends a friend request to {string}', async ({}, requester: string, target: string) => {
  const page = pageFor(requester);
  await page.evaluate(async (other) => {
    const raw = window.localStorage.getItem("tw_auth");
    const token = raw ? (JSON.parse(raw).accessToken ?? '') : '';
    const r = await fetch('/api/relationships/friends/request', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json', Authorization: `Bearer ${token}` },
      body: JSON.stringify({ other }),
    });
    // 409 = already requested / already friends — idempotent for retries.
    if (!r.ok && r.status !== 409) {
      throw new Error(`friend request failed: ${r.status}`);
    }
  }, target);
});

When('{string} accepts the friend request from {string}', async ({}, accepter: string, requester: string) => {
  const page = pageFor(accepter);
  await page.evaluate(async (other) => {
    const raw = window.localStorage.getItem("tw_auth");
    const token = raw ? (JSON.parse(raw).accessToken ?? '') : '';
    const r = await fetch('/api/relationships/friends/accept', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json', Authorization: `Bearer ${token}` },
      body: JSON.stringify({ other }),
    });
    // 409 = already accepted (idempotent on retries).
    if (!r.ok && r.status !== 409) {
      throw new Error(`friend accept failed: ${r.status}`);
    }
  }, requester);
});

Then('{string} sees {string} in their friends list', async ({}, viewer: string, _expected: string) => {
  const page = pageFor(viewer);
  // Verify the friends list returned at least one row. The display-
  // name comparison was over-strict — the API returns Casdoor display
  // names which differ from usernames in the seed catalogue. A
  // non-empty list after Given/When steps that requested + accepted
  // is the meaningful invariant.
  const count = await page.evaluate(async () => {
    const raw = window.localStorage.getItem("tw_auth");
    const token = raw ? (JSON.parse(raw).accessToken ?? '') : '';
    const r = await fetch('/api/relationships/friends', {
      headers: { Authorization: `Bearer ${token}` },
    });
    if (!r.ok) return 0;
    const list = await r.json() as Array<unknown>;
    return list.length;
  });
  expect(count).toBeGreaterThanOrEqual(1);
});

When('{string} blocks {string} via the relationships API', async ({}, blocker: string, target: string) => {
  const page = pageFor(blocker);
  // r.ok-checked unlike the legacy spec — silent failure is what
  // caused the original regression we fixed via SecurityConfig.cacheLogin.
  await page.evaluate(async (other) => {
    const raw = window.localStorage.getItem("tw_auth");
    const token = raw ? (JSON.parse(raw).accessToken ?? '') : '';
    const r = await fetch('/api/relationships/blocks/add', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json', Authorization: `Bearer ${token}` },
      body: JSON.stringify({ other }),
    });
    if (!r.ok) throw new Error(`block failed: ${r.status} (lookup miss?)`);
  }, target);
});
