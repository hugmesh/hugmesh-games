package games.hugmesh.bff.controller;

import games.hugmesh.bff.model.UserAvatarDto;
import games.hugmesh.bff.security.Roles;
import games.hugmesh.bff.service.UserAvatarService;
import games.hugmesh.character.AvatarSvgRenderer;
import games.hugmesh.character.CharacterDocument;
import games.hugmesh.character.CharacterSpec;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.Map;

/**
 * Cross-service user avatars (decision 028): each user keeps up to
 * {@link UserAvatarService#MAX_AVATARS_PER_USER} named avatars, one active,
 * each a portable Character Document rendered by the SAME canonical
 * {@code games.hugmesh.character} package every other service uses.
 *
 * <p><b>Auth design (deliberate asymmetry):</b> CRUD + select + bind are
 * owner-only, enforced in {@link UserAvatarService} against the JWT subject.
 * {@code image.svg} is readable by ANY authenticated user and carries NO
 * ownership check — an avatar exists to be seen by others (chat, forum,
 * profile), so the rendered face is the integration primitive other surfaces
 * embed. Ids are 43-char {@code Crypto.mintUid} tokens, i.e. unguessable
 * capability URLs, so possession of an id is possession of a share; do not
 * add an owner gate here. Unauthenticated callers still get a 401 from the
 * filter chain's {@code anyExchange().authenticated()} catch-all.
 */
@Tag(name = "User avatars", description = "Saved parametric avatars: CRUD (owner-only), active-avatar selection, per-character binding, and the rendered SVG any authenticated user may embed.")
@RestController
@RequestMapping("/api")
public class AvatarController {

    private static final String SVG = "image/svg+xml";

    private final UserAvatarService service;

    public AvatarController(UserAvatarService service) { this.service = service; }

    public record AvatarRequest(String name, Map<String, Object> document) {}
    public record BindRequest(String avatarId) {}

    @Operation(summary = "List the caller's saved avatars.")
    @GetMapping("/avatars")
    public Flux<UserAvatarDto> list(@AuthenticationPrincipal Jwt jwt) {
        return service.listFor(Roles.casdoorIdOf(jwt));
    }

    @Operation(summary = "Create an avatar from {name, document}. The id is server-minted and the owner server-stamped — the body cannot set either.")
    @PostMapping("/avatars")
    @ResponseStatus(HttpStatus.CREATED)
    public Mono<UserAvatarDto> create(@RequestBody AvatarRequest req,
                                      @AuthenticationPrincipal Jwt jwt) {
        // Validated SYNCHRONOUSLY so a bad document/name is a 400 from this
        // controller's own handler, not a 500 signalled from inside the Mono.
        String name = requireName(req == null ? null : req.name());
        CharacterSpec spec = CharacterDocument.fromMap(req == null ? null : req.document());
        return service.create(Roles.casdoorIdOf(jwt), name, spec);
    }

    @Operation(summary = "Update an avatar's name/document (owner only).")
    @PutMapping("/avatars/{id}")
    public Mono<UserAvatarDto> update(@PathVariable String id,
                                      @RequestBody AvatarRequest req,
                                      @AuthenticationPrincipal Jwt jwt) {
        String name = requireName(req == null ? null : req.name());
        CharacterSpec spec = CharacterDocument.fromMap(req == null ? null : req.document());
        return service.update(id, Roles.casdoorIdOf(jwt), name, spec);
    }

    @Operation(summary = "Delete an avatar (owner only). Characters bound to it survive — the FK is ON DELETE SET NULL.")
    @DeleteMapping("/avatars/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public Mono<Void> delete(@PathVariable String id, @AuthenticationPrincipal Jwt jwt) {
        return service.delete(id, Roles.casdoorIdOf(jwt));
    }

    @Operation(summary = "Make this the caller's active avatar; the previous active is cleared in the same statement.")
    @PostMapping("/avatars/{id}/select")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public Mono<Void> select(@PathVariable String id, @AuthenticationPrincipal Jwt jwt) {
        return service.select(id, Roles.casdoorIdOf(jwt));
    }

    @Operation(summary = "Bind an avatar to one of the caller's characters ({\"avatarId\": null} unbinds). Both the character and the avatar must be the caller's.")
    @PutMapping("/characters/{cid}/avatar")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public Mono<Void> bind(@PathVariable Long cid,
                           @RequestBody BindRequest req,
                           @AuthenticationPrincipal Jwt jwt) {
        return service.bindToCharacter(cid, req == null ? null : req.avatarId(),
                Roles.casdoorIdOf(jwt));
    }

    @Operation(summary = "The avatar rendered as SVG — readable by any authenticated user (see the class comment for why there is no owner gate).")
    @GetMapping(value = "/avatars/{id}/image.svg", produces = SVG)
    public Mono<ResponseEntity<String>> image(
            @PathVariable String id,
            @RequestParam(defaultValue = "neutral") String expression,
            @RequestParam(name = "w", defaultValue = "256") int width,
            @RequestParam(name = "h", defaultValue = "384") int height,
            @RequestParam(defaultValue = "false") boolean portrait) {
        // Unknown expression is a 400 from the whitelist here, not a
        // silently-neutral render; w/h are validated by the renderer itself
        // (MIN_DIM..MAX_DIM) and surface through the same handler below.
        String expr = CharacterSpec.token(expression, CharacterSpec.EXPRESSIONS,
                CharacterSpec.DEFAULT_EXPRESSION, "expression");
        return service.specOf(id)
                .map(spec -> svg(portrait
                        ? AvatarSvgRenderer.renderPortrait(spec, expr, width, height)
                        : AvatarSvgRenderer.renderExpression(spec, expr, width, height)));
    }

    private static String requireName(String name) {
        String n = name == null ? "" : name.trim();
        if (n.isEmpty() || n.length() > 64) {
            throw new IllegalArgumentException("name is required and must be 1..64 characters");
        }
        return n;
    }

    private static ResponseEntity<String> svg(String body) {
        return ResponseEntity.ok()
                .contentType(MediaType.valueOf(SVG))
                // Deterministic for a given document, but a document can change,
                // so let embedding surfaces revalidate rather than pin a stale face.
                .header("Cache-Control", "no-cache")
                .body(body);
    }

    // CONTROLLER-LOCAL by design (the AvatarConfigController idiom): the
    // whitelist/bounds rejects thrown by CharacterSpec/CharacterDocument and
    // by the renderer's dimension guard become the promised 400s. Returns a
    // Publisher so the reactive machinery (and any method-security proxy)
    // accepts the handler's return type.
    @ExceptionHandler(IllegalArgumentException.class)
    public Mono<ResponseEntity<String>> badArg(IllegalArgumentException e) {
        return Mono.just(ResponseEntity.badRequest().body(e.getMessage()));
    }
}
