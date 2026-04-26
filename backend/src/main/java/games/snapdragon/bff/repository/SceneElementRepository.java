package games.snapdragon.bff.repository;

import games.snapdragon.bff.model.SceneElement;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import reactor.core.publisher.Flux;

public interface SceneElementRepository extends ReactiveCrudRepository<SceneElement, Long> {
    Flux<SceneElement> findByLevelIdOrderBySortOrder(Long chapterId);
}
