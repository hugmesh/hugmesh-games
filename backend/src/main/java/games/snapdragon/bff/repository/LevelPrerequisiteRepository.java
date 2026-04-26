package games.snapdragon.bff.repository;

import games.snapdragon.bff.model.LevelPrerequisite;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import reactor.core.publisher.Flux;

public interface LevelPrerequisiteRepository extends ReactiveCrudRepository<LevelPrerequisite, Long> {
    Flux<LevelPrerequisite> findByLevelId(Long levelId);
}
