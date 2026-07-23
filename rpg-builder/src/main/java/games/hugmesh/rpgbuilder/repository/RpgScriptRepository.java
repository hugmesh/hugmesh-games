package games.hugmesh.rpgbuilder.repository;

import games.hugmesh.rpgbuilder.entity.RpgScript;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import reactor.core.publisher.Flux;

import java.util.UUID;

public interface RpgScriptRepository extends ReactiveCrudRepository<RpgScript, UUID> {
    Flux<RpgScript> findByProjectIdOrderByKindAscNameAsc(UUID projectId);
}
