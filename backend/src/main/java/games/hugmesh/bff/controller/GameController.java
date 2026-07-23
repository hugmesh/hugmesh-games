package games.hugmesh.bff.controller;

import games.hugmesh.bff.model.*;
import games.hugmesh.bff.repository.GameRepository;
import games.hugmesh.bff.repository.ChapterRepository;
import games.hugmesh.bff.security.Roles;
import games.hugmesh.bff.service.ProgressionService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@Tag(name = "Games",
     description = "Umbrella Game tier. Each game groups one or more Chapters (each its own WASM bundle). Public — no authentication required.")
@RestController
@RequestMapping("/api/games")
public class GameController {

    private final GameRepository games;
    private final ChapterRepository levels;
    private final ProgressionService progression;
    private final games.hugmesh.bff.security.DomainRegistry domainRegistry;

    public GameController(GameRepository games, ChapterRepository levels,
                          ProgressionService progression,
                          games.hugmesh.bff.security.DomainRegistry domainRegistry) {
        this.games          = games;
        this.levels         = levels;
        this.progression    = progression;
        this.domainRegistry = domainRegistry;
    }

    @Operation(
        summary = "List all umbrella games",
        description = """
            Returns every game ordered by sort_order. Games group related chapters
            (WASM bundles). Fetch /api/games/{id}/chapters to expand each
            (the legacy /api/games/{id}/levels path also still works).

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
        summary = "Chapters belonging to a game",
        description = """
            Returns all playable chapters for an umbrella game, in sort order.
            Both /{id}/chapters (canonical) and /{id}/levels (legacy alias,
            kept for already-exported WASM bundles that hardcode the old path)
            resolve here — same dual-mapping pattern as ChapterController.

            ```
            ──request──► chapterRepo.findByGameIdOrderBySortOrderAsc(id)
             ──────────────●──●──|
                           Chapter{id, slug, assetUrl, sceneType, …}…
            ```
            """)
    @GetMapping({ "/{id}/chapters", "/{id}/levels" })
    public Flux<Chapter> listChapters(@PathVariable Long id) {
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
                                .flatMap(l -> build AvailableChapterDto)
                                .collectList()
                              .map(ls -> GameWithChaptersDto))
             ──────────────●──●──|
                           GameWithChaptersDto…
            ```
            """)
    @GetMapping("/available/{characterId}")
    public Flux<GameWithChaptersDto> available(@PathVariable Long characterId,
                                             @org.springframework.security.core.annotation.AuthenticationPrincipal
                                             org.springframework.security.oauth2.jwt.Jwt jwt) {
        // Casdoor-side roles + user id come from the JWT. The progression
        // service intersects the catalog against game_access policies before
        // returning. With no JWT (legacy unauthenticated callers), only
        // scope='all' games are returned.
        java.util.List<String> roles = Roles.fromJwt(jwt);
        String userId = (jwt == null) ? null : jwt.getSubject();
        // Brand-scope the catalog: a branded principal sees only their brand's games
        // (+ globals); anonymous resolves to "default" → globals only; single-tenant
        // unchanged (every game + caller is 'default'). Decision 026.
        return progression.availableGamesWithLevels(characterId, roles, userId,
                Roles.domainOf(jwt, domainRegistry), Roles.isSuperadmin(jwt));
    }
}
