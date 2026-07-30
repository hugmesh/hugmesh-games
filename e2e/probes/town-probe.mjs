// Town-walk probe: login as player, open the six-realms town chapter, walk W,
// and dump the game's console telemetry ([world]/[player]/[bridge] prints)
// plus screenshots — the fast loop for the vanishing-room diagnosis.
// Run inside the e2e image: node results/town-probe.mjs
import { chromium } from '@playwright/test';

const BASE = 'https://frontend:8443';
const browser = await chromium.launch();
const ctx = await browser.newContext({ ignoreHTTPSErrors: true, viewport: { width: 1280, height: 720 } });
const page = await ctx.newPage();
const lines = [];
page.on('console', m => lines.push(`${new Date().toISOString().slice(11, 23)} ${m.text()}`));
page.on('pageerror', e => lines.push('PAGEERROR: ' + e.message));

// login
await page.goto(BASE + '/login', { waitUntil: 'domcontentloaded' });
await page.waitForSelector('input[formControlName="username"]', { timeout: 60_000 });
await page.fill('input[formControlName="username"]', 'player');
await page.fill('input[formControlName="password"]', process.env.E2E_PASSWORD || 'e2e-pass');
await page.click('button.login-btn');
await page.waitForURL(u => !u.pathname.startsWith('/login'), { timeout: 60_000 });
console.log('logged in');

// open six-realms chapter list -> town chapter (named Bramblewick).
// Selectors mirror biome-steps.ts playChapter() exactly.
await page.goto(BASE + '/?characterId=1', { waitUntil: 'domcontentloaded' });
const card = page.locator('.game-card[data-game-slug="six-realms"]').first();
await card.waitFor({ state: 'visible', timeout: 30_000 });
await card.click();
const row = page.locator('.chapter-row', { has: page.locator('.chapter-title', { hasText: 'Bramblewick' }) }).first();
await row.waitFor({ state: 'visible', timeout: 15_000 });
await row.locator('button.play-btn:not([disabled])').first().click();
console.log('chapter launched, waiting for WASM boot…');
const frame = page.frameLocator('iframe.game-frame');
await frame.locator('#canvas').waitFor({ state: 'visible', timeout: 120_000 });
await page.waitForTimeout(20_000); // render + settle (matches the suite's pre-poll era)
await page.screenshot({ path: 'results/town-probe-1-rendered.png' });

// Walk path under test. Legs are "Code:ms" and keys pressed alone are
// "Code:0" (a tap). Override with WALK=... to iterate without an edit.
const WALK = process.env.WALK
  || 'KeyA:2600,KeyW:2200,KeyA:2000,KeyE:0,KeyB:0,KeyN:0,Escape:0,KeyS:1800,KeyD:1700,KeyE:0';
const canvas = frame.locator('#canvas');
await canvas.click({ position: { x: 50, y: 50 } }).catch(() => page.locator('iframe').first().click({ position: { x: 50, y: 50 } }).catch(() => {}));
for (const leg of WALK.split(',')) {
  const [code, msRaw] = leg.trim().split(':');
  const ms = parseInt(msRaw, 10);
  console.log(`>>> leg ${code}:${ms}`);
  if (ms > 0) {
    await page.keyboard.down(code);
    await page.waitForTimeout(ms);
    await page.keyboard.up(code);
  } else {
    await page.keyboard.press(code);
  }
  await page.waitForTimeout(1200);   // let the echo + any transition land
}
await page.screenshot({ path: 'results/town-probe-2-after-walk.png' });
await page.waitForTimeout(8_000);
await page.screenshot({ path: 'results/town-probe-3-final.png' });

console.log('--- game console (world/player/bridge/errors) ---');
for (const l of lines.filter(l => /\[world\]|\[player\]|\[bridge\]|\[shop\]|ERROR|SCRIPT|enter_scene|session-control/.test(l)))
  console.log(' ', l.slice(0, 170));
console.log(`--- total console lines: ${lines.length} ---`);
await browser.close();
