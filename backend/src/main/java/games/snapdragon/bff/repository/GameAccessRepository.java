package games.snapdragon.bff.repository;

import games.snapdragon.bff.model.GameAccess;
import org.springframework.data.r2dbc.repository.Query;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

public interface GameAccessRepository extends ReactiveCrudRepository<GameAccess, Long> {

    Flux<GameAccess> findByGameId(Long gameId);

    @Query("DELETE FROM game_access WHERE game_id = :gameId AND scope = :scope AND scope_value IS NOT DISTINCT FROM :scopeValue")
    Mono<Void> deletePolicy(Long gameId, String scope, String scopeValue);

    /**
     * Game IDs visible to a principal given the union of:
     *   • {@code scope='all'} rows (everyone),
     *   • {@code scope='role'} rows whose value is in {@code roleNames},
     *   • {@code scope='user'} rows whose value equals {@code userId}.
     *
     * <p>Empty {@code roleNames} is OK — the {@code = ANY(...)} predicate
     * matches nothing in that case, falling back to all + user.
     */
    @Query("""
        SELECT DISTINCT game_id FROM game_access
         WHERE scope = 'all'
            OR (scope = 'role' AND scope_value = ANY(:roleNames))
            OR (scope = 'user' AND scope_value = :userId)
        """)
    Flux<Long> findVisibleGameIdsForPrincipal(String[] roleNames, String userId);
}
