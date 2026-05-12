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
 * Multiplayer session lifecycle: create / list / join / leave / close.
 *
 * <p>Postgres ({@code game_sessions} + {@code session_members}) is the
 * source of truth for membership and survives restarts. Hot-path control
 * fan-out lives in {@link GameStateService}'s per-session sinks; this
 * service is consulted on session-list/create/join/leave only — never on
 * every control event.
 *
 * <p>Cross-pod presence is mirrored into Cassandra's
 * {@code session_membership} table by {@link SessionPresenceService} so the
 * admin SSE stream and the session-picker presence dot stay correct
 * across pods.
 */
@Service
public class GameSessionService {

    private static final Logger log = LoggerFactory.getLogger(GameSessionService.class);

    private static final String VIS_PUBLIC  = "public";
    private static final String VIS_FRIENDS = "friends";
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

    /**
     * Create a new session for a game; the caller becomes the first member
     * and is flagged as creator. {@code maxPlayers} is clamped to [1, 16].
     * {@code visibility} defaults to {@code public} when null/blank.
     */
    public Mono<GameSessionDto> create(
            String creatorCasdoorId,
            String gameSlug,
            String name,
            int    maxPlayers,
            String visibility,
            Long   characterId,
            Integer slotIndex) {

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

    /** Lobby list scoped to the caller's friend graph + blocklist. */
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
                if (blockedIds.contains(s.getCreatorCasdoorId())) return Mono.<GameSession>empty();
                if (VIS_PRIVATE.equals(s.getVisibility())) {
                    if (!callerCasdoorId.equals(s.getCreatorCasdoorId())) return Mono.<GameSession>empty();
                }
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

    /** Unfiltered listing — admin/mod/publisher-only call site. */
    public Flux<GameSessionDto> listAllOpenForOversight(String gameSlug) {
        Flux<GameSession> source = (gameSlug == null || gameSlug.isBlank())
                ? sessions.findAllOpen()
                : sessions.findOpenByGameSlug(gameSlug);
        return source.flatMap(this::toDtoWithMembers);
    }

    private static String normaliseVisibility(String v) {
        if (v == null) return VIS_PUBLIC;
        String trimmed = v.trim().toLowerCase();
        return switch (trimmed) {
            case VIS_FRIENDS, VIS_PRIVATE -> trimmed;
            default -> VIS_PUBLIC;
        };
    }

    private java.util.function.Function<GameSession, Mono<GameSessionDto>> toDtoWithMembersFiltered(
            java.util.Set<String> blockedIds) {
        return s -> members.findActiveBySession(s.getId())
                .filter(m -> !blockedIds.contains(m.getCasdoorId()))
                .map(GameSessionDto.MemberDto::from)
                .collectList()
                .map(list -> GameSessionDto.from(s, list.size(), list));
    }

    public Mono<GameSessionDto> get(String sessionId) {
        return sessions.findById(sessionId)
                .switchIfEmpty(Mono.error(new ResponseStatusException(HttpStatus.NOT_FOUND, "session not found")))
                .flatMap(this::toDtoWithMembers);
    }

    /**
     * Add a player to a session. Idempotent on (sessionId, casdoorId): if
     * the caller is already an active member, the existing row is returned
     * and the supplied {@code slotIndex} updates the row when set.
     */
    public Mono<GameSessionDto> join(
            String sessionId,
            String casdoorId,
            Long   characterId,
            Integer slotIndex) {

        return sessions.findById(sessionId)
                .switchIfEmpty(Mono.error(new ResponseStatusException(HttpStatus.NOT_FOUND, "session not found")))
                .flatMap(s -> {
                    if (s.getClosedAt() != null) {
                        return Mono.error(new ResponseStatusException(HttpStatus.GONE, "session closed"));
                    }
                    return members.findActive(sessionId, casdoorId)
                            .flatMap(existing -> {
                                if (slotIndex != null) existing.setSlotIndex(slotIndex);
                                if (characterId != null) existing.setCharacterId(characterId);
                                return members.save(existing);
                            })
                            .switchIfEmpty(members.countBySessionIdAndLeftAtIsNull(sessionId)
                                    .flatMap(count -> {
                                        if (count >= s.getMaxPlayers()) {
                                            return Mono.error(new ResponseStatusException(HttpStatus.CONFLICT, "session full"));
                                        }
                                        return members.save(newMember(sessionId, casdoorId, characterId, slotIndex, false));
                                    }))
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
                    if (remaining > 0) return Mono.empty();
                    return sessions.findById(sessionId)
                            .flatMap(s -> {
                                if (s.getClosedAt() != null) return Mono.empty();
                                s.setClosedAt(Instant.now());
                                log.info("Session auto-closed (no active members): {}", sessionId);
                                return sessions.save(s).then()
                                        .doOnSuccess(v -> {
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
                                            playerPauses.remove(sessionId);
                                        });
                            });
                });
    }

    /**
     * Force-close a session — only the creator or an admin may call this.
     * The caller's identity is checked at the controller boundary; the
     * service trusts the call site.
     */
    public Mono<Void> close(String sessionId) {
        return sessions.findById(sessionId)
                .switchIfEmpty(Mono.error(new ResponseStatusException(HttpStatus.NOT_FOUND)))
                .flatMap(s -> {
                    if (s.getClosedAt() != null) return Mono.empty();
                    s.setClosedAt(Instant.now());
                    return sessions.save(s).then()
                            .doOnSuccess(v -> {
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

    /**
     * Session-wide pause — freeze every player's WASM iframe inside the
     * session. Authority check (creator / admin / RPG-script PAUSE
     * kind) happens at the controller boundary; the service trusts the
     * call site. Idempotent — re-pausing an already-paused session is a
     * no-op with the SSE event re-fired so a late subscriber catches it.
     */
    public Mono<GameSessionDto> pauseSession(String sessionId, String byCasdoorId, String reason) {
        return sessions.findById(sessionId)
                .switchIfEmpty(Mono.error(new ResponseStatusException(HttpStatus.NOT_FOUND)))
                .flatMap(s -> {
                    if (s.getClosedAt() != null) {
                        return Mono.error(new ResponseStatusException(HttpStatus.GONE, "session closed"));
                    }
                    Instant now = Instant.now();
                    if (s.getPausedAt() == null) {
                        s.setPausedAt(now);
                        s.setPausedByCasdoorId(byCasdoorId);
                        s.setPauseReason(reason != null ? reason : "manual");
                    }
                    return sessions.save(s);
                })
                .doOnSuccess(s -> {
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

    /** Resume a session-wide pause. Only meaningful when paused_at is set. */
    public Mono<GameSessionDto> resumeSession(String sessionId, String byCasdoorId) {
        return sessions.findById(sessionId)
                .switchIfEmpty(Mono.error(new ResponseStatusException(HttpStatus.NOT_FOUND)))
                .flatMap(s -> {
                    if (s.getClosedAt() != null) {
                        return Mono.error(new ResponseStatusException(HttpStatus.GONE, "session closed"));
                    }
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

    /**
     * Tab-takeover pause for one player. Triggered when the player POSTs
     * {@code /launch} from a new tab — the old tab's WASM iframe must
     * release input focus while the new tab loads its streams + assets.
     * Ephemeral (in-memory) by design; cleared on resumePlayer or on
     * session close.
     */
    public Mono<Void> pausePlayer(String sessionId, String casdoorId, String reason, String supersededLaunchUid) {
        if (sessionId == null || casdoorId == null) return Mono.empty();
        playerPauses.computeIfAbsent(sessionId, id -> new java.util.concurrent.ConcurrentHashMap<>())
                    .put(casdoorId, new PausedPlayer(
                            reason == null ? "tab-takeover" : reason,
                            supersededLaunchUid,
                            Instant.now()));
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
     */
    public Mono<Void> resumePlayer(String sessionId, String casdoorId) {
        if (sessionId == null || casdoorId == null) return Mono.empty();
        var perSession = playerPauses.get(sessionId);
        if (perSession != null) perSession.remove(casdoorId);
        gameState.emitSessionControl(new games.snapdragon.bff.model.SessionControlEvent(
                sessionId,
                games.snapdragon.bff.model.SessionControlEvent.Action.PLAYER_RESUMED,
                casdoorId, null, null, Instant.now()));
        return Mono.empty();
    }

    /**
     * Host-drop handler. Called when the session creator's membership
     * row gets a {@code left_at} stamp — the session is force-closed and
     * a SESSION_CLOSED event is emitted with reason
     * {@code host-disconnected} so every other player's host shell can
     * collapse the game iframe and surface a toast.
     */
    public Mono<Void> closeOnHostDrop(String sessionId) {
        return sessions.findById(sessionId)
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

    /** Active membership snapshot — used by ControlController's auth check. */
    public Mono<SessionMember> activeMembership(String sessionId, String casdoorId) {
        return members.findActive(sessionId, casdoorId);
    }

    /**
     * Authorize a control write: the caller (JWT subject) must be an active
     * member who currently owns {@code characterId}. Returns the membership
     * row on success; emits 403 on miss.
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
     */
    public Mono<SessionMember> authorizeControl(String casdoorId, Long characterId, String sessionIdOpt) {
        Mono<SessionMember> lookup = (sessionIdOpt != null && !sessionIdOpt.isBlank())
                ? members.findActiveOwnerInSession(sessionIdOpt, characterId)
                : members.findActiveOwnerOfCharacter(characterId);
        return lookup
                .switchIfEmpty(Mono.error(new ResponseStatusException(HttpStatus.FORBIDDEN, "no active session member owns this character")))
                .flatMap(m -> {
                    if (!casdoorId.equals(m.getCasdoorId())) {
                        return Mono.error(new ResponseStatusException(HttpStatus.FORBIDDEN, "character not owned by caller"));
                    }
                    return Mono.just(m);
                });
    }

    private Mono<GameSessionDto> toDtoWithMembers(GameSession s) {
        return members.findActiveBySession(s.getId())
                .map(GameSessionDto.MemberDto::from)
                .collectList()
                .map(list -> GameSessionDto.from(s, list.size(), list));
    }

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
