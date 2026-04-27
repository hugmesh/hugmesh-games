package games.snapdragon.bff.controller;

import games.snapdragon.bff.model.GameSessionDto;
import games.snapdragon.bff.service.GameSessionService;
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
 * <p>Hot-path control fan-out lives in {@link games.snapdragon.bff.service.GameStateService}'s
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
            @Parameter(description = "Filter to one game's slug. Omit to list all open sessions.")
            @RequestParam(required = false) String gameSlug) {
        return service.listOpen(gameSlug);
    }

    @Operation(summary = "Get one session with its active members")
    @GetMapping("/{id:[0-9a-fA-F-]{36}}")
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
    @PostMapping("/{id:[0-9a-fA-F-]{36}}/join")
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
    @DeleteMapping("/{id:[0-9a-fA-F-]{36}}/me")
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
    @DeleteMapping("/{id:[0-9a-fA-F-]{36}}")
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
}
