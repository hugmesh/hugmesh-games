package games.hugmesh.bff.controller;

import games.hugmesh.bff.model.GameTimeDto;
import games.hugmesh.bff.service.GameTimeService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

@Tag(name = "Game Time", description = "In-game clock. One game-minute elapses every ~10 real seconds. The full cycle advances and persists automatically.")
@RestController
@RequestMapping("/api/time")
public class GameTimeController {

    private final GameTimeService gameTimeService;

    public GameTimeController(GameTimeService gameTimeService) {
        this.gameTimeService = gameTimeService;
    }

    @Operation(
        summary = "Current in-game time",
        description = """
            Returns the current in-game day, hour, and minute. The `GameTimeService`
            scheduler advances the clock every ~10 real seconds and persists to
            `game_time_state`. SSE subscribers receive `time` events automatically —
            this endpoint is a one-shot snapshot for initial page load.

            ```
            GameTimeService (background):
              real clock ──10s──10s──10s──10s──⋯
                              │      │      │
                          minute++ minute++ day++ (at 23:59)
                          persist  emit time$ SSE

            GET /api/time:
            ──request──► Mono.just(gameTimeService.currentTime())
             ──────────────────────────●─|
                                        {dayNumber, hour, minute}
            ```
            """)
    @GetMapping
    public Mono<GameTimeDto> current() {
        return Mono.just(gameTimeService.currentTime());
    }
}
