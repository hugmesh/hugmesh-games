package games.hugmesh.bff.controller;

import games.hugmesh.bff.model.PlayerViewEvent;
import games.hugmesh.bff.service.GameSessionService;
import games.hugmesh.bff.service.GameStateService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;

import java.time.Instant;

/**
 * Per-player view-state ingress.
 *
 * <p>The Rust GDExtension's {@code view_egress} signal pushes a small
 * camera + orientation sample at ~10 Hz. The Godot bundle posts that
 * sample here; the BFF authorizes it (the JWT subject must be the active
 * member who owns the slot's character) and fans out on the per-session
 * {@code view} SSE channel that admins subscribe to via
 * {@code /api/admin/sessions/{id}/stream}.
 *
 * <p>The endpoint deliberately does NOT persist to Postgres — view samples
 * are pure ephemera. A short-lived ring of recent samples is mirrored
 * into Cassandra's {@code session_view_state} (60 s TTL) so an admin
 * joining the per-session view stream sees the latest sample immediately
 * instead of waiting for the next ~10 Hz tick.
 */
@Tag(name = "View egress",
     description = "Per-player camera/orientation samples streamed by the Rust GDExtension. Fans out on the per-session SSE view channel admins watch.")
@RestController
@RequestMapping("/api/view")
public class ViewEgressController {

    private final GameStateService     gameState;
    private final GameSessionService   sessions;

    public ViewEgressController(GameStateService gameState, GameSessionService sessions) {
        this.gameState = gameState;
        this.sessions  = sessions;
    }

    public record ViewSampleRequest(
            String  sessionId,
            int     slotIndex,
            Long    characterId,
            double  x, double y, double z,
            double  yaw, double pitch, double fov
    ) {}

    @Operation(
        summary = "Submit a player-view sample",
        description = """
            Authorizes the caller as the active member who owns
            `characterId` for `sessionId`, then fans out the sample on
            the per-session view SSE channel. The JWT subject must match
            the `session_members.casdoor_id` of the active row owning
            `characterId`; otherwise *403*.

            ```
            ──request──► sessions.authorizeControl(sub, characterId)
                          ──●──► gameState.emitView(PlayerViewEvent)
                             ──●─| 202 Accepted
            ```
            """)
    @PostMapping
    public Mono<ResponseEntity<Void>> submit(
            @AuthenticationPrincipal Jwt jwt,
            @RequestBody ViewSampleRequest req) {

        if (jwt == null) {
            return Mono.error(new ResponseStatusException(HttpStatus.UNAUTHORIZED, "JWT required"));
        }
        if (req.sessionId() == null || req.sessionId().isBlank() || req.characterId() == null) {
            return Mono.error(new ResponseStatusException(HttpStatus.BAD_REQUEST, "sessionId + characterId required"));
        }
        return sessions.authorizeControl(jwt.getSubject(), req.characterId())
                .map(member -> {
                    PlayerViewEvent ev = new PlayerViewEvent(
                            req.sessionId(), req.slotIndex(), req.characterId(),
                            req.x(), req.y(), req.z(),
                            req.yaw(), req.pitch(), req.fov(),
                            Instant.now());
                    gameState.emitView(ev);
                    return ResponseEntity.accepted().<Void>build();
                });
    }
}
