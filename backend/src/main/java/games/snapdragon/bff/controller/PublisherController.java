package games.snapdragon.bff.controller;

import games.snapdragon.bff.model.ModGameScope;
import games.snapdragon.bff.model.PublisherGameScope;
import games.snapdragon.bff.repository.ModGameScopeRepository;
import games.snapdragon.bff.repository.PublisherGameScopeRepository;
import games.snapdragon.bff.security.DomainRegistry;
import games.snapdragon.bff.security.Roles;
import games.snapdragon.bff.service.DomainScopeService;
import games.snapdragon.bff.service.PublisherService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Per-game publisher endpoints.
 *
 * <p>Game-bundle uploads still flow through {@code GameUploadController}
 * at {@code POST /api/admin/games/upload}; the route is shared and the
 * publisher's per-game scope is checked there before the install
 * transaction commits. The endpoints here cover what publishers do
 * <em>besides</em> uploading: grant / revoke the mod role for users on
 * games they own, plus list their own scope.
 */
@Tag(name = "Publisher",
     description = "Per-game publisher actions: list owned games, grant / revoke the mod role for those games. Admins bypass scope checks.")
@RestController
@RequestMapping("/api/publisher")
@PreAuthorize("hasAnyRole('PUBLISHER','ADMIN')")
public class PublisherController {

    private final PublisherService              publisher;
    private final PublisherGameScopeRepository  publisherScopes;
    private final ModGameScopeRepository        modScopes;
    private final DomainScopeService            domainScope;
    private final DomainRegistry                domainRegistry;

    public PublisherController(PublisherService publisher,
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

    /** Brand-scope gate: a brand-X caller (admin or publisher) may not act on a
     *  brand-Y game (SUPERADMIN exempt; inert single-tenant). Mirrors GameAccessController. */
    private Mono<Void> requireGameDomain(String gameSlug, Jwt jwt) {
        return domainScope.requireGameDomain(gameSlug, Roles.domainOf(jwt, domainRegistry), Roles.isSuperadmin(jwt));
    }

    public record GrantModRequest(String targetCasdoorId, String gameSlug) {}
    public record RevokeModRequest(String targetCasdoorId, String gameSlug) {}

    @Operation(summary = "List the games this caller owns as publisher")
    @GetMapping("/scopes")
    public Flux<PublisherGameScope> myScopes(@AuthenticationPrincipal Jwt jwt) {
        return publisherScopes.findActiveByCasdoor(jwt.getSubject());
    }

    @Operation(
        summary = "List active mods for one of this caller's games",
        description = """
            Authorizes that the caller owns `gameSlug`, then returns every
            active `mod_game_scopes` row for it. Useful for the publisher's
            "manage moderators" UI card.
            """)
    @GetMapping("/games/{gameSlug}/mods")
    public Flux<ModGameScope> modsForGame(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable String gameSlug) {
        return requireGameDomain(gameSlug, jwt)
                // Flux.defer so the ownership check + query are only ASSEMBLED once the
                // brand gate passes — a denied request short-circuits with no side work.
                .thenMany(Flux.defer(() -> publisher.requireOwns(jwt.getSubject(), gameSlug)
                        .thenMany(modScopes.findActiveByGame(gameSlug))));
    }

    @Operation(
        summary = "Grant a user the mod role for a game I own",
        description = """
            Idempotent on (target_casdoor_id, game_slug). The Casdoor
            `mod` role still needs to be assigned in Casdoor itself —
            this endpoint adds the per-game scope, not the role
            membership. (Admins seed both via `/api/admin/role-grants`.)
            """)
    @PostMapping("/grant-mod")
    public Mono<ModGameScope> grantMod(
            @AuthenticationPrincipal Jwt jwt,
            @RequestBody GrantModRequest req) {
        return requireGameDomain(req.gameSlug(), jwt)
                .then(Mono.defer(() -> publisher.grantMod(jwt.getSubject(), req.targetCasdoorId(), req.gameSlug())));
    }

    @Operation(summary = "Revoke a user's mod role for a game I own")
    @PostMapping("/revoke-mod")
    public Mono<Void> revokeMod(
            @AuthenticationPrincipal Jwt jwt,
            @RequestBody RevokeModRequest req) {
        return requireGameDomain(req.gameSlug(), jwt)
                .then(Mono.defer(() -> publisher.revokeMod(jwt.getSubject(), req.targetCasdoorId(), req.gameSlug())));
    }
}
