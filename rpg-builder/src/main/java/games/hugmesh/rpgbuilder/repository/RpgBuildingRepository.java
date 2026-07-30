package games.hugmesh.rpgbuilder.repository;

import games.hugmesh.rpgbuilder.entity.RpgBuilding;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import reactor.core.publisher.Flux;

import java.util.UUID;

public interface RpgBuildingRepository extends ReactiveCrudRepository<RpgBuilding, UUID> {
    Flux<RpgBuilding> findByProjectIdOrderByNameAsc(UUID projectId);
    Flux<RpgBuilding> findByHostRoomId(UUID hostRoomId);
}
