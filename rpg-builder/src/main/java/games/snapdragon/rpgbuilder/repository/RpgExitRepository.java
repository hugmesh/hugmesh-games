package games.snapdragon.rpgbuilder.repository;

import games.snapdragon.rpgbuilder.entity.RpgExit;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import reactor.core.publisher.Flux;

import java.util.UUID;

public interface RpgExitRepository extends ReactiveCrudRepository<RpgExit, UUID> {
    Flux<RpgExit> findByProjectId(UUID projectId);
    Flux<RpgExit> findByFromRoomId(UUID fromRoomId);
}
