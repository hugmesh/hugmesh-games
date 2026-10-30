package games.hugmesh.bff.controller;

import games.hugmesh.bff.security.GamesAdminPolicy;

import games.hugmesh.bff.model.GameSessionDto;
import games.hugmesh.bff.security.Roles;
import games.hugmesh.bff.service.GameSessionService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Multiplayer session lifecycle: create, list (for the game-menu picker),
 * get, join, leave, close. Distinct from the legacy single-session
 * {@code /api/sessions/connect} flow which retains its connect /
 * claim-controller / disconnect semantics for the single-player path.
 *
 * <p>Hot-path control fan-out lives in {@link games.hugmesh.bff.service.GameStateService}'s
 * per-session sinks; these endpoints do NOT participate in every control
 * event — they're only consulted on session lifecycle transitions.
 *
 * <p>The session creator names the session at create time. Other players
 * pick a session by name from the list endpoint and POST to {@code /join}
 * with their slot preference (slots 0..maxPlayers-1).
 */
@Tag(name = "Multiplayer sessions",
     description = "Named multiplayer sessions — create, list, join, leave, close. Hot-path controls fan out via per-session SSE sinks; subscribers of one session never see another session's events.")
@RestController
@RequestMapping("/api/sessions")
public class MultiplayerSessionController {

    private final GameSessionService service;
    /** Who is a games admin (realm-aware; see GamesAdminPolicy). */
    private final GamesAdminPolicy gamesAdmin;

    public MultiplayerSessionController(GameSessionService service,
                                        GamesAdminPolicy gamesAdmin) {
        this.gamesAdmin = gamesAdmin;
        this.service = service;
    }

    public record CreateSessionRequest(
            String  gameSlug,
            String  name,
            Integer maxPlayers,
            String  visibility,
            Long    characterId,
            Integer slotIndex
    ) {}

    public record JoinSessionRequest(
            Long    characterId,
            Integer slotIndex
    ) {}

    @Operation(
        summary = "Create a new multiplayer session",
        description = """
            Mints a UUID, persists a `game_sessions` row plus a `session_members`
            row for the caller (flagged as creator), and returns the session DTO.
            The caller is auto-joined; the session shows up in the open-sessions
            list for `gameSlug` immediately.

            ```
            ──request──► sessions.save(GameSession)
                          ──●──► members.save(SessionMember{is_creator=true})
                             ──●─| GameSessionDto{id, name, members:[creator]}
            ```
            """)
    @PostMapping
    public Mono<GameSessionDto> create(
            @AuthenticationPrincipal Jwt jwt,
            @RequestBody CreateSessionRequest req) {
        if (req.gameSlug() == null || req.gameSlug().isBlank()) {
            return Mono.error(new ResponseStatusException(HttpStatus.BAD_REQUEST, "gameSlug required"));
        }
        int max = req.maxPlayers() != null ? req.maxPlayers() : 3;
        return service.create(
                jwt.getSubject(),
                req.gameSlug(),
                req.name(),
                max,
                req.visibility(),
                req.characterId(),
                req.slotIndex());
    }

    @Operation(
        summary = "List open sessions",
        description = """
            Returns every session whose `closed_at IS NULL`, optionally filtered
            by `gameSlug`. The home-page session-picker calls this with the
            game's slug after the player clicks a game card.
            """)
    @GetMapping
    public Flux<GameSessionDto> listOpen(
            @AuthenticationPrincipal Jwt jwt,
            @Parameter(description = "Filter to one game's slug. Omit to list all open sessions.")
            @RequestParam(required = false) String gameSlug) {
        String me = jwt != null ? jwt.getSubject() : null;
        return service.listOpen(gameSlug, me);
    }

    @Operation(
        summary = "Get one session with its active members",
        description = """
            Doubles as the multiplayer presence heartbeat. Each call stamps
            `session_members.last_seen_at` for the CALLER;
            `SessionMemberReaper` releases the seats whose stamp goes stale,
            which is what stops a closed tab from holding a slot forever. A
            caller who is not a member of the session (an admin or mod
            observing it) reads the session normally and stamps nothing.

            Two clients poll it, and only together do they cover the catalog:

              * the Angular host shell (`SessionHeartbeatService`) every 20 s
                for as long as the tab holds a seat — this is the one that
                covers ALL games, because the shell owns the session
                regardless of which engine is inside the iframe;
              * `shadows-of-the-net`'s own Godot session bridge
                (`session_bridge.gd ROSTER_POLL_INTERVAL`) every 5 s. That is
                specific to that one game; no other game in the repo polls
                anything, so it must never be treated as the general signal.
            """)
    @GetMapping("/{id:[A-Za-z0-9_\\-]{16,86}}")
    public Mono<GameSessionDto> get(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable String id) {
        String me = jwt != null ? jwt.getSubject() : null;
        return service.get(id)
                .flatMap(dto -> service.heartbeat(id, me).thenReturn(dto));
    }

    @Operation(
        summary = "Join a session",
        description = """
            Idempotent on (sessionId, casdoorId): rejoining as the same caller
            updates the existing membership row (slot / character) instead of
            creating a duplicate. Slot collisions resolve as 409 Conflict via
            the `uq_session_members_active_slot` partial unique index.
            """)
    @PostMapping("/{id:[A-Za-z0-9_\\-]{16,86}}/join")
    public Mono<GameSessionDto> join(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable String id,
            @RequestBody JoinSessionRequest req) {
        return service.join(id, jwt.getSubject(), req.characterId(), req.slotIndex());
    }

    @Operation(
        summary = "Leave a session",
        description = """
            Stamps `left_at` on the caller's active membership row. Idempotent:
            calling twice is a no-op. If the caller was the last active member,
            the session itself is auto-closed; if the caller was the creator,
            the session closes with reason `host-disconnected`.

            Set `unload=true` when the browser fires this from its `pagehide`
            handler rather than from a player action. That flag is what lets a
            closed tab release its seat immediately instead of waiting out
            `SessionMemberReaper`'s timeout — and it is also what makes the
            call safe to fire from a tab that may have been superseded: a
            player who opened the game in a second tab has their OLD tab frozen
            but still open, and its eventual `pagehide` must not evict the seat
            the NEW tab is playing under. An `unload=true` leave arriving while
            a tab-takeover is pending for the caller is therefore dropped; an
            explicit leave (the default) is always honoured.
            """)
    @DeleteMapping("/{id:[A-Za-z0-9_\\-]{16,86}}/me")
    public Mono<Void> leave(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable String id,
            @Parameter(description = "True when fired from the browser's pagehide handler rather than by the player.")
            @RequestParam(name = "unload", defaultValue = "false") boolean unload) {
        return service.leave(id, jwt.getSubject(), unload);
    }

    @Operation(
        summary = "Force-close a session",
        description = """
            Marks `closed_at = NOW()` so the session disappears from the list
            endpoint. Only the creator may call this (admins force-close via
            `/api/admin/sessions/{id}`).
            """)
    @DeleteMapping("/{id:[A-Za-z0-9_\\-]{16,86}}")
    public Mono<Void> close(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable String id) {
        return service.get(id)
                .flatMap(s -> {
                    if (!jwt.getSubject().equals(s.creatorCasdoorId())) {
                        return Mono.error(new ResponseStatusException(HttpStatus.FORBIDDEN, "only the creator may close this session"));
                    }
                    return service.close(id);
                });
    }

    public record PauseRequest(String reason) {}
    public record PlayerPauseRequest(String reason, String supersededLaunchUid) {}

    @Operation(
        summary = "Session-wide pause",
        description = """
            Freezes every player's WASM iframe inside the session. The session
            creator or an admin may call this; the request fires the
            SESSION_PAUSED SessionControlEvent over the per-session and global
            SSE channels so every subscriber's game-bridge picks it up. Game
            content drives the actual pause behaviour (timeScale=0,
            stop-physics, etc.) via the HostBridge `session_control_received`
            signal. Idempotent: re-pausing an already-paused session re-emits
            the event without bumping `paused_at`.
            """)
    @PostMapping("/{id:[A-Za-z0-9_\\-]{16,86}}/pause")
    public Mono<GameSessionDto> pause(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable String id,
            @RequestBody(required = false) PauseRequest body) {
        String reason = body != null ? body.reason() : null;
        return service.get(id)
                .flatMap(s -> {
                    boolean isCreator = jwt.getSubject().equals(s.creatorCasdoorId());
                    // Games admin, the SAME principals the converter grants
                    // ROLE_GAMES_ADMIN (GamesAdminPolicy): the derived games_admin
                    // role in either realm, the realm `admin` only on the games
                    // realm. A bare platform ROLE_ADMIN on a mesh is not one.
                    boolean isAdmin   = gamesAdmin.isGamesAdmin(jwt);
                    if (!isCreator && !isAdmin) {
                        return Mono.error(new ResponseStatusException(
                                HttpStatus.FORBIDDEN, "only the creator or an admin may pause this session"));
                    }
                    return service.pauseSession(id, jwt.getSubject(), reason);
                });
    }

    @Operation(
        summary = "Resume a session-wide pause",
        description = """
            Clears `paused_at` and fires SESSION_RESUMED over the SSE channels.
            Creator-or-admin gated, same as pause.
            """)
    @PostMapping("/{id:[A-Za-z0-9_\\-]{16,86}}/resume")
    public Mono<GameSessionDto> resume(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable String id) {
        return service.get(id)
                .flatMap(s -> {
                    boolean isCreator = jwt.getSubject().equals(s.creatorCasdoorId());
                    // Games admin — same predicate as pause().
                    boolean isAdmin   = gamesAdmin.isGamesAdmin(jwt);
                    if (!isCreator && !isAdmin) {
                        return Mono.error(new ResponseStatusException(
                                HttpStatus.FORBIDDEN, "only the creator or an admin may resume this session"));
                    }
                    return service.resumeSession(id, jwt.getSubject());
                });
    }

    @Operation(
        summary = "Tab-takeover pause for self",
        description = """
            Posted by a NEW tab when it opens the running game: tells the BFF
            to fire PLAYER_PAUSED so the OLD tab's WASM iframe releases input
            focus while the new tab loads its streams + assets. Caller targets
            themselves only — the JWT subject is the affected casdoor id.
            Ephemeral; the BFF tracks pending takeovers in-memory.
            """)
    @PostMapping("/{id:[A-Za-z0-9_\\-]{16,86}}/players/me/pause")
    public Mono<Void> pauseSelf(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable String id,
            @RequestBody(required = false) PlayerPauseRequest body) {
        String reason  = body != null ? body.reason() : null;
        String supUid  = body != null ? body.supersededLaunchUid() : null;
        return service.pausePlayer(id, jwt.getSubject(), reason, supUid);
    }

    @Operation(
        summary = "Tab-takeover resume for self",
        description = """
            Posted by the NEW tab once it has finished loading streams + assets
            and is ready to drive input. Fires PLAYER_RESUMED so the OLD tab
            can fully tear down.
            """)
    @PostMapping("/{id:[A-Za-z0-9_\\-]{16,86}}/players/me/resume")
    public Mono<Void> resumeSelf(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable String id) {
        return service.resumePlayer(id, jwt.getSubject());
    }
}
