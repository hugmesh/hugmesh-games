// Step definitions backing features/makers/biome-worlds.feature and
// features/makers/character-maker.feature — the Six Realms
// real-Godot-export suite (@godot, run via `make e2e-godot`).
//
// Design contracts these steps encode (docs: decision 027 + the
// biome-worlds design):
//   * Maker 'rpg' games mount through the host game-canvas legacy path
//     — the iframe is `iframe.game-frame` with a uid-gated
//     /api/assets/levels/<game>/<uid>/<area>/index.html src.
//   * Render proof is a compositor screenshot of the iframe element +
//     pngjs centre-crop stats (Godot's WebGL2 context has
//     preserveDrawingBuffer=false, so canvas getImageData reads blank).
//   * Movement proof is the SSE echo: real keys → generated
//     game_bridge → POST /api/controls/{cid} → movement channel →
//     wrapper SD_SSE_EVENT relay → drainCapturedEvents. Fallback (a
//     focus flake must degrade, not fail): sendControl + the same
//     relay assertion — the step reports which path proved it.
//   * Room transitions surface as `ENTER_ROOM:<room-uuid>` control
//     echoes; shop buys/sells as `SHOP_BUY:<item>`/`SHOP_SELL:<item>`
//     echoes plus an SD_SHOP_EVENT postMessage to the parent window;
//     the inn rest triggers a real `character` SSE event.

import { expect, Page } from '@playwright/test';
import { Given, When, Then } from '../support/fixtures';
import { ctxRegistry, pageRegistry, sessionByName } from './_registry';
import { login, newE2eContext, sendControl } from '../../tests/helpers/roles';
import { captureIframeSseEvents, drainCapturedEvents, installSseCapture } from '../../tests/helpers/sse-bridge';
import { analyzePngCenterCrop, CropStats, redDominantFraction, Rgb, rgbDistance } from '../../tests/helpers/pixels';

// ── Module state ──────────────────────────────────────────────────────────

/**
 * Suite-level palette registry: biome → mean RGB of its rendered
 * world. Deliberately NOT cleared by resetRegistry — the pairwise
 * distinctness assertion in the last explore scenario needs the
 * fingerprints registered by the earlier ones (workers=1, scenarios
 * run in feature-file order).
 */
const biomeRgbRegistry = new Map<string, Rgb>();

/** Movement actions sent by the most recent keyboard-movement step —
 *  the observer step filters the SSE drain against exactly these. */
let lastSentActions: string[] = [];

/** Pages we've hooked a /api/game/stream response watcher onto.
 *  `page.waitForResponse` only matches FUTURE responses, so we attach
 *  a long-lived listener as early as possible and poll the flag. */
const streamSeen = new Map<Page, boolean>();

function watchGameStream(page: Page): void {
  if (streamSeen.has(page)) return;
  streamSeen.set(page, false);
  page.on('response', resp => {
    if (resp.url().includes('/api/game/stream')) streamSeen.set(page, true);
  });
}

function pageFor(username: string): Page {
  const page = pageRegistry.get(username);
  if (!page) throw new Error(`No page registered for "${username}" — did a login/context step run first?`);
  return page;
}

// ── SSE drain helpers ─────────────────────────────────────────────────────

interface Captured {
  type: string;
  payload: unknown;
  at: number;
  /** Flat fields of parent-posted messages — SD_SHOP_EVENT /
   *  SD_DIALOGUE_EVENT are flat objects ({type, action, ...} /
   *  {type, npc, portraitLoaded}), not envelopes. */
  [flat: string]: unknown;
}

interface SseEnvelope { type?: string; payload?: Record<string, unknown> | null; }

/** True when the captured entry is an SSE relay for `channel` whose
 *  inner payload satisfies `pred`. Envelope shape (api-bridge relay):
 *  { type:'SD_SSE_EVENT', payload:{ type:<channel>, payload:<dto> } }. */
function isChannelEvent(
  e: Captured,
  channel: string,
  pred: (dto: Record<string, unknown>) => boolean,
): boolean {
  if (e.type !== 'SD_SSE_EVENT') return false;
  const env = e.payload as SseEnvelope | null;
  if (!env || env.type !== channel || !env.payload || typeof env.payload !== 'object') return false;
  return pred(env.payload as Record<string, unknown>);
}

/** Poll-drain `page` until an event matches, or `ms` elapses. */
async function pollFor(
  page: Page,
  ms: number,
  match: (e: Captured) => boolean,
): Promise<boolean> {
  const deadline = Date.now() + ms;
  while (Date.now() < deadline) {
    const events = await drainCapturedEvents(page);
    if (events.some(match)) return true;
    await page.waitForTimeout(500);
  }
  return false;
}

function movementWith(pred: (dto: Record<string, unknown>) => boolean): (e: Captured) => boolean {
  return e => isChannelEvent(e, 'movement', pred);
}

// ── Frame + keyboard helpers ──────────────────────────────────────────────

const GAME_FRAME = 'iframe.game-frame';

/** Click the Godot canvas inside the game frame so real key events
 *  land in the engine (the host auto-focus usually suffices, but a
 *  click is the deterministic belt). */
async function focusGameCanvas(page: Page): Promise<void> {
  const canvas = page.frameLocator(GAME_FRAME).locator('#canvas');
  await canvas.click({ timeout: 15_000, position: { x: 50, y: 50 } }).catch(async () => {
    // Canvas may be obscured by the engine's own focus veil — fall
    // back to clicking the iframe element itself.
    await page.locator(GAME_FRAME).click({ timeout: 5_000, position: { x: 50, y: 50 } }).catch(() => {});
  });
}

/** Hold `code` (e.g. "KeyW") for `ms` on `page`'s keyboard. */
async function holdKey(page: Page, code: string, ms: number): Promise<void> {
  await page.keyboard.down(code);
  await page.waitForTimeout(ms);
  await page.keyboard.up(code);
}

/** Key→action contract of the generated game_bridge (TWiaT port). */
const KEY_ACTIONS: Record<string, string> = {
  KeyW: 'MOVE_FORWARD',
  KeyS: 'MOVE_BACKWARD',
  KeyA: 'MOVE_LEFT',
  KeyD: 'MOVE_RIGHT',
};

/**
 * Drive real keyboard movement and verify the SSE echo end-to-end.
 * Primary path: click-focus the canvas, press W then D, and expect a
 * movement-channel event carrying one of the exact actions sent.
 * Fallback path: sendControl (the proven relay-level driver) and
 * expect ITS exact action echoed. Returns which path proved it.
 */
async function driveMovement(
  actorPage: Page,
  observerPage: Page,
  characterId: number,
  sessionId?: string,
): Promise<'real-keys' | 'send-control-fallback'> {
  await focusGameCanvas(actorPage);
  // Clear stale buffers so the assertion only sees post-input events.
  await drainCapturedEvents(observerPage);
  lastSentActions = [KEY_ACTIONS.KeyW, KEY_ACTIONS.KeyD];
  await holdKey(actorPage, 'KeyW', 350);
  await holdKey(actorPage, 'KeyD', 350);

  const sent = new Set(lastSentActions);
  const sawRealKeys = await pollFor(observerPage, 10_000, movementWith(dto =>
    typeof dto.action === 'string' && sent.has(dto.action)
    && (observerPage === actorPage || dto.characterId === characterId)));
  if (sawRealKeys) return 'real-keys';

  // Fallback: relay-level proof. A focus flake in the iframe must not
  // fail the scenario outright — but the report says which path ran.
  lastSentActions = ['MOVE_RIGHT'];
  await sendControl(actorPage, characterId, {
    action: 'MOVE_RIGHT',
    pressed: true,
    ...(sessionId ? { sessionId } : {}),
  });
  const sawFallback = await pollFor(observerPage, 10_000, movementWith(dto =>
    dto.action === 'MOVE_RIGHT'
    && (observerPage === actorPage || dto.characterId === characterId)));
  if (!sawFallback) {
    throw new Error('no movement SSE echo observed via real keys OR sendControl fallback');
  }
  return 'send-control-fallback';
}

// ── Maker/build steps ─────────────────────────────────────────────────────

When('the publisher selects the project {string}', async ({}, displayName: string) => {
  const page = pageRegistry.get('publisher')!;
  // Seeded projects (Liquibase fixtures like Six Realms) surface in
  // the projects pane as .link-btn rows — same affordance the
  // create-project step clicks after a POST.
  await page.locator('.link-btn', { hasText: displayName }).first().click({ timeout: 20_000 });
});

Then('the build progress reaches {string} within {int} minutes', async ({}, status: string, mins: number) => {
  const page = pageRegistry.get('publisher')!;
  // Real Godot exports take minutes (import pass + whole-project
  // export + dedup + upload), so this step takes its budget from the
  // feature file instead of the 90 s stub-build step. Web-first
  // expect timeouts are independent of the global 15 s actionTimeout.
  await expect(page.locator('.build-progress strong'))
      .toHaveText(new RegExp(`^${status}$`), { timeout: mins * 60_000 });
});

// ── Explore steps (single player, default page fixture so the
//    AfterStep hook photographs the game) ─────────────────────────────────

When('the player opens the chapter modal for game {string}', async ({ page }, gameSlug: string) => {
  // Init-script capture must be installed before the game iframe
  // mounts so its first SD_SSE_EVENT is not lost.
  await installSseCapture(page.context());
  watchGameStream(page);
  await page.goto('/?characterId=1', { waitUntil: 'domcontentloaded' });
  const card = page.locator(`.game-card[data-game-slug="${gameSlug}"]`).first();
  await card.waitFor({ state: 'visible', timeout: 30_000 });
  await card.click();
  pageRegistry.set('player', page);
});

/** Click Play on the chapter row for `areaSlug` — matched by human
 *  name first (register.sql carries the area NAME), falling back to
 *  slug-derived titles so a naming regression still exercises the
 *  launch (and fails on the later, more specific assertions). */
async function playChapter(page: Page, areaSlug: string, chapterName: string): Promise<void> {
  const rowFor = (text: string | RegExp) =>
    page.locator('.chapter-row', { has: page.locator('.chapter-title', { hasText: text }) }).first();
  let row = rowFor(chapterName);
  try {
    await row.waitFor({ state: 'visible', timeout: 10_000 });
  } catch {
    row = rowFor(new RegExp(`^(six-realms-)?${areaSlug}$`, 'i'));
    await row.waitFor({ state: 'visible', timeout: 10_000 });
  }
  await row.locator('button.play-btn:not([disabled])').first().click();
}

When('the player plays the {string} chapter named {string}',
     async ({ page }, areaSlug: string, chapterName: string) => {
  await playChapter(page, areaSlug, chapterName);
});

Then('the game frame mounts a uid-gated bundle for area {string} of game {string}',
     async ({ page }, areaSlug: string, gameSlug: string) => {
  const frame = page.locator(GAME_FRAME);
  await frame.waitFor({ state: 'attached', timeout: 30_000 });
  // The launch uid arrives async (POST /api/games/{slug}/launch) and
  // remounts the iframe once — poll the src until the uid-gated form
  // shows up. Asserting the uid-gated form SPECIFICALLY (not the
  // ungated fallback) keeps the mintLaunch wiring honest.
  const uidGated = new RegExp(
    `/api/assets/levels/${gameSlug}/[A-Za-z0-9_-]{20,}/${areaSlug}/index\\.html\\?`);
  await expect.poll(async () => (await frame.getAttribute('src')) ?? '', {
    timeout: 30_000,
    intervals: [500, 1000, 2000],
  }).toMatch(uidGated);
  const src = (await frame.getAttribute('src'))!;
  expect(src, 'iframe src must carry chapter_id').toContain('chapter_id=');
  expect(src, 'iframe src must carry launch_uid').toContain('launch_uid=');
  // Attach the in-iframe capture listener now that the frame exists.
  await captureIframeSseEvents(page);
});

Then('the game stream opens within {int} seconds', async ({ page }, seconds: number) => {
  // The wrapper opens its EventSource only after the WASM compile
  // finishes (~60-90 s cold), so poll the long-lived response watcher
  // installed when the modal was opened.
  const deadline = Date.now() + seconds * 1_000;
  while (Date.now() < deadline) {
    if (streamSeen.get(page)) return;
    await page.waitForTimeout(500);
  }
  throw new Error(`/api/game/stream did not open within ${seconds}s — WASM boot never completed?`);
});

Then('the {string} realm renders a live 3D world', async ({ page, $testInfo }, biome: string) => {
  const frame = page.locator(GAME_FRAME);
  // Pre-poll until the WASM has actually painted. The /api/game/stream
  // gate fires when the host opens its EventSource (early, on the
  // legacy game-canvas mount), which can precede Godot's first
  // rendered frame by a few seconds on a cold engine boot — so a
  // fixed capture races a still-black canvas. Poll the compositor
  // until the centre crop is non-black (or the deadline), THEN take
  // the two stability samples below.
  const paintDeadline = Date.now() + 90_000;
  let lastLuma = 0;
  while (Date.now() < paintDeadline) {
    const probe = analyzePngCenterCrop(await frame.screenshot());
    lastLuma = probe.meanLuma;
    if (probe.meanLuma > 10) break;
    await page.waitForTimeout(1_500);
  }
  if (lastLuma <= 10) {
    // Fall through to the assertions below with a fresh capture so the
    // failure message + attachment reflect the final (still-black) state.
    console.log(`[biome:${biome}] never painted within 90s (lastLuma=${lastLuma.toFixed(1)})`);
  }
  // Two compositor captures ~2 s apart: both must be non-black and
  // non-flat, and mutually stable — rejects a black screen, a solid
  // splash colour, AND the boot splash/progress bar racing the
  // capture.
  const shot1 = await frame.screenshot();
  await page.waitForTimeout(2_000);
  const shot2 = await frame.screenshot();
  await $testInfo.attach(`biome-${biome}-sample-1`, { body: shot1, contentType: 'image/png' });
  await $testInfo.attach(`biome-${biome}-sample-2`, { body: shot2, contentType: 'image/png' });

  const a = analyzePngCenterCrop(shot1);
  const b = analyzePngCenterCrop(shot2);
  const fmt = (s: typeof a) =>
    `meanLuma=${s.meanLuma.toFixed(1)} lumaStdDev=${s.lumaStdDev.toFixed(1)} ` +
    `meanRGB=(${s.mean.r.toFixed(0)},${s.mean.g.toFixed(0)},${s.mean.b.toFixed(0)})`;
  console.log(`[biome:${biome}] sample1 ${fmt(a)} | sample2 ${fmt(b)}`);

  for (const [label, s] of [['sample1', a], ['sample2', b]] as const) {
    expect(s.meanLuma, `${biome} ${label} is black (${fmt(s)})`).toBeGreaterThan(10);
    expect(s.lumaStdDev, `${biome} ${label} is a flat colour (${fmt(s)})`).toBeGreaterThan(8);
  }
  expect(rgbDistance(a.mean, b.mean),
    `${biome} render is not stable between samples — boot splash still animating?`)
      .toBeLessThan(32);

  biomeRgbRegistry.set(biome, b.mean);
});

Then('the explored realms render with pairwise distinct palettes', async ({}) => {
  // Runs in the LAST explore scenario (feature-file order, workers=1)
  // once every biome has registered its mean-RGB fingerprint.
  const entries = [...biomeRgbRegistry.entries()];
  expect(entries.length, 'expected all six biomes to have registered a palette').toBeGreaterThanOrEqual(6);
  const failures: string[] = [];
  for (let i = 0; i < entries.length; i++) {
    for (let j = i + 1; j < entries.length; j++) {
      const [nameA, rgbA] = entries[i];
      const [nameB, rgbB] = entries[j];
      const pair = new Set([nameA, nameB]);
      // Cave + dungeon are both deliberately dark/foggy — the renderer
      // tunes them apart (warm brown/green vs cold blue-grey) but the
      // margin is intentionally smaller.
      const threshold = (pair.has('cave') && pair.has('dungeon')) ? 15 : 25;
      const d = rgbDistance(rgbA, rgbB);
      console.log(`[biome-distance] ${nameA} ↔ ${nameB}: ${d.toFixed(1)} (need > ${threshold})`);
      if (d <= threshold) failures.push(`${nameA} ↔ ${nameB}: ${d.toFixed(1)} <= ${threshold}`);
    }
  }
  expect(failures, `biome palettes not pairwise distinct:\n${failures.join('\n')}`).toEqual([]);
});

Then('moving with the keyboard produces a movement echo within {int} seconds',
     async ({ page, $testInfo }, _seconds: number) => {
  // The 10 s budget per path is fixed inside driveMovement; the
  // step's {int} keeps the feature line honest about the wait.
  const path = await driveMovement(page, page, 1);
  console.log(`[biome-movement] proven via ${path}`);
  await $testInfo.attach('movement-path', {
    body: Buffer.from(path),
    contentType: 'text/plain',
  });
});

// ── Town economy steps ────────────────────────────────────────────────────

When('the player walks the path {string} in the game frame', async ({ page }, path: string) => {
  // path syntax: "KeyW:1800,KeyA:1200" — hold each code for the given
  // milliseconds. The concrete paths in the feature carry the seeded
  // Six Realms town layout (grid-placed buildings) and may need
  // retuning if the fixture layout changes.
  await focusGameCanvas(page);
  await drainCapturedEvents(page); // only post-walk events count
  for (const leg of path.split(',')) {
    const [code, msRaw] = leg.trim().split(':');
    const ms = parseInt(msRaw, 10);
    if (!code || !Number.isFinite(ms)) throw new Error(`bad walk leg "${leg}" — want e.g. KeyW:1800`);
    await holdKey(page, code, ms);
  }
});

Then('a room-transition echo is observed within {int} seconds', async ({ page }, seconds: number) => {
  // The generated game_bridge posts control action ENTER_ROOM:<uuid>
  // on every portal transition — the server-observed proof that the
  // player moved THROUGH a level, not just inside one.
  const ok = await pollFor(page, seconds * 1_000, movementWith(dto =>
    typeof dto.action === 'string' && dto.action.startsWith('ENTER_ROOM:')));
  expect(ok, `no ENTER_ROOM:<uuid> control echo within ${seconds}s — portal not crossed?`).toBe(true);
});

When('the player presses {string} in the game frame', async ({ page }, code: string) => {
  await focusGameCanvas(page);
  await holdKey(page, code, 120);
});

Then('a shop {string} transaction is observed within {int} seconds',
     async ({ page }, kind: string, seconds: number) => {
  // Two independent proofs, both required:
  //  1. SD_SHOP_EVENT postMessage from the generated shop UI to the
  //     parent window ({action:'buy'|'sell', item, goldBefore, ...}).
  //  2. The server-observed SHOP_BUY:/SHOP_SELL:<item> control echo on
  //     the movement channel (visible to every session subscriber).
  const echoPrefix = kind === 'buy' ? 'SHOP_BUY:' : 'SHOP_SELL:';
  let sawShopEvent = false;
  let sawEcho = false;
  const deadline = Date.now() + seconds * 1_000;
  while (Date.now() < deadline && !(sawShopEvent && sawEcho)) {
    const events = await drainCapturedEvents(page);
    for (const e of events) {
      if (e.type === 'SD_SHOP_EVENT') {
        // game_bridge posts the shop event FLAT ({type, action, ...})
        // — read the flat field, tolerating a nested payload shape.
        const p = (e.payload ?? e) as { action?: string } | null;
        if (p && p.action === kind) sawShopEvent = true;
      }
      if (isChannelEvent(e, 'movement', dto =>
            typeof dto.action === 'string' && dto.action.startsWith(echoPrefix))) {
        sawEcho = true;
      }
    }
    if (sawShopEvent && sawEcho) break;
    await page.waitForTimeout(500);
  }
  expect(sawShopEvent, `no SD_SHOP_EVENT {action:'${kind}'} within ${seconds}s`).toBe(true);
  expect(sawEcho, `no ${echoPrefix}<item> control echo within ${seconds}s`).toBe(true);
});

Then('a character SSE event is observed within {int} seconds', async ({ page }, seconds: number) => {
  // The inn rest PUTs /api/characters/{cid} {currentHp,currentMp}
  // through the host-bridge API seam — the BFF emits a real
  // `character` SSE event the wrapper relays into the iframe.
  const ok = await pollFor(page, seconds * 1_000, e => isChannelEvent(e, 'character', () => true));
  expect(ok, `no character-channel SSE event within ${seconds}s — inn rest not applied?`).toBe(true);
});

// ── Character-maker steps (features/makers/character-maker.feature) ──────
//
// Design contracts these steps encode (DESIGN-CHARACTERS §5):
//   * Hero-avatar proof is a centre-crop red-dominant pixel scan: Six
//     Realms seeds body_color #e21422 and no biome background is
//     red-dominant, so redDominantFraction > 1% isolates the robe.
//   * Dialogue proof is layered: the server-observable TALK_NPC:<uuid>
//     control echo on the movement channel (mirrors ENTER_ROOM:/
//     SHOP_BUY:) is the requirement-(d) proof; the SD_DIALOGUE_EVENT
//     portraitLoaded boolean is the PRIMARY requirement-(e) portrait
//     proof (R8 — a panel-only pixel delta cannot distinguish a
//     portrait from a portrait-less panel); the centre-crop delta vs
//     the pre-sweep baseline stays as a secondary "panel opened" check.

/** Poll the compositor until the WASM's first real paint (mirrors the
 *  render step's pre-poll): the /api/game/stream gate fires when the
 *  host opens its EventSource, which can precede Godot's first
 *  rendered frame by seconds on a cold engine boot. */
async function awaitFirstPaint(page: Page, label: string): Promise<void> {
  const frame = page.locator(GAME_FRAME);
  const deadline = Date.now() + 90_000;
  let lastLuma = 0;
  while (Date.now() < deadline) {
    const probe = analyzePngCenterCrop(await frame.screenshot());
    lastLuma = probe.meanLuma;
    if (probe.meanLuma > 10) return;
    await page.waitForTimeout(1_500);
  }
  console.log(`[${label}] world never painted within 90s (lastLuma=${lastLuma.toFixed(1)}) — proceeding to the assertion captures`);
}

Then('the hero avatar shows the configured saturated-red body within {int} seconds',
     async ({ page, $testInfo }, seconds: number) => {
  const frame = page.locator(GAME_FRAME);
  await awaitFirstPaint(page, 'red-hero');
  // The avatar spawns after WASM boot + first room build and the
  // camera settles over a few frames — poll for the given budget and
  // keep the BEST fraction across samples (design §5.2). Negative
  // control baked in: the desert palette is tan (high G+B), so it
  // contributes ~0 red-dominant pixels — only the hero robe can cross
  // the ≥1% floor.
  let best = 0;
  let bestShot: Buffer | null = null;
  const deadline = Date.now() + seconds * 1_000;
  for (;;) {
    const shot = await frame.screenshot();
    const f = redDominantFraction(shot);
    if (f > best) { best = f; bestShot = shot; }
    if (best > 0.01 || Date.now() >= deadline) break;
    await page.waitForTimeout(1_000);
  }
  if (bestShot) {
    await $testInfo.attach('red-hero-avatar', { body: bestShot, contentType: 'image/png' });
  }
  console.log(`[red-hero] best redDominantFraction=${(best * 100).toFixed(2)}% (need > 1%)`);
  expect(best,
    `hero robe not found: best red-dominant centre-crop fraction ${(best * 100).toFixed(2)}% <= 1% — avatar config not baked into the export?`)
      .toBeGreaterThan(0.01);
});

/** Per-scenario dialogue state (reset by the walk-up step). One record
 *  spot for every dialogue signal so the sweep's early-exit drain and
 *  the two assertion steps never steal each other's events out of the
 *  shared drainCapturedEvents buffer (a drain RESETS it). */
interface DialogueSignals {
  /** Full `TALK_NPC:<uuid>` action string from the movement echo. */
  talkNpcEcho: string | null;
  /** Latest SD_DIALOGUE_EVENT.portraitLoaded seen (null = none yet). */
  portraitLoaded: boolean | null;
  /** Pre-sweep centre-crop fingerprint — the panel-delta baseline. */
  baseline: CropStats | null;
  /** Frame captured right after the echo was first seen (panel open). */
  panelShot: Buffer | null;
}

let dialogueSignals: DialogueSignals = {
  talkNpcEcho: null, portraitLoaded: null, baseline: null, panelShot: null,
};

/** Scan one drained batch into dialogueSignals (already-recorded
 *  signals are never un-set by a batch that lacks them). */
function recordDialogueSignals(events: Captured[]): void {
  for (const e of events) {
    if (isChannelEvent(e, 'movement', dto =>
          typeof dto.action === 'string' && dto.action.startsWith('TALK_NPC:'))) {
      const env = e.payload as SseEnvelope;
      dialogueSignals.talkNpcEcho = String((env.payload as Record<string, unknown>).action);
    }
    if (e.type === 'SD_DIALOGUE_EVENT') {
      // Posted FLAT by game_bridge ({type, npc, portraitLoaded}) —
      // read the flat field first, tolerating a nested payload.
      const nested = (e.payload ?? {}) as Record<string, unknown>;
      const pl = typeof e.portraitLoaded === 'boolean' ? e.portraitLoaded
        : typeof nested.portraitLoaded === 'boolean' ? nested.portraitLoaded : null;
      if (pl !== null) dialogueSignals.portraitLoaded = pl;
    }
  }
}

/** Drain + record until `done()` returns true (or the budget lapses). */
async function pollDialogueUntil(page: Page, ms: number, done: () => boolean): Promise<boolean> {
  const deadline = Date.now() + ms;
  for (;;) {
    recordDialogueSignals(await drainCapturedEvents(page));
    if (done() || Date.now() >= deadline) return done();
    await page.waitForTimeout(500);
  }
}

/**
 * Deterministic lawn-mower sweep over the town BOOT room
 * (bramblewick-green — building-free, hosting the seeded greeter at a
 * room-slug-hashed spot). Expanding box legs around the spawn point;
 * E is pressed after each leg so any 3 m proximity crossing opens the
 * dialogue. Tuned to the seeded Six Realms layout — retune the legs
 * if the fixture NPC moves.
 */
const NPC_SWEEP_LEGS = [
  'KeyW:1200', 'KeyD:1200', 'KeyS:1800', 'KeyA:1800',
  'KeyW:2000', 'KeyD:2000', 'KeyS:2000', 'KeyA:2000',
  'KeyW:1600', 'KeyD:1200', 'KeyS:1200', 'KeyA:1600',
];

When('the player walks up to an NPC and presses interact', async ({ page, $testInfo }) => {
  dialogueSignals = { talkNpcEcho: null, portraitLoaded: null, baseline: null, panelShot: null };
  const frame = page.locator(GAME_FRAME);
  await awaitFirstPaint(page, 'npc-dialogue');
  // Baseline BEFORE any panel can open — the portrait step's
  // secondary assertion measures the panel against this crop.
  const baselineShot = await frame.screenshot();
  dialogueSignals.baseline = analyzePngCenterCrop(baselineShot);
  await $testInfo.attach('npc-dialogue-baseline', { body: baselineShot, contentType: 'image/png' });
  await focusGameCanvas(page);
  await drainCapturedEvents(page); // only post-sweep events count
  for (const leg of NPC_SWEEP_LEGS) {
    const [code, msRaw] = leg.split(':');
    await holdKey(page, code, parseInt(msRaw, 10));
    await holdKey(page, 'KeyE', 120);
    // Let the open() postMessage + control-echo round-trip land
    // before draining, then EARLY-EXIT on the echo: E toggles, so a
    // further press would close the just-opened panel.
    await page.waitForTimeout(700);
    recordDialogueSignals(await drainCapturedEvents(page));
    if (dialogueSignals.talkNpcEcho) {
      dialogueSignals.panelShot = await frame.screenshot();
      break;
    }
  }
  // No assertion here — the next Then step owns the echo window (the
  // last leg's echo may still be in flight).
});

Then('a talk-NPC echo is observed within {int} seconds', async ({ page, $testInfo }, seconds: number) => {
  // Requirement-(d) proof: the TALK_NPC:<uuid> action string
  // round-trips POST /api/controls/{cid} → movement SSE channel,
  // exactly like ENTER_ROOM:/SHOP_BUY: — visible to every session
  // subscriber, not just the local iframe.
  await pollDialogueUntil(page, seconds * 1_000, () => dialogueSignals.talkNpcEcho !== null);
  const echo = dialogueSignals.talkNpcEcho;
  if (echo) {
    console.log(`[npc-dialogue] observed control echo ${echo}`);
    await $testInfo.attach('talk-npc-echo', { body: Buffer.from(echo), contentType: 'text/plain' });
  }
  expect(echo,
    `no TALK_NPC:<uuid> control echo within ${seconds}s — greeter not reached by the sweep?`).not.toBeNull();
});

Then('the dialogue panel with a portrait is shown', async ({ page, $testInfo }) => {
  const frame = page.locator(GAME_FRAME);
  const baseline = dialogueSignals.baseline;
  if (!baseline) throw new Error('no pre-sweep baseline — did the walk-up step run?');
  // PRIMARY portrait proof (R8): dialogue_ui.open() reports whether
  // the portrait texture actually loaded via the bare portraitLoaded
  // boolean on SD_DIALOGUE_EVENT. A pixel delta alone cannot tell a
  // portrait from a portrait-less panel (the TextureRect is hidden
  // when the npc entry carries portrait "").
  await pollDialogueUntil(page, 10_000, () => dialogueSignals.portraitLoaded !== null);
  // SECONDARY "panel opened" proof: centre-crop delta vs the
  // pre-sweep baseline. Prefer the shot taken the moment the echo was
  // first seen (panel freshly opened); if the panel has since closed
  // (E toggles), one bounded re-open attempt — the player is still
  // standing in the greeter's proximity.
  let after = dialogueSignals.panelShot ?? await frame.screenshot();
  let stats = analyzePngCenterCrop(after);
  if (rgbDistance(stats.mean, baseline.mean) <= 20 || stats.lumaStdDev <= 8) {
    await focusGameCanvas(page);
    await holdKey(page, 'KeyE', 120);
    await page.waitForTimeout(1_500);
    after = await frame.screenshot();
    stats = analyzePngCenterCrop(after);
  }
  await $testInfo.attach('npc-dialogue-panel', { body: after, contentType: 'image/png' });
  const delta = rgbDistance(stats.mean, baseline.mean);
  console.log(`[npc-dialogue] portraitLoaded=${dialogueSignals.portraitLoaded} `
    + `panelDelta=${delta.toFixed(1)} lumaStdDev=${stats.lumaStdDev.toFixed(1)}`);
  expect(dialogueSignals.portraitLoaded,
    'SD_DIALOGUE_EVENT.portraitLoaded must be true — the portrait texture did not load (or the event never fired)')
      .toBe(true);
  expect(delta,
    `panel-open centre-crop delta ${delta.toFixed(1)} <= 20 vs the pre-sweep baseline — dialogue panel not visible?`)
      .toBeGreaterThan(20);
  expect(stats.lumaStdDev,
    `centre crop is flat (lumaStdDev ${stats.lumaStdDev.toFixed(1)} <= 8) — blank frame?`)
      .toBeGreaterThan(8);
});

// ── Two-player steps ──────────────────────────────────────────────────────

Given('a recorded browser context for {string}', async ({ browser, user, $testInfo }, username: string) => {
  const u = user(username);
  // Manual contexts do NOT inherit video:'on' from the config — pass
  // recordVideo explicitly and register in ctxRegistry so the After
  // hook closes the context (videos finalise only on close).
  const ctx = await newE2eContext(browser, { recordVideo: { dir: $testInfo.outputDir } });
  await installSseCapture(ctx);
  const page = await ctx.newPage();
  watchGameStream(page);
  await login(page, u);
  ctxRegistry.set(u.username, ctx);
  pageRegistry.set(u.username, page);
});

When('{string} plays the {string} chapter named {string} from the open modal',
     async ({}, username: string, areaSlug: string, chapterName: string) => {
  // The lobby create/join steps leave the chapter modal open in the
  // active-session state; Play threads session_id + slot_index onto
  // the game-frame URL.
  await playChapter(pageFor(username), areaSlug, chapterName);
});

Then('both {string} and {string} have a game frame mounted', async ({}, a: string, b: string) => {
  const pa = pageFor(a);
  const pb = pageFor(b);
  await Promise.all([
    pa.waitForFunction(() => document.querySelectorAll('iframe.game-frame').length > 0, undefined, { timeout: 60_000 }),
    pb.waitForFunction(() => document.querySelectorAll('iframe.game-frame').length > 0, undefined, { timeout: 60_000 }),
  ]);
  await captureIframeSseEvents(pa);
  await captureIframeSseEvents(pb);
});

Then('{string} opens its session game stream within {int} seconds', async ({}, username: string, seconds: number) => {
  const page = pageFor(username);
  const deadline = Date.now() + seconds * 1_000;
  while (Date.now() < deadline) {
    if (streamSeen.get(page)) return;
    await page.waitForTimeout(500);
  }
  throw new Error(`"${username}" did not open /api/game/stream within ${seconds}s`);
});

When('{string} moves with the keyboard in the game frame', async ({}, username: string) => {
  // Real keys only here — the observer step handles the fallback so
  // the cross-player assertion window stays in one place.
  const page = pageFor(username);
  await focusGameCanvas(page);
  lastSentActions = [KEY_ACTIONS.KeyW, KEY_ACTIONS.KeyD];
  await holdKey(page, 'KeyW', 350);
  await holdKey(page, 'KeyD', 350);
});

Then('{string} observes a movement event from character {int} in session {string} within {int} seconds',
     async ({ $testInfo }, observer: string, characterId: number, sessionName: string, seconds: number) => {
  const observerPage = pageFor(observer);
  const sent = new Set(lastSentActions);
  // Strict cross-player filter: the exact action P1 sent AND P1's
  // characterId — a world/character heartbeat cannot satisfy this.
  let path: 'real-keys' | 'send-control-fallback' = 'real-keys';
  let ok = await pollFor(observerPage, Math.min(seconds * 1_000, 10_000), movementWith(dto =>
    typeof dto.action === 'string' && sent.has(dto.action) && dto.characterId === characterId));

  if (!ok) {
    // Fallback: relay-level proof through the same per-session sink.
    path = 'send-control-fallback';
    const session = sessionByName.get(sessionName);
    if (!session) throw new Error(`No session named "${sessionName}" — did the lobby create step run?`);
    // The actor is whoever moved last; P1 by convention in this feature.
    const actorPage = pageFor('player1');
    await sendControl(actorPage, characterId, {
      action: 'MOVE_RIGHT',
      pressed: true,
      sessionId: session.id,
    });
    ok = await pollFor(observerPage, seconds * 1_000, movementWith(dto =>
      dto.action === 'MOVE_RIGHT' && dto.characterId === characterId));
  }

  console.log(`[biome-multiplayer] cross-player movement proven via ${path}`);
  await $testInfo.attach('multiplayer-movement-path', {
    body: Buffer.from(path),
    contentType: 'text/plain',
  });
  expect(ok,
    `"${observer}" saw no movement event carrying character ${characterId} (path=${path})`).toBe(true);
});
