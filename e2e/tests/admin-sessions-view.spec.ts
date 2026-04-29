import { test, expect } from '@playwright/test';
import { SEED_USERS, login, createSession, joinSession, newE2eContext } from './helpers/roles';

/**
 * Pins the per-session view-table on /admin/sessions.
 *
 * Setup:
 *   1. player1 creates a public session in three-wizards.
 *   2. player2 joins as slot 1.
 *   3. Each player POSTs a single ViewSampleRequest directly to
 *      /api/view (simulating what the WASM bundle's HostBridge
 *      report_view does at ~10 Hz). Posting from the test bypasses
 *      the 30+ s WASM compile path so this spec stays fast.
 *
 * Observation:
 *   4. admin opens /admin/sessions, picks the session, and the
 *      "Per-player view" table renders rows for both slots.
 *
 * The endpoint authorizes by JWT subject = session_member ownership,
 * so each player must POST their OWN slot's sample.
 */
test.describe('admin /admin/sessions live view', () => {
  test('admin sees per-player view samples for an active session', async ({ browser }) => {
    const player1 = SEED_USERS.find(u => u.username === 'player1')!;
    const player2 = SEED_USERS.find(u => u.username === 'player2')!;
    const admin   = SEED_USERS.find(u => u.username === 'admin')!;

    const ctx1   = await newE2eContext(browser);
    const ctx2   = await newE2eContext(browser);
    const ctxA   = await newE2eContext(browser);
    const p1     = await ctx1.newPage();
    const p2     = await ctx2.newPage();
    const pAdmin = await ctxA.newPage();

    await login(p1, player1);
    await login(p2, player2);
    await login(pAdmin, admin);

    // 1. player1 creates session.
    const session = await createSession(p1, {
      gameSlug: 'three-wizards',
      name: 'admin-view-test',
      maxPlayers: 3,
      visibility: 'public',
      characterId: 1,
      slotIndex: 0,
    });

    // 2. player2 joins as slot 1.
    await joinSession(p2, session.id, { characterId: 2, slotIndex: 1 });

    // 3. Each player POSTs a view sample. (Simulates the WASM bundle's
    //    HostBridge.report_view → ApiBridgeService.handleViewEgress.)
    const postSample = async (
      page: typeof p1,
      slotIndex: number,
      characterId: number,
    ): Promise<number> => {
      return page.evaluate(async ({ sid, slot, cid }) => {
        const raw = window.sessionStorage.getItem('tw_auth');
        const token = raw ? (JSON.parse(raw).accessToken ?? '') : '';
        const r = await fetch('/api/view', {
          method: 'POST',
          headers: { 'Content-Type': 'application/json', Authorization: `Bearer ${token}` },
          body: JSON.stringify({
            sessionId: sid, slotIndex: slot, characterId: cid,
            x: 1.5, y: 0, z: -2.3, yaw: 0.1, pitch: 0.0, fov: 75,
          }),
        });
        return r.status;
      }, { sid: session.id, slot: slotIndex, cid: characterId });
    };

    expect(await postSample(p1, 0, 1)).toBe(204);
    expect(await postSample(p2, 1, 2)).toBe(204);

    // 4. admin opens /admin/sessions, picks the session, asserts the
    //    view-table renders rows for both slots.
    await pAdmin.goto('/admin/sessions');
    await pAdmin.waitForSelector('app-sessions-remote', { timeout: 30_000 });
    const sessionRow = pAdmin.locator(`button:has-text("${session.name}")`).first();
    await sessionRow.waitFor({ state: 'visible', timeout: 15_000 });
    await sessionRow.click();

    // Keep refreshing view samples while we wait for the admin SSE
    // merge to surface them. The /api/admin/sessions/{id}/stream
    // multicast can lag a single sample by 1-2 ticks; firing a few
    // gives the renderer multiple settle points without making the
    // spec dependent on exact SSE buffering behaviour.
    const refreshUntilTwoSlots = async (): Promise<number> => {
      const slotPills = pAdmin.locator('.view-table .slot-pill');
      const deadline = Date.now() + 60_000;
      while (Date.now() < deadline) {
        await postSample(p1, 0, 1);
        await postSample(p2, 1, 2);
        await pAdmin.waitForTimeout(800);
        const count = await slotPills.count();
        if (count >= 2) return count;
      }
      return await slotPills.count();
    };
    const finalCount = await refreshUntilTwoSlots();
    expect(finalCount).toBeGreaterThanOrEqual(2);

    await ctx1.close();
    await ctx2.close();
    await ctxA.close();
  });
});
