import { Injectable, NgZone, inject } from '@angular/core';
import { Router } from '@angular/router';
import { AuthService } from './auth.service';

/**
 * One per browser session. Subscribes to {@code /api/me/admin-actions/stream}
 * (a per-user SSE filtered server-side on the JWT subject) and reacts:
 *
 * <ul>
 *   <li><b>kick</b> — closes the active game window in this tab and
 *       redirects to /banned. Other tabs are notified via the
 *       {@code hugmesh-admin-actions} BroadcastChannel.</li>
 *   <li><b>ban</b> — same as kick, but the redirect is sticky: the
 *       auth guard's ban-status check will keep the user on /banned
 *       until an unban arrives.</li>
 *   <li><b>unban</b> — clears any local ban marker so the next
 *       navigation attempt allows entry to the home grid.</li>
 * </ul>
 *
 * <p>BroadcastChannel propagates the event to every tab on the same
 * origin so a player who has opened the game in three windows sees
 * every one of them close at once.
 */
@Injectable({ providedIn: 'root' })
export class AdminActionsListenerService {

  private readonly router = inject(Router);
  private readonly auth = inject(AuthService);
  private readonly zone = inject(NgZone);

  private source: EventSource | null = null;
  private channel: BroadcastChannel | null = null;

  private static readonly CHANNEL = 'hugmesh-admin-actions';

  /**
   * Wire the SSE stream + cross-tab channel. Idempotent. Called once
   * by AppComponent.ngOnInit when the user is authenticated.
   */
  start(): void {
    if (this.source) return;
    if (typeof window === 'undefined') return;
    if (!this.auth.isAuthenticated()) return;

    // BroadcastChannel — receive bans broadcast by sibling tabs.
    if ('BroadcastChannel' in window) {
      this.channel = new BroadcastChannel(AdminActionsListenerService.CHANNEL);
      this.channel.addEventListener('message', ev => {
        const data = ev.data as { type?: string; action?: any } | null;
        if (data?.type === 'admin-action' && data.action) {
          this.handle(data.action);
        }
      });
    }

    // The SSE channel is JWT-protected; the EventSource API doesn't
    // accept Authorization headers, so we rely on the cookie/JWT
    // path through the same-origin nginx proxy. Token refresh is
    // out-of-band; the auth interceptor handles it for HTTP calls.
    try {
      const url = '/api/me/admin-actions/stream';
      this.source = new EventSource(url, { withCredentials: true });
      this.source.addEventListener('admin-action', (e: MessageEvent) => {
        try {
          const a = JSON.parse(e.data);
          this.zone.run(() => this.handle(a));
          this.channel?.postMessage({ type: 'admin-action', action: a });
        } catch (err) {
          console.warn('[admin-actions] parse failed', err);
        }
      });
      this.source.addEventListener('error', () => {
        // Browser auto-reconnects; we don't tear down on transient errors.
      });
    } catch (err) {
      console.warn('[admin-actions] EventSource open failed', err);
    }
  }

  /** Idempotent teardown — used by tests and on logout. */
  stop(): void {
    this.source?.close();
    this.source = null;
    this.channel?.close();
    this.channel = null;
  }

  /**
   * Apply one admin action to the current tab's UI: close the game
   * iframe (if any) and route to /banned for kick + ban; bounce to
   * home for unban.
   */
  private handle(action: { action: string; message?: string | null }): void {
    if (action.action === 'unban') {
      // Clear any local ban marker. Routing decisions get re-evaluated
      // on next navigation; we intentionally don't auto-redirect, so
      // the user finishes whatever they were doing.
      sessionStorage.removeItem('tw_ban_marker');
      return;
    }
    if (action.action !== 'kick' && action.action !== 'ban') return;
    // Tear the iframe out of the DOM immediately so the player can't
    // keep playing while the redirect is mid-flight. Find and close
    // every game iframe — there can be more than one (open-in-popup
    // windows count too via BroadcastChannel).
    document.querySelectorAll('iframe').forEach(f => {
      try { f.remove(); } catch (_) { /* ignore */ }
    });
    // Stash the action so the BannedPageComponent can render it
    // before the API call returns.
    try {
      sessionStorage.setItem('tw_ban_marker', JSON.stringify(action));
    } catch (_) { /* sessionStorage may be disabled */ }
    this.router.navigate(['/banned']);
  }
}
