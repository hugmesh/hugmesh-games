package games.snapdragon.rpgbuilder.repository;

import games.snapdragon.rpgbuilder.entity.RpgArea;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import reactor.core.publisher.Flux;

import java.util.UUID;

public interface RpgAreaRepository extends ReactiveCrudRepository<RpgArea, UUID> {
    Flux<RpgArea> findByProjectIdOrderBySortOrderAscNameAsc(UUID projectId);
}
