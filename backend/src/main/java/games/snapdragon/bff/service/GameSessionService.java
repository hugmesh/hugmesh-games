package games.snapdragon.bff.service;

import games.snapdragon.bff.model.GameSession;
import games.snapdragon.bff.model.GameSessionDto;
import games.snapdragon.bff.model.SessionMember;
import games.snapdragon.bff.repository.GameSessionRepository;
import games.snapdragon.bff.repository.SessionMemberRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Instant;

/**
 * Multiplayer game-session lifecycle: create, list, join, leave, close, pause, and
 * resume.
 *
 * <h2>Responsibility</h2>
 *
 * <p>Owns the durable state of multiplayer sessions in Postgres
 * ({@code game_sessions} + {@code session_members} tables).  The hot-path control
 * fan-out for a running session lives in {@link GameStateService}'s per-session
 * {@link SessionSinks} — this service is consulted only on lifecycle transitions
 * (create / join / leave / close / pause / resume), not on every player input.
 *
 * <p>Cross-pod presence is mirrored into Cassandra's {@code session_membership}
 * table by {@code SessionPresenceService} so the admin SSE stream and the
 * session-picker presence dot stay accurate across pods.
 *
 * <h2>Caller-role matrix</h2>
 *
 * <pre>
 *  Operation              │ Player │ Mod │ Admin │ Superadmin
 *  ───────────────────────┼────────┼─────┼───────┼───────────
 *  create                 │  yes   │  -  │   -   │     -
 *  listOpen               │  yes   │  -  │   -   │     -
 *  listAllOpenForOversight │  -    │ yes │  yes  │    yes
 *  get                    │  yes   │ yes │  yes  │    yes
 *  join                   │  yes   │  -  │   -   │     -
 *  leave                  │  yes   │  -  │   -   │     -
 *  close                  │  yes*  │  -  │  yes  │    yes
 *  pauseSession           │  yes*  │  -  │  yes  │    yes
 *  resumeSession          │  yes*  │  -  │  yes  │    yes
 *  pausePlayer            │  yes   │  -  │   -   │     -
 *  resumePlayer           │  yes   │  -  │   -   │     -
 *  authorizeControl       │  yes   │  -  │   -   │     -
 * </pre>
 *
 * <p>* Creator of the session only; the controller boundary enforces this.
 *
 * <h2>Session visibility</h2>
 *
 * <p>{@code game_sessions.visibility} is one of {@code public}, {@code friends},
 * or {@code private}.  The {@link #listOpen} lobby filter applies the caller's
 * friend graph (via {@link RelationshipQuery#friendsOf}) and bidirectional
 * blocklist (via {@link RelationshipQuery#blocklistFor}) so sessions belonging to
 * blocked users are hidden and friends-only sessions are hidden from non-friends.
 *
 * <h2>UUID / Persistable pattern</h2>
 *
 * <p>Session ids are application-assigned (minted via {@link games.snapdragon.bff.util.Crypto#mintUid})
 * rather than DB-generated.  {@link GameSession} implements
 * {@code Persistable<String>} with a transient {@code isNew} flag that is set to
 * {@code true} on {@link GameSession#markNew()} — this prevents Spring Data R2DBC's
 * {@code save()} from issuing an UPDATE (which would affect zero rows and throw
 * {@code TransientDataAccessResourceException}) on a brand-new entity.
 *
 * <h2>Optional Kafka integration</h2>
 *
 * <p>When {@code app.kafka.enabled=true}, session lifecycle events are emitted to
 * the {@code state_audit} Kafka topic via {@link #audit}, and per-session Kafka
 * topics are created/deleted via {@link #topicManager}.  Both are {@link java.util.Optional}
 * so the in-process gameplay path continues to work when Kafka is disabled.
 *
 * @see GameStateService
 * @see SessionSinks
 * @see RelationshipQuery
 * @see ModerationService
 */
@Service
public class GameSessionService {

    private static final Logger log = LoggerFactory.getLogger(GameSessionService.class);

    /** Visibility constant — session visible to all authenticated players. */
    private static final String VIS_PUBLIC  = "public";
    /** Visibility constant — session visible only to mutual friends of the creator. */
    private static final String VIS_FRIENDS = "friends";
    /** Visibility constant — session visible only to the creator themselves. */
    private static final String VIS_PRIVATE = "private";

    private final GameSessionRepository    sessions;
    private final SessionMemberRepository  members;
    private final RelationshipQuery  relationships;
    private final games.snapdragon.bff.service.GameStateService gameState;

    /**
     * In-memory map of {@code sessionId → (casdoorId → PausedPlayer)}.
     * Per-player view pauses are ephemeral by design (see migration 042
     * comment) — they live for the brief window between new-tab launch
     * and new-tab loaded, so a pod restart clears them and the old /
     * new tabs reconcile via their own BroadcastChannel handshake.
     */
    private final java.util.concurrent.ConcurrentHashMap<String,
            java.util.concurrent.ConcurrentHashMap<String, PausedPlayer>> playerPauses =
                new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * Ephemeral record capturing the state of a per-player tab-takeover pause.
     *
     * @param reason              human-readable pause reason (e.g. {@code "tab-takeover"})
     * @param supersededLaunchUid the launch UID of the old tab being displaced
     * @param pausedAt            when the pause was recorded
     */
    private record PausedPlayer(String reason, String supersededLaunchUid, Instant pausedAt) {}

    /**
     * Optional Kafka tap. When {@code app.kafka.enabled=true} the audit
     * publisher emits session-lifecycle events to the {@code state_audit}
     * topic and the topic manager creates / deletes the per-session
     * Kafka topic that cross-pod consumers subscribe to. Both are
     * {@code Optional} so the in-process gameplay path keeps working when
     * Kafka is disabled (the default).
     */
    private final java.util.Optional<games.snapdragon.bff.audit.GameAuditPublisher> audit;
    private final java.util.Optional<games.snapdragon.bff.audit.SessionTopicManager> topicManager;

    public GameSessionService(GameSessionRepository sessions,
                              SessionMemberRepository members,
                              RelationshipQuery relationships,
                              games.snapdragon.bff.service.GameStateService gameState,
                              java.util.Optional<games.snapdragon.bff.audit.GameAuditPublisher> audit,
                              java.util.Optional<games.snapdragon.bff.audit.SessionTopicManager> topicManager) {
        this.sessions      = sessions;
        this.members       = members;
        this.relationships = relationships;
        this.gameState     = gameState;
        this.audit         = audit;
        this.topicManager  = topicManager;
    }

    // -------------------------------------------------------------------------
    // Session creation
    // -------------------------------------------------------------------------

    /**
     * Create a new session for a game; the caller becomes the first member
     * and is flagged as creator. {@code maxPlayers} is clamped to [1, 16].
     * {@code visibility} defaults to {@code public} when null/blank.
     *
     * <p>Reactive marble diagram:
     * <pre>
     *   sessions.save(newSession)              ← persist session row (markNew() set)
     *     → flatMap(saved → members.save(firstMember).thenReturn(saved))
     *                                          ← add creator as first member
     *     → doOnNext(Kafka tap + audit emit)   ← best-effort, non-blocking
     *     → flatMap(toDtoWithMembers)          ← assemble DTO with member list
     * </pre>
     *
     * @param creatorCasdoorId the Casdoor subject of the session creator
     * @param gameSlug         the game this session belongs to
     * @param name             session display name; must be 1–120 characters
     * @param maxPlayers       desired capacity, clamped to [1, 16]
     * @param visibility       {@code public}, {@code friends}, or {@code private};
     *                         defaults to {@code public} when null/blank/unrecognised
     * @param characterId      the creator's character id for this session
     * @param slotIndex        optional multiplayer slot index for the creator
     * @return a {@link Mono} emitting the new {@link GameSessionDto} including the
     *         creator as a member, or an error {@link Mono} with HTTP 400 when the
     *         name is invalid
     */
    public Mono<GameSessionDto> create(
            String creatorCasdoorId,
            String gameSlug,
            String name,
            int    maxPlayers,
            String visibility,
            Long   characterId,
            Integer slotIndex) {

        // Clamp maxPlayers to a sensible range; single-player sessions (cap=1) are valid.
        int cap = Math.max(1, Math.min(16, maxPlayers));
        String trimmed = name == null ? "" : name.trim();
        if (trimmed.isEmpty() || trimmed.length() > 120) {
            return Mono.error(new ResponseStatusException(HttpStatus.BAD_REQUEST, "session name 1..120 chars"));
        }
        String vis = normaliseVisibility(visibility);
        GameSession s = new GameSession();
        // Session id is what threads through the iframe URL
        // (?session_id=…) and lands on the wire as the per-session
        // sink key. Mint via the central Crypto utility so the
        // identifier shape stays uniform with launch UIDs +
        // session tokens — base64url(SHA3-256(entropy)).
        s.setId(games.snapdragon.bff.util.Crypto.mintUid());
        s.setName(trimmed);
        s.setGameSlug(gameSlug);
        s.setCreatorCasdoorId(creatorCasdoorId);
        s.setMaxPlayers(cap);
        s.setVisibility(vis);
        s.setCreatedAt(Instant.now());
        // markNew() sets the Persistable.isNew flag so R2DBC issues INSERT, not UPDATE.
        s.markNew();

        SessionMember firstMember = newMember(s.getId(), creatorCasdoorId, characterId, slotIndex, true);

        return sessions.save(s)
                .flatMap(saved -> members.save(firstMember).thenReturn(saved))
                .doOnNext(saved -> {
                    log.info("Session created: {} ({}, vis={}) by {}",
                            saved.getId(), saved.getName(), vis, creatorCasdoorId);
                    // Kafka tap — create the per-session topic + emit
                    // a lifecycle event. Best-effort; the in-process
                    // SSE sinks remain the gameplay-driving path.
                    topicManager.ifPresent(tm -> tm.createForSession(saved.getId()));
                    audit.ifPresent(a -> a.emit(
                            saved.getGameSlug(),
                            saved.getId(),
                            creatorCasdoorId,
                            "session.created",
                            java.util.Map.of(
                                    "visibility", vis,
                                    "max_players", cap,
                                    "name", saved.getName())));
                })
                .flatMap(this::toDtoWithMembers);
    }

    // -------------------------------------------------------------------------
    // Session listing
    // -------------------------------------------------------------------------

    /**
     * Lobby list scoped to the caller's friend graph + blocklist.
     *
     * <p>Applies three visibility filters in code after loading all open sessions
     * from Postgres:
     * <ol>
     *   <li><b>Blocked</b> — sessions created by a user the caller has blocked (or
     *       who has blocked the caller) are dropped entirely.</li>
     *   <li><b>Private</b> — only the creator sees their own private sessions.</li>
     *   <li><b>Friends</b> — only the creator and their mutual friends see
     *       friends-only sessions.</li>
     * </ol>
     *
     * <p>Anonymous callers ({@code callerCasdoorId} null/blank) see only public sessions.
     *
     * <p>Reactive marble diagram:
     * <pre>
     *   Mono.zip(
     *     relationships.friendsOf(callerCasdoorId),    ← mutual friend set
     *     relationships.blocklistFor(callerCasdoorId)  ← bidirectional block set
     *   )
     *     → flatMapMany(t →
     *         source (all open sessions or slug-filtered)
     *           → flatMap(visibility + block filter)   ← drop invisible sessions
     *           → flatMap(toDtoWithMembersFiltered)    ← drop blocked members from DTO
     *       )
     * </pre>
     *
     * @param gameSlug         optional game slug filter; pass null to list across all games
     * @param callerCasdoorId  the caller's Casdoor subject; null/blank → anonymous
     * @return a {@link Flux} of {@link GameSessionDto} records visible to the caller
     */
    public Flux<GameSessionDto> listOpen(String gameSlug, String callerCasdoorId) {
        Flux<GameSession> source = (gameSlug == null || gameSlug.isBlank())
                ? sessions.findAllOpen()
                : sessions.findOpenByGameSlug(gameSlug);
        if (callerCasdoorId == null || callerCasdoorId.isBlank()) {
            // Anonymous — show only public sessions.
            return source.filter(s -> VIS_PUBLIC.equals(s.getVisibility()))
                    .flatMap(this::toDtoWithMembers);
        }
        Mono<java.util.Set<String>> friends = relationships.friendsOf(callerCasdoorId);
        Mono<java.util.Set<String>> blocked = relationships.blocklistFor(callerCasdoorId);
        return Mono.zip(friends, blocked).flatMapMany(t -> {
            java.util.Set<String> friendIds  = t.getT1();
            java.util.Set<String> blockedIds = t.getT2();
            return source.flatMap(s -> {
                // Drop sessions created by a blocked user entirely.
                if (blockedIds.contains(s.getCreatorCasdoorId())) return Mono.<GameSession>empty();
                // Private sessions are visible only to the creator.
                if (VIS_PRIVATE.equals(s.getVisibility())) {
                    if (!callerCasdoorId.equals(s.getCreatorCasdoorId())) return Mono.<GameSession>empty();
                }
                // Friends-only sessions require mutual friendship with the creator.
                if (VIS_FRIENDS.equals(s.getVisibility())) {
                    if (!callerCasdoorId.equals(s.getCreatorCasdoorId())
                            && !friendIds.contains(s.getCreatorCasdoorId())) {
                        return Mono.<GameSession>empty();
                    }
                }
                return Mono.just(s);
            }).flatMap(toDtoWithMembersFiltered(blockedIds));
        });
    }

    /**
     * Unfiltered listing — admin/mod/publisher-only call site.
     *
     * <p>Returns all open sessions for the given game (or all games) without
     * applying any friend/block visibility filters.  Intended for moderation
     * dashboards and session oversight views where the moderator must be able to
     * see every session regardless of relationship state.
     *
     * @param gameSlug optional game slug filter; pass null to list across all games
     * @return a {@link Flux} of all open {@link GameSessionDto} records
     */
    public Flux<GameSessionDto> listAllOpenForOversight(String gameSlug) {
        Flux<GameSession> source = (gameSlug == null || gameSlug.isBlank())
                ? sessions.findAllOpen()
                : sessions.findOpenByGameSlug(gameSlug);
        return source.flatMap(this::toDtoWithMembers);
    }

    // -------------------------------------------------------------------------
    // Session retrieval
    // -------------------------------------------------------------------------

    /**
     * Fetch a single session by id, enriched with active member list.
     *
     * @param sessionId the session id to look up
     * @return a {@link Mono} emitting the {@link GameSessionDto}, or HTTP 404 when
     *         the session does not exist
     */
    public Mono<GameSessionDto> get(String sessionId) {
        return sessions.findById(sessionId)
                .switchIfEmpty(Mono.error(new ResponseStatusException(HttpStatus.NOT_FOUND, "session not found")))
                .flatMap(this::toDtoWithMembers);
    }

    // -------------------------------------------------------------------------
    // Join / leave
    // -------------------------------------------------------------------------

    /**
     * Add a player to a session. Idempotent on (sessionId, casdoorId): if
     * the caller is already an active member, the existing row is returned
     * and the supplied {@code slotIndex} updates the row when set.
     *
     * <p>Reactive marble diagram:
     * <pre>
     *   sessions.findById(sessionId)                    ← load session
     *     → flatMap(s →
     *         members.findActive(sessionId, casdoorId)  ← existing member?
     *           → flatMap(existing → update slot + save) ← idempotent re-join
     *           → switchIfEmpty(
     *               members.countBySessionIdAndLeftAtIsNull ← capacity check
     *                 → flatMap(count → if full: 409, else save newMember)
     *             )
     *           → onErrorMap(DuplicateKeyException → 409 slot-claimed)
     *           → thenReturn(s)
     *       )
     *     → flatMap(toDtoWithMembers)                   ← emit updated DTO
     * </pre>
     *
     * @param sessionId   the session to join
     * @param casdoorId   the joining player's Casdoor subject
     * @param characterId the character the player is using
     * @param slotIndex   optional multiplayer slot index to claim
     * @return a {@link Mono} emitting the updated {@link GameSessionDto}, or HTTP
     *         404 if the session does not exist, HTTP 410 if it is closed, or HTTP
     *         409 if it is full or the slot is already claimed
     */
    public Mono<GameSessionDto> join(
            String sessionId,
            String casdoorId,
            Long   characterId,
            Integer slotIndex) {

        return sessions.findById(sessionId)
                .switchIfEmpty(Mono.error(new ResponseStatusException(HttpStatus.NOT_FOUND, "session not found")))
                .flatMap(s -> {
                    // Reject joins to already-closed sessions.
                    if (s.getClosedAt() != null) {
                        return Mono.error(new ResponseStatusException(HttpStatus.GONE, "session closed"));
                    }
                    return members.findActive(sessionId, casdoorId)
                            .flatMap(existing -> {
                                // Already a member — idempotent; update slot/character if provided.
                                if (slotIndex != null) existing.setSlotIndex(slotIndex);
                                if (characterId != null) existing.setCharacterId(characterId);
                                return members.save(existing);
                            })
                            .switchIfEmpty(members.countBySessionIdAndLeftAtIsNull(sessionId)
                                    .flatMap(count -> {
                                        // Enforce the session's player cap before inserting a new member.
                                        if (count >= s.getMaxPlayers()) {
                                            return Mono.error(new ResponseStatusException(HttpStatus.CONFLICT, "session full"));
                                        }
                                        return members.save(newMember(sessionId, casdoorId, characterId, slotIndex, false));
                                    }))
                            // Two simultaneous joins racing on the same slot produce a DB unique violation.
                            .onErrorMap(DuplicateKeyException.class,
                                    e -> new ResponseStatusException(HttpStatus.CONFLICT, "slot already claimed"))
                            .thenReturn(s);
                })
                .flatMap(this::toDtoWithMembers);
    }

    /**
     * Mark the caller as having left. Append-only; the session_members row
     * stays for history.
     *
     * <p>Two close paths fire from here:
     *
     * <ul>
     *   <li>If the caller was the last active member, auto-close with
     *       reason {@code auto-empty} (existing behaviour).</li>
     *   <li>If the caller was the session creator, close immediately
     *       with reason {@code host-disconnected} so every remaining
     *       player's WASM iframe collapses via the SESSION_CLOSED SSE
     *       event. Mirrors what the user asked for: "if the session
     *       host drops, the entire session should quit and all player
     *       windows should close".</li>
     * </ul>
     *
     * <p>Reactive marble diagram:
     * <pre>
     *   members.findActive(sessionId, casdoorId)        ← look up membership row
     *     → flatMap(existing →
     *         stamp leftAt + save                        ← mark left (row kept for history)
     *         → audit emit (session.left)
     *         → thenReturn(wasCreator flag)
     *       )
     *     → flatMap(wasCreator →
     *         if creator: closeOnHostDrop(sessionId)    ← host-drop fast path
     *       )
     *     → then(members.countBySessionIdAndLeftAtIsNull) ← remaining after leave
     *     → flatMap(remaining →
     *         if 0: load session + stamp closedAt + save ← auto-empty close
     *               + emitSessionControl(SESSION_CLOSED) + audit + Kafka cleanup
     *       )
     * </pre>
     *
     * @param sessionId the session being left
     * @param casdoorId the leaving player's Casdoor subject
     * @return a {@link Mono} that completes when all side-effects are done
     */
    public Mono<Void> leave(String sessionId, String casdoorId) {
        return members.findActive(sessionId, casdoorId)
                .flatMap(existing -> {
                    boolean wasCreator = existing.isCreator();
                    existing.setLeftAt(Instant.now());
                    return members.save(existing).then()
                            .doOnSuccess(v -> audit.ifPresent(a -> a.emit(
                                    null, sessionId, casdoorId, "session.left",
                                    java.util.Map.of("wasCreator", wasCreator))))
                            .thenReturn(wasCreator);
                })
                .flatMap(wasCreator -> {
                    if (wasCreator) {
                        // Host-drop path — fan SESSION_CLOSED to every
                        // remaining player before the auto-empty check
                        // (the host's leave often empties the session
                        // anyway, but emitting the right reason matters).
                        return closeOnHostDrop(sessionId);
                    }
                    return Mono.<Void>empty();
                })
                .then(members.countBySessionIdAndLeftAtIsNull(sessionId))
                .flatMap(remaining -> {
                    // If there are still active members, the session stays open.
                    if (remaining > 0) return Mono.empty();
                    return sessions.findById(sessionId)
                            .flatMap(s -> {
                                // Guard against double-close (host-drop may have beaten us here).
                                if (s.getClosedAt() != null) return Mono.empty();
                                s.setClosedAt(Instant.now());
                                log.info("Session auto-closed (no active members): {}", sessionId);
                                return sessions.save(s).then()
                                        .doOnSuccess(v -> {
                                            // Notify all subscribers and clean up Kafka topic.
                                            gameState.emitSessionControl(
                                                    new games.snapdragon.bff.model.SessionControlEvent(
                                                            s.getId(),
                                                            games.snapdragon.bff.model.SessionControlEvent.Action.SESSION_CLOSED,
                                                            null, null, "last-member-left", Instant.now()));
                                            audit.ifPresent(a -> a.emit(
                                                    s.getGameSlug(), sessionId, null,
                                                    "session.closed",
                                                    java.util.Map.of("reason", "auto-empty")));
                                            topicManager.ifPresent(tm ->
                                                    tm.deleteForSession(sessionId));
                                            // Drop ephemeral per-player pause state for this session.
                                            playerPauses.remove(sessionId);
                                        });
                            });
                });
    }

    // -------------------------------------------------------------------------
    // Force-close
    // -------------------------------------------------------------------------

    /**
     * Force-close a session — only the creator or an admin may call this.
     * The caller's identity is checked at the controller boundary; the
     * service trusts the call site.
     *
     * <p>Emits {@link games.snapdragon.bff.model.SessionControlEvent.Action#SESSION_CLOSED}
     * with reason {@code force-closed} so every subscriber's host shell collapses
     * the game iframe.  Idempotent on an already-closed session (returns empty).
     *
     * @param sessionId the session to force-close
     * @return a {@link Mono} that completes when the close is persisted and the
     *         SSE event is emitted, or HTTP 404 when the session does not exist
     */
    public Mono<Void> close(String sessionId) {
        return sessions.findById(sessionId)
                .switchIfEmpty(Mono.error(new ResponseStatusException(HttpStatus.NOT_FOUND)))
                .flatMap(s -> {
                    // Idempotent: if already closed, nothing to do.
                    if (s.getClosedAt() != null) return Mono.empty();
                    s.setClosedAt(Instant.now());
                    return sessions.save(s).then()
                            .doOnSuccess(v -> {
                                // Fan out the close event before cleaning up Kafka.
                                gameState.emitSessionControl(
                                        new games.snapdragon.bff.model.SessionControlEvent(
                                                s.getId(),
                                                games.snapdragon.bff.model.SessionControlEvent.Action.SESSION_CLOSED,
                                                null, null, "force-closed", Instant.now()));
                                audit.ifPresent(a -> a.emit(
                                        s.getGameSlug(), sessionId, null,
                                        "session.closed",
                                        java.util.Map.of("reason", "force-close")));
                                topicManager.ifPresent(tm ->
                                        tm.deleteForSession(sessionId));
                                playerPauses.remove(sessionId);
                            });
                });
    }

    // -------------------------------------------------------------------------
    // Session-wide pause / resume
    // -------------------------------------------------------------------------

    /**
     * Session-wide pause — freeze every player's WASM iframe inside the
     * session. Authority check (creator / admin / RPG-script PAUSE
     * kind) happens at the controller boundary; the service trusts the
     * call site. Idempotent — re-pausing an already-paused session is a
     * no-op with the SSE event re-fired so a late subscriber catches it.
     *
     * @param sessionId    the session to pause
     * @param byCasdoorId  the Casdoor subject of the actor initiating the pause
     * @param reason       human-readable pause reason; defaults to {@code "manual"} when null
     * @return a {@link Mono} emitting the updated {@link GameSessionDto}, or HTTP 404/410
     *         when the session does not exist or is already closed
     */
    public Mono<GameSessionDto> pauseSession(String sessionId, String byCasdoorId, String reason) {
        return sessions.findById(sessionId)
                .switchIfEmpty(Mono.error(new ResponseStatusException(HttpStatus.NOT_FOUND)))
                .flatMap(s -> {
                    if (s.getClosedAt() != null) {
                        return Mono.error(new ResponseStatusException(HttpStatus.GONE, "session closed"));
                    }
                    Instant now = Instant.now();
                    // Only stamp the initial pause; idempotent re-pause leaves existing stamp intact.
                    if (s.getPausedAt() == null) {
                        s.setPausedAt(now);
                        s.setPausedByCasdoorId(byCasdoorId);
                        s.setPauseReason(reason != null ? reason : "manual");
                    }
                    return sessions.save(s);
                })
                .doOnSuccess(s -> {
                    // Emit the pause event after the DB write succeeds.
                    gameState.emitSessionControl(new games.snapdragon.bff.model.SessionControlEvent(
                            s.getId(),
                            games.snapdragon.bff.model.SessionControlEvent.Action.SESSION_PAUSED,
                            null, null, s.getPauseReason(), s.getPausedAt()));
                    audit.ifPresent(a -> a.emit(
                            s.getGameSlug(), s.getId(), byCasdoorId,
                            "session.paused",
                            java.util.Map.of("reason", s.getPauseReason() == null ? "manual" : s.getPauseReason())));
                })
                .flatMap(this::toDtoWithMembers);
    }

    /**
     * Resume a session-wide pause. Only meaningful when {@code paused_at} is set;
     * clearing the pause fields and emitting {@code SESSION_RESUMED} lets every
     * subscriber's WASM iframe re-acquire input focus.
     *
     * @param sessionId   the session to resume
     * @param byCasdoorId the Casdoor subject of the actor resuming the session
     * @return a {@link Mono} emitting the updated {@link GameSessionDto}, or HTTP 404/410
     */
    public Mono<GameSessionDto> resumeSession(String sessionId, String byCasdoorId) {
        return sessions.findById(sessionId)
                .switchIfEmpty(Mono.error(new ResponseStatusException(HttpStatus.NOT_FOUND)))
                .flatMap(s -> {
                    if (s.getClosedAt() != null) {
                        return Mono.error(new ResponseStatusException(HttpStatus.GONE, "session closed"));
                    }
                    // Clear all pause fields so the session row reflects "running" state.
                    s.setPausedAt(null);
                    s.setPausedByCasdoorId(null);
                    s.setPauseReason(null);
                    return sessions.save(s);
                })
                .doOnSuccess(s -> {
                    gameState.emitSessionControl(new games.snapdragon.bff.model.SessionControlEvent(
                            s.getId(),
                            games.snapdragon.bff.model.SessionControlEvent.Action.SESSION_RESUMED,
                            null, null, null, Instant.now()));
                    audit.ifPresent(a -> a.emit(
                            s.getGameSlug(), s.getId(), byCasdoorId,
                            "session.resumed", java.util.Map.of()));
                })
                .flatMap(this::toDtoWithMembers);
    }

    // -------------------------------------------------------------------------
    // Per-player pause / resume (tab-takeover)
    // -------------------------------------------------------------------------

    /**
     * Tab-takeover pause for one player. Triggered when the player POSTs
     * {@code /launch} from a new tab — the old tab's WASM iframe must
     * release input focus while the new tab loads its streams + assets.
     * Ephemeral (in-memory) by design; cleared on resumePlayer or on
     * session close.
     *
     * @param sessionId           the session containing the player
     * @param casdoorId           the player being paused
     * @param reason              human-readable reason; defaults to {@code "tab-takeover"}
     * @param supersededLaunchUid the launch UID of the old tab being displaced
     * @return an always-empty {@link Mono} (fire-and-forget side-effect)
     */
    public Mono<Void> pausePlayer(String sessionId, String casdoorId, String reason, String supersededLaunchUid) {
        if (sessionId == null || casdoorId == null) return Mono.empty();
        // Record the pause in the ephemeral per-session map.
        playerPauses.computeIfAbsent(sessionId, id -> new java.util.concurrent.ConcurrentHashMap<>())
                    .put(casdoorId, new PausedPlayer(
                            reason == null ? "tab-takeover" : reason,
                            supersededLaunchUid,
                            Instant.now()));
        // Emit PLAYER_PAUSED so the old tab's BroadcastChannel listener can begin teardown.
        gameState.emitSessionControl(new games.snapdragon.bff.model.SessionControlEvent(
                sessionId,
                games.snapdragon.bff.model.SessionControlEvent.Action.PLAYER_PAUSED,
                casdoorId,
                supersededLaunchUid,
                reason == null ? "tab-takeover" : reason,
                Instant.now()));
        return Mono.empty();
    }

    /**
     * Per-player resume — emitted by the NEW tab once it has finished
     * loading streams + assets and is ready to drive input. Drops the
     * in-memory entry and emits PLAYER_RESUMED so the old tab can fully
     * tear down.
     *
     * @param sessionId the session containing the player
     * @param casdoorId the player resuming input focus
     * @return an always-empty {@link Mono} (fire-and-forget side-effect)
     */
    public Mono<Void> resumePlayer(String sessionId, String casdoorId) {
        if (sessionId == null || casdoorId == null) return Mono.empty();
        // Remove the ephemeral pause record for this player.
        var perSession = playerPauses.get(sessionId);
        if (perSession != null) perSession.remove(casdoorId);
        gameState.emitSessionControl(new games.snapdragon.bff.model.SessionControlEvent(
                sessionId,
                games.snapdragon.bff.model.SessionControlEvent.Action.PLAYER_RESUMED,
                casdoorId, null, null, Instant.now()));
        return Mono.empty();
    }

    // -------------------------------------------------------------------------
    // Host-drop handling
    // -------------------------------------------------------------------------

    /**
     * Host-drop handler. Called when the session creator's membership
     * row gets a {@code left_at} stamp — the session is force-closed and
     * a SESSION_CLOSED event is emitted with reason
     * {@code host-disconnected} so every other player's host shell can
     * collapse the game iframe and surface a toast.
     *
     * <p>Idempotent: if the session is already closed, the filter short-circuits
     * and the downstream does nothing.
     *
     * @param sessionId the session whose creator just left
     * @return a {@link Mono} that completes when the close is persisted and the
     *         SSE event is emitted
     */
    public Mono<Void> closeOnHostDrop(String sessionId) {
        return sessions.findById(sessionId)
                // Skip if already closed (leave path may have raced ahead).
                .filter(s -> s.getClosedAt() == null)
                .flatMap(s -> {
                    s.setClosedAt(Instant.now());
                    return sessions.save(s).then()
                            .doOnSuccess(v -> {
                                gameState.emitSessionControl(new games.snapdragon.bff.model.SessionControlEvent(
                                        s.getId(),
                                        games.snapdragon.bff.model.SessionControlEvent.Action.SESSION_CLOSED,
                                        null, null, "host-disconnected", Instant.now()));
                                audit.ifPresent(a -> a.emit(
                                        s.getGameSlug(), s.getId(), s.getCreatorCasdoorId(),
                                        "session.closed",
                                        java.util.Map.of("reason", "host-disconnected")));
                                topicManager.ifPresent(tm -> tm.deleteForSession(s.getId()));
                                playerPauses.remove(s.getId());
                            });
                })
                .then();
    }

    // -------------------------------------------------------------------------
    // Membership / control authorization
    // -------------------------------------------------------------------------

    /**
     * Active membership snapshot — used by ControlController's auth check.
     *
     * @param sessionId the session to look up membership in
     * @param casdoorId the player's Casdoor subject
     * @return a {@link Mono} emitting the {@link SessionMember} row, or empty when
     *         the player is not an active member
     */
    public Mono<SessionMember> activeMembership(String sessionId, String casdoorId) {
        return members.findActive(sessionId, casdoorId);
    }

    /**
     * Authorize a control write: the caller (JWT subject) must be an active
     * member who currently owns {@code characterId}. Returns the membership
     * row on success; emits 403 on miss.
     *
     * <p>Delegates to {@link #authorizeControl(String, Long, String)} with a
     * null session id — uses the unscoped character-owner lookup, suitable when
     * no {@code X-Session-Id} header is available.
     *
     * @param casdoorId   the caller's Casdoor subject
     * @param characterId the character being controlled
     * @return a {@link Mono} emitting the {@link SessionMember} on success, or HTTP 403
     */
    public Mono<SessionMember> authorizeControl(String casdoorId, Long characterId) {
        return authorizeControl(casdoorId, characterId, null);
    }

    /**
     * Session-scoped overload — when a control arrives with
     * {@code X-Session-Id}, scope the membership lookup to that
     * session. Two players each using {@code character_id=1} in
     * different sessions race on the unscoped query; the loser hits
     * "character not owned by caller" on every move. The controller
     * passes its X-Session-Id header through here.
     *
     * @param casdoorId      the caller's Casdoor subject
     * @param characterId    the character being controlled
     * @param sessionIdOpt   the session id from the {@code X-Session-Id} header,
     *                       or null/blank to use the unscoped lookup
     * @return a {@link Mono} emitting the {@link SessionMember} on success,
     *         or HTTP 403 when the caller does not own the character in this session
     */
    public Mono<SessionMember> authorizeControl(String casdoorId, Long characterId, String sessionIdOpt) {
        // Prefer the session-scoped lookup to avoid cross-session character-id races.
        Mono<SessionMember> lookup = (sessionIdOpt != null && !sessionIdOpt.isBlank())
                ? members.findActiveOwnerInSession(sessionIdOpt, characterId)
                : members.findActiveOwnerOfCharacter(characterId);
        return lookup
                .switchIfEmpty(Mono.error(new ResponseStatusException(HttpStatus.FORBIDDEN, "no active session member owns this character")))
                .flatMap(m -> {
                    // Confirm the JWT subject matches the membership row's owner.
                    if (!casdoorId.equals(m.getCasdoorId())) {
                        return Mono.error(new ResponseStatusException(HttpStatus.FORBIDDEN, "character not owned by caller"));
                    }
                    return Mono.just(m);
                });
    }

    // -------------------------------------------------------------------------
    // Private helpers
    // -------------------------------------------------------------------------

    /**
     * Loads active members for {@code s} and assembles a {@link GameSessionDto}.
     * Used on every write path that must return the updated session state.
     *
     * @param s the session row to project
     * @return a {@link Mono} emitting the assembled DTO
     */
    private Mono<GameSessionDto> toDtoWithMembers(GameSession s) {
        return members.findActiveBySession(s.getId())
                .map(GameSessionDto.MemberDto::from)
                .collectList()
                .map(list -> GameSessionDto.from(s, list.size(), list));
    }

    /**
     * Variant of {@link #toDtoWithMembers} that additionally filters out blocked
     * users from the member list in the returned DTO.  Used by {@link #listOpen}
     * so a blocked user's avatar does not appear in the session card even if they
     * are a member of a visible session.
     *
     * @param blockedIds the set of Casdoor ids to suppress from member lists
     * @return a function mapping a session to a filtered DTO {@link Mono}
     */
    private java.util.function.Function<GameSession, Mono<GameSessionDto>> toDtoWithMembersFiltered(
            java.util.Set<String> blockedIds) {
        return s -> members.findActiveBySession(s.getId())
                // Drop members the caller has blocked (or who have blocked the caller).
                .filter(m -> !blockedIds.contains(m.getCasdoorId()))
                .map(GameSessionDto.MemberDto::from)
                .collectList()
                .map(list -> GameSessionDto.from(s, list.size(), list));
    }

    /**
     * Normalise a visibility string to one of the three known values.
     * Unknown or null input defaults to {@code "public"} so a misconfigured
     * client does not accidentally create a private or friends-only session.
     *
     * @param v the raw visibility string from the request
     * @return one of {@code "public"}, {@code "friends"}, or {@code "private"}
     */
    private static String normaliseVisibility(String v) {
        if (v == null) return VIS_PUBLIC;
        String trimmed = v.trim().toLowerCase();
        return switch (trimmed) {
            case VIS_FRIENDS, VIS_PRIVATE -> trimmed;
            default -> VIS_PUBLIC;
        };
    }

    /**
     * Build a new {@link SessionMember} row for the given player and session.
     * Does not persist — callers are responsible for saving via the repository.
     *
     * @param sessionId   the session to join
     * @param casdoorId   the joining player's Casdoor subject
     * @param characterId the character the player will use
     * @param slotIndex   optional slot index for multiplayer layout
     * @param creator     {@code true} when this is the session creator's membership row
     * @return an unsaved {@link SessionMember} entity ready for {@code members.save()}
     */
    private static SessionMember newMember(
            String sessionId, String casdoorId, Long characterId, Integer slotIndex, boolean creator) {
        SessionMember m = new SessionMember();
        m.setSessionId(sessionId);
        m.setCasdoorId(casdoorId);
        m.setCharacterId(characterId);
        m.setSlotIndex(slotIndex);
        m.setCreator(creator);
        m.setJoinedAt(Instant.now());
        return m;
    }
}
