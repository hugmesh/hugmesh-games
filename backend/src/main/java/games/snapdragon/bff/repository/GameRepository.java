package games.snapdragon.bff.repository;

import games.snapdragon.bff.model.Game;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Reactive R2DBC repository for {@code games} rows in Postgres.
 *
 * <p>Each {@link Game} is an umbrella title registered in the portal — for
 * example "Three Wizards In A Trenchcoat" or "Dodecahedron Demo". Games
 * contain one or more {@code chapters} and carry a {@code sort_order} that
 * controls their display sequence in the lobby.
 *
 * <p>The {@code slug} column is the stable URL-safe identifier used across
 * the system (chapter paths, game-access policies, scope grants, federation
 * module references, etc.).
 */
public interface GameRepository extends ReactiveCrudRepository<Game, Long> {

    // -------------------------------------------------------------------------
    // Single-game lookups
    // -------------------------------------------------------------------------

    /**
     * Returns the game with the given URL-safe slug.
     *
     * @param slug the unique slug identifying the game (e.g. {@code "three-wizards"})
     * @return a {@code Mono} emitting the matching game, or empty if no
     *         game with that slug is registered
     */
    Mono<Game> findBySlug(String slug);

    // -------------------------------------------------------------------------
    // Ordered game list
    // -------------------------------------------------------------------------

    /**
     * Returns every registered game ordered by {@code sort_order} ascending.
     *
     * <p>Used by the lobby to present games in their intended display sequence.
     *
     * @return a {@code Flux} streaming all game rows in ascending
     *         {@code sort_order}; empty if no games are registered
     */
    Flux<Game> findAllByOrderBySortOrderAsc();
}
