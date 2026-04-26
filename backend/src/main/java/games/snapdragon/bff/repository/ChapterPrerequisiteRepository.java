package games.snapdragon.bff.repository;

import games.snapdragon.bff.model.ChapterPrerequisite;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import reactor.core.publisher.Flux;

public interface ChapterPrerequisiteRepository extends ReactiveCrudRepository<ChapterPrerequisite, Long> {
    Flux<ChapterPrerequisite> findByChapterId(Long chapterId);
}
