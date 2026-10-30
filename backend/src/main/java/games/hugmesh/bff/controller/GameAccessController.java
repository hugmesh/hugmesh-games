package games.hugmesh.bff.controller;

import games.hugmesh.bff.model.GameAccess;
import games.hugmesh.bff.repository.GameAccessRepository;
import games.hugmesh.bff.repository.GameRepository;
import games.hugmesh.bff.security.DomainRegistry;
import games.hugmesh.bff.security.Roles;
import games.hugmesh.bff.service.DomainScopeService;
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
 * the {@code admin} role; this controller is gated on {@code ROLE_GAMES_ADMIN} via
 * the JWT's {@code roles} claim (see
 * {@link games.hugmesh.bff.config.SecurityConfig#casdoorAuthoritiesConverter()}).
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
    @PreAuthorize("hasRole('GAMES_ADMIN')")
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
    @PreAuthorize("hasRole('GAMES_ADMIN')")
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

        // scope='role' rows are matched at request time against Roles.fromJwt's
        // CANONICAL names (one leading ROLE_ stripped, lowercase), so the stored
        // value is canonicalised the same way: an admin typing "ROLE_GAMES" (the
        // systems Casdoor spelling the mesh's A4 free-text fallback invites) or
        // "Games" persists "games" and matches the ROLE_GAMES holder's claim.
        // 'user' values are Casdoor subject ids and stay verbatim.
        String scopeValue = "all".equals(req.scope()) ? null
                : "role".equals(req.scope()) ? Roles.canonical(req.value())
                : req.value();

        // Brand-scope gate (branch tenancy control) AND main's #70 scopeValue normalization
        // are both preserved: an admin may only grant on a game in their own domain, and the
        // 'all' scope persists a null scope_value.
        return requireGameDomain(slug, jwt).then(gameRepo.findBySlug(slug)
                .switchIfEmpty(Mono.error(new ResponseStatusException(HttpStatus.NOT_FOUND, "unknown game slug: " + slug)))
                .flatMap(g -> {
                    GameAccess row = new GameAccess();
                    row.setGameId(g.getId());
                    row.setScope(req.scope());
                    row.setScopeValue(scopeValue);
                    row.setGrantedBy(adminId);
                    return accessRepo.save(row)
                            // Unique constraint violation = grant already exists; treat as a
                            // no-op so the admin UI's "save" button is idempotent. Return the
                            // EXISTING row (matched on the same game_id + scope + scope_value
                            // triple the unique index guards) so clients get the real
                            // permissions record and audit trails stay coherent — not a blank
                            // placeholder. The earlier `g` is in scope here, so the lookup uses
                            // the real game id, never a -1 sentinel.
                            .onErrorResume(ex -> {
                                if (ex.getMessage() != null && ex.getMessage().contains("uq_game_access_triple")) {
                                    return accessRepo.findByGameId(g.getId())
                                            .filter(existing -> req.scope().equals(existing.getScope())
                                                    && java.util.Objects.equals(scopeValue, existing.getScopeValue()))
                                            .next()
                                            .switchIfEmpty(Mono.error(ex));
                                }
                                return Mono.error(ex);
                            });
                }));
    }

    @Operation(summary = "Revoke a previously-granted access policy (admin only).")
    @DeleteMapping("/{slug}/access")
    @PreAuthorize("hasRole('GAMES_ADMIN')")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public Mono<Void> revoke(@PathVariable String slug,
                             @RequestParam String scope,
                             @RequestParam(required = false) String value,
                             @AuthenticationPrincipal Jwt jwt) {
        if (!VALID_SCOPES.contains(scope)) {
            return Mono.error(new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "scope must be one of " + VALID_SCOPES));
        }
        // Same canonical form as grant(): a 'role' rule was stored canonicalised, so
        // revoking it as ?scope=role&value=ROLE_GAMES must address the stored "games".
        String scopeValue = "all".equals(scope) ? null
                : "role".equals(scope) ? Roles.canonical(value)
                : value;
        return requireGameDomain(slug, jwt).then(gameRepo.findBySlug(slug)
                .switchIfEmpty(Mono.error(new ResponseStatusException(HttpStatus.NOT_FOUND, "unknown game slug: " + slug)))
                .flatMap(g -> accessRepo.deletePolicy(g.getId(), scope, scopeValue)));
    }
}
