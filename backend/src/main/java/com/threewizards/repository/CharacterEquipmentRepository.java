package com.threewizards.repository;

import com.threewizards.model.CharacterEquipment;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

public interface CharacterEquipmentRepository extends ReactiveCrudRepository<CharacterEquipment, Long> {
    Flux<CharacterEquipment> findByCharacterId(Long characterId);
    Mono<CharacterEquipment> findByCharacterIdAndSlot(Long characterId, String slot);
}
