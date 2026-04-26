package games.snapdragon.bff.controller;

import games.snapdragon.bff.service.GameStateService;
import games.snapdragon.bff.service.GameTimeService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;

@Tag(name = "Game Stream", description = "Multiplexed SSE stream delivering all real-time game events on a single connection.")
@RestController
@RequestMapping("/api/game")
public class GameStreamController {

    private final GameStateService gameStateService;
    private final GameTimeService gameTimeService;

    public GameStreamController(GameStateService gameStateService, GameTimeService gameTimeService) {
        this.gameStateService = gameStateService;
        this.gameTimeService  = gameTimeService;
    }

    @Operation(
        summary = "Subscribe to the multiplexed SSE game stream",
        description = """
            Returns a never-ending `text/event-stream`. Five reactive sources are merged
            into one connection; distinguish them with the SSE `event:` field.

            ```
            movement$  ──●──────────────────●────────●──⋯  position / action changed
            world$     ──●──●──●──●──●──●──●──●──●──●──⋯  heartbeat  (1 s)
            character$ ────────────●────────────────────⋯  HP/MP/XP/level changed
            time$      ─────────────────────●────────────⋯  game clock (every ~10 s real)
            scene$     ──●──────────────────────────────⋯  scene transition
            keepalive  ─────────────────────────────────⋯  comment    (30 s)

            merged:    ──●──●──●──●──●──●──●──●──●──●──⋯
                          ServerSentEvent<Object>
            ```

            **Event payloads**

            | event | key fields |
            |---|---|
            | `movement` | `x`, `y`, `z`, `rotationY`, `action`, `pressed` |
            | `world` | `sessionId`, `tick`, `timestamp` |
            | `character` | `health`, `mana`, `level`, `xp`, `xpToNext` |
            | `time` | `dayNumber`, `hour`, `minute` |
            | `scene` | `sceneId`, `sceneName`, `sceneType` |

            Connect with `EventSource` and register a listener per event type:
            ```js
            const es = new EventSource('/api/game/stream');
            es.addEventListener('movement', e => { /* ... */ });
            es.addEventListener('time',     e => { /* ... */ });
            ```
            """)
    @GetMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<Object>> stream() {
        AtomicLong sseId = new AtomicLong(0);

        Flux<ServerSentEvent<Object>> movement = gameStateService.movementStream()
                .map(m -> sse(sseId, "movement", m));

        Flux<ServerSentEvent<Object>> world = gameStateService.worldStream()
                .map(w -> sse(sseId, "world", w));

        Flux<ServerSentEvent<Object>> stats = gameStateService.charStatStream()
                .map(c -> sse(sseId, "character", c));

        Flux<ServerSentEvent<Object>> time = gameTimeService.timeStream()
                .map(t -> sse(sseId, "time", t));

        Flux<ServerSentEvent<Object>> scene = gameStateService.sceneStream()
                .map(e -> sse(sseId, "scene", e));

        // First keepalive at t=0 so response headers flush immediately —
        // browser EventSource only fires onopen once headers arrive, and the
        // Angular HUD's connected$ stays false until then ("API offline").
        // Subsequent keepalives every 15s.
        Flux<ServerSentEvent<Object>> keepalive = Flux.interval(Duration.ZERO, Duration.ofSeconds(15))
                .map(i -> ServerSentEvent.<Object>builder().comment("keepalive").build());

        return Flux.merge(movement, world, stats, time, scene, keepalive);
    }

    @Operation(
        summary = "Current game state snapshot",
        description = """
            Returns the most recently emitted game state without subscribing to the stream.

            ```
            ──request──► Mono.just(currentState())
             ──────────────────────────●─|
                                        {snapshot}
            ```
            """)
    @GetMapping("/state")
    public Object currentState() {
        return gameStateService.currentState();
    }

    private ServerSentEvent<Object> sse(AtomicLong id, String event, Object data) {
        return ServerSentEvent.<Object>builder()
                .id(String.valueOf(id.incrementAndGet()))
                .event(event)
                .data(data)
                .build();
    }
}
