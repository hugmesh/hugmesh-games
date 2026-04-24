package com.threewizards.controller;

import com.threewizards.model.*;
import com.threewizards.repository.LevelRepository;
import com.threewizards.repository.SceneElementRepository;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@Tag(name = "Levels",
     description = "Individual playable levels — each level is its own compiled Godot WASM bundle served from /api/assets/levels/{slug}/.")
@RestController
@RequestMapping("/api/levels")
public class LevelController {

    private final LevelRepository levels;
    private final SceneElementRepository sceneElements;

    public LevelController(LevelRepository levels, SceneElementRepository sceneElements) {
        this.levels        = levels;
        this.sceneElements = sceneElements;
    }

    @Operation(summary = "List all levels across all games")
    @GetMapping
    public Flux<Level> list() {
        return levels.findAllByOrderBySortOrderAsc();
    }

    @Operation(summary = "Get one level (WASM bundle metadata)")
    @GetMapping("/{id}")
    public Mono<Level> get(@PathVariable Long id) {
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
                                 sceneElements.findByLevelIdOrderBySortOrder()
                                  ──●──●──●──● collectList()
                                             ──●─|  SceneDto{…, assetUrl, elements:[…]}
            ```
            """)
    @GetMapping("/{id}/scene")
    public Mono<SceneDto> getScene(@PathVariable Long id) {
        return levels.findById(id)
                .switchIfEmpty(Mono.error(new ResponseStatusException(HttpStatus.NOT_FOUND)))
                .flatMap(level -> sceneElements.findByLevelIdOrderBySortOrder(level.getId())
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
