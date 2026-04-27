package games.snapdragon.bff.repository;

import games.snapdragon.bff.model.GameSession;
import org.springframework.data.r2dbc.repository.Query;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import reactor.core.publisher.Flux;

public interface GameSessionRepository extends ReactiveCrudRepository<GameSession, String> {

    /** Open sessions (closed_at IS NULL) for a given game slug, newest first. */
    @Query("""
        SELECT * FROM game_sessions
         WHERE game_slug = :gameSlug
           AND closed_at IS NULL
         ORDER BY created_at DESC
        """)
    Flux<GameSession> findOpenByGameSlug(String gameSlug);

    /** All open sessions across every game. */
    @Query("""
        SELECT * FROM game_sessions
         WHERE closed_at IS NULL
         ORDER BY created_at DESC
        """)
    Flux<GameSession> findAllOpen();
}
