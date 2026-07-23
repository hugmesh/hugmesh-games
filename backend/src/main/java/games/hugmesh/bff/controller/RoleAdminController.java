package games.hugmesh.bff.controller;

import games.hugmesh.bff.model.ModGameScope;
import games.hugmesh.bff.model.PublisherGameScope;
import games.hugmesh.bff.repository.ModGameScopeRepository;
import games.hugmesh.bff.repository.PublisherGameScopeRepository;
import games.hugmesh.bff.security.DomainRegistry;
import games.hugmesh.bff.security.Roles;
import games.hugmesh.bff.service.DomainScopeService;
import games.hugmesh.bff.service.PublisherService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Admin-only role grant administration.
 *
 * <p>Admins manage the per-game scope tables (mod_game_scopes,
 * publisher_game_scopes) without going through Casdoor's UI. Casdoor
 * still owns the role catalog and role membership; these endpoints
 * own the per-game scoping that the BFF enforces.
 *
 * <p>Distinct from {@code /api/publisher/grant-mod} which a publisher
 * scoped to a game may also call.
 */
@Tag(name = "Admin role grants",
     description = "Admin-only management of per-game mod and publisher scopes. Casdoor owns role membership; these tables own the per-game scope.")
@RestController
@RequestMapping("/api/admin/role-grants")
@PreAuthorize("hasRole('ADMIN')")
public class RoleAdminController {

    private final PublisherService publisher;
    private final PublisherGameScopeRepository publisherScopes;
    private final ModGameScopeRepository       modScopes;
    private final DomainScopeService           domainScope;
    private final DomainRegistry               domainRegistry;

    public RoleAdminController(PublisherService publisher,
                               PublisherGameScopeRepository publisherScopes,
                               ModGameScopeRepository modScopes,
                               DomainScopeService domainScope,
                               DomainRegistry domainRegistry) {
        this.publisher       = publisher;
        this.publisherScopes = publisherScopes;
        this.modScopes       = modScopes;
        this.domainScope     = domainScope;
        this.domainRegistry  = domainRegistry;
    }

    /** Brand-scope gate: a brand-X admin may not grant/revoke scope on a brand-Y
     *  game in multi-brand shared-BFF mode (SUPERADMIN exempt; inert single-tenant).
     *  Mirrors {@code PublisherController.requireGameDomain}. */
    private Mono<Void> requireGameDomain(String gameSlug, Jwt jwt) {
        return domainScope.requireGameDomain(gameSlug, Roles.domainOf(jwt, domainRegistry), Roles.isSuperadmin(jwt));
    }

    public record GrantRequest(String targetCasdoorId, String gameSlug) {}

    @Operation(summary = "List every active publisher scope across all games")
    @GetMapping("/publishers")
    public Flux<PublisherGameScope> listPublishers() {
        return publisherScopes.findAll()
                .filter(row -> row.getRevokedAt() == null);
    }

    @Operation(summary = "List every active mod scope across all games")
    @GetMapping("/mods")
    public Flux<ModGameScope> listMods() {
        return modScopes.findAll()
                .filter(row -> row.getRevokedAt() == null);
    }

    @Operation(
        summary = "Grant publisher scope for a game",
        description = """
            Adds a publisher_game_scopes row tying the target user to
            the supplied game slug. The Casdoor `publisher` role
            membership must also be assigned (in Casdoor); this
            endpoint owns the per-game half only.
            """)
    @PostMapping("/grant-publisher")
    public Mono<PublisherGameScope> grantPublisher(
            @AuthenticationPrincipal Jwt jwt,
            @RequestBody GrantRequest req) {
        return requireGameDomain(req.gameSlug(), jwt)
                .then(Mono.defer(() ->
                        publisher.adminGrantPublisher(jwt.getSubject(), req.targetCasdoorId(), req.gameSlug())));
    }

    @Operation(summary = "Revoke a publisher scope (soft-delete)")
    @PostMapping("/revoke-publisher")
    public Mono<Void> revokePublisher(
            @AuthenticationPrincipal Jwt jwt,
            @RequestBody GrantRequest req) {
        return requireGameDomain(req.gameSlug(), jwt)
                .then(Mono.defer(() ->
                        publisher.adminRevokePublisher(jwt.getSubject(), req.targetCasdoorId(), req.gameSlug())));
    }

    @Operation(
        summary = "Grant mod scope for a game",
        description = """
            Admin variant of `/api/publisher/grant-mod` — admins can
            grant for any game without owning a publisher scope.
            """)
    @PostMapping("/grant-mod")
    public Mono<ModGameScope> grantMod(
            @AuthenticationPrincipal Jwt jwt,
            @RequestBody GrantRequest req) {
        // Admins reuse the publisher.grantMod path but bypass the
        // requireOwns scope check since they have global authority — yet a
        // brand-X admin must NOT grant scope on a brand-Y game (multi-brand
        // shared-BFF). Gate on the caller's brand owning the game first.
        return requireGameDomain(req.gameSlug(), jwt)
                .then(Mono.defer(() -> modScopes.findActive(req.targetCasdoorId(), req.gameSlug())
                        .switchIfEmpty(Mono.defer(() -> {
                            ModGameScope row = new ModGameScope();
                            row.setCasdoorId(req.targetCasdoorId());
                            row.setGameSlug(req.gameSlug());
                            row.setGrantedByCasdoorId(jwt.getSubject());
                            row.setGrantedAt(java.time.Instant.now());
                            return modScopes.save(row);
                        }))));
    }

    @Operation(summary = "Revoke a mod scope (soft-delete)")
    @PostMapping("/revoke-mod")
    public Mono<Void> revokeMod(
            @AuthenticationPrincipal Jwt jwt,
            @RequestBody GrantRequest req) {
        return requireGameDomain(req.gameSlug(), jwt)
                .then(Mono.defer(() -> modScopes.findActive(req.targetCasdoorId(), req.gameSlug())
                        .switchIfEmpty(Mono.error(new org.springframework.web.server.ResponseStatusException(
                                org.springframework.http.HttpStatus.NOT_FOUND, "no active mod scope to revoke")))
                        .flatMap(row -> {
                            row.setRevokedAt(java.time.Instant.now());
                            row.setRevokedByCasdoorId(jwt.getSubject());
                            return modScopes.save(row).then();
                        })));
    }
}
