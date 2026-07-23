package games.hugmesh.bff.repository;

import games.hugmesh.bff.model.SessionLogEntry;
import org.springframework.stereotype.Repository;
import reactor.core.publisher.Flux;

/**
 * Read-only join queries that power the session-history views.
 *
 * <p>Three call sites:
 * <ul>
 *   <li>player view — their own history (any game)</li>
 *   <li>admin view — anyone's history (any game)</li>
 *   <li>mod / publisher view — anyone's history scoped to games the
 *       caller has scope for (filtered server-side via the
 *       game-scope tables)</li>
 * </ul>
 *
 * <p>Implemented as a custom interface (no {@code ReactiveCrudRepository})
 * because the rows are joins of {@code session_members} +
 * {@code game_sessions} and don't map to a single entity.
 */
@Repository
public interface SessionLogRepository {

    /** All membership rows for {@code casdoorId} across every game,
     *  newest first. Used by the player's own history view + the
     *  admin's "view someone's history" panel. */
    Flux<SessionLogEntry> findByCasdoor(String casdoorId, int limit);

    /** Restricted to {@code gameSlug} — used by the mod / publisher
     *  history view to filter on the games they hold scope for. */
    Flux<SessionLogEntry> findByCasdoorAndGameSlug(String casdoorId, String gameSlug, int limit);
}
