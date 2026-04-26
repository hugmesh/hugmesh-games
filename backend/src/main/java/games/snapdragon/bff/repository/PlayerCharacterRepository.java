package games.snapdragon.bff.repository;

import games.snapdragon.bff.model.PlayerCharacter;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import reactor.core.publisher.Mono;

public interface PlayerCharacterRepository extends ReactiveCrudRepository<PlayerCharacter, Long> {
    Mono<PlayerCharacter> findByCasdoorId(String casdoorId);
}
