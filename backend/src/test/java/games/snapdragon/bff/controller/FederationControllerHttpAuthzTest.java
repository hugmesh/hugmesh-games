package games.snapdragon.bff.controller;

import games.snapdragon.bff.config.BanGateFilter;
import games.snapdragon.bff.config.DomainGuardFilter;
import games.snapdragon.bff.config.RateLimitFilter;
import games.snapdragon.bff.config.SecurityConfig;
import games.snapdragon.bff.config.SecurityHeadersFilter;
import games.snapdragon.bff.controller.support.SecuritySliceSupport;
import games.snapdragon.bff.repository.FederationModuleRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webflux.test.autoconfigure.WebFluxTest;
import org.springframework.context.annotation.ComponentScan.Filter;
import org.springframework.context.annotation.FilterType;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Flux;

import static org.mockito.Mockito.when;

/**
 * HTTP/filter-layer contract for {@link FederationController} (wave-2c target —
 * only a POJO {@link FederationControllerTest} of DTO mapping existed).
 * {@code /api/federation/manifest} is PUBLIC on purpose: the Angular host is
 * unauthenticated until login completes but still needs to know where the
 * {@code login} remote lives. {@code SecurityConfig} leaves
 * {@code /api/federation/**} on {@code anyExchange().permitAll()}.
 *
 * <p>This slice pins that public contract at the REAL filter chain (anonymous
 * is 200, NOT 401) and confirms an authenticated caller gets the same manifest.
 * Per-remote access (e.g. the {@code admin} remote) is enforced in the host's
 * route guard against the JWT roles claim, not here — so there is no role gate
 * to assert on this endpoint.
 */
@WebFluxTest(controllers = FederationController.class,
        excludeFilters = @Filter(type = FilterType.ASSIGNABLE_TYPE, classes = {
                BanGateFilter.class, DomainGuardFilter.class,
                RateLimitFilter.class, SecurityHeadersFilter.class}))
@Import({SecurityConfig.class, SecuritySliceSupport.TenantBeans.class})
class FederationControllerHttpAuthzTest {

    @Autowired WebTestClient web;

    @MockitoBean FederationModuleRepository repo;

    @Test
    void manifest_anonymous_is200_publicByDesign() {
        when(repo.findAllByEnabledTrueOrderByKindAscSlugAsc()).thenReturn(Flux.empty());
        web.get().uri("/api/federation/manifest")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.generatedAt").exists()
                .jsonPath("$.remotes").isArray();
    }

    @Test
    void manifest_authenticated_is200_sameShape() {
        when(repo.findAllByEnabledTrueOrderByKindAscSlugAsc()).thenReturn(Flux.empty());
        web.mutateWith(SecuritySliceSupport.user("alice"))
                .get().uri("/api/federation/manifest")
                .exchange()
                .expectStatus().isOk()
                .expectBody().jsonPath("$.remotes").isArray();
    }
}
