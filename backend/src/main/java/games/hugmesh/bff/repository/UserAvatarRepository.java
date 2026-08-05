package games.hugmesh.bff.repository;

import games.hugmesh.bff.model.UserAvatar;
import org.springframework.data.r2dbc.repository.Modifying;
import org.springframework.data.r2dbc.repository.Query;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Reactive R2DBC repository for {@code user_avatars} rows.
 *
 * <p>Every read/write path is keyed by {@code casdoor_id} (the JWT subject) —
 * ownership is enforced in {@link games.hugmesh.bff.service.UserAvatarService},
 * this repository just provides the scoped queries.
 */
public interface UserAvatarRepository extends ReactiveCrudRepository<UserAvatar, String> {

    /** The caller's saved avatars, oldest first so list order is stable. */
    Flux<UserAvatar> findByCasdoorIdOrderByCreatedAtAsc(String casdoorId);

    /** How many avatars the user already has — the service-layer cap input. */
    Mono<Long> countByCasdoorId(String casdoorId);

    /**
     * Make {@code avatarId} the user's ONLY active avatar in one statement:
     * the boolean expression sets the target true and every sibling false in
     * the same UPDATE, so there is no read-then-write window in which two
     * concurrent selects can leave two actives — the last statement to commit
     * wins whole. The partial unique index {@code uq_user_avatars_one_active}
     * is the backstop, never the mechanism.
     *
     * @return the number of rows touched (the user's whole avatar set)
     */
    @Modifying
    @Query("""
        UPDATE user_avatars
           SET is_active = (id = :avatarId), updated_at = now()
         WHERE casdoor_id = :casdoorId
        """)
    Mono<Long> activateExclusively(String casdoorId, String avatarId);
}
