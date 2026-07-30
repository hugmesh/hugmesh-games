package games.hugmesh.rpgbuilder.repository;

import games.hugmesh.rpgbuilder.entity.RpgRoom;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import reactor.core.publisher.Flux;

import java.util.UUID;

public interface RpgRoomRepository extends ReactiveCrudRepository<RpgRoom, UUID> {
    Flux<RpgRoom> findByProjectIdOrderByNameAsc(UUID projectId);
    Flux<RpgRoom> findByAreaIdOrderByNameAsc(UUID areaId);
    Flux<RpgRoom> findByBuildingIdOrderByFloorLevelAsc(UUID buildingId);
    Flux<RpgRoom> findByProjectIdAndSlugIn(UUID projectId, java.util.Collection<String> slugs);
}
