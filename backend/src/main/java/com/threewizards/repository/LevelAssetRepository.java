package com.threewizards.repository;

import com.threewizards.model.LevelAsset;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

public interface LevelAssetRepository extends ReactiveCrudRepository<LevelAsset, Long> {
    Flux<LevelAsset> findByLevelId(Long levelId);
    Mono<LevelAsset> findByLevelIdAndLabel(Long levelId, String label);
}
