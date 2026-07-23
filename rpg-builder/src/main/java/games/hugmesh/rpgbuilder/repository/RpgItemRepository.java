package games.hugmesh.rpgbuilder.repository;

import games.hugmesh.rpgbuilder.entity.RpgItem;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import reactor.core.publisher.Flux;

import java.util.UUID;

public interface RpgItemRepository extends ReactiveCrudRepository<RpgItem, UUID> {
    Flux<RpgItem> findByProjectIdOrderByNameAsc(UUID projectId);
}
