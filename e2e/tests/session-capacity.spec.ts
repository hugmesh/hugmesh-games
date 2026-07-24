import { test, expect, BrowserContext, Page } from '@playwright/test';
import { SEED_USERS, login, newE2eContext, createSession, authToken } from './helpers/roles';

/**
 * Session capacity — the full-session behaviour maxPlayers (1..16) is
 * settable but nothing asserted:
 *
 *   1. The lobby Join button's `[disabled]="s.activePlayers >= s.maxPlayers"`
 *      binding (home.component.html) AND the BFF-side reject on a direct
 *      join to a full session (GameSessionService.join → 409 "session full").
 *   2. A concurrent last-slot race: two joiners fire at the SAME free slot
 *      simultaneously; the session_members slot unique-constraint guarantees
 *      exactly one 2xx and the loser gets 409 "slot already claimed"
 *      (GameSessionService.join → onErrorMap(DuplicateKeyException)).
 *
 * Both are pure capacity mechanics, so this drives the /api/sessions REST
 * surface directly (no gameplay launch) for determinism — the CLAUDE.md
 * "direct /api/sessions leaves activeSessionId blind" caveat only matters
 * when a spec goes on to launch the iframe, which this one never does.
 */

const PLAYER1 = SEED_USERS.find(u => u.username === 'player1')!;
const PLAYER2 = SEED_USERS.find(u => u.username === 'player2')!;
const PLAYER3 = SEED_USERS.find(u => u.username === 'player3')!;

const GAME_SLUG = 'three-wizards';

/** Direct POST /api/sessions/{id}/join carrying the page's JWT; returns the
 *  HTTP status so the caller can assert the accept/reject without a helper
 *  that throws on non-2xx (joinSession() in helpers/roles.ts throws). */
async function joinStatus(
  page: Page, sessionId: string, body: { characterId?: number; slotIndex?: number },
): Promise<number> {
  const token = await authToken(page);
  return await page.evaluate(async ({ sid, payload, t }) => {
    const r = await fetch(`/api/sessions/${sid}/join`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json', Authorization: `Bearer ${t}` },
      body: JSON.stringify(payload),
    });
    return r.status;
  }, { sid: sessionId, payload: body, t: token });
}

/** Open the three-wizards card in the lobby so the session-picker list
 *  renders; the caller then inspects a specific .session-row. */
async function openLobby(page: Page): Promise<void> {
  await page.goto('/', { waitUntil: 'domcontentloaded' });
  const card = page.locator(`.game-card[data-game-slug="${GAME_SLUG}"]`);
  await card.waitFor({ state: 'visible', timeout: 25_000 });
  await card.click();
  await page.locator('.modal-backdrop').first().waitFor({ state: 'visible', timeout: 10_000 });
}

test.describe('session capacity', () => {
  test('a full (maxPlayers=2) session disables Join in the lobby and rejects a direct join with 409', async ({ browser }) => {
    const ctx1: BrowserContext = await newE2eContext(browser);
    const ctx2: BrowserContext = await newE2eContext(browser);
    const ctx3: BrowserContext = await newE2eContext(browser);
    const p1 = await ctx1.newPage();
    const p2 = await ctx2.newPage();
    const p3 = await ctx3.newPage();
    try {
      await login(p1, PLAYER1);
      await login(p2, PLAYER2);
      await login(p3, PLAYER3);

      // player1 creates a PUBLIC cap-2 session (creator claims slot 0 → 1/2).
      const name = `cap-full-${Date.now()}`;
      const session = await createSession(p1, {
        gameSlug: GAME_SLUG, name, maxPlayers: 2, visibility: 'public',
        characterId: 1, slotIndex: 0,
      });
      expect(session.maxPlayers).toBe(2);

      // player2 joins slot 1 → session is now full (2/2). helpers/roles
      // joinSession throws on non-2xx, so a successful call IS the 2xx proof.
      const p2Status = await joinStatus(p2, session.id, { characterId: 2, slotIndex: 1 });
      expect(p2Status, 'player2 must fill the last slot with a 2xx').toBeGreaterThanOrEqual(200);
      expect(p2Status).toBeLessThan(300);

      // player3 opens the lobby: the full session's Join button is disabled
      // by [disabled]="s.activePlayers >= s.maxPlayers".
      await openLobby(p3);
      const row = p3.locator('.session-row', {
        has: p3.locator('.session-name', { hasText: name }),
      }).first();
      await row.waitFor({ state: 'visible', timeout: 15_000 });
      await expect(
        row.locator('button.join-btn'),
        'the Join button on a full session must be disabled',
      ).toBeDisabled();

      // And a direct POST bypassing the disabled button is rejected 409.
      const p3Status = await joinStatus(p3, session.id, { characterId: 3, slotIndex: 1 });
      expect(p3Status, 'a direct join to a full session must be rejected with 409').toBe(409);
    } finally {
      await ctx1.close();
      await ctx2.close();
      await ctx3.close();
    }
  });

  test('two joiners racing for the same last slot: exactly one 2xx, the other 409', async ({ browser }) => {
    const ctx1: BrowserContext = await newE2eContext(browser);
    const ctx2: BrowserContext = await newE2eContext(browser);
    const ctx3: BrowserContext = await newE2eContext(browser);
    const p1 = await ctx1.newPage();
    const p2 = await ctx2.newPage();
    const p3 = await ctx3.newPage();
    try {
      await login(p1, PLAYER1);
      await login(p2, PLAYER2);
      await login(p3, PLAYER3);

      // cap-2 session, creator in slot 0 → exactly ONE free slot (slot 1).
      const name = `cap-race-${Date.now()}`;
      const session = await createSession(p1, {
        gameSlug: GAME_SLUG, name, maxPlayers: 2, visibility: 'public',
        characterId: 1, slotIndex: 0,
      });

      // player2 + player3 fire at the SAME slot (1) concurrently. The
      // session_members (session_id, slot_index) unique constraint makes
      // this deterministic: exactly one insert wins, the other maps to
      // 409 "slot already claimed" (onErrorMap(DuplicateKeyException)).
      const [s2, s3] = await Promise.all([
        joinStatus(p2, session.id, { characterId: 2, slotIndex: 1 }),
        joinStatus(p3, session.id, { characterId: 3, slotIndex: 1 }),
      ]);

      const accepted = [s2, s3].filter(s => s >= 200 && s < 300);
      const rejected = [s2, s3].filter(s => s === 409);
      expect(
        accepted.length,
        `exactly one racer must win the last slot (got statuses ${s2} / ${s3})`,
      ).toBe(1);
      expect(
        rejected.length,
        `the losing racer must get 409 (got statuses ${s2} / ${s3})`,
      ).toBe(1);
    } finally {
      await ctx1.close();
      await ctx2.close();
      await ctx3.close();
    }
  });
});
