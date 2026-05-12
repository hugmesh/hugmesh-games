package games.snapdragon.bff.controller;

import games.snapdragon.bff.service.GameStateService;
import games.snapdragon.bff.service.GameTimeService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
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
    public Flux<ServerSentEvent<Object>> stream(
            @Parameter(description = "Optional multiplayer session id. When supplied, movement / world / character events are filtered to this session only — subscribers of session A never see session B's emissions. Time / scene / input / audio / wasm-console channels remain global because they're not session-scoped.")
            @RequestParam(value = "sessionId", required = false) String sessionId) {
        AtomicLong sseId = new AtomicLong(0);

        // Per-session sinks isolate sessions from one another. Time, scene,
        // input, audio, and WASM-console channels stay global — they're
        // shared infrastructure (game clock, scene transitions, dev-tools)
        // not session state.
        Flux<ServerSentEvent<Object>> movement = (sessionId != null && !sessionId.isBlank()
                        ? gameStateService.movementStream(sessionId)
                        : gameStateService.movementStream())
                .map(m -> sse(sseId, "movement", m));

        Flux<ServerSentEvent<Object>> world = (sessionId != null && !sessionId.isBlank()
                        ? gameStateService.worldStream(sessionId)
                        : gameStateService.worldStream())
                .map(w -> sse(sseId, "world", w));

        Flux<ServerSentEvent<Object>> stats = (sessionId != null && !sessionId.isBlank()
                        ? gameStateService.charStatStream(sessionId)
                        : gameStateService.charStatStream())
                .map(c -> sse(sseId, "character", c));

        Flux<ServerSentEvent<Object>> time = gameTimeService.timeStream()
                .map(t -> sse(sseId, "time", t));

        Flux<ServerSentEvent<Object>> scene = gameStateService.sceneStream()
                .map(e -> sse(sseId, "scene", e));

        // Generalised input + audio + WASM-console channels.
        // Subscribers can opt into just one via the EventSource
        // event-listener model (`source.addEventListener('input', cb)`).
        // Merging onto a single connection keeps the host page from
        // needing N EventSources to see everything.
        Flux<ServerSentEvent<Object>> input = gameStateService.inputStream()
                .map(i -> sse(sseId, "input", i));
        Flux<ServerSentEvent<Object>> audio = gameStateService.audioStream()
                .map(a -> sse(sseId, "audio", a));
        Flux<ServerSentEvent<Object>> wasmConsole = gameStateService.wasmConsoleStream()
                .map(c -> sse(sseId, "wasm-console", c));

        // Session lifecycle (pause / resume / close) — per-session when
        // X-Session-Id is set so a player only sees their own session's
        // lifecycle events; global otherwise so the host shell can react
        // to a SESSION_CLOSED that targets a session this tab knows
        // about even when no session header is present.
        Flux<ServerSentEvent<Object>> sessionControl = (sessionId != null && !sessionId.isBlank()
                        ? gameStateService.sessionControlStream(sessionId)
                        : gameStateService.sessionControlStream())
                .map(ev -> sse(sseId, "session-control", ev));

        // First keepalive at t=0 so response headers flush immediately —
        // browser EventSource only fires onopen once headers arrive, and the
        // Angular HUD's connected$ stays false until then ("API offline").
        // Subsequent keepalives every 15s.
        Flux<ServerSentEvent<Object>> keepalive = Flux.interval(Duration.ZERO, Duration.ofSeconds(15))
                .map(i -> ServerSentEvent.<Object>builder().comment("keepalive").build());

        return Flux.merge(movement, world, stats, time, scene,
                          input, audio, wasmConsole, sessionControl, keepalive);
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
