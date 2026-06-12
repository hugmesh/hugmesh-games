package games.snapdragon.bff.controller;

import games.snapdragon.bff.model.GameAccess;
import games.snapdragon.bff.repository.GameAccessRepository;
import games.snapdragon.bff.repository.GameRepository;
import games.snapdragon.bff.security.DomainRegistry;
import games.snapdragon.bff.security.Roles;
import games.snapdragon.bff.service.DomainScopeService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.Set;

/**
 * Admin endpoints for per-game access policy. The Casdoor admin console assigns
 * the {@code admin} role; this controller is gated on {@code ROLE_ADMIN} via
 * the JWT's {@code roles} claim (see
 * {@link games.snapdragon.bff.config.SecurityConfig#casdoorAuthoritiesConverter()}).
 *
 * <p>Three policy shapes:
 * <ul>
 *   <li>{@code POST /api/admin/games/{slug}/access} with {@code {"scope":"all"}}
 *       — open the game to every authenticated user.</li>
 *   <li>{@code POST /api/admin/games/{slug}/access} with
 *       {@code {"scope":"role","value":"uploader"}} — limit to a Casdoor role.</li>
 *   <li>{@code POST /api/admin/games/{slug}/access} with
 *       {@code {"scope":"user","value":"<casdoor-user-id>"}} — single-user grant.</li>
 * </ul>
 *
 * <p>Multiple rows are additive (union). Revoke with the matching DELETE.
 */
@Tag(name = "Game access", description = "Admin-only: who can see which game (all / role / individual user).")
@RestController
@RequestMapping("/api/admin/games")
public class GameAccessController {

    private static final Set<String> VALID_SCOPES = Set.of("all", "role", "user");

    private final GameAccessRepository accessRepo;
    private final GameRepository gameRepo;
    private final DomainScopeService domainScope;
    private final DomainRegistry domainRegistry;

    public GameAccessController(GameAccessRepository accessRepo, GameRepository gameRepo,
                                DomainScopeService domainScope, DomainRegistry domainRegistry) {
        this.accessRepo     = accessRepo;
        this.gameRepo       = gameRepo;
        this.domainScope    = domainScope;
        this.domainRegistry = domainRegistry;
    }

    /** Brand-scope gate for an admin acting on {@code slug}: a brand-X admin may not
     *  read or mutate a brand-Y game's access policy (SUPERADMIN exempt). Inert in
     *  single-tenant — every game is {@code domain='default'} (global). */
    private Mono<Void> requireGameDomain(String slug, Jwt jwt) {
        return domainScope.requireGameDomain(slug, Roles.domainOf(jwt, domainRegistry), Roles.isSuperadmin(jwt));
    }

    public record AccessGrantRequest(String scope, String value) {}

    @Operation(summary = "List access policies for one game (admin only).")
    @GetMapping("/{slug}/access")
    @PreAuthorize("hasRole('ADMIN')")
    public Flux<GameAccess> list(@PathVariable String slug, @AuthenticationPrincipal Jwt jwt) {
        return requireGameDomain(slug, jwt)
                .thenMany(gameRepo.findBySlug(slug)
                        .switchIfEmpty(Mono.error(new ResponseStatusException(HttpStatus.NOT_FOUND, "unknown game slug: " + slug)))
                        .flatMapMany(g -> accessRepo.findByGameId(g.getId())));
    }

    @Operation(
        summary = "Grant access to a game (admin only)",
        description = """
            Body:
              {"scope":"all"}                                         — every user
              {"scope":"role","value":"uploader"}                     — Casdoor role
              {"scope":"user","value":"8257e529-914a-43ba-..."}       — Casdoor user id

            Adds a row to `game_access`. Idempotent — duplicates are silently
            absorbed by the unique constraint.
            """)
    @PostMapping("/{slug}/access")
    @PreAuthorize("hasRole('ADMIN')")
    @ResponseStatus(HttpStatus.CREATED)
    public Mono<GameAccess> grant(@PathVariable String slug,
                                  @RequestBody AccessGrantRequest req,
                                  @AuthenticationPrincipal Jwt jwt) {
        if (req == null || req.scope() == null || !VALID_SCOPES.contains(req.scope())) {
            return Mono.error(new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "scope must be one of " + VALID_SCOPES));
        }
        if (!"all".equals(req.scope()) && (req.value() == null || req.value().isBlank())) {
            return Mono.error(new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "value is required when scope is 'role' or 'user'"));
        }

        String adminId = jwt != null ? jwt.getSubject() : "system:unknown";

        return requireGameDomain(slug, jwt).then(gameRepo.findBySlug(slug)
                .switchIfEmpty(Mono.error(new ResponseStatusException(HttpStatus.NOT_FOUND, "unknown game slug: " + slug)))
                .flatMap(g -> {
                    GameAccess row = new GameAccess();
                    row.setGameId(g.getId());
                    row.setScope(req.scope());
                    row.setScopeValue("all".equals(req.scope()) ? null : req.value());
                    row.setGrantedBy(adminId);
                    return accessRepo.save(row);
                })
                .onErrorResume(ex -> {
                    // Unique constraint violation = grant already exists; treat as a no-op
                    // so the admin UI's "save" button is idempotent.
                    if (ex.getMessage() != null && ex.getMessage().contains("uq_game_access_triple")) {
                        return accessRepo.findByGameId(req.value() == null ? -1L : -1L)
                                .next()
                                .defaultIfEmpty(new GameAccess());
                    }
                    return Mono.error(ex);
                }));
    }

    @Operation(summary = "Revoke a previously-granted access policy (admin only).")
    @DeleteMapping("/{slug}/access")
    @PreAuthorize("hasRole('ADMIN')")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public Mono<Void> revoke(@PathVariable String slug,
                             @RequestParam String scope,
                             @RequestParam(required = false) String value,
                             @AuthenticationPrincipal Jwt jwt) {
        if (!VALID_SCOPES.contains(scope)) {
            return Mono.error(new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "scope must be one of " + VALID_SCOPES));
        }
        return requireGameDomain(slug, jwt).then(gameRepo.findBySlug(slug)
                .switchIfEmpty(Mono.error(new ResponseStatusException(HttpStatus.NOT_FOUND, "unknown game slug: " + slug)))
                .flatMap(g -> accessRepo.deletePolicy(g.getId(), scope, "all".equals(scope) ? null : value)));
    }
}
