package games.hugmesh.bff.repository;

import games.hugmesh.bff.model.SessionMember;
import org.springframework.data.r2dbc.repository.Modifying;
import org.springframework.data.r2dbc.repository.Query;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Instant;

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

    // -------------------------------------------------------------------------
    // Liveness (migration 059) — feeds SessionMemberReaper
    // -------------------------------------------------------------------------

    /**
     * Record a liveness signal for one active member.
     *
     * <p>A targeted UPDATE rather than a read-modify-{@code save()}: the
     * heartbeat runs on the roster poll (every 20 s per seated tab from the
     * host shell, and every 5 s on top of that for shadows-of-the-net, whose
     * game bridge polls too) and on the control hot path, so it must not pay
     * for loading the entity, and it must not clobber a concurrent
     * slot/character write on the same row. A departed member matches nothing
     * and the call is a silent no-op.
     *
     * @param sessionId the session the signal arrived for
     * @param casdoorId the member that produced the signal
     * @param seenAt    the instant the signal was observed
     * @return the number of rows touched — {@code 0} when the caller is not
     *         (or is no longer) an active member of that session
     */
    @Modifying
    @Query("""
        UPDATE session_members
           SET last_seen_at = :seenAt
         WHERE session_id = :sessionId
           AND casdoor_id = :casdoorId
           AND left_at IS NULL
        """)
    Mono<Integer> touchLastSeen(String sessionId, String casdoorId, Instant seenAt);

    /**
     * Record a liveness signal for every active member of one session.
     *
     * <p>Used on resume, because a session-wide pause can silence a whole
     * room at once. In {@code shadows-of-the-net} it definitely does:
     * {@code room_controller} sets {@code tree.paused = true} on
     * SESSION_PAUSED, which halts {@code session_bridge._process} and with it
     * that game's 5 s roster poll. The host shell's own heartbeat keeps
     * running through a pause, so on a current client the room is not
     * actually silent — but the room-wide re-stamp costs one UPDATE and is
     * the difference between "the first sweep after a resume reaps everybody"
     * and "it doesn't" on any client whose game half is the only thing
     * signalling.
     *
     * @param sessionId the session being resumed
     * @param seenAt    the instant to stamp
     * @return the number of member rows touched
     */
    @Modifying
    @Query("""
        UPDATE session_members
           SET last_seen_at = :seenAt
         WHERE session_id = :sessionId
           AND left_at IS NULL
        """)
    Mono<Integer> touchAllActive(String sessionId, Instant seenAt);

    /**
     * Reaper candidates — active members of an OPEN, UNPAUSED session whose
     * last liveness signal predates {@code cutoff}.
     *
     * <p>Paused sessions are excluded in SQL, not in code: a pause can
     * silence a room's game-side signal wholesale (the scene tree stops, and
     * with it any poll the game itself was doing), so every member of one can
     * be trivially "stale" and reaping them would delete the room the moment
     * somebody paused it.
     *
     * <p>{@code COALESCE(last_seen_at, joined_at)} covers rows written by a
     * code path that predates migration 059's column.
     *
     * @param cutoff members whose newest liveness signal is older than this
     *               instant are returned
     * @return the stale members, grouped by session and oldest row first
     */
    @Query("""
        SELECT m.* FROM session_members m
          JOIN game_sessions s ON s.id = m.session_id
         WHERE m.left_at IS NULL
           AND s.closed_at IS NULL
           AND s.paused_at IS NULL
           AND COALESCE(m.last_seen_at, m.joined_at) < :cutoff
         ORDER BY m.session_id, m.id
        """)
    Flux<SessionMember> findStaleActive(Instant cutoff);

    /**
     * Stamp {@code left_at} on one membership row, but only if it is still
     * active.
     *
     * <p>The {@code AND left_at IS NULL} predicate is the reaper's
     * concurrency guard: between the candidate SELECT and this UPDATE the
     * player may have reconnected and cleanly left (or a moderator may have
     * kicked them). A zero row count means somebody else already stamped the
     * row, and the reaper must NOT go on to fire the host-drop / auto-empty
     * close a second time.
     *
     * @param id     the {@code session_members} primary key
     * @param leftAt the departure instant to stamp
     * @return {@code 1} when this call stamped the row, {@code 0} when it was
     *         already stamped by a concurrent leave
     */
    @Modifying
    @Query("""
        UPDATE session_members
           SET left_at = :leftAt
         WHERE id = :id
           AND left_at IS NULL
        """)
    Mono<Integer> stampLeftIfActive(Long id, Instant leftAt);

    /**
     * Stamp {@code left_at} on every still-active member of a session.
     *
     * <p>Called from the close paths. Closing a session used to stamp
     * {@code game_sessions.closed_at} alone, leaving every member row
     * {@code left_at IS NULL} forever — those orphans still satisfied
     * {@code findActiveOwnerOfCharacter} and still counted as "in a session"
     * for presence/history readers. A closed session has no active members by
     * definition, so the ledger should say so.
     *
     * @param sessionId the session being closed
     * @param leftAt    the departure instant to stamp
     * @return the number of member rows stamped
     */
    @Modifying
    @Query("""
        UPDATE session_members
           SET left_at = :leftAt
         WHERE session_id = :sessionId
           AND left_at IS NULL
        """)
    Mono<Integer> stampAllActiveLeft(String sessionId, Instant leftAt);
}
