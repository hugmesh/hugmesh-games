package games.hugmesh.bff.controller;

import games.hugmesh.bff.service.GameSessionService;
import games.hugmesh.bff.service.GameStateService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Admin live observation of a single multiplayer session.
 *
 * <p>The {@code /api/admin/sessions/{id}/stream} endpoint merges the
 * per-session SSE channels (movement, world, character, view) into one
 * connection scoped to a single session id. Admins pick a session in the
 * SessionsShell admin remote; the player-view channel is what lets them
 * see each slot's camera position and orientation in real time.
 *
 * <p>This is distinct from the global admin firehose at
 * {@code /api/admin/stream} (per-pod stats / roster / counters) — that
 * endpoint stays as-is.
 */
@Tag(name = "Admin session stream",
     description = "Per-session SSE merge for admin observation. Movement / world / character / view, scoped to one session id.")
@RestController
@RequestMapping("/api/admin/sessions")
@PreAuthorize("hasRole('ADMIN')")
public class AdminSessionStreamController {

    private final GameStateService   gameState;
    private final GameSessionService sessions;

    public AdminSessionStreamController(GameStateService gameState, GameSessionService sessions) {
        this.gameState = gameState;
        this.sessions  = sessions;
    }

    @Operation(
        summary = "Subscribe to one session's merged SSE stream",
        description = """
            Returns a never-ending `text/event-stream` carrying every event
            that the session's members produce — movement, world, character
            stat changes, and per-slot view samples. Subscribers of session
            A never see session B's events; the per-session sinks are
            isolated by construction.

            ```
            movement(sessionA)$ ──●──●──●──●──⋯
            world(sessionA)$    ────●──────●──⋯
            character(sessionA)$──────●────────⋯
            view(sessionA)$     ──●●●●●●●●●●●●●⋯  (~10 Hz per slot)
            keepalive           ──────────────⋯  (15 s)

            merged: ──●──●●●●●●●●●●●●●●●●●●●●●⋯
                       ServerSentEvent<Object>
            ```

            **Event payloads**

            | event | key fields |
            |---|---|
            | `movement` | `characterId`, `chapterId`, `x/y/z`, `action`, `pressed`, `tick` |
            | `world` | `chapterId`, `roster: PlayerMovement[]`, `tick` |
            | `character` | `characterId`, `hp`, `mp`, `xp`, status flags |
            | `view` | `slotIndex`, `characterId`, `x/y/z`, `yaw/pitch/fov`, `occurredAt` |

            ```js
            const es = new EventSource('/api/admin/sessions/' + sessionId + '/stream');
            es.addEventListener('view', e => { /* render frustum hint */ });
            ```
            """)
    @GetMapping(value = "/{id}/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<Object>> stream(
            @Parameter(description = "Session id (UUID).")
            @PathVariable String id) {
        AtomicLong sseId = new AtomicLong(0);

        Flux<ServerSentEvent<Object>> movement = gameState.movementStream(id)
                .map(m -> sse(sseId, "movement", m));
        Flux<ServerSentEvent<Object>> world = gameState.worldStream(id)
                .map(w -> sse(sseId, "world", w));
        Flux<ServerSentEvent<Object>> character = gameState.charStatStream(id)
                .map(c -> sse(sseId, "character", c));
        Flux<ServerSentEvent<Object>> view = gameState.viewStream(id)
                .map(v -> sse(sseId, "view", v));
        Flux<ServerSentEvent<Object>> sessionControl = gameState.sessionControlStream(id)
                .map(ev -> sse(sseId, "session-control", ev));

        Flux<ServerSentEvent<Object>> keepalive = Flux.interval(Duration.ZERO, Duration.ofSeconds(15))
                .map(i -> ServerSentEvent.<Object>builder().comment("keepalive").build());

        // Touch the session early so 404s on non-existent ids surface
        // immediately instead of after a long silent connection.
        return sessions.get(id)
                .thenMany(Flux.merge(movement, world, character, view, sessionControl, keepalive));
    }

    private ServerSentEvent<Object> sse(AtomicLong id, String event, Object data) {
        return ServerSentEvent.<Object>builder()
                .id(String.valueOf(id.incrementAndGet()))
                .event(event)
                .data(data)
                .build();
    }
}
