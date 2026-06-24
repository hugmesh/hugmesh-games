package games.snapdragon.bff.controller;

import games.snapdragon.bff.config.BanGateFilter;
import games.snapdragon.bff.config.DomainGuardFilter;
import games.snapdragon.bff.config.RateLimitFilter;
import games.snapdragon.bff.config.SecurityConfig;
import games.snapdragon.bff.config.SecurityHeadersFilter;
import games.snapdragon.bff.controller.support.SecuritySliceSupport;
import games.snapdragon.bff.model.Game;
import games.snapdragon.bff.model.GameAccess;
import games.snapdragon.bff.repository.GameAccessRepository;
import games.snapdragon.bff.repository.GameRepository;
import games.snapdragon.bff.repository.GameSessionRepository;
import games.snapdragon.bff.service.DomainScopeService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webflux.test.autoconfigure.WebFluxTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan.Filter;
import org.springframework.context.annotation.FilterType;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.reactive.server.SecurityMockServerConfigurers.csrf;

/**
 * HTTP/filter-layer authorization matrix for {@link GameAccessController}
 * (wave-2c target). The existing {@link GameAccessControllerDomainScopeTest}
 * is a POJO test proving the SERVICE-layer brand gate; this slice drives the
 * REAL {@link SecurityConfig} filter chain at the WebTestClient boundary.
 *
 * <p><b>Defence-in-depth — BOTH gates asserted.</b> {@code /api/admin/games/**}
 * is doubly guarded: the {@code /api/admin/**} → {@code hasRole('ADMIN')} path
 * matcher in {@code SecurityConfig} AND each method's
 * {@code @PreAuthorize("hasRole('ADMIN')")}. A USER/MOD is 403 (would PASS if
 * EITHER gate alone were removed only if the other also went; the per-method
 * 403 + the {@code verify(never())} on the repo make the DENY
 * mutation-resistant regardless), and anonymous is 401.
 *
 * <p><b>True cross-brand DENY at the HTTP layer.</b> A brand-X admin token
 * (issuer ISS_X → {@code DOMAIN_brandx}) against a brand-Y game is 403 and the
 * access repo is NEVER written — wave-2 only ran single-tenant.
 */
@WebFluxTest(controllers = GameAccessController.class,
        properties = "app.tenancy.issuers="
                + "https://casdoor.brandx.example=brandx,"
                + "https://casdoor.brandy.example=brandy",
        excludeFilters = @Filter(type = FilterType.ASSIGNABLE_TYPE, classes = {
                BanGateFilter.class, DomainGuardFilter.class,
                RateLimitFilter.class, SecurityHeadersFilter.class}))
@Import({SecurityConfig.class, SecuritySliceSupport.TenantBeans.class,
        GameAccessControllerHttpAuthzTest.Beans.class})
class GameAccessControllerHttpAuthzTest {

    @Autowired WebTestClient web;

    @MockitoBean GameAccessRepository accessRepo;
    @MockitoBean GameRepository gameRepo;
    @MockitoBean GameSessionRepository sessionRepo;

    @TestConfiguration(proxyBeanMethods = false)
    static class Beans {
        @Bean DomainScopeService domainScopeService(GameRepository g, GameSessionRepository s) {
            return new DomainScopeService(g, s);
        }
    }

    private void stubGame(String slug, String domain) {
        Game g = new Game();
        g.setDomain(domain);
        when(gameRepo.findBySlug(eq(slug))).thenReturn(Mono.just(g));
    }

    // ── role gate (path matcher + @PreAuthorize) ──────────────────────────────

    @Test
    void list_anonymous_is401() {
        web.get().uri("/api/admin/games/gx/access")
                .exchange()
                .expectStatus().isUnauthorized();
        verify(gameRepo, never()).findBySlug(any());
    }

    @Test
    void grant_anonymous_is401_andNeverSaves() {
        web.mutateWith(csrf())
                .post().uri("/api/admin/games/gx/access")
                .bodyValue(new GameAccessController.AccessGrantRequest("all", null))
                .exchange()
                .expectStatus().isUnauthorized();
        verify(accessRepo, never()).save(any());
    }

    @Test
    void list_user_is403() {
        web.mutateWith(SecuritySliceSupport.user("eve"))
                .get().uri("/api/admin/games/gx/access")
                .exchange()
                .expectStatus().isForbidden();
        verify(gameRepo, never()).findBySlug(any());
    }

    @Test
    void grant_mod_is403_andNeverSaves() {
        web.mutateWith(SecuritySliceSupport.mod("m"))
                .mutateWith(csrf())
                .post().uri("/api/admin/games/gx/access")
                .bodyValue(new GameAccessController.AccessGrantRequest("all", null))
                .exchange()
                .expectStatus().isForbidden();
        verify(accessRepo, never()).save(any());
    }

    @Test
    void revoke_user_is403_andNeverDeletes() {
        web.mutateWith(SecuritySliceSupport.user("eve"))
                .mutateWith(csrf())
                .delete().uri("/api/admin/games/gx/access?scope=all")
                .exchange()
                .expectStatus().isForbidden();
        verify(accessRepo, never()).deletePolicy(any(), any(), any());
    }

    // ── TRUE cross-brand DENY at the HTTP layer ───────────────────────────────

    @Test
    void grant_brandXAdmin_onBrandYGame_is403_andNeverSaves() {
        stubGame("gy", "brandy");
        web.mutateWith(SecuritySliceSupport.adminOn("admin-x",
                        SecuritySliceSupport.ISS_X, SecuritySliceSupport.BRAND_X))
                .mutateWith(csrf())
                .post().uri("/api/admin/games/gy/access")
                .bodyValue(new GameAccessController.AccessGrantRequest("all", null))
                .exchange()
                .expectStatus().isForbidden();
        verify(accessRepo, never()).save(any());
    }

    @Test
    void list_brandXAdmin_onBrandYGame_is403() {
        stubGame("gy", "brandy");
        web.mutateWith(SecuritySliceSupport.adminOn("admin-x",
                        SecuritySliceSupport.ISS_X, SecuritySliceSupport.BRAND_X))
                .get().uri("/api/admin/games/gy/access")
                .exchange()
                .expectStatus().isForbidden();
        verify(accessRepo, never()).findByGameId(any());
    }

    @Test
    void revoke_brandXAdmin_onBrandYGame_is403_andNeverDeletes() {
        stubGame("gy", "brandy");
        web.mutateWith(SecuritySliceSupport.adminOn("admin-x",
                        SecuritySliceSupport.ISS_X, SecuritySliceSupport.BRAND_X))
                .mutateWith(csrf())
                .delete().uri("/api/admin/games/gy/access?scope=all")
                .exchange()
                .expectStatus().isForbidden();
        verify(accessRepo, never()).deletePolicy(any(), any(), any());
    }

    @Test
    void grant_brandXAdmin_onBrandXGame_is201_andSaves() {
        stubGame("gx", "brandx");
        when(accessRepo.save(any())).thenAnswer(inv -> Mono.just(inv.getArgument(0)));
        web.mutateWith(SecuritySliceSupport.adminOn("admin-x",
                        SecuritySliceSupport.ISS_X, SecuritySliceSupport.BRAND_X))
                .mutateWith(csrf())
                .post().uri("/api/admin/games/gx/access")
                .bodyValue(new GameAccessController.AccessGrantRequest("all", null))
                .exchange()
                .expectStatus().isCreated();
        verify(accessRepo).save(any());
    }

    @Test
    void list_brandYSuperadmin_onBrandXGame_is200_crossDomainBypass() {
        // SUPERADMIN reaches every brand — the bypass skips the brand gate. The
        // controller still looks up the game to read its access rows (stubbed
        // empty; the Game's id is null on a `new Game()`, matched with any()).
        stubGame("gx", "brandx");
        when(accessRepo.findByGameId(any())).thenReturn(Flux.<GameAccess>empty());
        web.mutateWith(SecuritySliceSupport.superadminOn("root",
                        SecuritySliceSupport.ISS_Y, SecuritySliceSupport.BRAND_Y))
                .get().uri("/api/admin/games/gx/access")
                .exchange()
                .expectStatus().isOk();
        verify(accessRepo).findByGameId(any());
    }
}
