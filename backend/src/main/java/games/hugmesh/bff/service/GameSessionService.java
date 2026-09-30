package games.hugmesh.bff.service;

import games.hugmesh.bff.model.GameSession;
import games.hugmesh.bff.model.GameSessionDto;
import games.hugmesh.bff.model.SessionMember;
import games.hugmesh.bff.repository.GameSessionRepository;
import games.hugmesh.bff.repository.SessionMemberRepository;
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
 * <h2>Member liveness</h2>
 *
 * <p>{@code session_members.left_at} used to be stamped only by an explicit
 * {@code DELETE /api/sessions/{id}/me} or by {@code ModerationService.kickPlayer},
 * so a closed tab / crash / dropped network held a seat and an avatar slot
 * against the room's cap forever. {@link #heartbeat} now records a
 * {@code last_seen_at} on the two signals a seated member produces — the
 * roster poll ({@code GET /api/sessions/{id}}) and every authorized control
 * write ({@link #authorizeControl}) — and {@link #reapStaleMembers} releases
 * the seats whose signal went quiet.
 *
 * <p>The roster poll is what makes the reaper safe for a player who is
 * THINKING rather than pressing keys, and it comes from the Angular host
 * shell ({@code SessionHeartbeatService}, every 20 s while a tab holds a
 * seat), NOT from the games. Only {@code shadows-of-the-net} polls on its own
 * ({@code session_bridge.gd}, every 5 s); the other nineteen games in the
 * repo emit nothing at all between player actions, so treating a game-side
 * poll as the general signal is what would let a sweep reap two people
 * halfway through a game of chess.
 *
 * <p>The complementary client-side leg is a {@code keepalive} DELETE fired
 * from the browser's {@code pagehide} handler, which covers a clean tab close
 * without waiting out the reaper's timeout.
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
 * <p>Session ids are application-assigned (minted via {@link games.hugmesh.bff.util.Crypto#mintUid})
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

    /**
     * The session creator's multiplayer slot. Pinned, not requested.
     *
     * <p>Host-authoritative Godot titles elect their in-game host from the
     * client-visible {@code slot_index} (shadows-of-the-net does
     * {@code _is_host = slot_index == 0}), while the BFF grants authority on
     * {@code session_members.is_creator} — {@code InputEventController}'s Gate
     * 0b rejects {@code kind:'world'} from anyone else. Those two definitions
     * used to be independent: {@code slotIndex} was a nullable client-supplied
     * field on both create and join, so a session created without one left
     * slot 0 free for the first guest, who then believed itself host, drove
     * the room's seat/stage arbitration, and had every one of its authoritative
     * broadcasts 403'd — while the real creator never broadcast at all.
     *
     * <p>Pinning the creator to slot 0 (here on create, enforced on join)
     * makes {@code slot_index == 0} and {@code is_creator} the same predicate.
     */
    public static final int CREATOR_SLOT = 0;

    private final GameSessionRepository    sessions;
    private final SessionMemberRepository  members;
    private final RelationshipQuery  relationships;
    private final games.hugmesh.bff.service.GameStateService gameState;

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
     * <p>Note on {@code supersededLaunchUid}: the name is a misnomer inherited
     * from the wire contract. The frontend fills it from
     * {@code TakeoverMessage.launchUid}, which {@code SessionControlService
     * .claimTakeover} sets to the launch UID of the <em>new</em> tab — so the
     * field actually carries the UID of the tab that is <em>doing</em> the
     * superseding, not the one being displaced. The old tab never tells the BFF
     * its own UID. Nothing keys off the value (it is echoed onto the
     * PLAYER_PAUSED event for observers); the takeover guards below therefore
     * key off the <em>presence</em> of the record, which is unambiguous.
     *
     * @param reason              human-readable pause reason (e.g. {@code "tab-takeover"})
     * @param supersededLaunchUid the launch UID carried on the takeover claim — see above
     * @param pausedAt            when the pause was recorded
     */
    private record PausedPlayer(String reason, String supersededLaunchUid, Instant pausedAt) {}

    /**
     * In-memory map of {@code sessionId\0casdoorId → last DB heartbeat write}.
     *
     * <p>The liveness signals fire far more often than the reaper needs: the
     * roster poll is every 20 s per seated tab (every 5 s on top of that in
     * shadows-of-the-net, which polls from the game as well) and
     * {@link #authorizeControl} runs on every single control write. Writing
     * {@code last_seen_at} on each one would put an UPDATE on the game's hot
     * path for no extra safety, so a member is written through at most once
     * per {@link #heartbeatMinInterval}. Per-pod state by design — each pod
     * independently rate-limits its own writes and they all target the same
     * row.
     *
     * <p>The interval must stay BELOW the host shell's poll period, or a beat
     * lands inside the suppression window, is dropped, and the member's
     * effective heartbeat period silently doubles.
     */
    private final java.util.concurrent.ConcurrentHashMap<String, Instant> lastHeartbeatWrite =
            new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * How long a member's heartbeat write is suppressed for after the previous
     * one. Must stay well under {@code app.sessions.member-timeout-ms} (the
     * reaper's staleness window) or a live player could be reaped between two
     * suppressed heartbeats.
     */
    private final java.time.Duration heartbeatMinInterval;

    /**
     * Optional Kafka tap. When {@code app.kafka.enabled=true} the audit
     * publisher emits session-lifecycle events to the {@code state_audit}
     * topic and the topic manager creates / deletes the per-session
     * Kafka topic that cross-pod consumers subscribe to. Both are
     * {@code Optional} so the in-process gameplay path keeps working when
     * Kafka is disabled (the default).
     */
    private final java.util.Optional<games.hugmesh.bff.audit.GameAuditPublisher> audit;
    private final java.util.Optional<games.hugmesh.bff.audit.SessionTopicManager> topicManager;

    public GameSessionService(GameSessionRepository sessions,
                              SessionMemberRepository members,
                              RelationshipQuery relationships,
                              games.hugmesh.bff.service.GameStateService gameState,
                              java.util.Optional<games.hugmesh.bff.audit.GameAuditPublisher> audit,
                              java.util.Optional<games.hugmesh.bff.audit.SessionTopicManager> topicManager,
                              @org.springframework.beans.factory.annotation.Value(
                                      "${app.sessions.heartbeat-min-interval-ms:15000}")
                              long heartbeatMinIntervalMs) {
        this.sessions      = sessions;
        this.members       = members;
        this.relationships = relationships;
        this.gameState     = gameState;
        this.audit         = audit;
        this.topicManager  = topicManager;
        // Clamp to >= 0 so a misconfigured 0/negative value degrades to
        // "write every signal" rather than to a nonsensical future instant.
        this.heartbeatMinInterval = java.time.Duration.ofMillis(Math.max(0L, heartbeatMinIntervalMs));
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
     * @param slotIndex        IGNORED — the creator is always seated at
     *                         {@link #CREATOR_SLOT}; kept on the signature so
     *                         existing callers/DTOs don't have to change
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
        s.setId(games.hugmesh.bff.util.Crypto.mintUid());
        s.setName(trimmed);
        s.setGameSlug(gameSlug);
        s.setCreatorCasdoorId(creatorCasdoorId);
        s.setMaxPlayers(cap);
        s.setVisibility(vis);
        s.setCreatedAt(Instant.now());
        // markNew() sets the Persistable.isNew flag so R2DBC issues INSERT, not UPDATE.
        s.markNew();

        // The creator ALWAYS takes CREATOR_SLOT — the requested slotIndex is
        // deliberately ignored so that "slot 0" and "may post kind:'world'"
        // can never name different members (see CREATOR_SLOT).
        SessionMember firstMember = newMember(s.getId(), creatorCasdoorId, characterId, CREATOR_SLOT, true);

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

    /**
     * The game a session belongs to, without the member enrichment of
     * {@link #get}. Used to bind a client-supplied {@code gameSlug} to the
     * session it claims to belong to. Empty when the session does not exist.
     */
    public Mono<String> gameSlugOf(String sessionId) {
        return sessions.findById(sessionId).map(GameSession::getGameSlug);
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
     * @param slotIndex   optional multiplayer slot index to claim; the creator
     *                    is forced onto {@link #CREATOR_SLOT} and any other
     *                    member asking for {@link #CREATOR_SLOT} is refused
     * @return a {@link Mono} emitting the updated {@link GameSessionDto}, or HTTP
     *         404 if the session does not exist, HTTP 410 if it is closed, or HTTP
     *         409 if it is full, the slot is already claimed, or a non-creator
     *         asked for the reserved creator slot
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
                    // CREATOR_SLOT is the host slot and belongs to the creator
                    // alone: a guest that takes it becomes a false host in every
                    // host-authoritative title (see CREATOR_SLOT). Refuse the
                    // claim outright rather than silently re-slotting, so the
                    // caller learns its request was not honoured. The creator
                    // is always put back on it, whatever it asked for.
                    boolean isCreator = casdoorId != null && casdoorId.equals(s.getCreatorCasdoorId());
                    if (!isCreator && slotIndex != null && slotIndex == CREATOR_SLOT) {
                        return Mono.error(new ResponseStatusException(HttpStatus.CONFLICT,
                                "slot " + CREATOR_SLOT + " is reserved for the session creator"));
                    }
                    Integer slot = isCreator ? CREATOR_SLOT : slotIndex;
                    return members.findActive(sessionId, casdoorId)
                            .flatMap(existing -> {
                                // Already a member — idempotent; update slot/character if provided.
                                if (slot != null) existing.setSlotIndex(slot);
                                if (characterId != null) existing.setCharacterId(characterId);
                                return members.save(existing);
                            })
                            .switchIfEmpty(members.countBySessionIdAndLeftAtIsNull(sessionId)
                                    .flatMap(count -> {
                                        // Enforce the session's player cap before inserting a new member.
                                        if (count >= s.getMaxPlayers()) {
                                            return Mono.error(new ResponseStatusException(HttpStatus.CONFLICT, "session full"));
                                        }
                                        return members.save(newMember(sessionId, casdoorId, characterId, slot, false));
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
        return leave(sessionId, casdoorId, false);
    }

    /**
     * {@link #leave} with the unload hint that carries the superseded-tab
     * guard.
     *
     * <p>{@code fromUnload} marks a leave the browser fired from its
     * {@code pagehide} handler rather than one the player asked for. The two
     * must be told apart because of the tab-takeover flow: when a player opens
     * the running game in a second tab, {@code SessionControlService
     * .onTakeoverMessage} freezes the OLD tab but leaves it open — and a frozen
     * tab still fires {@code pagehide} when the player eventually closes it,
     * or when the browser discards it to reclaim memory. That unload-leave
     * would stamp {@code left_at} on the membership row the NEW tab is actively
     * playing under, evicting a player who is sitting right there. So while a
     * takeover is pending for this member, an unload-leave is dropped.
     *
     * <p>An explicit leave is never dropped: it is the player saying so, and
     * the "leave" control only exists in the tab they are looking at.
     *
     * <p>Dropping the unload-leave costs nothing durable — the shelter only
     * lasts until {@link #resumePlayer} clears the record, and a player who
     * really did close every tab is collected by
     * {@link #reapStaleMembers} one staleness window later.
     *
     * <p><b>This guard is a backstop, not the fix.</b> Both tabs authenticate
     * as the same Casdoor subject with the same JWT, so once the takeover
     * resolves — the new tab loaded, {@link #resumePlayer} dropped the record —
     * the BFF has nothing left to tell them apart with, and the old tab may
     * stay open for hours before its {@code pagehide} finally fires. The
     * authoritative guard is therefore client-side: {@code
     * SessionControlService.superseded} is a one-way latch set on the tab that
     * RECEIVES a {@code takeover-claim} (BroadcastChannel does not deliver to
     * the sender, so a receiver is by definition the old tab), and
     * {@code SessionLeaveOnUnloadService} never fires from a latched tab. What
     * this guard covers is the narrow, common window the latch cannot: an old
     * tab closed while the new one is still compiling WASM.
     *
     * @param sessionId  the session being left
     * @param casdoorId  the leaving player's Casdoor subject
     * @param fromUnload {@code true} when the browser fired this from
     *                   {@code pagehide}, {@code false} when the player asked
     * @return a {@link Mono} that completes when all side-effects are done
     */
    public Mono<Void> leave(String sessionId, String casdoorId, boolean fromUnload) {
        if (fromUnload && hasPendingTakeover(sessionId, casdoorId)) {
            log.debug("[member-liveness] ignoring unload-leave from superseded tab: session={} member={}",
                    sessionId, casdoorId);
            return Mono.empty();
        }
        return members.findActive(sessionId, casdoorId)
                .flatMap(existing -> members
                        // Guarded stamp, not a read-modify-save(): the reaper
                        // may be stamping this very row from another pod. Only
                        // the caller that actually flips left_at may go on to
                        // fire the close paths, or the session gets two
                        // SESSION_CLOSED events and two `session.left` audits
                        // for one departure.
                        .stampLeftIfActive(existing.getId(), Instant.now())
                        .defaultIfEmpty(0)
                        .flatMap(stamped -> {
                            if (stamped == null || stamped == 0) return Mono.<Boolean>empty();
                            // The seat is gone; stop rate-limiting a member
                            // that no longer exists (see lastHeartbeatWrite).
                            forgetMember(sessionId, casdoorId);
                            audit.ifPresent(a -> a.emit(
                                    null, sessionId, casdoorId, "session.left",
                                    java.util.Map.of("wasCreator", existing.isCreator())));
                            return Mono.just(existing.isCreator());
                        }))
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
                .then(closeIfEmpty(sessionId, "auto-empty"));
    }

    /**
     * True while a tab-takeover pause is on record for this member — i.e. the
     * player has a second tab that just claimed the session. Reads the same
     * ephemeral map {@link #pausePlayer} writes and {@link #resumePlayer}
     * clears.
     */
    private boolean hasPendingTakeover(String sessionId, String casdoorId) {
        if (sessionId == null || casdoorId == null) return false;
        var perSession = playerPauses.get(sessionId);
        return perSession != null && perSession.containsKey(casdoorId);
    }

    /**
     * Close {@code sessionId} if no active member remains. Shared tail of
     * {@link #leave} and of the reaper's non-creator path so both emit the
     * same SESSION_CLOSED wire event and perform the same cleanup.
     *
     * <p>The SSE reason is {@code last-member-left} either way — the client
     * behaviour is identical and the audit {@code reason} carries the
     * distinction (a human leave vs. a reaped ghost) for the ledger.
     *
     * @param sessionId   the session to consider closing
     * @param auditReason the reason recorded on the {@code session.closed}
     *                    audit event ({@code auto-empty} / {@code auto-empty-reaped})
     * @return a {@link Mono} that completes when the close (if any) is persisted
     */
    private Mono<Void> closeIfEmpty(String sessionId, String auditReason) {
        return members.countBySessionIdAndLeftAtIsNull(sessionId)
                .flatMap(remaining -> {
                    // If there are still active members, the session stays open.
                    if (remaining > 0) return Mono.<Void>empty();
                    return sessions.findById(sessionId)
                            .flatMap(s -> {
                                // Guard against double-close (host-drop may have beaten us here).
                                if (s.getClosedAt() != null) return Mono.<Void>empty();
                                s.setClosedAt(Instant.now());
                                log.info("Session auto-closed (no active members): {}", sessionId);
                                return sessions.save(s).then()
                                        .doOnSuccess(v -> {
                                            // Notify all subscribers and clean up Kafka topic.
                                            gameState.emitSessionControl(
                                                    new games.hugmesh.bff.model.SessionControlEvent(
                                                            s.getId(),
                                                            games.hugmesh.bff.model.SessionControlEvent.Action.SESSION_CLOSED,
                                                            null, null, "last-member-left", Instant.now()));
                                            audit.ifPresent(a -> a.emit(
                                                    s.getGameSlug(), sessionId, null,
                                                    "session.closed",
                                                    java.util.Map.of("reason", auditReason)));
                                            topicManager.ifPresent(tm ->
                                                    tm.deleteForSession(sessionId));
                                            forgetSession(sessionId);
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
     * <p>Emits {@link games.hugmesh.bff.model.SessionControlEvent.Action#SESSION_CLOSED}
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
                    if (s.getClosedAt() != null) return Mono.<Void>empty();
                    s.setClosedAt(Instant.now());
                    return sessions.save(s)
                            // A closed session has no active members — release
                            // every seat the close would otherwise have left
                            // stamped `left_at IS NULL` forever.
                            .then(members.stampAllActiveLeft(sessionId, Instant.now()))
                            .then()
                            .doOnSuccess(v -> {
                                // Fan out the close event before cleaning up Kafka.
                                gameState.emitSessionControl(
                                        new games.hugmesh.bff.model.SessionControlEvent(
                                                s.getId(),
                                                games.hugmesh.bff.model.SessionControlEvent.Action.SESSION_CLOSED,
                                                null, null, "force-closed", Instant.now()));
                                audit.ifPresent(a -> a.emit(
                                        s.getGameSlug(), sessionId, null,
                                        "session.closed",
                                        java.util.Map.of("reason", "force-close")));
                                topicManager.ifPresent(tm ->
                                        tm.deleteForSession(sessionId));
                                forgetSession(sessionId);
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
                    gameState.emitSessionControl(new games.hugmesh.bff.model.SessionControlEvent(
                            s.getId(),
                            games.hugmesh.bff.model.SessionControlEvent.Action.SESSION_PAUSED,
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
                    // Re-stamp liveness for the whole room BEFORE it becomes
                    // reapable again. A pause can silence a room's game-side
                    // signal wholesale (shadows-of-the-net sets tree.paused =
                    // true on SESSION_PAUSED, which halts session_bridge's own
                    // roster poll), so on a client whose game half is the only
                    // thing signalling, every member is arbitrarily stale by
                    // the time somebody resumes.
                    //
                    // ORDER IS LOAD-BEARING: the touch must be committed while
                    // the row still reads paused_at IS NOT NULL, because that
                    // predicate is the only thing keeping findStaleActive off
                    // this room. Stamping after the save leaves a window in
                    // which the session is unpaused AND every member is stale
                    // — a sweep landing there reaps the room it just resumed,
                    // which is precisely the failure this method exists to
                    // avoid. Clearing the suppression stamps first likewise
                    // guarantees the touch is a real write, not a rate-limited
                    // no-op.
                    Instant now = Instant.now();
                    forgetHeartbeatWrites(s.getId());
                    return members.touchAllActive(s.getId(), now)
                            .then(sessions.save(s));
                })
                .doOnSuccess(s -> {
                    gameState.emitSessionControl(new games.hugmesh.bff.model.SessionControlEvent(
                            s.getId(),
                            games.hugmesh.bff.model.SessionControlEvent.Action.SESSION_RESUMED,
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
        gameState.emitSessionControl(new games.hugmesh.bff.model.SessionControlEvent(
                sessionId,
                games.hugmesh.bff.model.SessionControlEvent.Action.PLAYER_PAUSED,
                casdoorId,
                supersededLaunchUid,
                reason == null ? "tab-takeover" : reason,
                Instant.now()));
        // A takeover pause is itself proof of life — the player is mid-move
        // between two of their own tabs, and the frozen old tab will stop
        // polling the roster while the new one is still compiling WASM.
        return touchNow(sessionId, casdoorId);
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
        gameState.emitSessionControl(new games.hugmesh.bff.model.SessionControlEvent(
                sessionId,
                games.hugmesh.bff.model.SessionControlEvent.Action.PLAYER_RESUMED,
                casdoorId, null, null, Instant.now()));
        // The new tab is loaded and about to start polling — hand the member a
        // full staleness window so the reaper doesn't catch them in the gap.
        return touchNow(sessionId, casdoorId);
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
        return closeOnHostDropReporting(sessionId).then();
    }

    /**
     * {@link #closeOnHostDrop} that says whether it actually closed anything.
     *
     * <p>{@code true} when THIS call stamped {@code closed_at} and fired the
     * SESSION_CLOSED fan-out; {@code false} when the session was already
     * closed (or gone) and the whole body short-circuited. Callers that record
     * a consequence of the close — an audit row, a sweep count — must key off
     * this rather than off the {@code Mono<Void>} completing, which it does
     * either way.
     *
     * @param sessionId the session whose creator just left
     * @return {@code true} iff this call performed the close
     */
    private Mono<Boolean> closeOnHostDropReporting(String sessionId) {
        return sessions.findById(sessionId)
                // Skip if already closed (leave path may have raced ahead).
                .filter(s -> s.getClosedAt() == null)
                .flatMap(s -> {
                    s.setClosedAt(Instant.now());
                    return sessions.save(s)
                            // Same as close(): the guests the host just
                            // stranded are no longer active members.
                            .then(members.stampAllActiveLeft(s.getId(), Instant.now()))
                            .then()
                            .doOnSuccess(v -> {
                                gameState.emitSessionControl(new games.hugmesh.bff.model.SessionControlEvent(
                                        s.getId(),
                                        games.hugmesh.bff.model.SessionControlEvent.Action.SESSION_CLOSED,
                                        null, null, "host-disconnected", Instant.now()));
                                audit.ifPresent(a -> a.emit(
                                        s.getGameSlug(), s.getId(), s.getCreatorCasdoorId(),
                                        "session.closed",
                                        java.util.Map.of("reason", "host-disconnected")));
                                topicManager.ifPresent(tm -> tm.deleteForSession(s.getId()));
                                forgetSession(s.getId());
                            })
                            .thenReturn(true);
                })
                .defaultIfEmpty(false);
    }

    // -------------------------------------------------------------------------
    // Member liveness — heartbeat + reaper
    // -------------------------------------------------------------------------

    /**
     * Record that {@code casdoorId} is still alive inside {@code sessionId}.
     *
     * <p>Called from the two signals an <em>identified</em> member produces:
     * <ol>
     *   <li>{@code GET /api/sessions/{id}} — the roster poll, independent of
     *       whether the player is touching the keyboard. This is the one that
     *       keeps a THINKING player alive, and it comes from the Angular host
     *       shell ({@code SessionHeartbeatService}, every 20 s while the tab
     *       holds a seat) for every game. {@code shadows-of-the-net}
     *       additionally polls from inside the game
     *       ({@code session_bridge.gd ROSTER_POLL_INTERVAL}, 5 s) — that is
     *       specific to that title and is NOT where the general guarantee
     *       comes from.</li>
     *   <li>{@link #authorizeControl} — every {@code POST /api/controls/{cid}}
     *       and {@code POST /api/input/{cid}} carrying {@code X-Session-Id}.
     *       Player-action-driven, so on its own it reaps anyone who stops to
     *       think; it is a supplement to the poll, never a substitute.</li>
     * </ol>
     *
     * <p>The SSE stream is deliberately NOT a signal: the frontend opens its
     * {@code EventSource} with no credentials and {@code /api/game/**} is a
     * public path, so the stream controller has no member identity to
     * attribute a heartbeat to.
     *
     * <p>Rate-limited per pod to one DB write per member per
     * {@code app.sessions.heartbeat-min-interval-ms} — see
     * {@link #lastHeartbeatWrite}. Never fails the caller: a heartbeat is
     * bookkeeping, and dropping one costs at most one interval of freshness,
     * whereas propagating its error would 403 a legitimate control write.
     *
     * @param sessionId the session the signal arrived for; null/blank is a no-op
     * @param casdoorId the member that produced it; null/blank is a no-op
     * @return a {@link Mono} that completes when the write (if any) is done
     */
    public Mono<Void> heartbeat(String sessionId, String casdoorId) {
        if (sessionId == null || sessionId.isBlank() || casdoorId == null || casdoorId.isBlank()) {
            return Mono.empty();
        }
        Instant now = Instant.now();
        Instant suppressBefore = now.minus(heartbeatMinInterval);
        // compute() so two concurrent controls for the same member can't both
        // decide to write — the loser sees the winner's stamp already in place.
        java.util.concurrent.atomic.AtomicBoolean write = new java.util.concurrent.atomic.AtomicBoolean(false);
        lastHeartbeatWrite.compute(heartbeatKey(sessionId, casdoorId), (k, prev) -> {
            if (prev != null && prev.isAfter(suppressBefore)) return prev;
            write.set(true);
            return now;
        });
        if (!write.get()) return Mono.empty();
        return members.touchLastSeen(sessionId, casdoorId, now)
                .onErrorResume(err -> {
                    log.warn("[member-liveness] heartbeat write failed for {}/{}", sessionId, casdoorId, err);
                    // Drop the suppression stamp so the next signal retries
                    // instead of waiting out the interval on a failed write.
                    lastHeartbeatWrite.remove(heartbeatKey(sessionId, casdoorId));
                    return Mono.empty();
                })
                .then();
    }

    /**
     * Unconditional heartbeat — bypasses the rate limiter.
     *
     * <p>Used by the pause/resume transitions, where the point is precisely to
     * hand the member a full fresh staleness window at a moment when the
     * ordinary signals are about to stop (or have just restarted).
     *
     * @param sessionId the session; null/blank is a no-op
     * @param casdoorId the member; null/blank is a no-op
     * @return a {@link Mono} that completes when the write is done
     */
    private Mono<Void> touchNow(String sessionId, String casdoorId) {
        if (sessionId == null || sessionId.isBlank() || casdoorId == null || casdoorId.isBlank()) {
            return Mono.empty();
        }
        Instant now = Instant.now();
        lastHeartbeatWrite.put(heartbeatKey(sessionId, casdoorId), now);
        return members.touchLastSeen(sessionId, casdoorId, now)
                .onErrorResume(err -> {
                    log.warn("[member-liveness] forced heartbeat failed for {}/{}", sessionId, casdoorId, err);
                    lastHeartbeatWrite.remove(heartbeatKey(sessionId, casdoorId));
                    return Mono.empty();
                })
                .then();
    }

    /**
     * Release the seats of members whose liveness signal went quiet before
     * {@code cutoff} — the server-side half of the closed-tab fix, and the
     * only half that catches a crash, a closed lid, or a dropped network
     * (none of which get to run an unload handler).
     *
     * <p><b>Safe only because every seated tab polls.</b> The reaper decides
     * who is gone from the absence of a signal, so it is exactly as correct as
     * the assumption that a present player produces one. The games do not:
     * only {@code shadows-of-the-net} polls the roster from inside the game,
     * and the other nineteen emit nothing between player actions, which for a
     * turn-based board means both players go quiet while one of them thinks.
     * The guarantee comes from the Angular host shell's
     * {@code SessionHeartbeatService} instead — it owns the session for every
     * game and beats every 20 s while a tab holds a seat. If that leg is ever
     * removed, this sweep starts deleting live rooms; the
     * {@code app.sessions.reap-enabled} kill-switch on
     * {@link SessionMemberReaper} exists so an operator can stop it without a
     * release.
     *
     * <p>Two candidates are deliberately spared:
     * <ul>
     *   <li><b>Members of a paused or already-closed session</b> — a pause can
     *       silence a room's game-side signal wholesale (shadows-of-the-net
     *       sets {@code tree.paused = true} on SESSION_PAUSED, which halts
     *       {@code session_bridge._process} and with it its roster poll), so
     *       every member of one can look stale at once. Excluded twice: in SQL by
     *       {@code SessionMemberRepository.findStaleActive} so the sweep stays
     *       index-friendly, and again per candidate in {@link #reapOne},
     *       because the sweep consumes its candidate list over time and a
     *       session can be paused after its rows were selected.</li>
     *   <li><b>A member mid tab-takeover</b> — their old tab is frozen and
     *       their new tab may still be compiling WASM, so neither is polling.
     *       The pause record only shelters them for one staleness window, so a
     *       new tab that never finishes loading cannot pin the seat open
     *       forever.</li>
     * </ul>
     *
     * <p>The creator is reaped through {@link #closeOnHostDrop}, exactly as a
     * real host leave would be, NOT with a bare {@code left_at} stamp: the
     * creator's {@code is_creator} flag is read off
     * {@code findActiveOwnerInSession} by {@code InputEventController}'s Gate
     * 0b, so a stamped-but-still-open room 403s the host's own controls into an
     * SD_SESSION_EXPIRED loop and 403s its {@code kind:'world'} broadcasts into
     * a stand-down — leaving a hostless room that never closes.
     *
     * <p>Candidates are reaped sequentially ({@code concatMap}) so two members
     * of the same session cannot race each other's close check.
     *
     * <p>The candidate list is drained into memory before the first write.
     * Reaping straight off the streaming {@code Flux} would hold that cursor
     * open across every subsequent round-trip of the sweep — a re-read, an
     * UPDATE and a count per candidate — pinning one pooled connection for the
     * whole sweep. Buffering costs nothing: the set is bounded by the number of
     * seats on the cluster, and {@link #reapOne} re-reads the session state
     * anyway precisely because the list is a snapshot.
     *
     * @param cutoff members whose newest liveness signal predates this instant
     *               are reaped
     * @return a {@link Mono} emitting the number of rows this call actually
     *         stamped (a row a concurrent leave already stamped does not count)
     */
    public Mono<Long> reapStaleMembers(Instant cutoff) {
        return members.findStaleActive(cutoff)
                .filter(m -> !isPauseSheltered(m, cutoff))
                .collectList()
                .flatMapMany(Flux::fromIterable)
                .concatMap(this::reapOne)
                .filter(Boolean::booleanValue)
                .count();
    }

    /**
     * Reap one stale member. Returns {@code false} without side effects when
     * the session got paused or closed after the candidate list was selected,
     * or when the row was already stamped by a concurrent leave / kick.
     */
    private Mono<Boolean> reapOne(SessionMember m) {
        return sessions.findById(m.getSessionId())
                // Re-read the session state at reap time, not at select time:
                // pausing a room must protect every one of its members, even
                // the ones further down a candidate list that was assembled
                // while the room was still running.
                .filter(s -> s.getClosedAt() == null && s.getPausedAt() == null)
                .flatMap(s -> m.isCreator() ? reapCreator(m) : reapGuest(m))
                .defaultIfEmpty(false);
    }

    /**
     * Reap the session creator by closing the whole room, exactly as a real
     * host leave does.
     *
     * <p>Deliberately does NOT stamp {@code left_at} on the creator's own row
     * first. {@code InputEventController}'s Gate 0b resolves "is this caller
     * the host?" through {@code findActiveOwnerInSession}, whose predicate is
     * {@code left_at IS NULL} — so a creator stamped while the room is still
     * open 403s its own controls into an SD_SESSION_EXPIRED loop and 403s its
     * {@code kind:'world'} broadcasts into a host stand-down. The room is then
     * hostless, still open, and nothing left alive in it will ever close it:
     * strictly worse than the ghost seat we came to collect.
     *
     * <p>{@link #closeOnHostDrop} closes the session and only then stamps
     * every active seat ({@code stampAllActiveLeft}), so the ordering makes
     * that state unreachable — if the stamp half fails, the fallout is a
     * closed session with unstamped rows, which is inert and is exactly the
     * pre-existing condition this changeset also fixes.
     *
     * <p>Exactly-once falls out of {@code closeOnHostDrop}'s own
     * {@code closed_at IS NULL} filter rather than from a row claim — which is
     * why the audit and the sweep count come AFTER it and are conditional on
     * what it reports. Announcing the departure first would emit a
     * {@code session.left} row and inflate the sweep count for a close that a
     * concurrent force-close had already performed and this call then skipped.
     *
     * <p>The losing side of that race needs one thing doing: the concurrent
     * close ran its own {@code stampAllActiveLeft}, but if it did not (a
     * {@code closeIfEmpty} that saw the room as already empty, a close whose
     * stamp half failed), the creator's row is left active inside a CLOSED
     * session — and {@code findStaleActive} requires {@code s.closed_at IS
     * NULL}, so no future sweep would ever see it again. That is the exact
     * orphan class this changeset exists to eliminate, so the row is stamped
     * directly in that branch. The Gate 0b hazard that forbids stamping a LIVE
     * creator does not apply: the room is already closed, so there are no
     * controls left to 403.
     */
    private Mono<Boolean> reapCreator(SessionMember m) {
        return closeOnHostDropReporting(m.getSessionId())
                .flatMap(closed -> Boolean.TRUE.equals(closed)
                        ? Mono.just(true)
                        // Lost the race — recover the row rather than orphan it.
                        : members.stampLeftIfActive(m.getId(), Instant.now())
                                .defaultIfEmpty(0)
                                .map(stamped -> stamped != null && stamped > 0))
                .map(reaped -> {
                    if (reaped) {
                        logReap(m);
                        audit.ifPresent(a -> a.emit(
                                null, m.getSessionId(), m.getCasdoorId(), "session.left",
                                java.util.Map.of("wasCreator", true, "reason", "stale-heartbeat")));
                        forgetMember(m.getSessionId(), m.getCasdoorId());
                    }
                    return reaped;
                });
    }

    /**
     * Reap a non-creator member: claim the row, then close the room if that
     * emptied it. Returns {@code false} without side effects when the row was
     * already stamped by a concurrent leave or kick.
     */
    private Mono<Boolean> reapGuest(SessionMember m) {
        return members.stampLeftIfActive(m.getId(), Instant.now())
                .defaultIfEmpty(0)
                .flatMap(stamped -> {
                    // Zero rows ⇒ somebody else already ended this membership.
                    // Firing the close path again would double-emit
                    // SESSION_CLOSED and re-audit a departure that already
                    // happened, so bail out silently.
                    if (stamped == null || stamped == 0) return Mono.just(false);
                    logReap(m);
                    audit.ifPresent(a -> a.emit(
                            null, m.getSessionId(), m.getCasdoorId(), "session.left",
                            java.util.Map.of("wasCreator", false, "reason", "stale-heartbeat")));
                    forgetMember(m.getSessionId(), m.getCasdoorId());
                    return closeIfEmpty(m.getSessionId(), "auto-empty-reaped").thenReturn(true);
                });
    }

    /** One log line per released seat — the operator's record of who was collected and why. */
    private void logReap(SessionMember m) {
        log.info("[member-reaper] released stale seat: session={} member={} creator={} lastSeen={}",
                m.getSessionId(), m.getCasdoorId(), m.isCreator(),
                m.getLastSeenAt() != null ? m.getLastSeenAt() : m.getJoinedAt());
    }

    /**
     * True when a pending tab-takeover pause should shelter this member from
     * the reaper. The shelter expires with the same staleness window as the
     * heartbeat itself so a new tab that never finishes loading cannot hold
     * the seat open indefinitely.
     */
    private boolean isPauseSheltered(SessionMember m, Instant cutoff) {
        var perSession = playerPauses.get(m.getSessionId());
        if (perSession == null) return false;
        PausedPlayer paused = perSession.get(m.getCasdoorId());
        return paused != null && paused.pausedAt().isAfter(cutoff);
    }

    /** Composite key for {@link #lastHeartbeatWrite} — NUL can't occur in either id. */
    private static String heartbeatKey(String sessionId, String casdoorId) {
        return sessionId + '\0' + casdoorId;
    }

    /** Drop one member's heartbeat suppression stamp. */
    private void forgetMember(String sessionId, String casdoorId) {
        lastHeartbeatWrite.remove(heartbeatKey(sessionId, casdoorId));
    }

    /** Drop every heartbeat suppression stamp belonging to one session. */
    private void forgetHeartbeatWrites(String sessionId) {
        String prefix = sessionId + "\0";
        lastHeartbeatWrite.keySet().removeIf(k -> k.startsWith(prefix));
    }

    /**
     * Drop all ephemeral per-session state — pause records and heartbeat
     * suppression stamps. Called from every close path so neither map leaks
     * an entry for a session that no longer exists.
     */
    private void forgetSession(String sessionId) {
        playerPauses.remove(sessionId);
        forgetHeartbeatWrites(sessionId);
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
                    // An authorized control write is proof the player is alive.
                    // This is the one chokepoint every game's hot path crosses
                    // (ControlController AND InputEventController), so it is
                    // where the liveness signal belongs; the write itself is
                    // rate-limited and never fails the control.
                    return heartbeat(m.getSessionId(), m.getCasdoorId()).thenReturn(m);
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
        Instant now = Instant.now();
        m.setJoinedAt(now);
        // Joining IS a liveness signal — seed last_seen_at so a member who is
        // still loading their WASM bundle (30 s on a cold image, before the
        // first roster poll fires) is never a reaper candidate.
        m.setLastSeenAt(now);
        return m;
    }
}
