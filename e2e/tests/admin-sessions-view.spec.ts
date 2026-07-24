import { test, expect } from '@playwright/test';
import { SEED_USERS, login, createSession, joinSession, newE2eContext } from './helpers/roles';

/**
 * Pins the per-session view-channel that drives /admin/sessions.
 *
 * Setup:
 *   1. player1 creates a public session in three-wizards.
 *   2. player2 joins as slot 1.
 *   3. Each player POSTs a single ViewSampleRequest to /api/view
 *      (simulating what HostBridge.report_view does at ~10 Hz from
 *      inside the WASM iframe). Direct POSTs bypass the 30 s+ WASM
 *      compile path so this spec stays fast.
 *
 * Observation:
 *   4. admin opens an authenticated streaming fetch to
 *      /api/admin/sessions/{id}/stream and parses the text/event-stream
 *      bytes in-flight, asserting that ≥ 1 `event: view` line
 *      surfaces with a payload carrying both slots' characterId.
 *
 * Why direct fetch instead of driving the SessionsShell UI: the
 * shell's renderer uses `new EventSource(...)` which cannot send
 * Authorization headers, so the admin endpoint's
 * @PreAuthorize("hasRole('ADMIN')") rejects it without a cookie-
 * based auth fallback. The test asserts what matters — that the
 * BFF's per-session SSE merge actually carries view events to an
 * authenticated admin subscriber. The SessionsShell rendering is
 * already covered by frontend unit specs.
 */
test.describe('admin per-session view stream', () => {
  test('view samples flow through /api/admin/sessions/{id}/stream for both slots', async ({ browser }) => {
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
      name: 'admin-stream-test',
      maxPlayers: 3,
      visibility: 'public',
      characterId: 1,
      slotIndex: 0,
    });

    // 2. player2 joins as slot 1.
    await joinSession(p2, session.id, { characterId: 2, slotIndex: 1 });

    // 3. Helper to POST a view sample as the given player.
    const postSample = async (
      page: typeof p1,
      slotIndex: number,
      characterId: number,
    ): Promise<number> => {
      return page.evaluate(async ({ sid, slot, cid }) => {
        const raw = window.localStorage.getItem('tw_auth');
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

    // Smoke check the /api/view endpoint accepts both slots' samples
    // before the admin stream opens. (The samples themselves arrive
    // post-subscribe; SSE doesn't replay history.)
    // /api/view returns 202 Accepted (fire-and-forget — view samples
    // are pure ephemera, not persisted).
    expect(await postSample(p1, 0, 1)).toBe(202);
    expect(await postSample(p2, 1, 2)).toBe(202);

    // Start a background flood so the admin SSE stream sees new
    // events as it reads. SSE only delivers events fired AFTER
    // subscribe, so a one-shot pre-subscribe sample is invisible.
    let flooding = true;
    const flood = (async () => {
      while (flooding) {
        try {
          await postSample(p1, 0, 1);
          await postSample(p2, 1, 2);
        } catch { /* page might close before flood ends; ignore */ }
        await new Promise(r => setTimeout(r, 200));
      }
    })();

    // 4. Admin opens an authenticated streaming fetch to the per-
    //    session admin SSE endpoint, parses the text/event-stream
    //    bytes incrementally, and asserts ≥ 2 distinct slot indices
    //    surface in `event: view` payloads. Runs in-page so the JWT
    //    lives next to the same-origin host.
    const observed = await pAdmin.evaluate(async ({ sid }) => {
      const raw = window.localStorage.getItem('tw_auth');
      const token = raw ? (JSON.parse(raw).accessToken ?? '') : '';
      const ctrl = new AbortController();
      const seenSlots = new Set<number>();
      const seenChars = new Set<number>();
      const events: string[] = [];

      const r = await fetch(`/api/admin/sessions/${sid}/stream`, {
        headers: { Authorization: `Bearer ${token}`, Accept: 'text/event-stream' },
        signal: ctrl.signal,
      });
      if (!r.ok || !r.body) {
        return { status: r.status, slots: [], characters: [], rawSample: '', eventNames: [] };
      }

      const reader = r.body.getReader();
      const dec = new TextDecoder();
      let buf = '';
      let rawSample = '';
      const start = Date.now();
      while (Date.now() - start < 25_000 && seenSlots.size < 2) {
        const { value, done } = await reader.read();
        if (done) break;
        const chunk = dec.decode(value, { stream: true });
        rawSample += chunk;
        if (rawSample.length > 4000) rawSample = rawSample.slice(-4000); // keep last 4KB
        buf += chunk;
        const blocks = buf.split('\n\n');
        buf = blocks.pop() ?? '';
        for (const block of blocks) {
          // Parse SSE event name (first non-comment `event:` line).
          const evMatch = block.match(/(?:^|\n)event:\s*([a-z]+)/i);
          if (evMatch) events.push(evMatch[1]);
          // Parse `data:` line (could be on multiple lines, but our
          // BFF emits compact one-line JSON).
          const dataMatch = block.match(/(?:^|\n)data:\s*(.+)/);
          if (!dataMatch) continue;
          if (!evMatch || evMatch[1] !== 'view') continue;
          try {
            const payload = JSON.parse(dataMatch[1]);
            if (typeof payload?.slotIndex === 'number') seenSlots.add(payload.slotIndex);
            if (typeof payload?.characterId === 'number') seenChars.add(payload.characterId);
          } catch { /* skip malformed */ }
        }
      }
      ctrl.abort();
      return {
        status: r.status,
        slots: Array.from(seenSlots).sort(),
        characters: Array.from(seenChars).sort(),
        rawSample,
        eventNames: events,
      };
    }, { sid: session.id });

    flooding = false;
    await flood;

    expect(observed.status, `admin stream returned ${observed.status}`).toBe(200);
    expect(
      observed.slots,
      `admin stream must surface both slots — events seen: [${observed.eventNames.join(',')}], raw tail:\n${observed.rawSample}`,
    ).toEqual([0, 1]);
    expect(observed.characters, 'view payload must carry each slot\'s characterId').toEqual([1, 2]);

    await ctx1.close();
    await ctx2.close();
    await ctxA.close();
  });
});
