package games.snapdragon.rpgbuilder.repository;

import games.snapdragon.rpgbuilder.entity.RpgNpc;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import reactor.core.publisher.Flux;

import java.util.UUID;

public interface RpgNpcRepository extends ReactiveCrudRepository<RpgNpc, UUID> {
    Flux<RpgNpc> findByProjectIdOrderByNameAsc(UUID projectId);
}
