package games.snapdragon.bff.controller;

import games.snapdragon.bff.model.GameUpload;
import games.snapdragon.bff.repository.GameUploadRepository;
import games.snapdragon.bff.service.GameUploadService;
import games.snapdragon.bff.service.GameUploadService.InstalledUpload;
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

import java.io.ByteArrayOutputStream;
import java.io.IOException;

/**
 * Admin endpoint for hot-uploading a Snapdragon bundle zip produced by
 * the Godot editor plugin. Validation + atomic install happens in
 * {@link GameUploadService}; this controller is a thin REST surface.
 *
 * <p>Auth: gated on {@code ROLE_ADMIN} via the JWT roles claim. Players
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

    public GameUploadController(GameUploadService uploadService, GameUploadRepository uploads) {
        this.uploadService = uploadService;
        this.uploads = uploads;
    }

    @Operation(
        summary = "Install a Snapdragon bundle zip",
        description = """
            Multipart upload (field name: `bundle`). Body:
              • zip with manifest.json (schema_version 2), bundle/, register.sql
              • produced by `Project → Tools → Snapdragon → Export to Snapdragon…`

            On success returns the installed-upload summary. On failure rolls
            back the DB transaction and removes the staged dir.
            """)
    @PostMapping(value = "/upload", consumes = "multipart/form-data")
    @PreAuthorize("hasAnyRole('ADMIN','PUBLISHER')")
    public Mono<InstalledUpload> upload(@RequestPart("bundle") FilePart bundle,
                                        @AuthenticationPrincipal Jwt jwt) {
        // Admins can upload bundles for any game; publishers must hold a
        // publisher_game_scopes row for the slug carried in the manifest.
        // Per-game scope check happens inside install() once the manifest
        // is parsed (the slug is not knowable from the multipart wrapper
        // alone).
        String uploadedBy = jwt != null ? jwt.getSubject() : "system:anonymous-admin";
        return collectBytes(bundle)
                .flatMap(bytes -> uploadService.install(bytes, uploadedBy));
    }

    @Operation(summary = "Upload history for one slug (newest first).")
    @GetMapping("/{slug}/uploads")
    @PreAuthorize("hasAnyRole('ADMIN','PUBLISHER')")
    public Flux<GameUpload> history(@PathVariable String slug) {
        return uploads.findBySlugOrderByUploadedAtDesc(slug);
    }

    private static Mono<byte[]> collectBytes(FilePart part) {
        return part.content()
                .reduce(new ByteArrayOutputStream(), (out, buf) -> {
                    try {
                        byte[] tmp = new byte[buf.readableByteCount()];
                        buf.read(tmp);
                        out.write(tmp);
                    } catch (IOException e) {
                        throw new RuntimeException(e);
                    } finally {
                        org.springframework.core.io.buffer.DataBufferUtils.release(buf);
                    }
                    return out;
                })
                .map(ByteArrayOutputStream::toByteArray);
    }

    @SuppressWarnings("unused")
    private static HttpStatus _h() { return HttpStatus.OK; }
}
