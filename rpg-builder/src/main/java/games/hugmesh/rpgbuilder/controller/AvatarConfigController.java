package games.hugmesh.rpgbuilder.controller;

import games.hugmesh.character.AvatarSvgRenderer;
import games.hugmesh.character.CharacterDocument;
import games.hugmesh.character.CharacterSpec;
import games.hugmesh.character.GlbExporter;
import games.hugmesh.rpgbuilder.entity.RpgAvatarConfig;
import games.hugmesh.rpgbuilder.security.Roles;
import games.hugmesh.rpgbuilder.service.AvatarConfigService;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;

import java.util.Map;
import java.util.UUID;

/**
 * Per-project hero avatar config under the existing /api/rpg surface.
 * Same role gate as {@link RpgProjectController}; per-project ownership
 * enforced in-service via the composite sub|username principal.
 *
 * <p>Beyond the original config GET/PUT, decision 028 adds the renditions
 * every other surface consumes — an SVG (with an expression, and a portrait
 * crop for profile pictures and post attachments) and the portable Character
 * Document for import/export into other generators and games. The SVG comes
 * from the SAME renderer that the maker preview uses, which is what keeps the
 * 2D and 3D representations in agreement.
 */
@RestController
@RequestMapping("/api/rpg")
@PreAuthorize("hasAnyRole('ADMIN','PUBLISHER')")
public class AvatarConfigController {

    private static final String SVG = "image/svg+xml";

    private final AvatarConfigService avatars;

    public AvatarConfigController(AvatarConfigService avatars) { this.avatars = avatars; }

    @GetMapping("/projects/{pid}/avatar-config")
    public Mono<RpgAvatarConfig> get(@PathVariable UUID pid, Authentication auth) {
        return avatars.get(pid, Roles.principalOf(auth));
    }

    @PutMapping("/projects/{pid}/avatar-config")
    public Mono<RpgAvatarConfig> put(@PathVariable UUID pid, @RequestBody RpgAvatarConfig in,
                                     Authentication auth) {
        return avatars.put(pid, in, Roles.principalOf(auth));
    }

    /**
     * The saved character as a picture. {@code portrait=true} crops to head and
     * shoulders — the shape a profile picture or an attached expression still
     * wants.
     */
    @GetMapping(value = "/projects/{pid}/avatar.svg", produces = SVG)
    public Mono<ResponseEntity<String>> avatarSvg(
            @PathVariable UUID pid,
            @RequestParam(defaultValue = "neutral") String expression,
            @RequestParam(name = "w", defaultValue = "256") int width,
            @RequestParam(name = "h", defaultValue = "384") int height,
            @RequestParam(defaultValue = "false") boolean portrait,
            Authentication auth) {
        // Validate the expression here so an unknown one is a 400 from the
        // whitelist, not a silently-neutral render.
        String expr = CharacterSpec.token(expression, CharacterSpec.EXPRESSIONS,
                CharacterSpec.DEFAULT_EXPRESSION, "expression");
        return avatars.spec(pid, Roles.principalOf(auth))
                .map(spec -> svg(portrait
                        ? AvatarSvgRenderer.renderPortrait(spec, expr, width, height)
                        : AvatarSvgRenderer.renderExpression(spec, expr, width, height)));
    }

    /**
     * The saved character as a binary glTF 2.0 asset.
     *
     * <p>Served as an attachment because this one is a file to open in Blender
     * or import into another engine, not something to render inline. The rig's
     * joint hierarchy survives the export, so what lands there is still an
     * articulated character rather than a frozen picture of one.
     */
    @GetMapping(value = "/projects/{pid}/avatar.glb", produces = GlbExporter.MIME_TYPE)
    public Mono<ResponseEntity<byte[]>> avatarGlb(@PathVariable UUID pid, Authentication auth) {
        return avatars.spec(pid, Roles.principalOf(auth))
                .map(spec -> ResponseEntity.ok()
                        .contentType(MediaType.valueOf(GlbExporter.MIME_TYPE))
                        // The id is a UUID, so nothing caller-controlled reaches
                        // the header value.
                        .header("Content-Disposition", "attachment; filename=\"avatar-" + pid + ".glb\"")
                        .header("Cache-Control", "no-cache")
                        .body(GlbExporter.export(spec, "avatar-" + pid)));
    }

    /** The portable Character Document — export for another generator or game. */
    @GetMapping("/projects/{pid}/avatar.json")
    public Mono<Map<String, Object>> avatarDocument(@PathVariable UUID pid, Authentication auth) {
        return avatars.spec(pid, Roles.principalOf(auth))
                .map(spec -> CharacterDocument.toMap(spec, ""));
    }

    /**
     * The {@code Char3DConfig} projection the XR / 3D-chat assembler consumes.
     * Exposed separately because it is lossy and speaks a different schema —
     * callers should know which one they are asking for.
     */
    @GetMapping("/projects/{pid}/avatar-char3d.json")
    public Mono<Map<String, Object>> avatarChar3d(@PathVariable UUID pid, Authentication auth) {
        return avatars.spec(pid, Roles.principalOf(auth))
                .map(spec -> CharacterDocument.toChar3DConfig(spec, ""));
    }

    /** Import a Character Document, replacing this project's hero. */
    @PostMapping("/projects/{pid}/avatar.json")
    public Mono<RpgAvatarConfig> importDocument(@PathVariable UUID pid,
                                                @RequestBody Map<String, Object> doc,
                                                Authentication auth) {
        // Parsed (and fully validated) synchronously so a bad document is a
        // 400 from the controller's own handler rather than a 500 signalled
        // from inside the Mono.
        CharacterSpec spec = CharacterDocument.fromMap(doc);
        return avatars.putSpec(pid, spec, Roles.principalOf(auth));
    }

    /**
     * Stateless preview: render a POSTed config WITHOUT persisting it.
     *
     * <p>This is the parity mechanism, not a convenience — the maker's live
     * slider preview comes from the same renderer that produces the final art,
     * so what an author sees while dragging is what they get. A separate
     * client-side drawing routine is exactly the drift decision 028 removes.
     */
    @PostMapping(value = "/avatar-preview.svg", produces = SVG)
    public Mono<ResponseEntity<String>> previewSvg(
            @RequestBody RpgAvatarConfig in,
            @RequestParam(defaultValue = "neutral") String expression,
            @RequestParam(name = "w", defaultValue = "256") int width,
            @RequestParam(name = "h", defaultValue = "384") int height,
            @RequestParam(defaultValue = "false") boolean portrait) {
        // Same validation the PUT applies — a preview is not a way around the
        // whitelist, and a texture id is ignored here because nothing is staged.
        CharacterSpec spec = AvatarConfigService.toSpec(in);
        String expr = CharacterSpec.token(expression, CharacterSpec.EXPRESSIONS,
                CharacterSpec.DEFAULT_EXPRESSION, "expression");
        return Mono.just(svg(portrait
                ? AvatarSvgRenderer.renderPortrait(spec, expr, width, height)
                : AvatarSvgRenderer.renderExpression(spec, expr, width, height)));
    }

    private static ResponseEntity<String> svg(String body) {
        return ResponseEntity.ok()
                .contentType(MediaType.valueOf(SVG))
                // Deterministic for a given config, but a config can change, so
                // let a caller revalidate rather than pinning a stale face.
                .header("Cache-Control", "no-cache")
                .body(body);
    }

    // @ExceptionHandler is CONTROLLER-LOCAL: RpgProjectController#badArg
    // does not cover this class, so the whitelist rejects thrown
    // synchronously by AvatarConfigService need their own copy here
    // (verbatim from RpgProjectController). Must return a Publisher:
    // with @EnableReactiveMethodSecurity the class-level @PreAuthorize
    // also proxies this method, and the reactive authorization
    // interceptor rejects non-reactive return types.
    @ExceptionHandler(IllegalArgumentException.class)
    public Mono<ResponseEntity<String>> badArg(IllegalArgumentException e) {
        return Mono.just(ResponseEntity.badRequest().body(e.getMessage()));
    }
}
