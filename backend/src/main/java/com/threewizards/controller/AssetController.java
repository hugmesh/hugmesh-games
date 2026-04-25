package com.threewizards.controller;

import com.threewizards.repository.GameLaunchRepository;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;

@Tag(name = "Assets", description = "Static asset delivery for the Godot WASM bundle and other game files. Public — no authentication required.")
@RestController
@RequestMapping("/api/assets")
public class AssetController {

    private final GameLaunchRepository launches;
    /**
     * When {@code true}, the level-asset path requires a launch UID
     * minted by {@code POST /api/games/{slug}/launch}. While the
     * federated client is still rolling out the launch flow, this stays
     * {@code false} so existing iframe consumers (the legacy SPA path)
     * keep working. Flip via env: {@code APP_ASSETS_REQUIRE_LAUNCH_UID=true}.
     */
    private final boolean requireLaunchUid;

    public AssetController(GameLaunchRepository launches,
                           @Value("${app.assets.require-launch-uid:false}") boolean requireLaunchUid) {
        this.launches = launches;
        this.requireLaunchUid = requireLaunchUid;
    }

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
        summary = "Serve a level-scoped asset (legacy path, no launch UID)",
        description = """
            Legacy path that does NOT require a launch uid. Kept while the
            iframe-based SPA is still in production; the federated remote
            uses the {uid}-suffixed variant below. Flip
            `app.assets.require-launch-uid=true` in application.yml to make
            this path 403, forcing all consumers onto the gated path.
            """)
    @GetMapping("/levels/{slug}/{filename:.+}")
    public Mono<ResponseEntity<Resource>> getLevelAsset(
            @PathVariable String slug,
            @PathVariable String filename) {
        if (requireLaunchUid) {
            return Mono.error(new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "launch-uid required — use /api/assets/levels/{slug}/{uid}/{filename}"));
        }
        return serveFile("assets/levels/" + slug + "/" + filename);
    }

    @Operation(
        summary = "Serve a level-scoped asset, gated on a launch uid",
        description = """
            The federated remote calls `POST /api/games/{slug}/launch` to
            mint a per-session uid, then loads its WASM bundle from this
            endpoint. The BFF resolves the uid in `game_launches` and
            confirms it (a) hasn't been terminated, (b) is still within
            its TTL, and (c) was issued for this exact game slug — so a
            valid uid for game X can't be repurposed to fetch game Y's
            assets. Mismatches return 404 (not 403) so an attacker can't
            tell whether a uid exists.
            """)
    @GetMapping("/levels/{slug}/{uid}/{filename:.+}")
    public Mono<ResponseEntity<Resource>> getLevelAssetByUid(
            @PathVariable String slug,
            @PathVariable String uid,
            @PathVariable String filename) {
        return launches.findByUidAndTerminatedAtIsNullAndExpiresAtAfter(uid, Instant.now())
                .switchIfEmpty(Mono.error(new ResponseStatusException(HttpStatus.NOT_FOUND)))
                .flatMap(launch -> {
                    if (!Objects.equals(launch.getGameSlug(), slug)) {
                        return Mono.error(new ResponseStatusException(HttpStatus.NOT_FOUND));
                    }
                    return serveFile("assets/levels/" + slug + "/" + filename);
                });
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
