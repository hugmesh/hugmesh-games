package com.threewizards.repository;

import com.threewizards.model.CharacterSkill;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import reactor.core.publisher.Flux;

public interface CharacterSkillRepository extends ReactiveCrudRepository<CharacterSkill, Long> {
    Flux<CharacterSkill> findByCharacterId(Long characterId);
}
