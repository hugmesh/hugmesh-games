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
import java.util.UUID;

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
    private final UserRelationshipService  relationships;

    public GameSessionService(GameSessionRepository sessions,
                              SessionMemberRepository members,
                              UserRelationshipService relationships) {
        this.sessions      = sessions;
        this.members       = members;
        this.relationships = relationships;
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
        s.setId(UUID.randomUUID().toString());
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
                .doOnNext(saved -> log.info("Session created: {} ({}, vis={}) by {}",
                        saved.getId(), saved.getName(), vis, creatorCasdoorId))
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
     * stays for history. If the caller was the last active member, also
     * close the session itself.
     */
    public Mono<Void> leave(String sessionId, String casdoorId) {
        return members.findActive(sessionId, casdoorId)
                .flatMap(existing -> {
                    existing.setLeftAt(Instant.now());
                    return members.save(existing).then();
                })
                .then(members.countBySessionIdAndLeftAtIsNull(sessionId))
                .flatMap(remaining -> {
                    if (remaining > 0) return Mono.empty();
                    return sessions.findById(sessionId)
                            .flatMap(s -> {
                                if (s.getClosedAt() != null) return Mono.empty();
                                s.setClosedAt(Instant.now());
                                log.info("Session auto-closed (no active members): {}", sessionId);
                                return sessions.save(s).then();
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
                    return sessions.save(s).then();
                });
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
        return members.findActiveOwnerOfCharacter(characterId)
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
