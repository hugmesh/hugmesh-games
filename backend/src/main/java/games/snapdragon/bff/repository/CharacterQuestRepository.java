package games.snapdragon.bff.repository;

import games.snapdragon.bff.model.CharacterQuest;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

public interface CharacterQuestRepository extends ReactiveCrudRepository<CharacterQuest, Long> {
    Flux<CharacterQuest> findByCharacterId(Long characterId);
    Mono<CharacterQuest> findByCharacterIdAndQuestId(Long characterId, Long questId);
}
