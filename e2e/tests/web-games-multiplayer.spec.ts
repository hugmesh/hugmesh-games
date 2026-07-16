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
 *   • The guest-side leg of that relay exists on main since PR #116:
 *     GameStateService.emitInput routes every kind:'world' payload onto the
 *     per-session `worldNative` sink, and GameStreamController merges
 *     `worldNativeStream(sessionId)` into the SAME `world` SSE event the
 *     guest bridge already subscribes to. Cross-pod parity rides the
 *     `world-native` Kafka channel since PR #117 (default-off — the
 *     single-pod e2e stack is fully covered by the in-pod #116 path).
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
 * Nine games proven — chess, connect-four, dots-and-boxes, go, gomoku,
 * reversi (turn-based: the world echo fires STRICTLY because P1 moved) and
 * tron, asteroids-duel, bomberman (realtime: P1 steers; the host's tick
 * loop broadcasts the authoritative world snapshots).
 *
 * Click coordinates are NEVER hardcoded pixels. Every click game sizes its
 * canvas responsively in layout() from the iframe's live viewport, and the
 * real e2e page CSS (home.component.scss `.canvas-section` max-width 1100px
 * + `.canvas-container` aspect-ratio 16/9 + a 100%-sized iframe) resolves
 * that to ~566px on Playwright's 1280x720 Desktop Chrome viewport — NOT the
 * 480/540px the canvases' static HTML attributes suggest. So each entry
 * declares its target square/intersection as a stable FRACTION of the
 * board, and the shared driver (`actViaGameContext`) computes
 * offsetX/offsetY from the live `#board` getBoundingClientRect() at act
 * time. Every game's canvasTo*() helper scales offsetX by
 * internalWidth/rect.width, so CSS-pixel offsets stay correct even if the
 * canvas is CSS-squeezed. The stackless "click-fraction math" suite at the
 * bottom of this file re-derives each game's inverse mapping from its own
 * layout() formulas and pins every fraction across the full responsive
 * size range.
 */

const PLAYER1 = SEED_USERS.find(u => u.username === 'player1')!;
const PLAYER2 = SEED_USERS.find(u => u.username === 'player2')!;

/** A click target as a stable fraction of the live #board rect (0..1). */
interface FractionClick {
  fx: number;
  fy: number;
}

interface RelayGame {
  slug: string;
  /** Per-game window boot flag the bundle sets once mountGame() resolves. */
  bootFlag: string;
  /** The window field exposing the GameContext (for the action evaluate). */
  ctxFlag: string;
  /**
   * Click-driven games: target points, each a fraction of the live #board
   * rect (resolved via getBoundingClientRect() inside the iframe at act
   * time — never a hardcoded pixel). Applied in order through the bundle's
   * own `ctx.handleLocalInput`, exactly as a human click would arrive.
   */
  clicks?: FractionClick[];
  /** Key-driven (realtime) games: a single synthesized keydown `key`. */
  key?: string;
}

const RELAY_GAMES: RelayGame[] = [
  {
    // Chess: two clicks — select e2, then move to e4. 1.e4 is a legal
    // opening move, so chess_apply_uci accepts it and the host broadcasts
    // the resulting world/state. canvasToSquare (chess.js:670-689) floors
    // the scaled offset into a 0-7 grid index; rankIdx 0 = rank 8 (top).
    slug: 'chess',
    bootFlag: '__chessReady',
    ctxFlag: '__chessCtx',
    clicks: [
      { fx: 4.5 / 8, fy: 6.5 / 8 }, // e2 cell centre: fileIdx 4 (e-file), rankIdx 6 (rank 2)
      { fx: 4.5 / 8, fy: 4.5 / 8 }, // e4 cell centre: fileIdx 4, rankIdx 4 (rank 4)
    ],
  },
  {
    // Tron (realtime): steer the local cycle up via a synthesized ArrowUp
    // keydown through the exposed GameContext. The host applies it to the
    // authoritative engine and the tick loop broadcasts world snapshots the
    // guest re-seats from.
    slug: 'tron',
    bootFlag: '__tronReady',
    ctxFlag: '__tronCtx',
    key: 'ArrowUp',
  },
  {
    // Asteroids-duel (realtime, host-authoritative rAF tick loop — same
    // family as tron): a synthesized ArrowUp = KEYMAP thrust for ship 0
    // (asteroids-duel.js:94), and P1 IS slot 0/host so onLocalInput's
    // guest-remap branch is skipped. The engine's set_input rejects only
    // idx >= players (players=2 in a session duel) — continuous control
    // axes have no move-legality to violate. The 30 Hz host loop calls
    // sendWorld() every tick once booted (asteroids-duel.js:262-269), so
    // world snapshots stream to P2; the keydown additionally proves the
    // handleLocalInput → held-state → engine input path end to end.
    slug: 'asteroids-duel',
    bootFlag: '__asteroidsReady',
    ctxFlag: '__asteroidsCtx',
    key: 'ArrowUp',
  },
  {
    // Bomberman (realtime tick-loop duel): ArrowRight = KEYMAP ['p1','right']
    // (bomberman.js:84) and P1 is the host, so the "only drive YOUR player"
    // gate (mine = 'p1') passes. bomberman_input records the intent; the
    // engine's set_action rejects only GameOver/PlayerDead — and the move is
    // real: P1 spawns at (1,1) and (2,1) is a guaranteed-clear escape lane
    // for EVERY seed (Rust test spawn_corners_and_escape_lanes_are_clear).
    // The host's 160 ms tick loop broadcasts the authoritative world
    // (broadcastWorld, bomberman.js:389-396), which is what P2 receives.
    slug: 'bomberman',
    bootFlag: '__bombermanReady',
    ctxFlag: '__bombermanCtx',
    key: 'ArrowRight',
  },
  {
    // Connect Four: one click dropping a disc into the dead-centre column.
    // layout() (connect-four.js:469-493) makes width = cell*7 + padding*2,
    // and colFromEvent subtracts the padding back out — so the horizontal
    // centre is ALWAYS column floor(3.5) = 3: the padding cancels
    // algebraically for any responsive cell/padding. A fresh board has all
    // 7 columns legal (c4_legal → [0..6]), so c4_apply_drop(3) is accepted
    // and onLocalInput returns {input, world, state} for the relay.
    slug: 'connect-four',
    bootFlag: '__connectFourReady',
    ctxFlag: '__connectFourCtx',
    clicks: [
      { fx: 0.5, fy: 0.5 }, // board centre → column 3 of 7 (padding cancels: floor((w/2 - pad)/cell) = 3)
    ],
  },
  {
    // Dots and Boxes: click the midpoint of H(0,0) — the top-left
    // horizontal edge, index 0 in hIndex(row,col) = row*GRID + col. Every
    // one of the 24 edges is legal on a fresh board (Game::claim rejects
    // only GameOver/OutOfBounds/AlreadyClaimed), so the very first claim
    // always succeeds. layout() (dots-and-boxes.js:434-453) pins
    // pad = round(px*0.12) and step = (px - 2*pad)/3, so H(0,0)'s midpoint
    // (pad + step/2, pad) is the same FRACTION of the board at every
    // resolved size; the ±0.5px pad rounding is noise against the ≥10px
    // canvasToEdge hit radius.
    slug: 'dots-and-boxes',
    bootFlag: '__dabReady',
    ctxFlag: '__dabCtx',
    clicks: [
      { fx: 0.12 + 0.76 / 6, fy: 0.12 }, // midpoint of edge H(0,0): x = pad + step/2, y = pad (pad = 12% of board, step = 76%/3)
    ],
  },
  {
    // Go: click tengen (e5, the centre star point of the 9x9 board). By
    // layout()'s construction (go.js:509-528: margin = cell = px/10) the
    // centre intersection (col 4, row 4 — drawRow is also 4 after the
    // bottom-up flip) sits at EXACTLY px/2 in both axes for any resolved
    // size, so the snap distance is 0. An interior first move on an empty
    // board is unconditionally legal (no captures → not suicide, no ko
    // yet), so go_apply("e5") is accepted and the host broadcasts
    // world/state.
    slug: 'go',
    bootFlag: '__goReady',
    ctxFlag: '__goCtx',
    clicks: [
      { fx: 0.5, fy: 0.5 }, // tengen: margin = cell = px/10 ⇒ intersection (4,4) is at exactly (px/2, px/2)
    ],
  },
  {
    // Gomoku: click the centre intersection (col 7, row 7) of the 15x15
    // grid. layout() (gomoku.js:470-490) sets margin = cell = px/16, so the
    // centre point x = margin + 7*cell = 8*cell = px/2 EXACTLY at any
    // resolved size — snap distance 0. Every one of the 225 points is a
    // legal first move on the fresh engine (is_legal only checks
    // non-terminal + in-bounds + empty), so gomoku_apply is accepted and
    // onLocalInput returns {input, world, state} for the relay.
    slug: 'gomoku',
    bootFlag: '__gomokuReady',
    ctxFlag: '__gomokuCtx',
    clicks: [
      { fx: 0.5, fy: 0.5 }, // centre intersection (7,7): margin = cell = px/16 ⇒ x = 8*cell = px/2 exactly
    ],
  },
  {
    // Reversi: a single click places a disc directly (no select step —
    // reversi.js:240-251 requires only that the square is in state.legal).
    // The standard Othello opening has EXACTLY four legal Black placements
    // — c4, d3, e6, f5 — asserted against the real engine by the Rust test
    // opening_legal_move_set_is_the_four (snapdragon-reversi lib.rs), so
    // clicking c4's cell centre is guaranteed accepted on a fresh session.
    // canvasToSquare (reversi.js:516-535): col = floor(fx*8), rankIdx =
    // 7 - floor(fy*8); c4 = fileIdx 2, rankIdx 3 → rowFromTop 4.
    slug: 'reversi',
    bootFlag: '__reversiReady',
    ctxFlag: '__reversiCtx',
    clicks: [
      { fx: 2.5 / 8, fy: 4.5 / 8 }, // c4 cell centre: col 2 (c-file), rowFromTop 4 (rank 4)
    ],
  },
];

// ── battleship is deliberately ABSENT ──────────────────────────────────────
// A P1-only iframe action cannot drive battleship's two-sided
// placement → ready → fire round-trip. Placement is intentionally never
// relayed (fog-of-war — battleship.js:377 and the wasm shim's doc comment),
// so each browser's engine only ever has its OWN fleet placed; the engine's
// firing_phase() (extensions/snapdragon-battleship/src/lib.rs:389-391)
// requires BOTH boards fully_placed() on the SAME Game instance, so the
// mirrored battleship_ready for the opponent no-ops forever, no client ever
// reaches status:'firing', and battleship's only sendWorld() call (the
// incoming-shot branch, battleship.js:445-482) is unreachable. Covering it
// needs a two-sided driver — BOTH iframes place + ready before P1 fires — a
// different harness shape, not a missing RELAY_GAMES entry. Don't re-derive.

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
      await actInGameFrame(p1, game);

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

/**
 * The ONE shared in-iframe action driver. Runs in the iframe's window scope
 * via frame.evaluate(fn, args) — Playwright serializes only the function
 * SOURCE, so this must stay fully self-contained (a reference to any
 * spec-scope helper would be undefined at runtime inside the page).
 *
 * Key games: synthesize a single keydown through `ctx.handleLocalInput`.
 * Click games: resolve the LIVE `#board` rect via getBoundingClientRect()
 * and turn each declared fraction into a CSS-pixel offsetX/offsetY. Every
 * game's canvasTo*() scales offsetX by internalWidth/rect.width, so
 * fraction × rect.width lands on fraction × internalWidth in engine pixels
 * — exact at any resolved board size, including a CSS-squeezed canvas.
 */
function actViaGameContext(args: {
  ctxFlag: string;
  key: string | null;
  clicks: Array<{ fx: number; fy: number }>;
}): void {
  const w = window as unknown as Record<string, unknown>;
  const ctx = w[args.ctxFlag] as { handleLocalInput: (e: unknown) => void } | undefined;
  if (!ctx || typeof ctx.handleLocalInput !== 'function') {
    throw new Error(`GameContext (${args.ctxFlag}) not exposed on iframe window`);
  }
  if (args.key !== null) {
    ctx.handleLocalInput({ type: 'keydown', key: args.key });
    return;
  }
  const board = document.getElementById('board');
  if (!board) throw new Error('#board canvas not found in iframe document');
  const rect = board.getBoundingClientRect();
  if (!(rect.width > 0) || !(rect.height > 0)) {
    throw new Error(`#board resolved to a zero-size rect (${rect.width}x${rect.height}) — board not laid out yet`);
  }
  for (const c of args.clicks) {
    ctx.handleLocalInput({ type: 'click', offsetX: rect.width * c.fx, offsetY: rect.height * c.fy });
  }
}

/** Drive the game's declared action INSIDE the host iframe's window scope
 *  (same-origin — wasm-fragment is proxied under the host). */
async function actInGameFrame(page: Page, game: RelayGame): Promise<void> {
  const frame = page.frameLocator('iframe.game-frame');
  // evaluate inside the frame's execution context
  await frame.locator('canvas').first().waitFor({ state: 'attached', timeout: 10_000 });
  const handle = await page.$('iframe.game-frame');
  const cf = await handle?.contentFrame();
  if (!cf) throw new Error('game-frame contentFrame unavailable');
  await cf.evaluate(actViaGameContext, {
    ctxFlag: game.ctxFlag,
    key: game.key ?? null,
    clicks: game.clicks ?? [],
  });
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

/* ──────────────────────────────────────────────────────────────────────────
 * click-fraction math — stackless guard.
 *
 * Each test below transcribes ITS game's inverse click mapping from that
 * game's own layout()/canvasTo*() source (file:line cited) and asserts the
 * shipped fractions resolve to the intended square/column/intersection/edge
 * across the game's ENTIRE responsive size range. No browser, no stack —
 * these run anywhere `playwright test` runs, so a geometry change in a game
 * bundle breaks THIS file loudly instead of surfacing as a flaky
 * live-relay timeout.
 * ────────────────────────────────────────────────────────────────────────── */

const bySlug = (slug: string): RelayGame => RELAY_GAMES.find(g => g.slug === slug)!;

test.describe('click-fraction math — static guard (no stack needed)', () => {
  test('chess fractions resolve to e2 → e4 at every layout() size', () => {
    // chess.js:651-668 layout(): cell = floor(target/8), board px = cell*8.
    // chess.js:670-689 canvasToSquare(): idx = floor(offset*scale/cell);
    // with offset = fraction*rect.width, offset*scale = fraction*cell*8.
    const [sel, dst] = bySlug('chess').clicks!;
    for (let target = 240; target <= 640; target++) {
      const cell = Math.floor(target / 8);
      const px = cell * 8;
      expect(Math.floor((sel.fx * px) / cell)).toBe(4); // e-file
      expect(Math.floor((sel.fy * px) / cell)).toBe(6); // rankIdx 6 = rank 2
      expect(Math.floor((dst.fx * px) / cell)).toBe(4); // e-file
      expect(Math.floor((dst.fy * px) / cell)).toBe(4); // rankIdx 4 = rank 4
    }
  });

  test('reversi fraction resolves to c4 at every layout() size', () => {
    // reversi.js:498-514 layout(): identical cell = floor(target/8) shape.
    // reversi.js:516-535 canvasToSquare(): col = floor(x/cell),
    // rankIdx = 7 - floor(y/cell). c4 = fileIdx 2, rankIdx 3.
    const [c4] = bySlug('reversi').clicks!;
    for (let target = 240; target <= 640; target++) {
      const cell = Math.floor(target / 8);
      const px = cell * 8;
      expect(Math.floor((c4.fx * px) / cell)).toBe(2); // c-file
      expect(7 - Math.floor((c4.fy * px) / cell)).toBe(3); // rankIdx 3 = rank 4
    }
  });

  test('connect-four centre click resolves to column 3 for every responsive cell', () => {
    // connect-four.js:469-493 layout(): cell ∈ [36,92],
    // padding = max(6, floor(cell*0.12)), width = cell*7 + padding*2.
    // connect-four.js:496-510 colFromEvent(): col = floor((x - padding)/cell).
    const [centre] = bySlug('connect-four').clicks!;
    for (let cell = 36; cell <= 92; cell++) {
      const padding = Math.max(6, Math.floor(cell * 0.12));
      const width = cell * 7 + padding * 2;
      expect(Math.floor((centre.fx * width - padding) / cell)).toBe(3);
    }
  });

  test('go centre click snaps to tengen (4,4) at every layout() size', () => {
    // go.js:509-528 layout(): cell = px/10, margin = cell (9x9 default).
    // go.js:542-568 canvasToPoint(): col = round((x-margin)/cell),
    // drawRow likewise, row = 8 - drawRow; reject if dist > cell*0.6.
    const [tengen] = bySlug('go').clicks!;
    for (let px = 300; px <= 640; px++) {
      const cell = px / 10;
      const margin = cell;
      const x = tengen.fx * px;
      const y = tengen.fy * px;
      const col = Math.round((x - margin) / cell);
      const drawRow = Math.round((y - margin) / cell);
      expect(col).toBe(4);
      expect(8 - drawRow).toBe(4); // engine row (0 = bottom)
      const dist = Math.hypot(x - (margin + col * cell), y - (margin + drawRow * cell));
      expect(dist).toBeLessThan(cell * 0.6); // in fact ~0: (4+1)*cell = px/2 exactly
    }
  });

  test('gomoku centre click snaps to intersection (7,7) at every layout() size', () => {
    // gomoku.js:470-490 layout(): cell = px/16, margin = cell (SIZE=15).
    // gomoku.js:498-522 canvasToPoint(): col/row = round((offset-margin)/cell);
    // reject if dist > cell*0.6.
    const [centre] = bySlug('gomoku').clicks!;
    for (let px = 300; px <= 640; px++) {
      const cell = px / 16;
      const margin = cell;
      const x = centre.fx * px;
      const y = centre.fy * px;
      const col = Math.round((x - margin) / cell);
      const row = Math.round((y - margin) / cell);
      expect(col).toBe(7);
      expect(row).toBe(7);
      const dist = Math.hypot(x - (margin + col * cell), y - (margin + row * cell));
      expect(dist).toBeLessThan(cell * 0.6); // in fact ~0: (7+1)*cell = px/2 exactly
    }
  });

  test('dots-and-boxes fraction picks edge H(0,0)=0, unambiguously, at every layout() size', () => {
    // dots-and-boxes.js:434-453 layout(): pad = round(px*0.12),
    // step = (px - 2*pad)/3, hit = max(10, round(step*0.34)).
    // dots-and-boxes.js:466-521 canvasToEdge(): nearest edge by
    // point-to-segment distance, must be < hit. Edge indices per
    // hIndex(row,col) = row*3 + col, vIndex(row,col) = 12 + row*4 + col.
    const [mid] = bySlug('dots-and-boxes').clicks!;
    const pointToSegment = (qx: number, qy: number, ax: number, ay: number, bx: number, by: number): number => {
      const dx = bx - ax;
      const dy = by - ay;
      const len2 = dx * dx + dy * dy || 1;
      let t = ((qx - ax) * dx + (qy - ay) * dy) / len2;
      t = Math.max(0, Math.min(1, t));
      return Math.hypot(qx - (ax + t * dx), qy - (ay + t * dy));
    };
    for (let px = 240; px <= 640; px++) {
      const pad = Math.round(px * 0.12);
      const step = (px - 2 * pad) / 3;
      const hit = Math.max(10, Math.round(step * 0.34));
      const dot = (r: number, c: number) => ({ x: pad + c * step, y: pad + r * step });
      const x = mid.fx * px;
      const y = mid.fy * px;
      // Mirror canvasToEdge's scan exactly: H edges then V edges, strict `<`
      // keeps the first-best; also track the runner-up to prove the pick is
      // unambiguous, not a hit-radius coin-flip.
      let best: number | null = null;
      let bestDist = hit;
      let runnerUp = Infinity;
      const consider = (idx: number, d: number): void => {
        if (d < bestDist) {
          if (best !== null) runnerUp = Math.min(runnerUp, bestDist);
          bestDist = d;
          best = idx;
        } else if (d < runnerUp) {
          runnerUp = d;
        }
      };
      for (let row = 0; row < 4; row++) {
        for (let col = 0; col < 3; col++) {
          const a = dot(row, col);
          const b = dot(row, col + 1);
          consider(row * 3 + col, pointToSegment(x, y, a.x, a.y, b.x, b.y));
        }
      }
      for (let row = 0; row < 3; row++) {
        for (let col = 0; col < 4; col++) {
          const a = dot(row, col);
          const b = dot(row + 1, col);
          consider(12 + row * 4 + col, pointToSegment(x, y, a.x, a.y, b.x, b.y));
        }
      }
      expect(best).toBe(0); // hIndex(0,0)
      expect(bestDist).toBeLessThan(1); // essentially ON the segment (pad rounding ≤ 0.5px)
      expect(runnerUp).toBeGreaterThan(hit / 2); // nearest rival is far outside ambiguity range
    }
  });

  test('every relay entry declares exactly one action shape', () => {
    for (const game of RELAY_GAMES) {
      const hasClicks = Array.isArray(game.clicks) && game.clicks.length > 0;
      const hasKey = typeof game.key === 'string' && game.key.length > 0;
      expect(
        hasClicks !== hasKey,
        `${game.slug}: must declare clicks XOR key (got clicks=${hasClicks}, key=${hasKey})`,
      ).toBe(true);
      for (const c of game.clicks ?? []) {
        expect(c.fx).toBeGreaterThan(0);
        expect(c.fx).toBeLessThan(1);
        expect(c.fy).toBeGreaterThan(0);
        expect(c.fy).toBeLessThan(1);
      }
    }
  });
});
