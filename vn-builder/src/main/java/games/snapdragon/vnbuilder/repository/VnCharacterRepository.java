package games.snapdragon.vnbuilder.repository;

import games.snapdragon.vnbuilder.entity.VnCharacter;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import reactor.core.publisher.Flux;

import java.util.UUID;

public interface VnCharacterRepository extends ReactiveCrudRepository<VnCharacter, UUID> {
    Flux<VnCharacter> findByProjectIdOrderByDisplayNameAsc(UUID projectId);
}
