package games.hugmesh.bff.repository;

import games.hugmesh.bff.model.SessionMember;
import org.springframework.data.r2dbc.repository.Query;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

public interface SessionMemberRepository extends ReactiveCrudRepository<SessionMember, Long> {

    /** Active members of a session (left_at IS NULL), ordered by slot. */
    @Query("""
        SELECT * FROM session_members
         WHERE session_id = :sessionId
           AND left_at IS NULL
         ORDER BY slot_index NULLS LAST, joined_at ASC
        """)
    Flux<SessionMember> findActiveBySession(String sessionId);

    /** The active membership row for a (session, casdoor) pair, if any. */
    @Query("""
        SELECT * FROM session_members
         WHERE session_id = :sessionId
           AND casdoor_id = :casdoorId
           AND left_at IS NULL
         LIMIT 1
        """)
    Mono<SessionMember> findActive(String sessionId, String casdoorId);

    /** All active sessions a casdoor user is currently a member of. */
    @Query("""
        SELECT * FROM session_members
         WHERE casdoor_id = :casdoorId
           AND left_at IS NULL
        """)
    Flux<SessionMember> findActiveByCasdoor(String casdoorId);

    /**
     * The session_member that owns a particular character right now
     * (if any). Used by ControlController to authorize a multi-player
     * control write — the JWT subject must match the active owner of
     * the targeted character for some session.
     *
     * <p>Unscoped form. When a control comes in with X-Session-Id the
     * caller should prefer {@link #findActiveOwnerInSession} so the
     * authz lookup pins to the correct session. Without it, two
     * players each using {@code character_id=1} in different sessions
     * race and the loser sees "character not owned by caller" on
     * every move.
     */
    @Query("""
        SELECT * FROM session_members
         WHERE character_id = :characterId
           AND left_at IS NULL
         LIMIT 1
        """)
    Mono<SessionMember> findActiveOwnerOfCharacter(Long characterId);

    /**
     * Scoped form — the (session, character) tuple is unique among
     * active members thanks to the partial slot constraint, so this
     * always returns the right row for X-Session-Id-bearing controls.
     */
    @Query("""
        SELECT * FROM session_members
         WHERE session_id = :sessionId
           AND character_id = :characterId
           AND left_at IS NULL
         LIMIT 1
        """)
    Mono<SessionMember> findActiveOwnerInSession(String sessionId, Long characterId);

    Mono<Long> countBySessionIdAndLeftAtIsNull(String sessionId);
}
