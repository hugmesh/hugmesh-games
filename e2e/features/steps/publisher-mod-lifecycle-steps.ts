// Step definitions for features/security/publisher-grant-revoke-mod-lifecycle.feature.
//
// The happy-path + revoke counterpart to security/mod-grant-gates.feature
// (which covers only the rejection matrix). A publisher grants a Casdoor
// mod the per-game scope on a game they own, the mod GAINS real power
// (stops a session), the publisher revokes, and the mod LOSES the power
// (next stop 403s) — a cross-user grant → power → revoke → no-power
// lifecycle the *DomainScopeTest suites only pin at the unit layer.
//
// IMPORTANT modelling note: POST /api/publisher/grant-mod adds ONLY the
// mod_game_scopes row — it does NOT confer the Casdoor `mod` role
// (PublisherController.grantMod doc). So the grantee must already hold
// ROLE_MOD; we use the seed `mod` user (Casdoor mod, zero game scope),
// NOT a plain player, or /api/mod/* would 403 on the role gate, not the
// scope gate, and the ALLOW leg could never render.

import { expect } from '@playwright/test';
import { When, Then } from '../support/fixtures';
import { pageRegistry, responseState, sessionByName } from './_registry';
import { authToken } from '../../tests/helpers/roles';

function pageFor(username: string) {
  const p = pageRegistry.get(username);
  if (!p) throw new Error(`No logged-in page for "${username}". Stage a browser context first.`);
  return p;
}

/** Read a logged-in user's Casdoor id (the JWT `sub` claim the BFF reads
 *  as casdoorId) out of their own access token. Grant/revoke target the
 *  REAL subject so the grantee's /api/mod/scopes actually reflects it —
 *  the mod-grant-gates feature's literal "tw-player-002" placeholder
 *  wouldn't match any real subject. */
async function casdoorIdOf(username: string): Promise<string> {
  const token = await authToken(pageFor(username));
  if (!token) throw new Error(`No token for "${username}"`);
  const seg = token.split('.')[1];
  if (!seg) throw new Error(`Token for "${username}" is not a JWT`);
  const b64 = seg.replace(/-/g, '+').replace(/_/g, '/');
  const padded = b64 + '='.repeat((4 - (b64.length % 4)) % 4);
  const sub = JSON.parse(Buffer.from(padded, 'base64').toString('utf8')).sub;
  if (!sub) throw new Error(`Token for "${username}" has no sub claim`);
  return sub as string;
}

async function postJson(username: string, path: string, body: unknown): Promise<number> {
  const page = pageFor(username);
  const token = await authToken(page);
  return await page.evaluate(async ({ p, t, b }) => {
    const r = await fetch(p, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json', ...(t ? { Authorization: `Bearer ${t}` } : {}) },
      body: JSON.stringify(b),
    });
    return r.status;
  }, { p: path, t: token, b: body });
}

When('{string} grants mod on {string} to {string}',
  async ({}, grantor: string, gameSlug: string, target: string) => {
    const targetCasdoorId = await casdoorIdOf(target);
    responseState.lastStatus = await postJson(grantor, '/api/publisher/grant-mod',
      { targetCasdoorId, gameSlug });
  });

When('{string} revokes mod on {string} from {string}',
  async ({}, grantor: string, gameSlug: string, target: string) => {
    const targetCasdoorId = await casdoorIdOf(target);
    responseState.lastStatus = await postJson(grantor, '/api/publisher/revoke-mod',
      { targetCasdoorId, gameSlug });
  });

When('{string} attempts to stop the session named {string}',
  async ({}, username: string, name: string) => {
    const session = sessionByName.get(name);
    if (!session) throw new Error(`No session named "${name}" — did the create step run?`);
    responseState.lastStatus = await postJson(username, `/api/mod/sessions/${session.id}/stop`,
      { reason: 'lifecycle-e2e' });
  });

/** Read the caller's own mod scopes and return the game slugs. */
async function modScopeSlugs(username: string): Promise<string[]> {
  const page = pageFor(username);
  const token = await authToken(page);
  return await page.evaluate(async (t) => {
    const r = await fetch('/api/mod/scopes', { headers: t ? { Authorization: `Bearer ${t}` } : {} });
    if (!r.ok) return [`__status_${r.status}`];
    const list = (await r.json()) as Array<{ gameSlug: string }>;
    return list.map(s => s.gameSlug);
  }, token);
}

Then('{string} sees {string} in their mod scopes',
  async ({}, username: string, gameSlug: string) => {
    expect(await modScopeSlugs(username)).toContain(gameSlug);
  });

Then('{string} does not see {string} in their mod scopes',
  async ({}, username: string, gameSlug: string) => {
    expect(await modScopeSlugs(username)).not.toContain(gameSlug);
  });
