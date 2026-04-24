package com.threewizards.repository;

import com.threewizards.model.PlayerPosition;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import reactor.core.publisher.Mono;

public interface PlayerPositionRepository extends ReactiveCrudRepository<PlayerPosition, Long> {
    Mono<PlayerPosition> findByCharacterId(Long characterId);
}
