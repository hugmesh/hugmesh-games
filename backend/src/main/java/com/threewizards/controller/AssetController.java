package com.threewizards.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;

import java.util.Map;

@Tag(name = "Assets", description = "Static asset delivery for the Godot WASM bundle and other game files. Public — no authentication required.")
@RestController
@RequestMapping("/api/assets")
public class AssetController {

    private static final Map<String, MediaType> MEDIA_TYPES = Map.ofEntries(
            Map.entry("html", MediaType.TEXT_HTML),
            Map.entry("js",   MediaType.valueOf("application/javascript")),
            Map.entry("css",  MediaType.valueOf("text/css")),
            Map.entry("png",  MediaType.IMAGE_PNG),
            Map.entry("jpg",  MediaType.IMAGE_JPEG),
            Map.entry("jpeg", MediaType.IMAGE_JPEG),
            Map.entry("svg",  MediaType.valueOf("image/svg+xml")),
            Map.entry("json", MediaType.APPLICATION_JSON),
            Map.entry("bin",  MediaType.APPLICATION_OCTET_STREAM),
            Map.entry("wasm", MediaType.valueOf("application/wasm")),
            Map.entry("pck",  MediaType.APPLICATION_OCTET_STREAM),
            Map.entry("glb",  MediaType.valueOf("model/gltf-binary")),
            Map.entry("gltf", MediaType.valueOf("model/gltf+json"))
    );

    @Operation(
        summary = "Serve a root-level static asset",
        description = """
            Serves a file from `classpath:assets/{filename}` with content-type
            resolved from the file extension.

            ```
            ──request──► Mono.fromCallable(() -> ClassPathResource("assets/" + filename))
                               │
                     ┌─────────┴──────────┐
                     │ exists             │ missing
                     ▼                    ▼
              .contentType(mediaType)  ResponseEntity.notFound()
              .body(resource)
              ──────●─|                   ──✗  404
            ```
            """)
    @GetMapping("/{filename:.+}")
    public Mono<ResponseEntity<Resource>> getAsset(@PathVariable String filename) {
        return serveFile("assets/" + filename);
    }

    @Operation(
        summary = "Serve a level-scoped asset (compiled WASM bundle or registered asset)",
        description = """
            Primary path for the per-level Godot WASM bundle.
            Each level gets its own directory under `classpath:assets/levels/{slug}/`,
            containing its compiled `index.html`, `index.wasm`, `index.pck`, and any
            textures/plugins registered via the level_assets table.

            ```
            ──request──► Mono.fromCallable(() ->
                            ClassPathResource("assets/levels/" + slug + "/" + filename))
                               │
                     ┌─────────┴──────────┐
                     │ exists             │ missing
                     ▼                    ▼
              .contentType(mediaType)   ──✗  404
              .body(resource)
              ──────●─|
            ```

            When a level transition is needed in the game window, the Godot WASM
            signals the Angular host via `window.parent.postMessage(...)` and the
            Angular `game-canvas.component.ts` swaps the iframe `src` to the next
            level's `assetUrl` (pointed at this endpoint).
            """)
    @GetMapping("/levels/{slug}/{filename:.+}")
    public Mono<ResponseEntity<Resource>> getLevelAsset(
            @PathVariable String slug,
            @PathVariable String filename) {
        return serveFile("assets/levels/" + slug + "/" + filename);
    }

    private Mono<ResponseEntity<Resource>> serveFile(String path) {
        return Mono.fromCallable(() -> {
            ClassPathResource resource = new ClassPathResource(path);
            if (!resource.exists()) {
                return ResponseEntity.<Resource>notFound().build();
            }
            String filename = path.substring(path.lastIndexOf('/') + 1);
            String ext = filename.contains(".")
                    ? filename.substring(filename.lastIndexOf('.') + 1).toLowerCase()
                    : "";
            MediaType mediaType = MEDIA_TYPES.getOrDefault(ext, MediaType.APPLICATION_OCTET_STREAM);
            return ResponseEntity.ok()
                    .contentType(mediaType)
                    .<Resource>body(resource);
        });
    }
}
