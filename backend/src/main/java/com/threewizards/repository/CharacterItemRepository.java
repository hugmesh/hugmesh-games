package com.threewizards.repository;

import com.threewizards.model.CharacterItem;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import reactor.core.publisher.Flux;

public interface CharacterItemRepository extends ReactiveCrudRepository<CharacterItem, Long> {
    Flux<CharacterItem> findByCharacterId(Long characterId);
}
