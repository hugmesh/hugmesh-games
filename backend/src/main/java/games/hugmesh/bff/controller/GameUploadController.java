package games.hugmesh.bff.controller;

import games.hugmesh.bff.security.GamesAdminPolicy;

import games.hugmesh.bff.model.GameUpload;
import games.hugmesh.bff.repository.GameUploadRepository;
import games.hugmesh.bff.security.DomainRegistry;
import games.hugmesh.bff.security.Roles;
import games.hugmesh.bff.service.DomainScopeService;
import games.hugmesh.bff.service.GameUploadService;
import games.hugmesh.bff.service.GameUploadService.InstalledUpload;
import games.hugmesh.bff.service.PublisherService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.HttpStatus;
import org.springframework.http.codec.multipart.FilePart;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;


/**
 * Admin endpoint for hot-uploading a HugMesh bundle zip produced by
 * the Godot editor plugin. Validation + atomic install happens in
 * {@link GameUploadService}; this controller is a thin REST surface.
 *
 * <p>Auth: gated on {@code ROLE_GAMES_ADMIN} via the JWT roles claim. Players
 * with {@code uploader} role can also reach it through the
 * {@code upload-games} permission once we wire that into the
 * {@code @PreAuthorize} expression.
 */
@Tag(name = "Game uploads", description = "Admin-only: install a Godot bundle zip + run register.sql.")
@RestController
@RequestMapping("/api/admin/games")
public class GameUploadController {

    private final GameUploadService uploadService;
    private final GameUploadRepository uploads;
    private final PublisherService publisherService;
    private final DomainScopeService domainScope;
    private final DomainRegistry domainRegistry;
    /** Who is a games admin (realm-aware; see GamesAdminPolicy). */
    private final GamesAdminPolicy gamesAdmin;

    public GameUploadController(GameUploadService uploadService,
                                GameUploadRepository uploads,
                                PublisherService publisherService,
                                DomainScopeService domainScope,
                                DomainRegistry domainRegistry,
                                GamesAdminPolicy gamesAdmin) {
        this.gamesAdmin = gamesAdmin;
        this.uploadService    = uploadService;
        this.uploads          = uploads;
        this.publisherService = publisherService;
        this.domainScope      = domainScope;
        this.domainRegistry   = domainRegistry;
    }

    @Operation(
        summary = "Install a HugMesh bundle zip",
        description = """
            Multipart upload (field name: `bundle`). Body:
              • zip with manifest.json (schema_version 2), bundle/, register.sql
              • produced by `Project → Tools → HugMesh → Export to HugMesh…`

            On success returns the installed-upload summary. On failure rolls
            back the DB transaction and removes the staged dir.
            """)
    @PostMapping(value = "/upload", consumes = "multipart/form-data")
    @PreAuthorize("hasAnyRole('GAMES_ADMIN','PUBLISHER')")
    public Mono<InstalledUpload> upload(@RequestPart("bundle") FilePart bundle,
                                        @AuthenticationPrincipal Jwt jwt) {
        // Admins can upload bundles for any game; publishers must hold a
        // publisher_game_scopes row for the slug carried in the manifest.
        // The slug isn't knowable from the multipart wrapper alone — it
        // surfaces only after the zip is staged + the manifest parsed —
        // so the per-game scope check is threaded into install() as a
        // post-stage hook. Admins bypass with `slug -> Mono.empty()`.
        String uploadedBy = jwt != null ? jwt.getSubject() : "system:anonymous-admin";
        boolean isAdmin = jwt != null && gamesAdmin.isGamesAdmin(jwt);
        boolean isSuperadmin = jwt != null && Roles.isSuperadmin(jwt);

        // The uploader's TRUSTED brand — issuer-derived, never a user-settable claim.
        // "default" on the single-Casdoor cluster. null only in multi-brand mode for an
        // issuer outside the registry (the decoder 401s such tokens upstream; we
        // fail closed here defensively rather than stamping an untrusted upload global).
        String domain = Roles.domainOf(jwt, domainRegistry);
        if (domain == null) {
            return Mono.error(new org.springframework.web.server.ResponseStatusException(
                    HttpStatus.FORBIDDEN, "upload from an untrusted issuer/domain"));
        }

        java.util.function.Function<String, Mono<Void>> requireScope =
                requireScope(jwt, domain, isAdmin, isSuperadmin);
        return collectBytes(bundle)
                .flatMap(bytes -> uploadService.install(bytes, uploadedBy, domain, requireScope));
    }

    /**
     * The post-stage authorization hook threaded into {@code install()} (the slug is
     * known only after the manifest is parsed). Two gates, in order:
     * <ol>
     *   <li><b>OWNERSHIP</b> — admins bypass; publishers must hold a
     *       {@code publisher_game_scopes} row for the slug.</li>
     *   <li><b>BRAND</b> — the uploader's {@code domain} must own an already-existing
     *       game with this slug (a brand-X admin cannot overwrite a brand-Y title);
     *       SUPERADMIN bypasses. A brand-NEW slug passes (nothing to own yet) and is
     *       stamped with the uploader's domain by {@code GameUploadService}.</li>
     * </ol>
     * Package-private so the composition is unit-testable without driving a full upload.
     */
    java.util.function.Function<String, Mono<Void>> requireScope(Jwt jwt, String domain,
                                                                 boolean isAdmin, boolean isSuperadmin) {
        return slug -> {
            Mono<Void> ownership = isAdmin
                    ? Mono.empty()
                    : jwt == null
                            ? Mono.error(new org.springframework.web.server.ResponseStatusException(
                                    HttpStatus.UNAUTHORIZED, "JWT required for publisher uploads"))
                            // Both identifiers: seeded scope rows key on the username
                            // (the sub is a UUID Casdoor re-mints every fresh boot),
                            // runtime-granted rows key on the sub.
                            : publisherService.requireOwns(
                                    jwt.getSubject(), Roles.usernameOf(jwt), slug);
            return ownership.then(domainScope.requireGameDomain(slug, domain, isSuperadmin));
        };
    }

    @Operation(summary = "Upload history for one slug (newest first).")
    @GetMapping("/{slug}/uploads")
    @PreAuthorize("hasAnyRole('GAMES_ADMIN','PUBLISHER')")
    public Flux<GameUpload> history(@PathVariable String slug) {
        return uploads.findBySlugOrderByUploadedAtDesc(slug);
    }

    private static Mono<byte[]> collectBytes(FilePart part) {
        // DataBufferUtils.join collects the full Flux<DataBuffer> into a single buffer
        // without requiring a try/catch inside the reduce lambda — buffer release and
        // IOException from buf.read are both handled by the join operator internally.
        return org.springframework.core.io.buffer.DataBufferUtils.join(part.content())
                .map(buf -> {
                    try {
                        byte[] bytes = new byte[buf.readableByteCount()];
                        buf.read(bytes);
                        return bytes;
                    } finally {
                        org.springframework.core.io.buffer.DataBufferUtils.release(buf);
                    }
                });
    }

    @SuppressWarnings("unused")
    private static HttpStatus _h() { return HttpStatus.OK; }
}
