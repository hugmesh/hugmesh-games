package games.hugmesh.bff.controller;

import games.hugmesh.bff.repository.GameLaunchRepository;
import games.hugmesh.bff.repository.GameRepository;
import games.hugmesh.bff.upload.UploadStorage;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;

import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;

@Tag(name = "Assets", description = "Static asset delivery for the Godot WASM bundle and other game files. Public — no authentication required.")
@RestController
@RequestMapping("/api/assets")
public class AssetController {

    private final GameLaunchRepository launches;
    private final GameRepository games;
    /**
     * When {@code true}, the level-asset path requires a launch UID
     * minted by {@code POST /api/games/{slug}/launch}. While the
     * federated client is still rolling out the launch flow, this stays
     * {@code false} so existing iframe consumers (the legacy SPA path)
     * keep working. Flip via env: {@code APP_ASSETS_REQUIRE_LAUNCH_UID=true}.
     */
    private final boolean requireLaunchUid;
    /**
     * Storage strategy for hot-uploaded games. Resolves {@code locator +
     * relativePath} into either a {@code FileSystemResource} (filesystem
     * strategy) or a {@code ByteArrayResource} pulled from S3.
     * Configured via {@code app.uploads.storage}; see
     * {@link games.hugmesh.bff.upload.UploadStorageConfig}.
     */
    private final UploadStorage storage;
    /**
     * When {@code true}, the level-asset endpoints attempt to mint a
     * pre-signed read URL from the {@link UploadStorage} strategy and
     * 302-redirect the client to fetch the bytes directly from the
     * object store. Falls back to streaming through the BFF when the
     * strategy returns {@code null} (filesystem mode, or the object
     * isn't present). Decision-020 follow-up; default {@code false}
     * keeps the existing streaming semantics for compatibility.
     */
    private final boolean presignedReadsEnabled;
    /**
     * TTL the BFF requests on every pre-signed URL. The strategy may
     * cap it lower; the storage layer enforces a 15-minute hard
     * maximum (defensive — keeps a leaked URL from being replayable
     * indefinitely).
     */
    private final Duration presignedReadTtl;

    public AssetController(GameLaunchRepository launches,
                           GameRepository games,
                           UploadStorage storage,
                           @Value("${app.assets.require-launch-uid:false}") boolean requireLaunchUid,
                           @Value("${app.uploads.s3.presigned-reads:false}") boolean presignedReadsEnabled,
                           @Value("${app.uploads.s3.presigned-reads-ttl-seconds:300}") long presignedReadsTtlSec) {
        this.launches = launches;
        this.games = games;
        this.storage = storage;
        this.requireLaunchUid = requireLaunchUid;
        this.presignedReadsEnabled = presignedReadsEnabled;
        this.presignedReadTtl = Duration.ofSeconds(Math.max(60, presignedReadsTtlSec));
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
    // `{*filename}` is Spring WebFlux's capture-the-rest syntax — it
    // greedily binds every remaining path segment (with embedded `/`s)
    // into one variable. The original `{filename:.+}` matches a SINGLE
    // segment, so a maker-uploaded chapter whose asset_url contains a
    // subdir (e.g. `square-row/index.html`) couldn't be reached via
    // this endpoint at all — the URL had too many segments and Spring
    // fell off both routes to 404. The capture-rest variant resolves
    // the URL exactly like the production wasm-fragment proxy already
    // does, plus the filename will include a leading `/` so we strip
    // it in serveLevelFile before resolving against the version dir.
    @GetMapping("/levels/{slug}/{*filename}")
    public Mono<ResponseEntity<Resource>> getLevelAsset(
            @PathVariable String slug,
            @PathVariable String filename) {
        if (requireLaunchUid) {
            return Mono.error(new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "launch-uid required — use /api/assets/levels/{slug}/{uid}/{filename}"));
        }
        return serveLevelFile(slug, stripLeadingSlash(filename));
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
    @GetMapping("/levels/{slug}/{uid}/{*filename}")
    public Mono<ResponseEntity<Resource>> getLevelAssetByUid(
            @PathVariable String slug,
            @PathVariable String uid,
            @PathVariable String filename) {
        final String cleanFilename = stripLeadingSlash(filename);
        return launches.findByUidAndTerminatedAtIsNullAndExpiresAtAfter(uid, Instant.now())
                .switchIfEmpty(Mono.error(new ResponseStatusException(HttpStatus.NOT_FOUND)))
                .flatMap(launch -> {
                    if (!Objects.equals(launch.getGameSlug(), slug)) {
                        return Mono.error(new ResponseStatusException(HttpStatus.NOT_FOUND));
                    }
                    // Active-version pin: serve from the dir captured at
                    // launch time so a hot upload mid-session doesn't swap
                    // WASM under the player's feet. Falls back to the
                    // current games.current_version_dir / classpath when
                    // the launch wasn't pinned (legacy launch row, or
                    // game has never been hot-uploaded).
                    String pinned = launch.getVersionDir();
                    if (pinned == null || pinned.isBlank()) {
                        return serveLevelFile(slug, cleanFilename);
                    }
                    return serveFromPinnedDir(pinned, slug, cleanFilename);
                });
    }

    /**
     * Spring WebFlux's {@code @PathVariable} on a {@code {*name}}
     * capture-the-rest pattern always includes a leading {@code /} —
     * that's the spec, but it'd cause every {@link Path#resolve}
     * downstream to throw "absolute path can't resolve against base".
     * Strip it once at the boundary so the rest of this controller
     * (and the same code paths the single-segment legacy variant
     * fed) work unchanged.
     */
    private static String stripLeadingSlash(String s) {
        if (s == null) return "";
        return s.startsWith("/") ? s.substring(1) : s;
    }

    private Mono<ResponseEntity<Resource>> serveFromPinnedDir(String locator,
                                                              String slug,
                                                              String filename) {
        return Mono.fromCallable(() -> {
            // Pre-signed URL fast path — when enabled AND the strategy
            // supports it (S3), the browser fetches the bytes directly
            // from the object store and the BFF stays cold.
            ResponseEntity<Resource> redirect = tryPresignRedirect(locator, filename);
            if (redirect != null) return redirect;

            Resource res = storage.read(locator, filename);
            if (res == null) {
                // Pinned location gone (manual cleanup, disk pressure prune,
                // S3 bucket lifecycle delete) — fall back to the classpath
                // build of the level. This accepts a small mid-session
                // asset drift but avoids 404.
                return respondWith(new ClassPathResource("assets/levels/" + slug + "/" + filename),
                                   filename);
            }
            return respondWith(res, filename);
        });
    }

    /**
     * Returns a 302-redirect to a pre-signed URL when:
     * <ul>
     *   <li>{@code app.uploads.s3.presigned-reads=true}</li>
     *   <li>The active {@link UploadStorage} supports pre-signing
     *       (S3 strategy returns non-null from
     *       {@link UploadStorage#presignReadUrl}).</li>
     *   <li>The object exists at the pinned locator + relative path.</li>
     * </ul>
     * Returns {@code null} when any of those is false; callers fall
     * back to streaming the bytes through the BFF.
     */
    private ResponseEntity<Resource> tryPresignRedirect(String locator, String filename) {
        if (!presignedReadsEnabled || locator == null || locator.isBlank()) return null;
        URI url = storage.presignReadUrl(locator, filename, presignedReadTtl);
        if (url == null) return null;
        // Cache-Control: max-age tracks the presign TTL so a CDN /
        // browser cache doesn't keep the URL longer than its
        // signature validity. The presign window is the source of
        // truth.
        return ResponseEntity.status(HttpStatus.FOUND)
                .header(HttpHeaders.LOCATION, url.toString())
                .header(HttpHeaders.CACHE_CONTROL,
                        "private, max-age=" + Math.max(60, presignedReadTtl.toSeconds() / 2))
                .<Resource>build();
    }

    private Mono<ResponseEntity<Resource>> serveFile(String path) {
        return Mono.fromCallable(() -> respondWith(new ClassPathResource(path), path));
    }

    /**
     * Two-tier resolution for level assets:
     * <ol>
     *   <li>Hot-uploaded version — read {@code games.current_version_dir}
     *       (an opaque locator the {@link UploadStorage} strategy
     *       understands) and look for the file there.</li>
     *   <li>Classpath fallback — for games shipped in the API image
     *       (cave / field / dodecahedron at boot).</li>
     * </ol>
     * The first non-404 wins. Zip-slip-safety lives in the strategy
     * itself: {@code FilesystemUploadStorage.read} rejects any filename
     * that would escape the version dir after normalisation; the S3
     * strategy can't be zip-slipped because the key is built by
     * concatenation, never resolved against a filesystem path.
     */
    private Mono<ResponseEntity<Resource>> serveLevelFile(String slug, String filename) {
        return games.findBySlug(slug)
                .map(g -> {
                    String locator = g.getCurrentVersionDir();
                    if (locator == null || locator.isBlank()) {
                        return Mono.fromCallable(() ->
                                respondWith(new ClassPathResource("assets/levels/" + slug + "/" + filename),
                                            filename));
                    }
                    return Mono.fromCallable(() -> {
                        // Pre-signed URL fast path (decision-020 follow-up).
                        ResponseEntity<Resource> redirect = tryPresignRedirect(locator, filename);
                        if (redirect != null) return redirect;

                        Resource res = storage.read(locator, filename);
                        if (res == null) {
                            // Fall back to classpath if the hot-uploaded
                            // version doesn't have the file (a partial
                            // upload or a renamed asset).
                            return respondWith(new ClassPathResource("assets/levels/" + slug + "/" + filename),
                                               filename);
                        }
                        return respondWith(res, filename);
                    });
                })
                .flatMap(m -> m)
                .switchIfEmpty(Mono.fromCallable(() ->
                        respondWith(new ClassPathResource("assets/levels/" + slug + "/" + filename), filename)));
    }

    private static ResponseEntity<Resource> respondWith(Resource resource, String filenameForExt) {
        if (!resource.exists()) {
            return ResponseEntity.<Resource>notFound().build();
        }
        String ext = filenameForExt.contains(".")
                ? filenameForExt.substring(filenameForExt.lastIndexOf('.') + 1).toLowerCase()
                : "";
        MediaType mediaType = MEDIA_TYPES.getOrDefault(ext, MediaType.APPLICATION_OCTET_STREAM);
        return ResponseEntity.ok()
                .contentType(mediaType)
                .<Resource>body(resource);
    }
}
