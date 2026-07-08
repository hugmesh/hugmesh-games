package games.snapdragon.bff.repository;

import games.snapdragon.bff.model.GameLaunch;
import org.springframework.data.r2dbc.repository.Modifying;
import org.springframework.data.r2dbc.repository.Query;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Instant;

/**
 * Reactive R2DBC repository for {@code game_launches} rows in Postgres.
 *
 * <p>A {@link GameLaunch} row represents one authorized play session for a
 * user. When a player clicks "Play", the launch service mints a
 * cryptographically random {@code uid} (via {@code Crypto.mintUid()}),
 * records the launch with an {@code expires_at} deadline, and returns the uid
 * to the frontend. The asset gateway ({@code AssetController}) validates that
 * uid on every WASM bundle request, ensuring unauthenticated actors cannot
 * download game bundles.
 *
 * <p>A launch becomes inactive when either:
 * <ul>
 *   <li>{@code terminated_at} is set (explicit termination by the user,
 *       admin, or system), or</li>
 *   <li>{@code expires_at} has passed (natural expiry).</li>
 * </ul>
 *
 * <p>A scheduled cleanup task calls {@link #sweepExpired} to stamp
 * {@code terminated_at} on past-expiry rows so the table doesn't grow
 * unbounded.
 */
public interface GameLaunchRepository extends ReactiveCrudRepository<GameLaunch, Long> {

    // -------------------------------------------------------------------------
    // Asset-gate lookups
    // -------------------------------------------------------------------------

    /**
     * Returns the launch with the given uid only if it is still active —
     * i.e. not yet terminated and not yet expired past the given cutoff.
     *
     * <p>Called by the {@code AssetController} on every WASM bundle request
     * to gate access. If this returns empty, the request is rejected with 403.
     *
     * @param uid    the launch uid token minted at play-start
     * @param cutoff the instant before which {@code expires_at} must fall;
     *               typically {@code Instant.now()}
     * @return a {@code Mono} emitting the active launch, or empty if the uid
     *         is unknown, already terminated, or expired
     */
    Mono<GameLaunch> findByUidAndTerminatedAtIsNullAndExpiresAtAfter(String uid, Instant cutoff);

    /**
     * Returns the launch with the given uid regardless of termination or
     * expiry status.
     *
     * <p>Used by admin and termination endpoints that need the full row
     * to inspect or update.
     *
     * @param uid the launch uid token
     * @return a {@code Mono} emitting the matching launch, or empty if
     *         no launch with that uid exists
     */
    Mono<GameLaunch> findByUid(String uid);

    // -------------------------------------------------------------------------
    // Admin list
    // -------------------------------------------------------------------------

    /**
     * Returns all non-terminated launches across all users, newest first.
     *
     * <p>Used by the admin panel to show the current set of active play
     * sessions and allow administrators to forcibly terminate them.
     *
     * @return a {@code Flux} streaming every launch where {@code terminated_at}
     *         is null, ordered by {@code started_at} descending; may include
     *         expired-but-not-swept rows
     */
    Flux<GameLaunch> findByTerminatedAtIsNullOrderByStartedAtDesc();

    // -------------------------------------------------------------------------
    // Scheduled cleanup
    // -------------------------------------------------------------------------

    /**
     * Bulk-terminates every launch whose {@code expires_at} has already
     * passed and that has not yet been explicitly terminated.
     *
     * <p>Run by the scheduled cleanup task so the {@code game_launches}
     * table doesn't grow unbounded. The {@code cutoff} is typically
     * {@code Instant.now()} at job-run time.
     *
     * @param cutoff rows with {@code expires_at} earlier than this instant
     *               will be stamped with {@code terminated_at = NOW()}
     * @return a {@code Mono} emitting the number of rows updated
     */
    @Modifying
    @Query("""
        UPDATE game_launches
           SET terminated_at = NOW()
         WHERE terminated_at IS NULL
           AND expires_at < :cutoff
        """)
    Mono<Integer> sweepExpired(Instant cutoff);
}
