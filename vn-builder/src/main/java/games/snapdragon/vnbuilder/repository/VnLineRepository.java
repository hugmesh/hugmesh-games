package games.snapdragon.vnbuilder.repository;

import games.snapdragon.vnbuilder.entity.VnLine;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import reactor.core.publisher.Flux;

import java.util.UUID;

public interface VnLineRepository extends ReactiveCrudRepository<VnLine, UUID> {
    Flux<VnLine> findBySceneIdOrderByLineIndexAsc(UUID sceneId);
}
