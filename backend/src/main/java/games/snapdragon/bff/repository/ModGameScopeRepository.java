package games.snapdragon.bff.repository;

import games.snapdragon.bff.model.ModGameScope;
import org.springframework.data.r2dbc.repository.Query;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

public interface ModGameScopeRepository extends ReactiveCrudRepository<ModGameScope, Long> {

    @Query("""
        SELECT * FROM mod_game_scopes
         WHERE casdoor_id = :casdoorId
           AND revoked_at IS NULL
        """)
    Flux<ModGameScope> findActiveByCasdoor(String casdoorId);

    @Query("""
        SELECT * FROM mod_game_scopes
         WHERE casdoor_id = :casdoorId
           AND game_slug  = :gameSlug
           AND revoked_at IS NULL
         LIMIT 1
        """)
    Mono<ModGameScope> findActive(String casdoorId, String gameSlug);

    @Query("""
        SELECT * FROM mod_game_scopes
         WHERE game_slug = :gameSlug
           AND revoked_at IS NULL
         ORDER BY granted_at DESC
        """)
    Flux<ModGameScope> findActiveByGame(String gameSlug);
}
