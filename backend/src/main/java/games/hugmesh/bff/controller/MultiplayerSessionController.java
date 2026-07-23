package games.hugmesh.bff.controller;

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

    public MultiplayerSessionController(GameSessionService service) {
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

    @Operation(summary = "Get one session with its active members")
    @GetMapping("/{id:[A-Za-z0-9_\\-]{16,86}}")
    public Mono<GameSessionDto> get(@PathVariable String id) {
        return service.get(id);
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
            the session itself is auto-closed.
            """)
    @DeleteMapping("/{id:[A-Za-z0-9_\\-]{16,86}}/me")
    public Mono<Void> leave(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable String id) {
        return service.leave(id, jwt.getSubject());
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
                    // Canonical roles-array admin check (ROLE_ADMIN / roles
                    // claim), the SAME predicate the rest of the BFF uses
                    // (hasRole('ADMIN') + CharacterController's Roles.isAdmin).
                    // The legacy `isAdmin` boolean claim is NOT a bypass here —
                    // it is only honoured by the SecurityConfig authorities
                    // converter's back-compat fallback, never by Roles.isAdmin.
                    boolean isAdmin   = Roles.isAdmin(jwt);
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
                    // Canonical roles-array admin check — same as pause(); the
                    // legacy `isAdmin` boolean claim is not honoured here.
                    boolean isAdmin   = Roles.isAdmin(jwt);
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
