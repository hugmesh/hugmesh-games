package games.snapdragon.bff.repository;

import games.snapdragon.bff.model.Chapter;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Each Chapter is one compiled Godot WASM bundle. Ordered within a game by sort_order.
 */
public interface ChapterRepository extends ReactiveCrudRepository<Chapter, Long> {
    Mono<Chapter> findBySlug(String slug);
    Flux<Chapter> findAllByOrderBySortOrderAsc();
    Flux<Chapter> findByGameIdOrderBySortOrderAsc(Long gameId);
}
