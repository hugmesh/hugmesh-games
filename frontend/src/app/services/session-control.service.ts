import { Injectable, inject, signal, OnDestroy } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { AuthService } from './auth.service';
import { NotificationService } from './notification.service';

export type SessionControlAction =
  | 'SESSION_PAUSED'
  | 'SESSION_RESUMED'
  | 'PLAYER_PAUSED'
  | 'PLAYER_RESUMED'
  | 'SESSION_CLOSED';

export interface SessionControlEvent {
  sessionId:   string;
  action:      SessionControlAction;
  casdoorId:   string | null;
  launchUid:   string | null;
  reason:      string | null;
  occurredAt:  string;
}

/** What a tab broadcasts on the same-origin BroadcastChannel when it takes
 *  over playback for a (player, gameSlug) pair. */
interface TakeoverMessage {
  kind:        'takeover-claim' | 'takeover-released';
  casdoorId:   string;
  gameSlug:    string;
  launchUid:   string;
  sessionId:   string | null;
  ts:          number;
}

/**
 * Two coordination surfaces in one service:
 *
 *  1. Cross-tab takeover via the same-origin `BroadcastChannel('hugmesh
 *     -player-takeover')`. When a tab finishes a `POST
 *     /api/games/{slug}/launch` it broadcasts a `takeover-claim` with the
 *     new launch UID; sibling tabs of the same player listen and, if they
 *     were already running the same game, freeze their WASM iframe (via
 *     `pause()` below) and POST
 *     `/api/sessions/{id}/players/me/pause` so the BFF fires
 *     PLAYER_PAUSED for the rest of the session subscribers (mods + admin
 *     observers see the pause too).
 *
 *  2. The `session-control` SSE channel listener — every host shell that
 *     subscribes to `/api/game/stream` (which is everyone, the
 *     ApiBridgeService keeps the connection open) handles incoming
 *     {@link SessionControlEvent}s:
 *      * SESSION_PAUSED / SESSION_RESUMED → the `paused` signal flips and
 *        the game-canvas component forwards an `SD_GAME_PAUSE` /
 *        `SD_GAME_RESUME` postMessage to the iframe.
 *      * PLAYER_PAUSED targeting *me* → same as my own takeover-claim;
 *        pause if I haven't already.
 *      * SESSION_CLOSED → toast + close iframes via the
 *        `sessionClosed` signal that HomeComponent listens for.
 *
 * Both surfaces converge on the same `paused` / `sessionClosed` signals
 * so any downstream component (game-canvas, admin live sessions, session
 * log indicators) just reads the signal — there's no second pubsub.
 */
@Injectable({ providedIn: 'root' })
export class SessionControlService implements OnDestroy {
  private static readonly TAKEOVER_CHANNEL = 'hugmesh-player-takeover';

  private readonly http = inject(HttpClient);
  private readonly auth = inject(AuthService);
  private readonly notify = inject(NotificationService);

  /** True when the current player's view is paused (session-wide or
   *  per-player takeover). Game-canvas reads this and routes postMessage
   *  to the iframe. */
  readonly paused = signal(false);

  /** Reason string mirroring the latest SSE event — surfaced in the
   *  game-canvas overlay so the user knows whether to act. */
  readonly pauseReason = signal<string | null>(null);

  /** Latest SESSION_CLOSED event; HomeComponent listens and tears down
   *  the iframe + shows a toast. */
  readonly sessionClosed = signal<{ sessionId: string; reason: string } | null>(null);

  /**
   * Latch: true while ANOTHER tab of this player holds the game, i.e. this
   * tab is the stale one.
   *
   * `BroadcastChannel` never delivers a message to the context that posted
   * it, so a tab that RECEIVES a `takeover-claim` for itself is by definition
   * the OLD tab — that receipt is the only unambiguous "I am superseded"
   * signal either side gets.
   *
   * <h3>What does NOT clear it</h3>
   *
   * No BFF-driven event does. `PLAYER_RESUMED` is broadcast to every
   * subscriber of the session and matches on `casdoorId`, so the old tab
   * un-pauses on it too; treating that as "I'm live again" would hand the
   * latch straight back to the tab it was raised against. And the BFF cannot
   * re-raise it afterwards — both tabs carry the same subject and the same
   * JWT, so it has nothing left to tell them apart with.
   *
   * <h3>What DOES clear it</h3>
   *
   * Exactly one thing: {@link claimTakeover}, i.e. THIS tab launching a
   * chapter. That is the mirror image of how the latch is raised — the tab
   * that posts the claim is, by the same BroadcastChannel asymmetry, the new
   * live tab, and the sibling that receives it latches in its place. Without
   * this the latch would be permanent for the document: a player who was
   * superseded, went back to the first tab and re-launched would be live in a
   * tab that still refuses to release its seat, and every exit from it —
   * Close button or tab close — would ghost the seat for the full reaper
   * window this fix exists to avoid.
   *
   * <h3>Who reads it</h3>
   *
   * {@link SessionLeaveOnUnloadService} (the `pagehide` leg) and
   * `HomeComponent.closeLevel` (the Close button). `left_at` is per MEMBER,
   * not per tab, so a leave sent from a superseded tab evicts the player the
   * NEW tab is playing as — strictly worse than the ghost seat. Both exits
   * must check this, not just the unload one.
   */
  readonly superseded = signal(false);

  private channel: BroadcastChannel | null = null;

  constructor() {
    try {
      this.channel = new BroadcastChannel(SessionControlService.TAKEOVER_CHANNEL);
      this.channel.addEventListener('message', e => this.onTakeoverMessage(e.data as TakeoverMessage));
    } catch {
      // Older browsers / privacy modes: silently skip. The SSE path
      // still works because each tab independently subscribes to
      // /api/game/stream; the takeover bonus just doesn't fire.
      this.channel = null;
    }
  }

  ngOnDestroy(): void {
    if (this.channel) {
      try { this.channel.close(); } catch { /* */ }
      this.channel = null;
    }
  }

  /** Wire this once on a SessionControl SSE event arrival. */
  onSessionControlEvent(ev: SessionControlEvent): void {
    const myCasdoor = (this.auth.getClaims()?.['sub'] as string | undefined) ?? null;
    switch (ev.action) {
      case 'SESSION_PAUSED':
        this.paused.set(true);
        this.pauseReason.set(ev.reason || 'paused');
        break;
      case 'SESSION_RESUMED':
        this.paused.set(false);
        this.pauseReason.set(null);
        break;
      case 'PLAYER_PAUSED':
        if (ev.casdoorId && ev.casdoorId === myCasdoor) {
          this.paused.set(true);
          this.pauseReason.set(ev.reason || 'view-switching');
        }
        break;
      case 'PLAYER_RESUMED':
        if (ev.casdoorId && ev.casdoorId === myCasdoor) {
          this.paused.set(false);
          this.pauseReason.set(null);
        }
        break;
      case 'SESSION_CLOSED':
        this.sessionClosed.set({ sessionId: ev.sessionId, reason: ev.reason || 'closed' });
        this.notify.error(
          ev.reason === 'host-disconnected'
            ? 'Session ended — host disconnected.'
            : 'Session ended.',
          ev.reason === 'force-closed' ? 'Closed by moderator.' : undefined);
        break;
    }
  }

  /**
   * Called by a NEW tab right after `POST /api/games/{slug}/launch`
   * resolves. Broadcasts to sibling tabs of the same player so they
   * can pause their WASM iframe.
   *
   * <p>Also releases {@link superseded}: this tab is the one posting the
   * claim, and BroadcastChannel does not deliver to the sender, so by the
   * same asymmetry that raises the latch on a receiver, a sender is the live
   * tab. The re-claim path is real — a player superseded by a second tab can
   * come back to the first one and launch again — and leaving the latch set
   * there would make every exit from the now-live tab ghost its seat.
   *
   * <p>Cleared before the early return so the release does not depend on
   * whether a channel exists: with no BroadcastChannel the latch can never
   * have been raised in the first place (nothing else sets it), so clearing
   * it is a no-op there rather than a hole.
   */
  claimTakeover(opts: { gameSlug: string; launchUid: string; sessionId: string | null }): void {
    this.superseded.set(false);
    const me = (this.auth.getClaims()?.['sub'] as string | undefined) ?? null;
    if (!me || !this.channel) return;
    const msg: TakeoverMessage = {
      kind:      'takeover-claim',
      casdoorId: me,
      gameSlug:  opts.gameSlug,
      launchUid: opts.launchUid,
      sessionId: opts.sessionId,
      ts:        Date.now(),
    };
    try { this.channel.postMessage(msg); } catch { /* */ }
  }

  /**
   * Called by the NEW tab once it has finished loading the iframe + SSE
   * handshake completed. POSTs to the BFF so the OLD tab's
   * PLAYER_PAUSED gets a matching PLAYER_RESUMED via SSE.
   */
  releaseTakeover(sessionId: string | null): void {
    const me = (this.auth.getClaims()?.['sub'] as string | undefined) ?? null;
    if (!me) return;
    if (!sessionId) return; // single-session legacy path doesn't need it
    this.http.post<void>(`/api/sessions/${sessionId}/players/me/resume`, {}).subscribe({
      next: () => { /* */ },
      error: () => { /* */ },
    });
    if (this.channel) {
      try {
        this.channel.postMessage({
          kind:      'takeover-released',
          casdoorId: me,
          gameSlug:  '',
          launchUid: '',
          sessionId: sessionId,
          ts:        Date.now(),
        } as TakeoverMessage);
      } catch { /* */ }
    }
  }

  /** Old tab's reaction to a sibling claiming takeover. */
  private onTakeoverMessage(msg: TakeoverMessage): void {
    const me = (this.auth.getClaims()?.['sub'] as string | undefined) ?? null;
    if (!me || !msg || msg.casdoorId !== me) return;
    if (msg.kind === 'takeover-claim') {
      // I'm the OLD tab — freeze and notify the BFF so other subscribers
      // see the PLAYER_PAUSED event.
      // Latch first: `paused` flips back off when the new tab's
      // PLAYER_RESUMED reaches this tab, but "superseded" is permanent for
      // the lifetime of this document — see the signal's doc comment.
      this.superseded.set(true);
      this.paused.set(true);
      this.pauseReason.set('view moved to another tab');
      if (msg.sessionId) {
        this.http.post<void>(`/api/sessions/${msg.sessionId}/players/me/pause`, {
          reason: 'tab-takeover',
          supersededLaunchUid: msg.launchUid,
        }).subscribe({ next: () => { /* */ }, error: () => { /* */ } });
      }
    }
  }
}
