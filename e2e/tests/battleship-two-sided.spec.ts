import { test, expect, BrowserContext, Page, Frame } from '@playwright/test';
import { SEED_USERS, login, newE2eContext } from './helpers/roles';
import { installSseCapture } from './helpers/sse-bridge';

/**
 * Battleship — the one relay-capable web-native game with ZERO UI-level
 * multiplayer coverage. web-games-multiplayer.spec.ts documents (and the
 * engine enforces) that a P1-only action can never drive battleship's
 * two-sided placement → ready → fire round-trip: placement is deliberately
 * never relayed (fog of war), and firing_phase() requires BOTH boards
 * fully_placed(), so a single-sided driver never reaches status:'firing'.
 *
 * This is the two-sided driver the exclusion comment calls for: BOTH
 * iframes place their fleet + press Ready through their own GameContext,
 * both engines reach the firing phase, then P1 fires a known coordinate and
 * we assert:
 *   • the shot crossed to P2 and was resolved on P2's OWN board (hit),
 *   • the resolved result relayed back onto P1's enemy fog (hit),
 *   • FOG OF WAR: P1's fog reveals EXACTLY the one fired cell — P1 never
 *     learned P2's layout (placement never crossed the wire).
 *
 * Same harness shape as web-games-multiplayer.spec.ts (lobby flow, the
 * per-game window boot flag, in-iframe GameContext driving) — so it lives
 * as a tests/ spec, not a BDD feature, because the in-iframe ctx driving
 * primitive lives here, not in the Gherkin step layer.
 */

const PLAYER1 = SEED_USERS.find(u => u.username === 'player1')!;
const PLAYER2 = SEED_USERS.find(u => u.username === 'player2')!;
const GAME_SLUG = 'battleship';

function escapeRe(s: string): string {
  return s.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
}

/* ── lobby-flow helpers (mirror web-games-multiplayer.spec.ts) ───────────── */

async function openGameCard(page: Page, characterId: number): Promise<void> {
  await page.goto(`/?characterId=${characterId}`, { waitUntil: 'domcontentloaded' });
  const card = page.locator('.game-card', {
    has: page.locator('.chip--game-slug', { hasText: new RegExp(`^${escapeRe(GAME_SLUG)}$`) }),
  }).first();
  await card.waitFor({ state: 'visible', timeout: 25_000 });
  await card.click();
}

async function createSessionViaLobby(page: Page, sessionName: string, characterId: number): Promise<string> {
  await openGameCard(page, characterId);
  const nameInput = page.locator('#session-name-input');
  await nameInput.waitFor({ state: 'visible', timeout: 10_000 });
  await nameInput.fill(sessionName);
  await page.locator('input[type="radio"][value="public"]').check();
  await page.locator('button.create-btn').click();
  await page.locator('.session-picker-active').waitFor({ state: 'visible', timeout: 15_000 });
  return await page.evaluate(async ({ name, slug }) => {
    const raw = window.localStorage.getItem('tw_auth');
    const token = raw ? (JSON.parse(raw).accessToken ?? '') : '';
    const r = await fetch(`/api/sessions?gameSlug=${encodeURIComponent(slug)}`, {
      headers: { Authorization: `Bearer ${token}` },
    });
    const list = (await r.json()) as Array<{ id: string; name: string }>;
    return (list.find(s => s.name === name) ?? list[0]).id;
  }, { name: sessionName, slug: GAME_SLUG });
}

async function joinSessionViaLobby(page: Page, sessionName: string, characterId: number): Promise<void> {
  await openGameCard(page, characterId);
  const row = page.locator('.session-row', {
    has: page.locator('.session-name', { hasText: sessionName }),
  }).first();
  await row.waitFor({ state: 'visible', timeout: 15_000 });
  await row.locator('button.join-btn').click();
  await page.locator('.session-picker-active').waitFor({ state: 'visible', timeout: 15_000 });
}

async function launchFirstChapter(page: Page): Promise<void> {
  const play = page.locator('.modal-backdrop button.play-btn:not([disabled])').first();
  await play.waitFor({ state: 'visible', timeout: 15_000 });
  await play.click();
}

/** The battleship game-frame's contentFrame (same-origin under the host proxy). */
async function gameFrame(page: Page): Promise<Frame> {
  const handle = await page.$('iframe.game-frame');
  const cf = await handle?.contentFrame();
  if (!cf) throw new Error('battleship game-frame contentFrame unavailable');
  return cf;
}

/* ── in-iframe drivers (function SOURCE is serialized — keep self-contained) ─ */

/**
 * Place all 5 ships down column A (rows 0..4, horizontal, no overlap — the
 * engine's standard distinct-row plan) and press Ready. Offsets are a
 * fraction of the LIVE #own rect so canvasToCell's scale-by-rect maps them
 * to the intended cell at any resolved board size. Returns the post-state
 * so the caller can assert the whole fleet placed + readied.
 */
function placeAndReadyInFrame(): { toPlace: number; ready: boolean; status: string } {
  const w = window as unknown as Record<string, unknown>;
  const ctx = w.__battleshipCtx as {
    handleLocalInput: (e: unknown) => void;
    GAME: { _getState: () => { toPlace: unknown[]; localReady: boolean; status: string } };
  } | undefined;
  if (!ctx || typeof ctx.handleLocalInput !== 'function') throw new Error('battleship ctx not exposed');
  const own = document.getElementById('own');
  if (!own) throw new Error('#own canvas not found');
  const rect = own.getBoundingClientRect();
  if (!(rect.width > 0) || !(rect.height > 0)) throw new Error('own board not laid out');
  const SIZE = 10;
  for (let row = 0; row < 5; row++) {
    ctx.handleLocalInput({
      type: 'click', board: 'own',
      offsetX: 0.5 * (rect.width / SIZE),
      offsetY: (row + 0.5) * (rect.height / SIZE),
    });
  }
  ctx.handleLocalInput({ type: 'ready' });
  const s = ctx.GAME._getState();
  return { toPlace: s.toPlace.length, ready: s.localReady, status: s.status };
}

/** Fire at enemy cell (col,row) via a click on the #enemy board. */
function fireInFrame(args: { col: number; row: number }): void {
  const w = window as unknown as Record<string, unknown>;
  const ctx = w.__battleshipCtx as { handleLocalInput: (e: unknown) => void } | undefined;
  if (!ctx || typeof ctx.handleLocalInput !== 'function') throw new Error('battleship ctx not exposed');
  const enemy = document.getElementById('enemy');
  if (!enemy) throw new Error('#enemy canvas not found');
  const rect = enemy.getBoundingClientRect();
  const SIZE = 10;
  ctx.handleLocalInput({
    type: 'click', board: 'enemy',
    offsetX: (args.col + 0.5) * (rect.width / SIZE),
    offsetY: (args.row + 0.5) * (rect.height / SIZE),
  });
}

/** Snapshot the client state's fog/own grids for assertions. */
function readStateInFrame(): {
  status: string; ownAt00: string | null; ownStruck: number;
  enemyAt00: string | null; enemyNonNull: number;
} {
  const w = window as unknown as Record<string, unknown>;
  const ctx = w.__battleshipCtx as {
    GAME: { _getState: () => {
      status: string;
      own: Array<Array<string | null>>;
      enemy: Array<Array<string | null>>;
    } };
  };
  const s = ctx.GAME._getState();
  const struck = (g: Array<Array<string | null>>) =>
    g.reduce((n, col) => n + col.filter(v => v === 'hit' || v === 'miss' || v === 'sunk').length, 0);
  const nonNull = (g: Array<Array<string | null>>) =>
    g.reduce((n, col) => n + col.filter(v => v !== null).length, 0);
  return {
    status: s.status,
    ownAt00: s.own[0][0],
    ownStruck: struck(s.own),
    enemyAt00: s.enemy[0][0],
    enemyNonNull: nonNull(s.enemy),
  };
}

/** Poll an iframe's client-state status until it equals `want` (or throw). */
async function waitForStatus(page: Page, want: string, timeoutMs: number): Promise<void> {
  const deadline = Date.now() + timeoutMs;
  while (Date.now() < deadline) {
    const cf = await gameFrame(page);
    const st = await cf.evaluate(readStateInFrame);
    if (st.status === want) return;
    await page.waitForTimeout(500);
  }
  const cf = await gameFrame(page);
  const st = await cf.evaluate(readStateInFrame);
  throw new Error(`battleship never reached status='${want}' (last='${st.status}')`);
}

async function waitBootFlag(page: Page, timeoutMs: number): Promise<void> {
  const cf = await gameFrame(page);
  await cf.waitForFunction(() => (window as unknown as Record<string, unknown>).__battleshipReady === true,
    undefined, { timeout: timeoutMs });
}

test.describe('battleship — two-sided multiplayer relay', () => {
  test('both fleets place + ready → firing phase; P1 fires, P2 resolves, result relays back, fog stays fogged', async ({ browser }) => {
    const ctx1: BrowserContext = await newE2eContext(browser);
    const ctx2: BrowserContext = await newE2eContext(browser);
    await installSseCapture(ctx1);
    await installSseCapture(ctx2);
    const p1: Page = await ctx1.newPage();
    const p2: Page = await ctx2.newPage();
    try {
      // Distinct characters (1 / 2) so the per-character control authz
      // doesn't collide (the loser 403s on the fire relay POST).
      await login(p1, PLAYER1);
      await login(p2, PLAYER2);

      const sessionName = `e2e-battleship-${Date.now()}`;
      const sessionId = await createSessionViaLobby(p1, sessionName, 1);
      await joinSessionViaLobby(p2, sessionName, 2);

      // Arm the session-scoped EventSource waits BEFORE launching (they only
      // match future responses) so the fire relay lands on a live subscriber.
      const stream1 = p1.waitForResponse(r => r.url().includes(`/api/game/stream?sessionId=${sessionId}`), { timeout: 60_000 });
      const stream2 = p2.waitForResponse(r => r.url().includes(`/api/game/stream?sessionId=${sessionId}`), { timeout: 60_000 });

      await launchFirstChapter(p1);
      await launchFirstChapter(p2);

      await Promise.all([
        p1.waitForFunction(() => document.querySelectorAll('iframe.game-frame').length > 0, undefined, { timeout: 30_000 }),
        p2.waitForFunction(() => document.querySelectorAll('iframe.game-frame').length > 0, undefined, { timeout: 30_000 }),
      ]);
      await Promise.all([stream1, stream2]);
      await Promise.all([waitBootFlag(p1, 120_000), waitBootFlag(p2, 120_000)]);

      // ── BOTH sides place their fleet + ready ─────────────────────────
      const cf1 = await gameFrame(p1);
      const cf2 = await gameFrame(p2);
      const place1 = await cf1.evaluate(placeAndReadyInFrame);
      const place2 = await cf2.evaluate(placeAndReadyInFrame);
      expect(place1, 'P1 must place all 5 ships and ready').toMatchObject({ toPlace: 0, ready: true });
      expect(place2, 'P2 must place all 5 ships and ready').toMatchObject({ toPlace: 0, ready: true });

      // ── both engines reach the firing phase (the two-sided handshake) ─
      // Neither could reach it single-sided: firing_phase() needs BOTH
      // boards fully_placed(), so this is the ready-relay crossing proof.
      await waitForStatus(p1, 'firing', 30_000);
      await waitForStatus(p2, 'firing', 30_000);

      // ── P1 fires at A1 (col 0,row 0) — a KNOWN P2 ship cell (carrier bow,
      //    placed at A1 above) → deterministic 'hit'. ──────────────────
      await cf1.evaluate(fireInFrame, { col: 0, row: 0 });

      // ── P2's OWN board records the incoming shot (the shot crossed) ──
      await expect.poll(async () => (await (await gameFrame(p2)).evaluate(readStateInFrame)).ownStruck,
        { timeout: 15_000, message: "P2's own board must record the incoming shot" }).toBe(1);
      const p2state = await (await gameFrame(p2)).evaluate(readStateInFrame);
      expect(p2state.ownAt00, 'the shot at A1 hits P2 carrier bow').toBe('hit');

      // ── the resolved result relays back onto P1's enemy fog ──────────
      await expect.poll(async () => (await (await gameFrame(p1)).evaluate(readStateInFrame)).enemyAt00,
        { timeout: 15_000, message: "P1's fog must record the relayed result at A1" }).toBe('hit');

      // ── FOG OF WAR: P1's fog reveals EXACTLY the one fired cell ──────
      // Placement never crossed the wire, so P1 knows exactly the single
      // coordinate it fired — never P2's layout. A leak would push this >1.
      const p1state = await (await gameFrame(p1)).evaluate(readStateInFrame);
      expect(p1state.enemyNonNull,
        'P1 must know ONLY the one cell it fired — fog of war (no layout leak)').toBe(1);

      // Liveness: both frames still render their boards after the exchange.
      await expect(p1.frameLocator('iframe.game-frame').locator('canvas').first()).toBeVisible({ timeout: 10_000 });
      await expect(p2.frameLocator('iframe.game-frame').locator('canvas').first()).toBeVisible({ timeout: 10_000 });
    } finally {
      await ctx1.close();
      await ctx2.close();
    }
  });
});
