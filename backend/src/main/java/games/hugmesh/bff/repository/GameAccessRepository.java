package games.hugmesh.bff.repository;

import games.hugmesh.bff.model.GameAccess;
import org.springframework.data.r2dbc.repository.Query;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Reactive R2DBC repository for {@code game_access} rows in Postgres.
 *
 * <p>Each {@link GameAccess} row defines one access policy entry for a game —
 * a scoped grant that controls which principals may see and launch it.
 * Three scope kinds are supported:
 *
 * <ul>
 *   <li>{@code scope='all'} — the game is visible to every authenticated user.</li>
 *   <li>{@code scope='role'} — the game is visible only to users holding
 *       a specific Casdoor role (stored in {@code scope_value}).</li>
 *   <li>{@code scope='user'} — the game is visible only to a specific user
 *       identified by Casdoor user id (stored in {@code scope_value}).</li>
 * </ul>
 *
 * <p>A game may have multiple policy rows; visibility is the union of all
 * matching entries. Admins manage policies via the game-access admin endpoints.
 */
public interface GameAccessRepository extends ReactiveCrudRepository<GameAccess, Long> {

    // -------------------------------------------------------------------------
    // Policy list
    // -------------------------------------------------------------------------

    /**
     * Returns all access policy rows for the given game.
     *
     * <p>Used by admin endpoints that display or manage the full policy set
     * for a game.
     *
     * @param gameId the surrogate id of the game whose policies are to be loaded
     * @return a {@code Flux} streaming every policy row for that game;
     *         empty if no policies have been defined (game is effectively hidden)
     */
    Flux<GameAccess> findByGameId(Long gameId);

    // -------------------------------------------------------------------------
    // Policy mutation
    // -------------------------------------------------------------------------

    /**
     * Deletes the policy entry that exactly matches the given scope triplet.
     *
     * <p>The {@code IS NOT DISTINCT FROM} comparison treats {@code NULL}
     * {@code scope_value} as equal to SQL {@code NULL}, so {@code scope='all'}
     * rows (which carry a null value) are matched correctly.
     *
     * @param gameId     the game the policy belongs to
     * @param scope      the scope kind ({@code "all"}, {@code "role"}, or {@code "user"})
     * @param scopeValue the scope target value, or {@code null} for {@code scope='all'}
     * @return a {@code Mono} that completes when the delete is done
     */
    @Query("DELETE FROM game_access WHERE game_id = :gameId AND scope = :scope AND scope_value IS NOT DISTINCT FROM :scopeValue")
    Mono<Void> deletePolicy(Long gameId, String scope, String scopeValue);

    // -------------------------------------------------------------------------
    // Principal visibility check
    // -------------------------------------------------------------------------

    /**
     * Returns the set of game IDs visible to a given principal.
     *
     * <p>A game is included when at least one of its policy rows matches the
     * principal via the union of:
     * <ul>
     *   <li>{@code scope='all'} — always matches (game open to everyone),</li>
     *   <li>{@code scope='role'} — matches when {@code scope_value} is in
     *       {@code roleNames},</li>
     *   <li>{@code scope='user'} — matches when {@code scope_value = userId}.</li>
     * </ul>
     *
     * <p>An empty {@code roleNames} array is safe — the {@code = ANY(...)}
     * predicate matches nothing, so the result falls back to {@code all} +
     * {@code user} entries only.
     *
     * @param roleNames the Casdoor role names held by the principal (may be empty)
     * @param userId    the principal's Casdoor user id
     * @return a {@code Flux} streaming the distinct game ids visible to this
     *         principal; empty if no games match the principal's access grants
     */
    @Query("""
        SELECT DISTINCT game_id FROM game_access
         WHERE scope = 'all'
            OR (scope = 'role' AND scope_value = ANY(:roleNames))
            OR (scope = 'user' AND scope_value = :userId)
        """)
    Flux<Long> findVisibleGameIdsForPrincipal(String[] roleNames, String userId);
}
