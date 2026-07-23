package games.hugmesh.bff.controller;

import games.hugmesh.bff.controller.RoleAdminController.GrantRequest;
import games.hugmesh.bff.model.Game;
import games.hugmesh.bff.model.ModGameScope;
import games.hugmesh.bff.model.PublisherGameScope;
import games.hugmesh.bff.repository.GameRepository;
import games.hugmesh.bff.repository.GameSessionRepository;
import games.hugmesh.bff.repository.ModGameScopeRepository;
import games.hugmesh.bff.repository.PublisherGameScopeRepository;
import games.hugmesh.bff.security.DomainRegistry;
import games.hugmesh.bff.service.DomainScopeService;
import games.hugmesh.bff.service.PublisherService;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * BUG-D regression: {@link RoleAdminController}'s admin grant/revoke endpoints must
 * enforce caller-domain == target-game-domain, mirroring {@link PublisherController}.
 * Without the {@code requireGameDomain} prefix a brand-X ADMIN could grant/revoke
 * publisher- or mod-scope on a brand-Y {@code gameSlug} in multi-brand shared-BFF mode
 * (the {@code /api/admin/**} matcher only proves the caller holds ADMIN, not that the
 * admin's brand owns the addressed game).
 *
 * <p>FAIL-FIRST: against the unfixed controller (no brand gate) every DENY case below
 * reaches the scope write and returns success — these assertions fail. With the gate
 * they 403 BEFORE the write, and the never()-verifies prove the gate fires first.
 *
 * <p>Delineated along the project taxonomy: CROSS-USER isolation (brand-X admin vs
 * brand-X / brand-Y games, ALLOW + DENY), MULTIUSER (SUPERADMIN bypass), SINGLE-TENANT
 * (default registry + 'default' game ⇒ gate inert).
 */
class RoleAdminControllerDomainScopeTest {

    private static final String ISS_X = "https://casdoor.brandx.example";
    private static final String ISS_Y = "https://casdoor.brandy.example";

    private GameRepository gameRepo;
    private PublisherService publisher;
    private PublisherGameScopeRepository publisherScopes;
    private ModGameScopeRepository modScopes;

    private RoleAdminController controllerFor(DomainRegistry registry) {
        gameRepo = mock(GameRepository.class);
        publisherScopes = mock(PublisherGameScopeRepository.class);
        modScopes = mock(ModGameScopeRepository.class);
        publisher = new PublisherService(publisherScopes, modScopes);
        DomainScopeService scope = new DomainScopeService(gameRepo, mock(GameSessionRepository.class));
        return new RoleAdminController(publisher, publisherScopes, modScopes, scope, registry);
    }

    private static DomainRegistry multiBrand() {
        return new DomainRegistry("", ISS_X + "=brandx," + ISS_Y + "=brandy");
    }

    private void stubGame(String slug, String domain) {
        Game g = new Game();
        g.setDomain(domain);
        when(gameRepo.findBySlug(eq(slug))).thenReturn(Mono.just(g));
    }

    /** Make the scope lookups pass so a gate-passed grant/revoke can complete. */
    private void stubScopesPass(String slug) {
        lenient().when(publisherScopes.findActive(any(), eq(slug)))
                .thenReturn(Mono.just(new PublisherGameScope()));
        lenient().when(modScopes.findActive(any(), eq(slug)))
                .thenReturn(Mono.just(new ModGameScope()));
        lenient().when(publisherScopes.save(any())).thenAnswer(inv -> Mono.just(inv.getArgument(0)));
        lenient().when(modScopes.save(any())).thenAnswer(inv -> Mono.just(inv.getArgument(0)));
    }

    private static Jwt admin(String issuer) {
        return jwt(issuer, "admin-x", List.of("admin"));
    }

    private static Jwt superadmin(String issuer) {
        return jwt(issuer, "root", List.of("admin", "superadmin"));
    }

    private static Jwt jwt(String issuer, String sub, List<String> roles) {
        return Jwt.withTokenValue("t").header("alg", "RS256")
                .issuer(issuer).subject(sub)
                .issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(300))
                .claim("roles", roles).build();
    }

    private static void assertForbidden(org.reactivestreams.Publisher<?> p) {
        StepVerifier.create(p)
                .expectErrorSatisfies(err -> {
                    assertThat(err).isInstanceOf(ResponseStatusException.class);
                    assertThat(((ResponseStatusException) err).getStatusCode().value()).isEqualTo(403);
                })
                .verify();
    }

    // ===================================================================
    // CROSS-USER — brand-X admin vs brand-X / brand-Y games (ALLOW + DENY)
    // ===================================================================
    @Nested
    class CrossUserIsolation {

        @Test
        void grantPublisherDeniesForeignBrandBeforeWrite() {
            RoleAdminController c = controllerFor(multiBrand());
            stubGame("gy", "brandy");
            assertForbidden(c.grantPublisher(admin(ISS_X), new GrantRequest("target", "gy")));
            verify(publisherScopes, never()).findActive(any(), any());   // gate fired first
            verify(publisherScopes, never()).save(any());
        }

        @Test
        void grantPublisherAllowsOwnBrand() {
            RoleAdminController c = controllerFor(multiBrand());
            stubGame("gx", "brandx");
            stubScopesPass("gx");
            // own-brand admin: gate passes → existing active scope returned.
            StepVerifier.create(c.grantPublisher(admin(ISS_X), new GrantRequest("target", "gx")))
                    .expectNextCount(1).verifyComplete();
            verify(publisherScopes).findActive(any(), eq("gx"));         // service ran
        }

        @Test
        void revokePublisherDeniesForeignBrand() {
            RoleAdminController c = controllerFor(multiBrand());
            stubGame("gy", "brandy");
            assertForbidden(c.revokePublisher(admin(ISS_X), new GrantRequest("target", "gy")));
            verify(publisherScopes, never()).findActive(any(), any());
        }

        @Test
        void grantModDeniesForeignBrandBeforeWrite() {
            RoleAdminController c = controllerFor(multiBrand());
            stubGame("gy", "brandy");
            assertForbidden(c.grantMod(admin(ISS_X), new GrantRequest("target", "gy")));
            verify(modScopes, never()).findActive(any(), any());
            verify(modScopes, never()).save(any());
        }

        @Test
        void grantModAllowsOwnBrand() {
            RoleAdminController c = controllerFor(multiBrand());
            stubGame("gx", "brandx");
            stubScopesPass("gx");
            StepVerifier.create(c.grantMod(admin(ISS_X), new GrantRequest("target", "gx")))
                    .expectNextCount(1).verifyComplete();
            verify(modScopes).findActive(any(), eq("gx"));               // service ran
        }

        @Test
        void revokeModDeniesForeignBrand() {
            RoleAdminController c = controllerFor(multiBrand());
            stubGame("gy", "brandy");
            assertForbidden(c.revokeMod(admin(ISS_X), new GrantRequest("target", "gy")));
            verify(modScopes, never()).findActive(any(), any());
        }
    }

    // ===================================================================
    // MULTIUSER-SITUATIONAL — SUPERADMIN cross-domain bypass
    // ===================================================================
    @Nested
    class MultiUserSituational {

        @Test
        void superadminGrantsOnAnyBrandsGame() {
            RoleAdminController c = controllerFor(multiBrand());
            stubGame("gy", "brandy");
            stubScopesPass("gy");
            // SUPERADMIN bypasses the lookup entirely (DomainScopeService short-circuits),
            // so the service runs on the foreign-brand game.
            StepVerifier.create(c.grantMod(superadmin(ISS_X), new GrantRequest("target", "gy")))
                    .expectNextCount(1).verifyComplete();
            verify(modScopes).findActive(any(), eq("gy"));
        }
    }

    // ===================================================================
    // SINGLE-TENANT — default registry + 'default' game: gate inert
    // ===================================================================
    @Nested
    class SingleTenant {

        @Test
        void defaultGameAllowsEveryAdmin() {
            RoleAdminController c = controllerFor(new DomainRegistry("", ""));
            stubGame("g", "default");
            stubScopesPass("g");
            StepVerifier.create(c.grantMod(admin(ISS_X), new GrantRequest("target", "g")))
                    .expectNextCount(1).verifyComplete();
        }
    }
}
