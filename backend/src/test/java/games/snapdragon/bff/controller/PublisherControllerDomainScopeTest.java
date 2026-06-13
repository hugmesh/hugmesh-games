package games.snapdragon.bff.controller;

import games.snapdragon.bff.controller.PublisherController.GrantModRequest;
import games.snapdragon.bff.controller.PublisherController.RevokeModRequest;
import games.snapdragon.bff.model.Game;
import games.snapdragon.bff.model.ModGameScope;
import games.snapdragon.bff.model.PublisherGameScope;
import games.snapdragon.bff.repository.GameRepository;
import games.snapdragon.bff.repository.GameSessionRepository;
import games.snapdragon.bff.repository.ModGameScopeRepository;
import games.snapdragon.bff.repository.PublisherGameScopeRepository;
import games.snapdragon.bff.security.DomainRegistry;
import games.snapdragon.bff.service.DomainScopeService;
import games.snapdragon.bff.service.PublisherService;
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
 * Controller-level proof that {@link PublisherController} enforces caller-domain ==
 * target-game-domain on its game-addressed actions (grant/revoke mod, list mods). A
 * brand-X admin (who bypasses the publisher ownership check) must not manage moderators
 * on a brand-Y game; SUPERADMIN bypasses; single-tenant is inert.
 *
 * <p>The DENY path asserts 403 AND that the publisher ownership check ({@code
 * publisherScopes.findActive}) was never reached — proving the brand gate fires before
 * the service. The ALLOW path stubs the ownership check to pass so the call completes,
 * proving the gate let it through.
 */
class PublisherControllerDomainScopeTest {

    private static final String ISS_X = "https://casdoor.brandx.example";
    private static final String ISS_Y = "https://casdoor.brandy.example";

    private GameRepository gameRepo;
    private PublisherGameScopeRepository publisherScopes;
    private ModGameScopeRepository modScopes;

    private PublisherController controllerFor(DomainRegistry registry) {
        gameRepo = mock(GameRepository.class);
        publisherScopes = mock(PublisherGameScopeRepository.class);
        modScopes = mock(ModGameScopeRepository.class);
        PublisherService publisher = new PublisherService(publisherScopes, modScopes);
        DomainScopeService scope = new DomainScopeService(gameRepo, mock(GameSessionRepository.class));
        return new PublisherController(publisher, publisherScopes, modScopes, scope, registry);
    }

    private static DomainRegistry multiBrand() {
        return new DomainRegistry("", ISS_X + "=brandx," + ISS_Y + "=brandy");
    }

    private void stubGame(String slug, String domain) {
        Game g = new Game();
        g.setDomain(domain);
        when(gameRepo.findBySlug(eq(slug))).thenReturn(Mono.just(g));
    }

    /** Make the publisher ownership + mod lookups pass so grant/revoke complete. */
    private void stubOwnershipPasses(String slug) {
        lenient().when(publisherScopes.findActive(any(), eq(slug)))
                .thenReturn(Mono.just(new PublisherGameScope()));
        lenient().when(modScopes.findActive(any(), eq(slug)))
                .thenReturn(Mono.just(new ModGameScope()));
        lenient().when(modScopes.findActiveByGame(eq(slug)))
                .thenReturn(reactor.core.publisher.Flux.just(new ModGameScope()));
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
    // CROSS-USER — brand-X admin vs brand-X / brand-Y games (ALLOW+DENY)
    // ===================================================================
    @Nested
    class CrossUserIsolation {

        @Test
        void grantModDeniesForeignBrandBeforeOwnershipCheck() {
            PublisherController c = controllerFor(multiBrand());
            stubGame("gy", "brandy");
            assertForbidden(c.grantMod(admin(ISS_X), new GrantModRequest("target", "gy")));
            verify(publisherScopes, never()).findActive(any(), any());   // gate fired first
        }

        @Test
        void grantModAllowsOwnBrand() {
            PublisherController c = controllerFor(multiBrand());
            stubGame("gx", "brandx");
            stubOwnershipPasses("gx");
            StepVerifier.create(c.grantMod(admin(ISS_X), new GrantModRequest("target", "gx")))
                    .expectNextCount(1).verifyComplete();
            verify(publisherScopes).findActive(any(), eq("gx"));         // gate passed → service ran
        }

        @Test
        void revokeModDeniesForeignBrand() {
            PublisherController c = controllerFor(multiBrand());
            stubGame("gy", "brandy");
            assertForbidden(c.revokeMod(admin(ISS_X), new RevokeModRequest("target", "gy")));
            verify(publisherScopes, never()).findActive(any(), any());
        }

        @Test
        void modsForGameDeniesForeignBrand() {
            PublisherController c = controllerFor(multiBrand());
            stubGame("gy", "brandy");
            assertForbidden(c.modsForGame(admin(ISS_X), "gy"));
            verify(publisherScopes, never()).findActive(any(), any());
        }

        @Test
        void modsForGameAllowsOwnBrandAndEmitsRows() {
            // The only Flux.defer surface — prove the gate-passed path actually
            // SUBSCRIBES the deferred body (ownership check + row query) and propagates
            // elements (a thenMany→then regression would drop them and this would fail).
            PublisherController c = controllerFor(multiBrand());
            stubGame("gx", "brandx");
            stubOwnershipPasses("gx");
            StepVerifier.create(c.modsForGame(admin(ISS_X), "gx")).expectNextCount(1).verifyComplete();
            verify(publisherScopes).findActive(any(), eq("gx"));   // ownership ran
            verify(modScopes).findActiveByGame(eq("gx"));          // Flux body emitted
        }
    }

    // ===================================================================
    // MULTIUSER-SITUATIONAL — SUPERADMIN cross-domain bypass
    // ===================================================================
    @Nested
    class MultiUserSituational {

        @Test
        void superadminManagesAnyBrandsGame() {
            PublisherController c = controllerFor(multiBrand());
            stubGame("gy", "brandy");
            stubOwnershipPasses("gy");
            StepVerifier.create(c.grantMod(superadmin(ISS_X), new GrantModRequest("target", "gy")))
                    .expectNextCount(1).verifyComplete();
            verify(publisherScopes).findActive(any(), eq("gy"));         // bypass reached the service
        }
    }

    // ===================================================================
    // SINGLE-TENANT — default registry + 'default' game: gate inert
    // ===================================================================
    @Nested
    class SingleTenant {

        @Test
        void defaultGameAllowsEveryAdmin() {
            PublisherController c = controllerFor(new DomainRegistry("", ""));
            stubGame("g", "default");
            stubOwnershipPasses("g");
            StepVerifier.create(c.grantMod(admin(ISS_X), new GrantModRequest("target", "g")))
                    .expectNextCount(1).verifyComplete();
        }
    }
}
