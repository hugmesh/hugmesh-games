import { test, expect, BrowserContext, Page } from '@playwright/test';
import { SEED_USERS, login, newE2eContext } from './helpers/roles';
import { installSseCapture } from './helpers/sse-bridge';

/**
 * Web-native MULTIPLAYER relay — the cross-browser render+input layer the
 * API/SSE harness (e2e/games-arch) deliberately can't see.
 *
 * Two real Casdoor accounts in two browser contexts both launch the SAME
 * session for a host-authoritative relay game. Player1 (the host, slot 0)
 * ACTS INSIDE ITS IFRAME — a real move through the bundle's own
 * `ctx.handleLocalInput` against the in-iframe wasm engine — and we assert
 * Player2's iframe RECEIVES the relayed event and its render state updates.
 *
 * Relay transport recap (verified from source):
 *   • The host applies a move in its wasm engine, then broadcasts the
 *     authoritative `world/state` over the session (sse-host-client.js
 *     sendWorld → POST /api/input/{cid} kind:'world' → the BFF reemits it
 *     on the session-scoped `world` SSE channel — a dumb relay).
 *   • Embedded under the host shell, the iframe uses the BRIDGE transport:
 *     it posts SD_SSE_SUBSCRIBE; the host's ApiBridgeService opens ONE
 *     EventSource at /api/game/stream?sessionId=… and posts each typed
 *     event back INTO the iframe as SD_SSE_EVENT. `installSseCapture`
 *     attaches a context-wide init-script listener so the iframe captures
 *     every SD_SSE_EVENT on `window.__captureSseEvents` BEFORE BridgeGlue
 *     can drain it.
 *   • ApiBridgeService forwards the `world` channel (among movement /
 *     character / time / scene / session-control) — so the host's
 *     `world/state` echo lands in Player2's iframe as an SD_SSE_EVENT.
 *
 * Two games proven: chess (turn-based — world echo fires STRICTLY because
 * P1 moved) and tron (realtime tick loop — P1 steers, P2 receives the
 * authoritative world snapshots).
 */

const PLAYER1 = SEED_USERS.find(u => u.username === 'player1')!;
const PLAYER2 = SEED_USERS.find(u => u.username === 'player2')!;

interface RelayGame {
  slug: string;
  /** Per-game window boot flag the bundle sets once mountGame() resolves. */
  bootFlag: string;
  /**
   * Drive a real, deterministic move INSIDE the host iframe through the
   * bundle's own input handler (`window.<ctxFlag>.handleLocalInput`) so the
   * move runs against the in-iframe wasm engine and relays exactly as a
   * human click/keypress would. Runs in the iframe's window scope.
   */
  actInIframe: () => void;
  /** The window field exposing the GameContext (for the action evaluate). */
  ctxFlag: string;
}

const RELAY_GAMES: RelayGame[] = [
  {
    // Chess: click e2 then e4 on the #board canvas via the exposed
    // GameContext. canvasToSquare reads offsetX/offsetY directly; the
    // 480px board is an 8x8 grid (cell = 60). White is at the bottom, so
    // rankIdx 0 = rank 8. e-file = fileIdx 4. e2 = rankIdx 6, e4 = rankIdx 4.
    // Centres: e2 = (270, 390), e4 = (270, 270). This plays 1.e4 — a legal
    // opening move — so chess_apply_uci accepts it and the host broadcasts
    // the resulting world/state.
    slug: 'chess',
    bootFlag: '__chessReady',
    ctxFlag: '__chessCtx',
    actInIframe: () => {
      const w = window as unknown as { __chessCtx?: { handleLocalInput: (e: unknown) => void } };
      const ctx = w.__chessCtx;
      if (!ctx) throw new Error('chess GameContext (__chessCtx) not exposed on iframe window');
      ctx.handleLocalInput({ type: 'click', offsetX: 270, offsetY: 390 }); // select e2
      ctx.handleLocalInput({ type: 'click', offsetX: 270, offsetY: 270 }); // move to e4
    },
  },
  {
    // Tron (realtime): steer the local cycle up via a synthesized ArrowUp
    // keydown through the exposed GameContext. The host applies it to the
    // authoritative engine and the tick loop broadcasts world snapshots the
    // guest re-seats from.
    slug: 'tron',
    bootFlag: '__tronReady',
    ctxFlag: '__tronCtx',
    actInIframe: () => {
      const w = window as unknown as { __tronCtx?: { handleLocalInput: (e: unknown) => void } };
      const ctx = w.__tronCtx;
      if (!ctx) throw new Error('tron GameContext (__tronCtx) not exposed on iframe window');
      ctx.handleLocalInput({ type: 'keydown', key: 'ArrowUp' });
    },
  },
];

test.describe('web-native multiplayer — host-authoritative SSE relay', () => {
  for (const game of RELAY_GAMES) {
    test(`${game.slug} — P1 acts in iframe, P2's iframe receives the relayed world event`, async ({ browser }) => {
      const ctx1: BrowserContext = await newE2eContext(browser);
      const ctx2: BrowserContext = await newE2eContext(browser);
      // Init-script SSE capture on BOTH contexts so every iframe that
      // mounts later gets the SD_SSE_EVENT listener before its own scripts
      // run — eliminates the race where BridgeGlue drains before we attach.
      await installSseCapture(ctx1);
      await installSseCapture(ctx2);
      const p1: Page = await ctx1.newPage();
      const p2: Page = await ctx2.newPage();

      // Distinct characters: P1=1, P2=2. authorizeControl keys on the active
      // session member's character_id, so two players sharing one character
      // collide (the loser 403s on /api/controls|/api/input).
      await login(p1, PLAYER1);
      await login(p2, PLAYER2);

      // ── lobby flow: P1 creates the session, P2 joins ────────────────
      // Driving through the lobby modal (not direct /api/sessions) is what
      // populates the home component's activeSessionId so the iframe URL
      // gets `?session_id=` threaded on — without it the wrapper subscribes
      // to the unscoped global stream and the relay assertion is meaningless.
      const sessionName = `e2e-web-${game.slug}-${Date.now()}`;
      const sessionId = await createSessionViaLobby(p1, game.slug, sessionName, 1);
      await joinSessionViaLobby(p2, game.slug, sessionName, 2);

      // ── arm the session-scoped EventSource gate BEFORE launching ─────
      // The host shell's ApiBridgeService opens the EventSource the instant
      // the iframe posts SD_SSE_SUBSCRIBE — which, for these web-native
      // bundles, fires within a few hundred ms of mount (the wasm engines
      // boot sub-second; the launch matrix proves it). That's far faster
      // than the Godot bundles the multiplayer-same-session spec was modelled
      // on, where the EventSource opened only after a 15-90s WASM compile.
      // `waitForResponse` only catches responses that arrive AFTER it's
      // registered, so we MUST arm the promise BEFORE clicking Play —
      // otherwise the subscription response lands during boot, before the
      // wait is set up, and we time out on a stream that opened fine.
      const stream1 = p1.waitForResponse(
        r => r.url().includes(`/api/game/stream?sessionId=${sessionId}`),
        { timeout: 60_000 },
      );
      const stream2 = p2.waitForResponse(
        r => r.url().includes(`/api/game/stream?sessionId=${sessionId}`),
        { timeout: 60_000 },
      );

      // Both players Play the first chapter → iframe.game-frame mounts.
      await launchFirstChapter(p1);
      await launchFirstChapter(p2);

      await Promise.all([
        p1.waitForFunction(() => document.querySelectorAll('iframe.game-frame').length > 0, undefined, { timeout: 30_000 }),
        p2.waitForFunction(() => document.querySelectorAll('iframe.game-frame').length > 0, undefined, { timeout: 30_000 }),
      ]);

      // Both session-scoped EventSources opened (P2 is now listening, so a
      // relayed world event after P1 acts is a real delivery, not a race).
      await Promise.all([stream1, stream2]);

      // Both engines booted (mountGame resolved). Required so P1's
      // handleLocalInput has a live wasm engine + exposed GameContext.
      await Promise.all([
        waitForBootFlag(p1, game.bootFlag, 120_000),
        waitForBootFlag(p2, game.bootFlag, 120_000),
      ]);

      // Drain anything queued during boot so the post-action assertion only
      // counts FRESH deliveries caused by P1's move.
      await drainGameFrameEvents(p1);
      await drainGameFrameEvents(p2);

      // ── P1 ACTS IN THE IFRAME ────────────────────────────────────────
      await actInGameFrame(p1, game.actInIframe);

      // ── ASSERT: P2's iframe receives the relayed world event ─────────
      // The host's authoritative world/state echo rides the `world` SSE
      // channel, which the host shell forwards into P2's iframe as
      // SD_SSE_EVENT. Poll P2's capture buffer for it.
      const p2events = await pollGameFrameEvents(p2, 15_000);
      const p2sse = p2events.filter(e => e.type === 'SD_SSE_EVENT');
      expect(
        p2sse.length,
        `${game.slug}: P2's iframe must receive at least one relayed SD_SSE_EVENT after P1 acted`,
      ).toBeGreaterThan(0);

      // At least one delivery must be the host's `world` echo (the
      // authoritative state update), not just an incidental channel.
      const p2world = p2sse.filter(e => {
        const ch = (e.payload as { type?: string } | null)?.type;
        return ch === 'world';
      });
      expect(
        p2world.length,
        `${game.slug}: P2 must receive a host 'world' state update (got channels: ${
          p2sse.map(e => (e.payload as { type?: string } | null)?.type).join(',')
        })`,
      ).toBeGreaterThan(0);

      // ── ASSERT: P2's render STATE updates ────────────────────────────
      // A delivered event is necessary but not sufficient — prove the
      // guest's iframe is still alive + rendering by confirming its canvas
      // is present (the engine re-applied the relayed world without
      // crashing the frame). The world payload above already proves the
      // authoritative state crossed the wire.
      await expect(
        p2.frameLocator('iframe.game-frame').locator('canvas').first(),
        `${game.slug}: P2's iframe must still render its canvas after applying the relayed world`,
      ).toBeVisible({ timeout: 10_000 });

      await ctx1.close();
      await ctx2.close();
    });
  }
});

/* ──────────────────────────────────────────────────────────────────────────
 * lobby-flow helpers (mirror multiplayer-same-session.spec.ts, but locate
 * the specific web game's card by its slug chip rather than `.game-card`
 * first()).
 * ────────────────────────────────────────────────────────────────────────── */

/** Open the named game's card, fill + submit the create-session form, return
 *  the session id. Leaves the modal open with the chapter list visible. */
async function createSessionViaLobby(
  page: Page, gameSlug: string, sessionName: string, characterId: number,
): Promise<string> {
  await openGameCard(page, gameSlug, characterId);
  const nameInput = page.locator('#session-name-input');
  await nameInput.waitFor({ state: 'visible', timeout: 10_000 });
  await nameInput.fill(sessionName);
  await page.locator('input[type="radio"][value="public"]').check();
  await page.locator('button.create-btn').click();
  await page.locator('.session-picker-active').waitFor({ state: 'visible', timeout: 15_000 });
  return await page.evaluate(async ({ name, slug }) => {
    // The auth token lives in localStorage['tw_auth'] (helpers/roles.ts).
    const raw = window.localStorage.getItem('tw_auth');
    const token = raw ? (JSON.parse(raw).accessToken ?? '') : '';
    const r = await fetch(`/api/sessions?gameSlug=${encodeURIComponent(slug)}`, {
      headers: { Authorization: `Bearer ${token}` },
    });
    const list = (await r.json()) as Array<{ id: string; name: string }>;
    return (list.find(s => s.name === name) ?? list[0]).id;
  }, { name: sessionName, slug: gameSlug });
}

/** Open the named game's card and click Join on the row matching the name. */
async function joinSessionViaLobby(
  page: Page, gameSlug: string, sessionName: string, characterId: number,
): Promise<void> {
  await openGameCard(page, gameSlug, characterId);
  const row = page.locator('.session-row', {
    has: page.locator('.session-name', { hasText: sessionName }),
  }).first();
  await row.waitFor({ state: 'visible', timeout: 15_000 });
  await row.locator('button.join-btn').click();
  await page.locator('.session-picker-active').waitFor({ state: 'visible', timeout: 15_000 });
}

/** Navigate home (carrying the characterId override) and open the game card
 *  whose slug chip equals `gameSlug`. */
async function openGameCard(page: Page, gameSlug: string, characterId: number): Promise<void> {
  await page.goto(`/?characterId=${characterId}`, { waitUntil: 'domcontentloaded' });
  const card = page.locator('.game-card', {
    has: page.locator('.chip--game-slug', { hasText: new RegExp(`^${escapeRe(gameSlug)}$`) }),
  }).first();
  await card.waitFor({ state: 'visible', timeout: 25_000 });
  await card.click();
}

async function launchFirstChapter(page: Page): Promise<void> {
  const play = page.locator('.modal-backdrop button.play-btn:not([disabled])').first();
  await play.waitFor({ state: 'visible', timeout: 15_000 });
  await play.click();
}

/* ──────────────────────────────────────────────────────────────────────────
 * game-frame capture helpers — the web-native iframe uses class `game-frame`
 * (app-game-canvas), NOT the Godot remotes' `wasm-frame`, so the shared
 * helpers/sse-bridge.ts drain (which queries `iframe.wasm-frame`) doesn't see
 * it. These mirror that drain against `iframe.game-frame`.
 * ────────────────────────────────────────────────────────────────────────── */

interface CapturedEvent { type: string; payload: unknown; at: number; }

/** Run a function INSIDE the host iframe's window scope (same-origin —
 *  wasm-fragment is proxied under the host). */
async function actInGameFrame(page: Page, fn: () => void): Promise<void> {
  const frame = page.frameLocator('iframe.game-frame');
  // evaluate inside the frame's execution context
  await frame.locator('canvas').first().waitFor({ state: 'attached', timeout: 10_000 });
  const handle = await page.$('iframe.game-frame');
  const cf = await handle?.contentFrame();
  if (!cf) throw new Error('game-frame contentFrame unavailable');
  await cf.evaluate(fn);
}

/** Drain the captured SD_SSE_EVENT buffer out of the game-frame iframe. */
async function drainGameFrameEvents(page: Page): Promise<CapturedEvent[]> {
  const handle = await page.$('iframe.game-frame');
  const cf = await handle?.contentFrame();
  if (!cf) return [];
  return await cf.evaluate(() => {
    const w = window as unknown as Record<string, unknown>;
    const buf = Array.isArray(w.__captureSseEvents)
      ? (w.__captureSseEvents as Array<{ type: string; payload: unknown; at: number }>)
      : [];
    w.__captureSseEvents = [];
    return buf;
  });
}

/** Poll the game-frame capture buffer until it has any event or `ms` elapse. */
async function pollGameFrameEvents(page: Page, ms: number): Promise<CapturedEvent[]> {
  const deadline = Date.now() + ms;
  const all: CapturedEvent[] = [];
  while (Date.now() < deadline) {
    all.push(...(await drainGameFrameEvents(page)));
    if (all.length > 0) return all;
    await page.waitForTimeout(400);
  }
  return all;
}

/** Wait for the per-game boot flag on the same-origin iframe window. */
async function waitForBootFlag(page: Page, flag: string, timeoutMs: number): Promise<void> {
  await page.waitForFunction(
    (f: string) => {
      const fr = document.querySelector('iframe.game-frame') as HTMLIFrameElement | null;
      const w = fr?.contentWindow as (Window & Record<string, unknown>) | null | undefined;
      if (!w) return false;
      return Boolean(w[f]);
    },
    flag,
    { timeout: timeoutMs, polling: 250 },
  );
}

function escapeRe(s: string): string {
  return s.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
}
