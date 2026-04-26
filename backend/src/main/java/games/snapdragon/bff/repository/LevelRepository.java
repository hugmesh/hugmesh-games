package games.snapdragon.bff.repository;

import games.snapdragon.bff.model.Level;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Each Level is one compiled Godot WASM bundle. Ordered within a game by sort_order.
 */
public interface LevelRepository extends ReactiveCrudRepository<Level, Long> {
    Mono<Level> findBySlug(String slug);
    Flux<Level> findAllByOrderBySortOrderAsc();
    Flux<Level> findByGameIdOrderBySortOrderAsc(Long gameId);
}
