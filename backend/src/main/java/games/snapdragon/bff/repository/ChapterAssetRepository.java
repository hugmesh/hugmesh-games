package games.snapdragon.bff.repository;

import games.snapdragon.bff.model.ChapterAsset;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

public interface ChapterAssetRepository extends ReactiveCrudRepository<ChapterAsset, Long> {
    Flux<ChapterAsset> findByLevelId(Long chapterId);
    Mono<ChapterAsset> findByLevelIdAndLabel(Long chapterId, String label);
}
