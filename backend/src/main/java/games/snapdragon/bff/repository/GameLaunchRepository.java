package games.snapdragon.bff.repository;

import games.snapdragon.bff.model.GameLaunch;
import org.springframework.data.r2dbc.repository.Modifying;
import org.springframework.data.r2dbc.repository.Query;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Instant;

public interface GameLaunchRepository extends ReactiveCrudRepository<GameLaunch, Long> {

    /**
     * Look up an active (non-terminated, not yet expired) launch by uid.
     * Used by the AssetController gate.
     */
    Mono<GameLaunch> findByUidAndTerminatedAtIsNullAndExpiresAtAfter(String uid, Instant cutoff);

    Mono<GameLaunch> findByUid(String uid);

    /** Active (non-terminated) launches across all users — admin view. */
    Flux<GameLaunch> findByTerminatedAtIsNullOrderByStartedAtDesc();

    /**
     * Bulk-terminate every launch whose {@code expires_at} has already
     * passed and that hasn't been explicitly terminated yet. Run by the
     * scheduled cleanup so the table doesn't grow unbounded.
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
