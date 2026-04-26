package games.snapdragon.bff.repository;

import games.snapdragon.bff.model.CharacterSkillSlot;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

public interface CharacterSkillSlotRepository extends ReactiveCrudRepository<CharacterSkillSlot, Long> {
    Flux<CharacterSkillSlot> findByCharacterIdOrderBySlotNumber(Long characterId);
    Mono<CharacterSkillSlot> findByCharacterIdAndSlotNumber(Long characterId, int slotNumber);
}
