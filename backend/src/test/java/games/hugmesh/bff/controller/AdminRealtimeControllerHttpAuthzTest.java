package games.hugmesh.bff.controller;

import games.hugmesh.bff.config.BanGateFilter;
import games.hugmesh.bff.config.DomainGuardFilter;
import games.hugmesh.bff.config.RateLimitFilter;
import games.hugmesh.bff.config.SecurityConfig;
import games.hugmesh.bff.config.SecurityHeadersFilter;
import games.hugmesh.bff.controller.support.SecuritySliceSupport;
import games.hugmesh.bff.model.Game;
import games.hugmesh.bff.model.GameSession;
import games.hugmesh.bff.repository.GameRepository;
import games.hugmesh.bff.repository.GameSessionRepository;
import games.hugmesh.bff.service.DomainScopeService;
import games.hugmesh.bff.service.realtime.AuthoritativeTickService;
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
import reactor.core.publisher.Mono;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.reactive.server.SecurityMockServerConfigurers.csrf;

/**
 * HTTP/filter-layer authorization matrix for {@link AdminRealtimeController}
 * (wave-2c target). The existing {@link AdminRealtimeControllerTest} is a POJO
 * test that proves the recover happy-path / 404 / 409 and the SERVICE-layer
 * brand gate, but it explicitly notes the ROLE_ADMIN gate is "annotation-level
 * ... not exercised by direct calls". This slice exercises exactly that.
 *
 * <p><b>Defence-in-depth — BOTH gates present.</b> {@code /api/admin/realtime/**}
 * is guarded by the {@code /api/admin/**} → {@code hasRole('ADMIN')} path
 * matcher AND the class {@code @PreAuthorize("hasRole('ADMIN')")}. A USER/MOD
 * is 403, anonymous is 401, and a denied caller's recover NEVER reaches the
 * tick service ({@code verify(never())} — mutation-resistant).
 *
 * <p><b>True cross-brand DENY at the HTTP layer.</b> A brand-X admin
 * (issuer ISS_X → {@code DOMAIN_brandx}) recovering a brand-Y session is 403,
 * and {@code recoverMatch} is never called — the brand gate fires before any
 * recovery side work.
 */
@WebFluxTest(controllers = AdminRealtimeController.class,
        properties = "app.tenancy.issuers="
                + "https://casdoor.brandx.example=brandx,"
                + "https://casdoor.brandy.example=brandy",
        excludeFilters = @Filter(type = FilterType.ASSIGNABLE_TYPE, classes = {
                BanGateFilter.class, DomainGuardFilter.class,
                RateLimitFilter.class, SecurityHeadersFilter.class}))
@Import({SecurityConfig.class, SecuritySliceSupport.TenantBeans.class,
        AdminRealtimeControllerHttpAuthzTest.Beans.class})
class AdminRealtimeControllerHttpAuthzTest {

    @Autowired WebTestClient web;

    @MockitoBean AuthoritativeTickService ticks;
    @MockitoBean GameRepository gameRepo;
    @MockitoBean GameSessionRepository sessionRepo;

    @TestConfiguration(proxyBeanMethods = false)
    static class Beans {
        @Bean DomainScopeService domainScopeService(GameRepository g, GameSessionRepository s) {
            return new DomainScopeService(g, s);
        }
    }

    private static final String SID = "s-y";

    private void stubSession(String gameSlug, String domain) {
        GameSession s = new GameSession();
        s.setId(SID);
        s.setGameSlug(gameSlug);
        when(sessionRepo.findById(eq(SID))).thenReturn(Mono.just(s));
        Game g = new Game();
        g.setDomain(domain);
        when(gameRepo.findBySlug(eq(gameSlug))).thenReturn(Mono.just(g));
    }

    // ── role gate (path matcher + @PreAuthorize) ──────────────────────────────

    @Test
    void recover_anonymous_is401_andNeverRecovers() {
        web.mutateWith(csrf())
                .post().uri("/api/admin/realtime/" + SID + "/recover")
                .exchange()
                .expectStatus().isUnauthorized();
        verify(ticks, never()).recoverMatch(any());
    }

    @Test
    void recover_user_is403_andNeverRecovers() {
        web.mutateWith(SecuritySliceSupport.user("eve"))
                .mutateWith(csrf())
                .post().uri("/api/admin/realtime/" + SID + "/recover")
                .exchange()
                .expectStatus().isForbidden();
        verify(ticks, never()).recoverMatch(any());
    }

    @Test
    void recover_mod_is403_andNeverRecovers() {
        web.mutateWith(SecuritySliceSupport.mod("m"))
                .mutateWith(csrf())
                .post().uri("/api/admin/realtime/" + SID + "/recover")
                .exchange()
                .expectStatus().isForbidden();
        verify(ticks, never()).recoverMatch(any());
    }

    // ── TRUE cross-brand DENY at the HTTP layer ───────────────────────────────

    @Test
    void recover_brandXAdmin_onBrandYSession_is403_andNeverRecovers() {
        stubSession("gy", "brandy");
        web.mutateWith(SecuritySliceSupport.adminOn("admin-x",
                        SecuritySliceSupport.ISS_X, SecuritySliceSupport.BRAND_X))
                .mutateWith(csrf())
                .post().uri("/api/admin/realtime/" + SID + "/recover")
                .exchange()
                .expectStatus().isForbidden();
        // Brand gate fires before any recovery side work.
        verify(ticks, never()).recoverMatch(any());
    }

    @Test
    void recover_brandXAdmin_onBrandXSession_is200_andRecovers() {
        stubSession("gx", "brandx");
        when(ticks.recoverMatch(eq(SID))).thenReturn(Mono.empty());
        web.mutateWith(SecuritySliceSupport.adminOn("admin-x",
                        SecuritySliceSupport.ISS_X, SecuritySliceSupport.BRAND_X))
                .mutateWith(csrf())
                .post().uri("/api/admin/realtime/" + SID + "/recover")
                .exchange()
                .expectStatus().isOk();
        verify(ticks).recoverMatch(eq(SID));
    }

    @Test
    void recover_brandYSuperadmin_onBrandXSession_is200_crossDomainBypass() {
        when(ticks.recoverMatch(eq(SID))).thenReturn(Mono.empty());
        web.mutateWith(SecuritySliceSupport.superadminOn("root",
                        SecuritySliceSupport.ISS_Y, SecuritySliceSupport.BRAND_Y))
                .mutateWith(csrf())
                .post().uri("/api/admin/realtime/" + SID + "/recover")
                .exchange()
                .expectStatus().isOk();
        verify(ticks).recoverMatch(eq(SID));
        // SUPERADMIN bypass resolves no session for the domain gate.
        verify(sessionRepo, never()).findById(any(String.class));
    }
}
