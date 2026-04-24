package com.threewizards.controller;

import com.threewizards.model.*;
import com.threewizards.repository.GameRepository;
import com.threewizards.repository.LevelRepository;
import com.threewizards.service.ProgressionService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@Tag(name = "Games",
     description = "Umbrella Game tier. Each game groups one or more Levels (each its own WASM bundle). Public — no authentication required.")
@RestController
@RequestMapping("/api/games")
public class GameController {

    private final GameRepository games;
    private final LevelRepository levels;
    private final ProgressionService progression;

    public GameController(GameRepository games, LevelRepository levels,
                          ProgressionService progression) {
        this.games       = games;
        this.levels      = levels;
        this.progression = progression;
    }

    @Operation(
        summary = "List all umbrella games",
        description = """
            Returns every game ordered by sort_order. Games group related levels
            (WASM bundles). Fetch /api/games/{id}/levels to expand each.

            ```
            ──request──► gameRepo.findAllByOrderBySortOrderAsc()
             ──────────────●──●──|
                           Game{id, name, slug, sortOrder, …}…
            ```
            """)
    @GetMapping
    public Flux<Game> list() {
        return games.findAllByOrderBySortOrderAsc();
    }

    @Operation(summary = "Get one umbrella game")
    @GetMapping("/{id}")
    public Mono<Game> get(@PathVariable Long id) {
        return games.findById(id)
                .switchIfEmpty(Mono.error(new ResponseStatusException(HttpStatus.NOT_FOUND)));
    }

    @Operation(
        summary = "Levels belonging to a game",
        description = """
            Returns all playable levels for an umbrella game, in sort order.

            ```
            ──request──► levelRepo.findByGameIdOrderBySortOrderAsc(id)
             ──────────────●──●──|
                           Level{id, slug, assetUrl, sceneType, …}…
            ```
            """)
    @GetMapping("/{id}/levels")
    public Flux<Level> listLevels(@PathVariable Long id) {
        return levels.findByGameIdOrderBySortOrderAsc(id);
    }

    @Operation(
        summary = "Games with nested unlocked levels for a character",
        description = """
            Returns every umbrella game with its levels nested, each level
            annotated with unlock state + quest progress for the given character.

            ```
            ──request──► charQuests.findByCharacterId.collectList
                          → games.findAll().flatMap(g ->
                              levels.findByGameId(g.id)
                                .flatMap(l -> build AvailableLevelDto)
                                .collectList()
                              .map(ls -> GameWithLevelsDto))
             ──────────────●──●──|
                           GameWithLevelsDto…
            ```
            """)
    @GetMapping("/available/{characterId}")
    public Flux<GameWithLevelsDto> available(@PathVariable Long characterId) {
        return progression.availableGamesWithLevels(characterId);
    }
}
