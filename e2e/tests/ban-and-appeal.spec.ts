import { test, expect, BrowserContext, Page } from '@playwright/test';
import { SEED_USERS, login, newE2eContext, authToken } from './helpers/roles';

/**
 * Ban → ban gate → /banned page → appeal contact — a full cross-user
 * consequence chain that had zero e2e: the ban action, the BanGateFilter
 * that 403s a banned user's /api/* calls, the auth-guard redirect to
 * /banned, and the GET /api/security/appeal-contact resolver.
 *
 * Why a handwritten spec (not a .feature)? A ban mutates SHARED user
 * state that every other scenario logging in as that user depends on, so
 * the unban MUST be guaranteed even on a mid-test failure — a try/finally
 * the Gherkin step layer can't express. Same "mutate + restore" shape as
 * diagnostics-gate.spec.ts (which flips the wasm-console debug flag and
 * restores it). Runs in the `chromium` project, serial, single worker.
 *
 * NOTE (recorded during authoring): two audit-sketch claims don't hold on
 * this stack and are deliberately NOT asserted (asserting them would be
 * vacuous/false):
 *   • ban() calls SessionService.disconnect, which closes only the LEGACY
 *     player_sessions row — it does NOT close the multiplayer game_sessions,
 *     so "the victim's session shows closed in admin oversight" is false here.
 *   • the live admin-actions SSE listener uses EventSource (no bearer header)
 *     so it can't authenticate on this bearer-token stack; the redirect to
 *     /banned is driven by the auth-guard's /api/me/ban-status probe (HTTP,
 *     bearer-authed) instead — which is what this test exercises.
 */

const ADMIN  = SEED_USERS.find(u => u.username === 'admin')!;
const VICTIM = SEED_USERS.find(u => u.username === 'player2')!;

/** Decode a logged-in page's Casdoor id (JWT `sub`) from its token. */
async function casdoorIdOf(page: Page): Promise<string> {
  const token = await authToken(page);
  if (!token) throw new Error('no token on page');
  const seg = token.split('.')[1];
  const b64 = seg.replace(/-/g, '+').replace(/_/g, '/');
  const padded = b64 + '='.repeat((4 - (b64.length % 4)) % 4);
  const sub = JSON.parse(Buffer.from(padded, 'base64').toString('utf8')).sub;
  if (!sub) throw new Error('token has no sub');
  return sub as string;
}

/** POST /api/admin/actions/{verb}/{casdoorId} as the admin page; returns status. */
async function adminAction(page: Page, verb: 'ban' | 'unban', target: string, message: string): Promise<number> {
  const token = await authToken(page);
  return await page.evaluate(async ({ v, tgt, t, msg }) => {
    const r = await fetch(`/api/admin/actions/${v}/${tgt}`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json', Authorization: `Bearer ${t}` },
      body: JSON.stringify({ message: msg }),
    });
    return r.status;
  }, { v: verb, tgt: target, t: token, msg: message });
}

/** GET a path as the page; return { status, banHeader }. */
async function probe(page: Page, path: string): Promise<{ status: number; ban: string | null }> {
  const token = await authToken(page);
  return await page.evaluate(async ({ p, t }) => {
    const r = await fetch(p, { headers: t ? { Authorization: `Bearer ${t}` } : {} });
    return { status: r.status, ban: r.headers.get('X-HugMesh-Ban') };
  }, { p: path, t: token });
}

test.describe('ban → banned page → appeal contact', () => {
  test('admin ban blocks the victim at the gate, redirects to /banned, and surfaces the appeal contact', async ({ browser }) => {
    const ctxAdmin: BrowserContext = await newE2eContext(browser);
    const ctxVictim: BrowserContext = await newE2eContext(browser);
    const admin = await ctxAdmin.newPage();
    const victim = await ctxVictim.newPage();

    let victimSub = '';
    try {
      await login(admin, ADMIN);
      await login(victim, VICTIM);
      victimSub = await casdoorIdOf(victim);

      // Baseline: victim reaches a normal protected read before the ban.
      expect((await probe(victim, '/api/games')).status,
        'victim must reach /api/games before the ban').toBe(200);

      // ── admin bans the victim ────────────────────────────────────────
      const banStatus = await adminAction(admin, 'ban', victimSub, 'e2e: conduct violation');
      expect(banStatus, 'ban must be accepted').toBe(200);

      // ── ban gate: the victim's next non-allowlisted /api/* 403s ──────
      const gated = await probe(victim, '/api/games');
      expect(gated.status, 'BanGateFilter must 403 a banned victim').toBe(403);
      expect(gated.ban, 'the 403 must carry the X-HugMesh-Ban marker').toBe('active');

      // ── ban-status (allowlisted) reports banned ──────────────────────
      const statusResp = await victim.evaluate(async (t) => {
        const r = await fetch('/api/me/ban-status', { headers: { Authorization: `Bearer ${t}` } });
        return { status: r.status, body: await r.json() };
      }, await authToken(victim));
      expect(statusResp.status).toBe(200);
      expect(statusResp.body.banned, 'ban-status must report banned:true').toBe(true);

      // ── appeal-contact resolver returns an email (anonymous by design) ─
      const appeal = await victim.evaluate(async () => {
        const r = await fetch('/api/security/appeal-contact');
        return { status: r.status, body: await r.json() };
      });
      expect(appeal.status).toBe(200);
      expect(appeal.body.email, 'appeal-contact must resolve a non-empty email')
        .toMatch(/.+@.+/);

      // ── auth-guard redirect: navigating a protected route lands /banned ─
      await victim.goto('/', { waitUntil: 'domcontentloaded' });
      await victim.waitForURL('**/banned', { timeout: 20_000 });

      // ── /banned renders the ban + the appeal email (now surfaced) ────
      await expect(victim.locator('.banned-title')).toHaveText(/banned/i, { timeout: 15_000 });
      await expect(
        victim.locator('.banned-appeal-link'),
        'the banned page must surface the resolved appeal-contact email',
      ).toHaveText(appeal.body.email as string, { timeout: 10_000 });
    } finally {
      // GUARANTEED cleanup — a persistent ban would poison every other
      // scenario that logs in as this shared seed user.
      if (victimSub) {
        const unbanStatus = await adminAction(admin, 'unban', victimSub, 'e2e cleanup');
        expect(unbanStatus, 'unban cleanup must succeed').toBe(200);
        // Prove the restore: the victim reaches a protected read again.
        const restored = await probe(victim, '/api/games');
        expect(restored.status, 'victim must be un-gated after unban').toBe(200);
      }
      await ctxAdmin.close();
      await ctxVictim.close();
    }
  });
});
