import { test, expect } from '@playwright/test';
import { SEED_USERS, login, newE2eContext } from './helpers/roles';

/**
 * Pins the two-layer gate on POST /api/diagnostics/wasm-console:
 *   1. Caller must hold ROLE_ADMIN  (controller-level @PreAuthorize).
 *   2. system_settings.wasm_console_debug_enabled must be true.
 *
 * Failures of either layer surface as 403; only with both green does
 * the endpoint return 202 Accepted and fan out on the SSE channel.
 *
 * Decision 012 doc covers the rationale; this spec is the e2e gate
 * called out in PR #28's test plan.
 */
test.describe('diagnostics gate (admin + flag)', () => {
  test('non-admin player gets 403 from POST /api/diagnostics/wasm-console', async ({ page }) => {
    const player = SEED_USERS.find(u => u.username === 'player')!;
    await login(page, player);
    const status = await page.evaluate(async () => {
      const raw = window.localStorage.getItem('tw_auth');
      const token = raw ? (JSON.parse(raw).accessToken ?? '') : '';
      const r = await fetch('/api/diagnostics/wasm-console', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json', Authorization: `Bearer ${token}` },
        body: JSON.stringify({ level: 'error', message: 'should never persist' }),
      });
      return r.status;
    });
    expect(status).toBe(403);
  });

  test('admin with debug-mode off → 403; flip on → 202', async ({ browser }) => {
    // Single admin context. The flag is process-singleton in the BFF
    // (DebugModeService.AtomicBoolean), so we MUST flip it back to
    // false at the end so other specs running in the same suite get
    // the documented default.
    const ctx = await newE2eContext(browser);
    const page = await ctx.newPage();
    const admin = SEED_USERS.find(u => u.username === 'admin')!;
    await login(page, admin);

    // Force flag OFF first so the default-on assumption isn't tested.
    await page.evaluate(async (flag) => {
      const raw = window.localStorage.getItem('tw_auth');
      const token = raw ? (JSON.parse(raw).accessToken ?? '') : '';
      const r = await fetch('/api/admin/debug-mode/wasm-console', {
        method: 'PUT',
        headers: { 'Content-Type': 'application/json', Authorization: `Bearer ${token}` },
        body: JSON.stringify({ enabled: flag }),
      });
      if (!r.ok) throw new Error(`debug-mode toggle failed: ${r.status}`);
    }, false);

    // Flag OFF → 403 even for admin.
    const offStatus = await page.evaluate(async () => {
      const raw = window.localStorage.getItem('tw_auth');
      const token = raw ? (JSON.parse(raw).accessToken ?? '') : '';
      const r = await fetch('/api/diagnostics/wasm-console', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json', Authorization: `Bearer ${token}` },
        body: JSON.stringify({ level: 'error', message: 'flag off, should be 403' }),
      });
      return r.status;
    });
    expect(offStatus).toBe(403);

    // Flip flag ON.
    await page.evaluate(async (flag) => {
      const raw = window.localStorage.getItem('tw_auth');
      const token = raw ? (JSON.parse(raw).accessToken ?? '') : '';
      const r = await fetch('/api/admin/debug-mode/wasm-console', {
        method: 'PUT',
        headers: { 'Content-Type': 'application/json', Authorization: `Bearer ${token}` },
        body: JSON.stringify({ enabled: flag }),
      });
      if (!r.ok) throw new Error(`debug-mode toggle failed: ${r.status}`);
    }, true);

    // Flag ON → 202 (Accepted).
    const onStatus = await page.evaluate(async () => {
      const raw = window.localStorage.getItem('tw_auth');
      const token = raw ? (JSON.parse(raw).accessToken ?? '') : '';
      const r = await fetch('/api/diagnostics/wasm-console', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json', Authorization: `Bearer ${token}` },
        body: JSON.stringify({ level: 'error', message: 'flag on, should be 202' }),
      });
      return r.status;
    });
    expect(onStatus).toBe(202);

    // Restore default (off) so dependent specs don't see leaked state.
    await page.evaluate(async () => {
      const raw = window.localStorage.getItem('tw_auth');
      const token = raw ? (JSON.parse(raw).accessToken ?? '') : '';
      await fetch('/api/admin/debug-mode/wasm-console', {
        method: 'PUT',
        headers: { 'Content-Type': 'application/json', Authorization: `Bearer ${token}` },
        body: JSON.stringify({ enabled: false }),
      });
    });

    await ctx.close();
  });
});

