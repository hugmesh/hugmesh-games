import { Injectable, OnDestroy, inject } from '@angular/core';
import { MultiplayerSessionService } from './multiplayer-session.service';

/**
 * Keeps the player's multiplayer seat alive for EVERY game, by polling the
 * roster endpoint on a timer for as long as this tab holds a seat.
 *
 * <h2>Why this exists</h2>
 *
 * The BFF's `SessionMemberReaper` releases a seat whose `last_seen_at` has
 * gone stale. `GET /api/sessions/{id}` is one of the two signals that refresh
 * it — and the original design assumed that signal was universal because
 * "the Godot session bridge polls the roster every 5 s". It is not. Exactly
 * ONE of the games in this repo emits it: `shadows-of-the-net`, from its own
 * `scripts/session_bridge.gd`. The other games — chess, go, reversi,
 * connect-four, gomoku, dots-and-boxes, battleship, minesweeper, sudoku,
 * tetris, tron, bomberman, match-three, asteroids-duel, three-wizards,
 * dodecahedron and the Godot ports — emit nothing while nobody presses a key,
 * and the other signal (`authorizeControl`) only fires on a control write.
 *
 * <p>The lobby is game-generic, so all of them get `session_members` rows.
 * Without this service, two players thinking over a turn-based board — which
 * is most of that list — produce no signal at all, and the sweep reaps both
 * of them mid-game. If the one reaped is the room creator, `closeOnHostDrop`
 * destroys the room for everybody.
 *
 * <h2>Why the host shell and not each game</h2>
 *
 * The Angular host shell is the ONE component every game shares: it owns
 * `activeSessionId`, it created or joined the session, and it renders the
 * iframe the game runs in. Putting the heartbeat here covers all of them
 * uniformly and keeps future games covered by default, whereas a per-game
 * heartbeat would have to be written 19 more times in three different
 * engines and would silently omit the twentieth. It is also the only option
 * that does not require reaching into `games/**`.
 *
 * <h2>Why the roster GET and not a dedicated endpoint</h2>
 *
 * `GET /api/sessions/{id}` is already wired to
 * `GameSessionService.heartbeat` and already carries the caller's identity,
 * so reusing it adds no endpoint, no authorization surface, and no second
 * thing to keep in sync. It also returns the current roster, which is what a
 * seated client wants anyway.
 *
 * <h2>Interval vs. the reaper's window</h2>
 *
 * {@link INTERVAL_MS} is 20 s while the tab is in the foreground. A
 * BACKGROUNDED tab is still a seated player, and browsers clamp
 * `setInterval` in hidden tabs to roughly once per minute, so the server's
 * `app.sessions.member-timeout-ms` must clear several throttled beats — it
 * defaults to 180 s (three of them) for exactly this reason. Changing either
 * number without the other is what re-opens the bug.
 *
 * <p>The beat is deliberately NOT suppressed while
 * {@link SessionControlService.superseded} is set. A frozen tab is still a
 * tab the player has open, the takeover latch is per-PLAYER rather than
 * per-session (a claim raised by launching game B freezes the tab playing
 * game A), and the cost of an extra GET is nothing next to the cost of
 * evicting somebody who is still sitting there.
 */
@Injectable({ providedIn: 'root' })
export class SessionHeartbeatService implements OnDestroy {
  /**
   * Foreground beat period. Must stay comfortably under the BFF's
   * `app.sessions.member-timeout-ms`, and above its
   * `app.sessions.heartbeat-min-interval-ms` (15 s) or every other beat is
   * swallowed by the server's write rate-limiter and the effective period
   * silently doubles.
   */
  static readonly INTERVAL_MS = 20_000;

  private readonly multiplayer = inject(MultiplayerSessionService);

  /** The session whose seat this tab is keeping alive; null when not seated. */
  private sessionId: string | null = null;
  private timer: ReturnType<typeof setInterval> | null = null;

  /**
   * Start (or stop, with null) keeping a seat alive. Called from the same
   * places that hand the unload watcher its seat, so the two legs can never
   * disagree about which session this tab is in.
   */
  track(sessionId: string | null): void {
    this.sessionId = sessionId;
    if (sessionId) {
      this.ensureTimer();
    } else {
      this.stopTimer();
    }
  }

  /** The session this tab is beating for; exposed for specs. */
  tracked(): string | null {
    return this.sessionId;
  }

  ngOnDestroy(): void {
    this.stopTimer();
    this.sessionId = null;
  }

  private ensureTimer(): void {
    if (this.timer !== null || typeof window === 'undefined') return;
    this.timer = setInterval(() => this.beat(), SessionHeartbeatService.INTERVAL_MS);
  }

  private stopTimer(): void {
    if (this.timer === null) return;
    clearInterval(this.timer);
    this.timer = null;
  }

  /**
   * One liveness signal. Errors are swallowed: a beat that lost a race with
   * a close, a 404 on a room that just ended, or a transient network blip
   * are all things the next beat (or the reaper) resolves, and there is no
   * user-facing action to offer.
   */
  private beat(): void {
    const id = this.sessionId;
    if (!id) return;
    this.multiplayer.get(id).subscribe({
      next: () => { /* the GET itself is the signal */ },
      error: () => { /* see doc comment */ },
    });
  }
}
