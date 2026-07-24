// Step definitions for features/moderation/mod-stop-kick-consequence.feature.
//
// The mod happy-path had NO e2e: security/mod-grant-gates covers only
// grant rejections, and the ModShell stop/kick actions live only in a
// mocked Karma unit spec. These steps prove the CONSEQUENCE reaches the
// players in a live two-player three-wizards session:
//   • kick → the kicked player's next /api/controls 403s (lost control)
//     while the other player's control still succeeds (kept playing);
//   • stop → sessionService.close fires SESSION_CLOSED, which the host
//     shell's _sessionClosedEffect turns into closeLevel() on BOTH
//     players → both game iframes tear down at the render layer;
//   • cross-scope DENY → a mod scoped to a DIFFERENT game (mod-dodecahedron)
//     gets 403 on a direct stop (requireScope), victim-creates-first.
//
// The stop/kick are driven through the real /api/mod/* endpoints the
// ModShell buttons POST to (mod_game_scopes enforced server-side) — the
// value the audit flags is the consequence reaching players, which is
// identical whether the click or its exact API call triggers it.

import { expect, Page } from '@playwright/test';
import { When, Then } from '../support/fixtures';
import { pageRegistry, responseState, sessionByName } from './_registry';
import { authToken } from '../../tests/helpers/roles';

function pageFor(username: string): Page {
  const p = pageRegistry.get(username);
  if (!p) throw new Error(`No logged-in page for "${username}". Stage a browser context first.`);
  return p;
}

/** Read a logged-in user's Casdoor id (JWT `sub`) from their own token —
 *  the real subject the BFF matches session_members / mod actions on. */
async function casdoorIdOf(username: string): Promise<string> {
  const token = await authToken(pageFor(username));
  if (!token) throw new Error(`No token for "${username}"`);
  const seg = token.split('.')[1];
  const b64 = seg.replace(/-/g, '+').replace(/_/g, '/');
  const padded = b64 + '='.repeat((4 - (b64.length % 4)) % 4);
  const sub = JSON.parse(Buffer.from(padded, 'base64').toString('utf8')).sub;
  if (!sub) throw new Error(`Token for "${username}" has no sub claim`);
  return sub as string;
}

function sessionId(name: string): string {
  const s = sessionByName.get(name);
  if (!s) throw new Error(`No session named "${name}" — did the create step run?`);
  return s.id;
}

/** POST a control for `characterId` scoped to a session; return the status.
 *  Mirrors the iframe's own POST /api/controls/{cid} with X-Session-Id. */
async function controlStatus(username: string, characterId: number, name: string): Promise<number> {
  const page = pageFor(username);
  const token = await authToken(page);
  return await page.evaluate(async ({ cid, sid, t }) => {
    const r = await fetch(`/api/controls/${cid}`, {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
        Authorization: `Bearer ${t}`,
        'X-Session-Id': sid,
      },
      body: JSON.stringify({ action: 'MOVE_UP', pressed: true, chapterId: 1, timestamp: Date.now() }),
    });
    return r.status;
  }, { cid: characterId, sid: sessionId(name), t: token });
}

When('{string} kicks {string} from the session named {string}',
  async ({}, mod: string, target: string, name: string) => {
    const targetCasdoorId = await casdoorIdOf(target);
    const page = pageFor(mod);
    const token = await authToken(page);
    responseState.lastStatus = await page.evaluate(async ({ sid, t, tgt }) => {
      const r = await fetch(`/api/mod/sessions/${sid}/kick`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json', Authorization: `Bearer ${t}` },
        body: JSON.stringify({ targetCasdoorId: tgt, reason: 'consequence-e2e' }),
      });
      return r.status;
    }, { sid: sessionId(name), t: token, tgt: targetCasdoorId });
  });

Then('{string}\'s next control for character {int} in session {string} is rejected 403',
  async ({}, username: string, characterId: number, name: string) => {
    const status = await controlStatus(username, characterId, name);
    expect(status, `${username}'s control should 403 after being kicked (got ${status})`).toBe(403);
  });

Then('{string}\'s control for character {int} in session {string} still succeeds',
  async ({}, username: string, characterId: number, name: string) => {
    const status = await controlStatus(username, characterId, name);
    expect(status, `${username} should keep control (got ${status})`).toBeLessThan(400);
  });

Then('{string}\'s game iframe is still mounted', async ({}, username: string) => {
  const page = pageFor(username);
  await expect
    .poll(() => page.evaluate(() => document.querySelectorAll('iframe.wasm-frame').length),
      { timeout: 5_000 })
    .toBeGreaterThan(0);
});

Then('{string} and {string} both lose their game iframe',
  async ({}, a: string, b: string) => {
    // SESSION_CLOSED → ApiBridgeService → SessionControlService.sessionClosed
    // → HomeComponent._sessionClosedEffect → closeLevel() removes the
    // canvas-section, so iframe.wasm-frame drops to 0 on both players.
    for (const username of [a, b]) {
      const page = pageFor(username);
      await expect
        .poll(() => page.evaluate(() => document.querySelectorAll('iframe.wasm-frame').length),
          { timeout: 30_000, message: `${username}'s game iframe must tear down on SESSION_CLOSED` })
        .toBe(0);
    }
  });
