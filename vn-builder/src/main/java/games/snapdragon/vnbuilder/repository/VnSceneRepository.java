package games.snapdragon.vnbuilder.repository;

import games.snapdragon.vnbuilder.entity.VnScene;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import reactor.core.publisher.Flux;

import java.util.UUID;

public interface VnSceneRepository extends ReactiveCrudRepository<VnScene, UUID> {
    Flux<VnScene> findByProjectIdOrderBySortOrderAscNameAsc(UUID projectId);
    Flux<VnScene> findByActIdOrderBySortOrderAscNameAsc(UUID actId);
}
