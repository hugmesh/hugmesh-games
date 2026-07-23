package games.hugmesh.bff.repository;

import games.hugmesh.bff.model.AdminAction;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Reactive R2DBC repository for {@code admin_actions} rows in Postgres.
 *
 * <p>Each {@link AdminAction} records a moderation decision made by an
 * administrator against a specific user — typically a ban, unban, warning,
 * or other account-status change. Rows are append-only; the most-recent
 * action for a user is the authoritative current state.
 *
 * <p>The primary read pattern is per-target: the banned-page controller
 * loads the full action history for the caller's own Casdoor id so the
 * player can see why their access was restricted and what the admin wrote.
 */
public interface AdminActionRepository extends ReactiveCrudRepository<AdminAction, Long> {

    // -------------------------------------------------------------------------
    // Per-target history
    // -------------------------------------------------------------------------

    /**
     * Returns all admin actions taken against {@code targetCasdoorId},
     * ordered newest-first.
     *
     * <p>The frontend banned-page reads {@code index 0} of this result to
     * resolve "are you banned?" and "what message did the admin leave?" in
     * a single database call.
     *
     * @param targetCasdoorId the Casdoor user id of the player who was acted on
     * @return a {@code Flux} streaming every action row for that target,
     *         newest first; empty if no actions have ever been taken
     */
    Flux<AdminAction> findByTargetCasdoorIdOrderByCreatedAtDesc(String targetCasdoorId);

    /**
     * Returns only the single most-recent admin action for {@code targetCasdoorId}.
     *
     * <p>Used when only the current account status is needed (e.g. a
     * lightweight banned-check that doesn't need the full history).
     *
     * @param targetCasdoorId the Casdoor user id of the player to check
     * @return a {@code Mono} emitting the latest action row, or empty if
     *         no actions have been recorded for that user
     */
    Mono<AdminAction> findFirstByTargetCasdoorIdOrderByCreatedAtDesc(String targetCasdoorId);
}
