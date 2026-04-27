package games.snapdragon.bff.repository;

import games.snapdragon.bff.model.PublisherGameScope;
import org.springframework.data.r2dbc.repository.Query;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

public interface PublisherGameScopeRepository extends ReactiveCrudRepository<PublisherGameScope, Long> {

    @Query("""
        SELECT * FROM publisher_game_scopes
         WHERE casdoor_id = :casdoorId
           AND revoked_at IS NULL
        """)
    Flux<PublisherGameScope> findActiveByCasdoor(String casdoorId);

    @Query("""
        SELECT * FROM publisher_game_scopes
         WHERE casdoor_id = :casdoorId
           AND game_slug  = :gameSlug
           AND revoked_at IS NULL
         LIMIT 1
        """)
    Mono<PublisherGameScope> findActive(String casdoorId, String gameSlug);

    @Query("""
        SELECT * FROM publisher_game_scopes
         WHERE game_slug = :gameSlug
           AND revoked_at IS NULL
         ORDER BY granted_at DESC
        """)
    Flux<PublisherGameScope> findActiveByGame(String gameSlug);
}
