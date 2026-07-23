package games.hugmesh.bff.controller;

import games.hugmesh.bff.model.*;
import games.hugmesh.bff.repository.ChapterRepository;
import games.hugmesh.bff.repository.SceneElementRepository;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Chapter (formerly "level") metadata + scene endpoint.
 *
 * <p>Both URL prefixes are accepted: the canonical {@code /api/chapters/*}
 * path and the legacy {@code /api/levels/*} path. The dual mapping
 * exists because the patched Godot bundle's {@code index.html} hardcodes
 * the URL it prefetches the scene from at export time — every WASM
 * bundle currently in the wild calls {@code /api/levels/{id}/scene}.
 * Keeping the legacy alias means the rename ships without a forced
 * re-export of every game bundle. New callers should prefer the
 * canonical {@code /api/chapters} prefix.
 */
@Tag(name = "Chapters",
     description = "Individual playable chapters — each chapter is its own compiled Godot WASM bundle served from /api/assets/chapters/{slug}/. Legacy `/api/levels/*` paths still work.")
@RestController
@RequestMapping({ "/api/chapters", "/api/levels" })
public class ChapterController {

    private final ChapterRepository levels;
    private final SceneElementRepository sceneElements;

    public ChapterController(ChapterRepository levels, SceneElementRepository sceneElements) {
        this.levels        = levels;
        this.sceneElements = sceneElements;
    }

    @Operation(summary = "List all levels across all games")
    @GetMapping
    public Flux<Chapter> list() {
        return levels.findAllByOrderBySortOrderAsc();
    }

    @Operation(summary = "Get one level (WASM bundle metadata)")
    @GetMapping("/{id}")
    public Mono<Chapter> get(@PathVariable Long id) {
        return levels.findById(id)
                .switchIfEmpty(Mono.error(new ResponseStatusException(HttpStatus.NOT_FOUND)));
    }

    @Operation(
        summary = "Scene configuration for a level",
        description = """
            Returns the scene descriptor consumed by `scene_loader.gd` on startup.
            Includes the level's `assetUrl` and all `SceneElementDto` records
            ordered by sort_order.

            ```
            ──request──► levels.findById(id)
                          ──●──► flatMap ──►
                                 sceneElements.findByChapterIdOrderBySortOrder()
                                  ──●──●──●──● collectList()
                                             ──●─|  SceneDto{…, assetUrl, elements:[…]}
            ```
            """)
    @GetMapping("/{id}/scene")
    public Mono<SceneDto> getScene(@PathVariable Long id) {
        return levels.findById(id)
                .switchIfEmpty(Mono.error(new ResponseStatusException(HttpStatus.NOT_FOUND)))
                .flatMap(level -> sceneElements.findByChapterIdOrderBySortOrder(level.getId())
                        .map(e -> new SceneElementDto(
                                e.getId(), e.getElementType(), e.getName(),
                                e.getPositionX(), e.getPositionY(), e.getPositionZ(),
                                e.getRotationX(), e.getRotationY(), e.getRotationZ(),
                                e.getProperties()))
                        .collectList()
                        .map(elements -> new SceneDto(
                                level.getId(), level.getName(), level.getSlug(),
                                level.getSceneType(), level.getAssetUrl(), elements)));
    }
}
