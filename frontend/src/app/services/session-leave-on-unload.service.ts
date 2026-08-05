import { Injectable, OnDestroy, inject } from '@angular/core';
import { AuthService } from './auth.service';
import { SessionControlService } from './session-control.service';

/**
 * Releases the player's seat when their tab goes away.
 *
 * <h2>Why this exists</h2>
 *
 * `session_members.left_at` was stamped only by an explicit
 * `DELETE /api/sessions/{id}/me` (the in-app "leave") or by a moderator kick.
 * Closing the browser tab issued neither, so the row stayed active forever:
 * the player kept a seat and an avatar slot against the room's 16-player cap
 * with nothing alive left to release it. The BFF's `SessionMemberReaper`
 * collects those ghosts, but only after a full staleness window — this is the
 * leg that releases the seat in milliseconds when the browser does get to run
 * an unload handler. The two are complementary: this one cannot fire on a
 * crash, a lost network, or a shut laptop lid; the reaper cannot be instant.
 *
 * <h2>Why `pagehide` and not `beforeunload` / `unload`</h2>
 *
 * `unload` is never fired on mobile Safari and blocks the back/forward cache
 * everywhere; `beforeunload` is for prompting, not for side effects. `pagehide`
 * is the one event that fires on tab close, navigation away, AND bfcache
 * eviction. `visibilitychange` is deliberately NOT used — a backgrounded tab is
 * still a seated player.
 *
 * <h2>Why `fetch(..., { keepalive: true })` and not `sendBeacon`</h2>
 *
 * `navigator.sendBeacon` cannot carry headers. Auth here is a bearer JWT read
 * out of localStorage and attached by {@link authInterceptor}; there is no
 * cookie session, and `/api/sessions/**` is `.authenticated()`, so a beacon
 * would be silently 401'd and the seat would leak exactly as before. `fetch`
 * with `keepalive` survives document teardown the same way a beacon does but
 * takes an `Authorization` header. It also cannot go through Angular's
 * `HttpClient` — an in-flight `HttpClient` request is cancelled when the
 * document tears down, and the interceptor is not reachable from `fetch`, so
 * the header is attached by hand here. Same pattern as
 * `WasmConsoleForwarderService`.
 *
 * <h2>The superseded-tab guard</h2>
 *
 * `left_at` is per MEMBER, not per tab. When a player opens the running game in
 * a second tab, `SessionControlService.onTakeoverMessage` freezes the OLD tab
 * but leaves it open — and a frozen tab still fires `pagehide` when the player
 * eventually closes it or the browser discards it to reclaim memory. Firing an
 * unload-leave from there would stamp the membership row the NEW tab is
 * actively playing under, evicting a player who is sitting right in front of
 * the game. So a tab that has been superseded never fires. The BFF has a
 * matching guard, but it can only cover the window before the takeover
 * resolves: after that both tabs are the same subject with the same JWT and the
 * server has nothing left to tell them apart with. This latch is the real
 * guard; `unload=true` tells the BFF to apply its own as a backstop.
 */
@Injectable({ providedIn: 'root' })
export class SessionLeaveOnUnloadService implements OnDestroy {
  private readonly auth = inject(AuthService);
  private readonly sessionControl = inject(SessionControlService);

  /** The session whose seat this tab currently holds; null when not in one. */
  private sessionId: string | null = null;
  private started = false;

  /**
   * Begin watching for tab teardown. Idempotent — the host shell calls this
   * once at bootstrap, before any session exists.
   */
  start(): void {
    if (this.started || typeof window === 'undefined') return;
    this.started = true;
    window.addEventListener('pagehide', this.onPageHide);
  }

  /**
   * Detach the listener with the injector that owns it. In the browser this
   * fires only on a full teardown, but a global listener that outlives its
   * service is a leak either way — and the same idiom keeps
   * {@link SessionControlService}'s BroadcastChannel honest.
   */
  ngOnDestroy(): void {
    if (!this.started) return;
    window.removeEventListener('pagehide', this.onPageHide);
    this.started = false;
  }

  /**
   * Tell the watcher which session this tab is seated in. Called with the id
   * on join / create and with null on any clean departure, so a tab that
   * already left does not fire a redundant leave on close.
   */
  track(sessionId: string | null): void {
    this.sessionId = sessionId;
  }

  /** The session this tab would release on close; exposed for specs. */
  tracked(): string | null {
    return this.sessionId;
  }

  private readonly onPageHide = (): void => {
    const id = this.sessionId;
    if (!id) return;
    // A superseded tab must not release the seat its successor is playing on.
    if (this.sessionControl.superseded()) return;
    const token = this.auth.getAccessToken();
    if (!token) return;
    // Clear first: `pagehide` can fire more than once for one document (bfcache
    // enter, then the real close), and a second DELETE is wasted work.
    this.sessionId = null;
    try {
      void fetch(`/api/sessions/${encodeURIComponent(id)}/me?unload=true`, {
        method: 'DELETE',
        headers: { Authorization: `Bearer ${token}` },
        keepalive: true,
      }).catch(() => {
        // The document is going away; there is nobody left to tell. The BFF's
        // reaper is the backstop for exactly this.
      });
    } catch {
      // Same — never let a teardown-time failure surface.
    }
  };
}
