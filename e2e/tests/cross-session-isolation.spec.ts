import { test, expect, BrowserContext, Page } from '@playwright/test';
import {
  SEED_USERS, login, sendControl, newE2eContext,
} from './helpers/roles';
import { captureIframeSseEvents, drainCapturedEvents, installSseCapture } from './helpers/sse-bridge';

const PLAYER1 = SEED_USERS.find(u => u.username === 'player1')!;
const PLAYER2 = SEED_USERS.find(u => u.username === 'player2')!;

/**
 * Cross-session isolation. Two players each create their own session
 * for the same game and load the same chapter; they never join each
 * other's session. After P1 moves in session A, P2 (in session B)
 * must NOT receive any movement / world / character events from
 * session A's sink.
 *
 * This is the e2e flip-side of `SessionIsolationTest` on the BFF —
 * that test pins the per-session sink contract at the service layer;
 * this test pins it through every hop (sink fan-out → SSE → Angular
 * wrapper relay → iframe consumer).
 */
test.describe('cross-session isolation', () => {
  test('two sessions for the same game don\'t cross-stream events', async ({ browser }) => {
    const ctxA: BrowserContext = await newE2eContext(browser);
    const ctxB: BrowserContext = await newE2eContext(browser);
    // Init-script capture so every iframe gets the listener before
    // its own scripts run — eliminates the race where BridgeGlue
    // drains the SSE queue before our capture attaches.
    await installSseCapture(ctxA);
    await installSseCapture(ctxB);
    const pA: Page = await ctxA.newPage();
    const pB: Page = await ctxB.newPage();

    await login(pA, PLAYER1);
    await login(pB, PLAYER2);

    // Drive each player through the lobby-modal UI so the home
    // component's `activeSessionId` is actually populated. The
    // chapter launcher reads it and threads `?session_id=…` onto
    // the iframe URL — that's what the wrapper needs to open an
    // EventSource scoped to the per-session sink. (Earlier
    // iteration of this test created sessions via the API helper
    // which left the home component blind to the session id, so
    // the iframe URL had no session_id and the wrapper subscribed
    // to the unscoped global stream — the bleed we're trying to
    // pin against.)
    // Distinct characters per player — `findActiveOwnerOfCharacter`
    // is keyed on `character_id`, so two players on the same character
    // collide in the BFF's authorize-control check (the latest member
    // wins; the other side's POST /api/controls/1 returns 403).
    const sessionAId = await createSessionViaLobby(pA, `iso-A-${Date.now()}`, 1);
    const sessionBId = await createSessionViaLobby(pB, `iso-B-${Date.now()}`, 2);
    expect(sessionAId).not.toEqual(sessionBId);

    // Both players already have the modal open with the chapter
    // list visible (the create-session flow leaves it that way);
    // click Play on the first chapter to mount the iframe with
    // the session id threaded onto the URL.
    await launchFirstChapter(pA);
    await launchFirstChapter(pB);

    await Promise.all([
      pA.waitForFunction(() => document.querySelectorAll('iframe.wasm-frame').length > 0, undefined, { timeout: 30_000 }),
      pB.waitForFunction(() => document.querySelectorAll('iframe.wasm-frame').length > 0, undefined, { timeout: 30_000 }),
    ]);

    await captureIframeSseEvents(pA);
    await captureIframeSseEvents(pB);

    // Wait for both wrappers to actually open their session-scoped
    // EventSource. Without this guard we race the in-iframe wasm boot —
    // the wrapper's `?sessionId=` GET fires only after game_bridge.gd's
    // _request_sse_subscription posts SD_SSE_SUBSCRIBE, which only
    // happens once the WASM compile finishes (15-30s on a cold image).
    // If we send the control before B has subscribed, B's empty buffer
    // is meaningless — it would just mean B wasn't listening yet.
    await Promise.all([
      pA.waitForResponse(
        r => r.url().includes(`/api/game/stream?sessionId=${sessionAId}`),
        { timeout: 60_000 },
      ),
      pB.waitForResponse(
        r => r.url().includes(`/api/game/stream?sessionId=${sessionBId}`),
        { timeout: 60_000 },
      ),
    ]);

    // Drain whatever queued during boot so the post-control assertion
    // only sees fresh deliveries.
    await drainCapturedEvents(pA);
    await drainCapturedEvents(pB);

    // P1 (session A) moves their character. The BFF should fan this
    // out on session A's sinks ONLY — session B's subscriber must
    // see nothing.
    await sendControl(pA, 1, {
      action:    'MOVE_RIGHT',
      pressed:   true,
      chapterId: 1,
      sessionId: sessionAId,
    });

    // Wait long enough for the SSE delivery — roughly two ticks of
    // the BFF's emit pipeline. If session B got anything carrying
    // P1's character (id 1), it's a leak.
    await pA.waitForTimeout(2_500);

    const eventsA = await drainCapturedEvents(pA);
    const eventsB = await drainCapturedEvents(pB);

    // The contract — session B must NOT receive any event carrying
    // session A's character (id 1). The isolation guarantee depends
    // on which transport the iframe is using:
    //
    //   • New bundle (post-SD_SSE_SUBSCRIBE refactor): the Angular
    //     wrapper opens a single EventSource scoped to the iframe's
    //     own session id. Per-session sink fan-out keeps B isolated.
    //   • Old bundle (pre-refactor, in-iframe EventSource at the
    //     unscoped /api/game/stream): EVERY iframe subscribes to the
    //     global sink and sees every movement. The bleed is by
    //     design until you re-export the Godot bundles
    //     (`make build-rust && make build-godot`).
    //
    // The new wasm posts SD_SSE_SUBSCRIBE during boot; we look for
    // it in the captured events to decide which mode we're in. When
    // the old bundle is in play we surface a clear warning instead
    // of failing — re-exporting bundles is a build-tooling step the
    // test runner can't perform.
    // Two ways the iframe ends up without a session-scoped stream:
    //   (a) Old wasm bundle (pre-SD_SSE_SUBSCRIBE refactor) — uses
    //       an in-iframe EventSource against the unscoped
    //       /api/game/stream.
    //   (b) The chapter-launch UI didn't thread sessionId onto the
    //       iframe URL because the spec created sessions via direct
    //       API calls rather than through the lobby modal. The
    //       iframe boots without ?session_id=, the wrapper opens an
    //       unscoped EventSource, and we get the same bleed by
    //       different cause.
    //
    // Both cases are environmental — the BFF's per-session sink
    // isolation is locked in by SessionIsolationTest at the unit
    // layer. When the iframe has no session id we surface a clear
    // warning instead of failing.
    const sessionScoped = await pA.evaluate(() => {
      const frames = Array.from(document.querySelectorAll('iframe.wasm-frame')) as HTMLIFrameElement[];
      return frames.some(f => {
        try {
          const url = new URL(f.src);
          return url.searchParams.has('session_id');
        } catch { return false; }
      });
    }).catch(() => false);
    const sseB = eventsB.filter(e => {
      if (e.type !== 'SD_SSE_EVENT') return false;
      const blob = JSON.stringify(e.payload ?? {});
      return blob.includes('"characterId":1') || blob.includes('"slot":0,"characterId":1');
    });

    // Strict positive: session A must hear its own movement. The
    // BridgeGlue queue drain (helpers/sse-bridge.ts) catches both
    // events captured by our listener AND events queued by Rust
    // BridgeGlue itself, so the assertion isn't racy with the
    // wrapper's first postMessage.
    expect(
      sessionScoped,
      'iframe URL must include session_id (lobby flow must populate activeSessionId)',
    ).toBe(true);
    const sseA = eventsA.filter(e => e.type === 'SD_SSE_EVENT');
    expect(
      sseA.length,
      'session A must hear at least one SSE event from its own movement',
    ).toBeGreaterThan(0);

    // Strict negative: session B must NOT see session A's events.
    expect(
      sseB,
      `session B must NOT receive session A's events (got ${sseB.length})`,
    ).toEqual([]);

    await ctxA.close();
    await ctxB.close();
  });
});

/**
 * Drive the lobby modal: opens the first game's modal, fills the
 * create-session form (public visibility, a unique name), submits,
 * waits for the picker to flip into the "active session" state, and
 * returns the resulting session id by reading it back via the
 * `/api/sessions` listing.
 *
 * Leaves the modal open with the chapter list still visible so the
 * caller can click Play.
 */
async function createSessionViaLobby(page: Page, sessionName: string, characterId = 1): Promise<string> {
  await page.goto('/?characterId=' + characterId, { waitUntil: 'domcontentloaded' });
  const card = page.locator('.game-card').first();
  await card.waitFor({ state: 'visible', timeout: 20_000 });
  await card.click();

  const nameInput = page.locator('#session-name-input');
  await nameInput.waitFor({ state: 'visible', timeout: 10_000 });
  await nameInput.fill(sessionName);
  // Public visibility — anyone-can-see, but the test still drives
  // a separate iframe per player so they only ever see their own
  // session via the wrapper's subscription scope.
  await page.locator('input[type="radio"][value="public"]').check();
  await page.locator('button.create-btn').click();

  // The home component flips into the "session active" branch and
  // shows the slot indicator; that's the cue that activeSessionId
  // is populated on the component. We then read the freshest
  // matching session id off /api/sessions for return.
  await page.locator('.session-picker-active').waitFor({ state: 'visible', timeout: 15_000 });

  return await page.evaluate(async (name: string) => {
    const raw = window.sessionStorage.getItem('tw_auth');
    const token = raw ? (JSON.parse(raw).accessToken ?? '') : '';
    const r = await fetch('/api/sessions?gameSlug=three-wizards', {
      headers: { Authorization: `Bearer ${token}` },
    });
    const list = (await r.json()) as Array<{ id: string; name: string }>;
    return (list.find(s => s.name === name) ?? list[0]).id;
  }, sessionName);
}

async function launchFirstChapter(page: Page): Promise<void> {
  // Modal is still open from createSessionViaLobby — click the
  // first available Play button to mount the iframe with
  // ?session_id=<own session> threaded onto the URL.
  const play = page.locator('.modal-backdrop button.play-btn:not([disabled])').first();
  await play.waitFor({ state: 'visible', timeout: 15_000 });
  await play.click();
}

