package games.snapdragon.bff.repository;

import games.snapdragon.bff.model.GameSession;
import org.springframework.data.r2dbc.repository.Query;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import reactor.core.publisher.Flux;

/**
 * Reactive R2DBC repository for {@code game_sessions} rows in Postgres.
 *
 * <p>A {@link GameSession} is a multiplayer play session created by a player in
 * the lobby. Its primary key is a {@code String} (a {@code Crypto.mintUid()}
 * token rather than a surrogate bigserial) so application code can know the
 * session id before the INSERT completes. Entities whose id is application-
 * assigned must implement {@code Persistable<T>} with a {@code @Transient isNew}
 * flag; otherwise {@code save()} issues an UPDATE that affects zero rows and
 * throws {@code TransientDataAccessResourceException}.
 *
 * <p>A session is "open" while {@code closed_at IS NULL}. Sessions are closed
 * when the last member leaves or when an admin terminates them.
 */
public interface GameSessionRepository extends ReactiveCrudRepository<GameSession, String> {

    // -------------------------------------------------------------------------
    // Open session lists
    // -------------------------------------------------------------------------

    /**
     * Returns all open (not yet closed) sessions for the given game slug,
     * ordered newest-first.
     *
     * <p>Used by the lobby to list sessions that new players can join for a
     * specific game.
     *
     * @param gameSlug the slug of the game to filter by (e.g. {@code "three-wizards"})
     * @return a {@code Flux} streaming every open session for that game,
     *         newest first; empty if no open sessions exist for that slug
     */
    @Query("""
        SELECT * FROM game_sessions
         WHERE game_slug = :gameSlug
           AND closed_at IS NULL
         ORDER BY created_at DESC
        """)
    Flux<GameSession> findOpenByGameSlug(String gameSlug);

    /**
     * Returns all open (not yet closed) sessions across every game, newest first.
     *
     * <p>Used by the admin sessions panel to show the full set of currently
     * active sessions across all titles.
     *
     * @return a {@code Flux} streaming every open session regardless of game,
     *         newest first; empty if no sessions are currently open
     */
    @Query("""
        SELECT * FROM game_sessions
         WHERE closed_at IS NULL
         ORDER BY created_at DESC
        """)
    Flux<GameSession> findAllOpen();
}
