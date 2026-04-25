package com.threewizards.repository;

import com.threewizards.model.GameLaunch;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import reactor.core.publisher.Mono;

import java.time.Instant;

public interface GameLaunchRepository extends ReactiveCrudRepository<GameLaunch, Long> {

    /**
     * Look up an active (non-terminated, not yet expired) launch by uid.
     * Used by the AssetController gate.
     */
    Mono<GameLaunch> findByUidAndTerminatedAtIsNullAndExpiresAtAfter(String uid, Instant cutoff);

    Mono<GameLaunch> findByUid(String uid);
}
