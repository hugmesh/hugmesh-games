package games.snapdragon.bff.repository;

import games.snapdragon.bff.model.Game;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

public interface GameRepository extends ReactiveCrudRepository<Game, Long> {
    Mono<Game> findBySlug(String slug);
    Flux<Game> findAllByOrderBySortOrderAsc();
}
