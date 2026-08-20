package games.hugmesh.bff.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;

/**
 * Scheduled sweeper that releases the seats of players who stopped
 * responding — the server-side half of the closed-tab ghost fix.
 *
 * <h2>Responsibility</h2>
 *
 * <p>{@code session_members.left_at} is stamped only by an explicit
 * {@code DELETE /api/sessions/{id}/me} or by {@code ModerationService.kickPlayer}.
 * A player whose browser tab was closed, whose machine crashed, whose laptop
 * lid shut, or whose network dropped never issues either, so their row stayed
 * active forever — holding a seat and an avatar slot against the room's
 * player cap with nothing alive left to release it.
 *
 * <p>This sweeper is the backstop. The client-side leg (a {@code keepalive}
 * DELETE on {@code pagehide}) covers a clean tab close within milliseconds;
 * this one covers everything that never gets to run an unload handler, at the
 * cost of one timeout window. They are complementary, not alternatives.
 *
 * <p>All the reap logic — the paused-session and tab-takeover exemptions, the
 * concurrent-leave guard, and the creator's {@code closeOnHostDrop} route —
 * lives in {@link GameSessionService#reapStaleMembers(Instant)}, which owns
 * the ephemeral pause state the exemptions read. This class is only the clock.
 *
 * <h2>What the sweep depends on, and why the timeout is what it is</h2>
 *
 * <p>Reaping is only safe while every seated player emits a liveness signal
 * even when they are doing nothing. That property does NOT come from the
 * games. Exactly one of the games in this repo polls the roster on its own —
 * {@code shadows-of-the-net}, from {@code scripts/session_bridge.gd}. The
 * other nineteen (chess, go, reversi, connect-four, gomoku, dots-and-boxes,
 * battleship, minesweeper, sudoku, tetris, tron, bomberman, match-three,
 * asteroids-duel, the Godot ports, three-wizards, dodecahedron) emit nothing
 * at all between player actions, and the lobby is game-generic, so all of them
 * get {@code session_members} rows. Two players thinking over a turn-based
 * board are BOTH idle, so an idle-only reaper takes the whole room — and if
 * the room's creator is one of them, {@code closeOnHostDrop} destroys it for
 * everybody mid-game.
 *
 * <p>The property therefore comes from the Angular host shell instead: it owns
 * the session for every game, and {@code SessionHeartbeatService} polls
 * {@code GET /api/sessions/{id}} every 20 s for as long as the tab holds a
 * seat. That is the signal this sweeper is calibrated against.
 *
 * <h2>Schedule</h2>
 *
 * <pre>
 *   enabled        : ${app.sessions.reap-enabled}            (default true)
 *   member timeout : ${app.sessions.member-timeout-ms}       (default 180 000 ms = 3 min)
 *   fixed-rate     : ${app.sessions.reap-fixed-rate-ms}      (default  30 000 ms = 30 s)
 *   initial-delay  : ${app.sessions.reap-initial-delay-ms}   (default  60 000 ms =  1 min)
 * </pre>
 *
 * <p>180 s is nine missed host-shell heartbeats — but the number that actually
 * sets the floor is browser timer throttling, not the nominal period: a
 * BACKGROUNDED tab is still a seated player, and browsers clamp
 * {@code setInterval} in a hidden document to roughly one call per minute. The
 * window has to clear several of those throttled beats, so 3 min buys three
 * where 90 s bought one and a half. Lowering it without also lowering
 * {@code SessionHeartbeatService.INTERVAL_MS} — and accounting for the clamp —
 * starts evicting players who are still sitting there. The 1-minute initial
 * delay keeps the first sweep away from the connection pool's startup,
 * matching {@link GameLaunchSweeper}.
 *
 * <p>{@code app.sessions.reap-enabled=false} disables the sweep outright,
 * leaving the heartbeat writes harmlessly in place. It exists because this is
 * a scheduled job that DELETES people from live rooms: if a client-side
 * regression ever takes the heartbeat out again, an operator needs a way to
 * stop the bleeding that does not require a code change.
 *
 * <h2>Failure behaviour</h2>
 *
 * <p>Mirrors {@link GameLaunchSweeper}: the reactive call gets an
 * {@code onError} handler, a transient database error is logged as a WARNING
 * and the sweep abandoned, and the next scheduled run retries. No exception
 * ever reaches the scheduler thread.
 *
 * <h2>Caller-role access matrix</h2>
 *
 * <pre>
 *   Player      — not applicable (this is a background task)
 *   Publisher   — not applicable
 *   Mod         — not applicable
 *   Admin       — may observe sweep effects via the admin session-list endpoint
 *   Superadmin  — same as Admin
 * </pre>
 *
 * @see GameSessionService#reapStaleMembers(Instant)
 * @see GameSessionService#heartbeat(String, String)
 * @see GameLaunchSweeper
 */
@Service
public class SessionMemberReaper {

    private static final Logger log = LoggerFactory.getLogger(SessionMemberReaper.class);

    private final GameSessionService sessions;

    /**
     * How long a member may go without a liveness signal before their seat is
     * released. Must stay well above {@code app.sessions.heartbeat-min-interval-ms}
     * (the per-member write rate limit) or a live player could fall through the
     * gap between two suppressed heartbeats — and above the browser's
     * background-tab timer clamp, see the class doc.
     */
    private final Duration memberTimeout;

    /**
     * Kill-switch. When false the sweep never runs — no candidate query, no
     * stamp, no close. Off-switch rather than on-switch because the ghost seat
     * the sweep collects is a real bug, but a scheduled job that evicts players
     * from live rooms is the kind of thing an operator must be able to stop
     * without waiting for a release.
     */
    private final boolean enabled;

    public SessionMemberReaper(GameSessionService sessions,
                               @Value("${app.sessions.reap-enabled:true}") boolean enabled,
                               @Value("${app.sessions.member-timeout-ms:180000}") long memberTimeoutMs) {
        this.sessions = sessions;
        this.enabled = enabled;
        // Floor at one second: a zero/negative timeout would make every member
        // — including one that heartbeat a millisecond ago — a reap candidate.
        this.memberTimeout = Duration.ofMillis(Math.max(1000L, memberTimeoutMs));
        if (enabled) {
            log.info("[member-reaper] enabled; releasing seats quiet for more than {}", this.memberTimeout);
        } else {
            log.warn("[member-reaper] DISABLED by app.sessions.reap-enabled=false — "
                    + "seats abandoned by a closed tab will be released only by an explicit leave");
        }
    }

    // -------------------------------------------------------------------------
    // Scheduled sweep
    // -------------------------------------------------------------------------

    /**
     * Release every seat whose last liveness signal is older than the member
     * timeout.
     *
     * <p>The cutoff is derived once at method entry so the query and the log
     * line reference the same logical point in time even if the reactive
     * subscription resolves a few milliseconds later.
     *
     * <p>On success: logs at INFO with the number of seats released when at
     * least one was; silent when the sweep found nothing, which is the normal
     * case on a healthy cluster.
     *
     * <p>Returns immediately when {@link #enabled} is false — the check is
     * here rather than on the bean so the schedule stays declarative and the
     * switch can be reasoned about at the one place the damage is done.
     */
    @Scheduled(fixedRateString = "${app.sessions.reap-fixed-rate-ms:30000}",
               initialDelayString = "${app.sessions.reap-initial-delay-ms:60000}")
    public void reap() {
        if (!enabled) return;
        Instant cutoff = Instant.now().minus(memberTimeout);
        sessions.reapStaleMembers(cutoff)
                .subscribe(
                    reaped -> {
                        if (reaped != null && reaped > 0) {
                            log.info("[member-reaper] released {} stale session seats (cutoff={})",
                                    reaped, cutoff);
                        }
                    },
                    // A transient DB error must not stop future sweeps — log and move on.
                    err -> log.warn("[member-reaper] sweep failed", err)
                );
    }
}
