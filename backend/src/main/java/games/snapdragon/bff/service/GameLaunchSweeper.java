package games.snapdragon.bff.service;

import games.snapdragon.bff.repository.GameLaunchRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Instant;

/**
 * Scheduled sweeper that bulk-terminates stale {@code game_launches} rows.
 *
 * <h2>Responsibility</h2>
 *
 * <p>Every time a player clicks a game card the controller inserts a
 * {@code game_launches} row. Launches that are never explicitly closed —
 * because the player closed the tab, the BFF crashed mid-launch, or the
 * client network dropped — would otherwise accumulate indefinitely. This
 * sweeper prevents unbounded table growth by marking every row whose
 * {@code expires_at} timestamp has passed but whose terminal state was
 * never written.
 *
 * <h2>Schedule</h2>
 *
 * <pre>
 *   fixed-rate  : ${app.launches.sweep-fixed-rate-ms}   (default 300 000 ms = 5 min)
 *   initial-delay: ${app.launches.sweep-initial-delay-ms} (default  60 000 ms = 1 min)
 * </pre>
 *
 * <p>The 1-minute initial delay prevents the sweeper from racing with
 * the connection pool's own startup, which can occasionally leave rows
 * in a transitional state on a fresh deploy.
 *
 * <h2>Failure behaviour</h2>
 *
 * <p>Each sweep subscribes to the reactive {@link GameLaunchRepository#sweepExpired}
 * call and supplies an {@code onError} handler. A transient database error
 * is logged as a WARNING and the sweep is silently abandoned — the next
 * scheduled run will retry. A single failed sweep does not prevent future
 * runs and does not propagate an exception to the scheduler thread.
 *
 * <h2>Caller-role access matrix</h2>
 *
 * <pre>
 *   Player      — not applicable (this is a background task)
 *   Publisher   — not applicable
 *   Mod         — not applicable
 *   Admin       — may observe sweep effects via the admin launch-list endpoint
 *   Superadmin  — same as Admin
 * </pre>
 *
 * @see GameLaunchRepository#sweepExpired(Instant)
 */
@Service
public class GameLaunchSweeper {

    private static final Logger log = LoggerFactory.getLogger(GameLaunchSweeper.class);

    private final GameLaunchRepository launches;

    public GameLaunchSweeper(GameLaunchRepository launches) {
        this.launches = launches;
    }

    // -------------------------------------------------------------------------
    // Scheduled sweep
    // -------------------------------------------------------------------------

    /**
     * Marks every {@code game_launches} row whose {@code expires_at} is on or
     * before the current instant as terminated.
     *
     * <p>The cutoff is captured once at method entry so the DB query and the
     * log message reference the same logical point in time even if the
     * reactive subscription resolves a few milliseconds later.
     *
     * <p>The sweep fires at a fixed rate (default every 5 minutes). Cadence is
     * tunable via {@code app.launches.sweep-fixed-rate-ms} without redeploying.
     *
     * <p>On success: logs at INFO with the number of rows terminated when at
     * least one row was swept; silent when zero rows were affected.
     * On error: logs a WARNING and returns — the scheduler thread is never
     * interrupted and the next run proceeds normally.
     */
    @Scheduled(fixedRateString = "${app.launches.sweep-fixed-rate-ms:300000}",
               initialDelayString = "${app.launches.sweep-initial-delay-ms:60000}")
    public void sweep() {
        // Capture the cutoff once so the query and the log line are consistent.
        Instant cutoff = Instant.now();
        launches.sweepExpired(cutoff)
                .subscribe(
                    swept -> {
                        // Only log when rows were actually terminated; silence is fine
                        // on idle clusters where no launches have expired.
                        if (swept != null && swept > 0) {
                            log.info("[launch-sweeper] terminated {} expired launches (cutoff={})",
                                    swept, cutoff);
                        }
                    },
                    // A transient DB error must not stop future sweep runs — log and move on.
                    err -> log.warn("[launch-sweeper] sweep failed", err)
                );
    }
}
