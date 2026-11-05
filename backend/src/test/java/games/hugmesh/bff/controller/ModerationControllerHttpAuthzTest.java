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
import games.hugmesh.bff.repository.ModGameScopeRepository;
import games.hugmesh.bff.service.DomainScopeService;
import games.hugmesh.bff.service.ModerationService;
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
 * HTTP/filter-layer authorization matrix for {@link ModerationController}
 * (wave-2c target). The existing {@link ModerationControllerDomainScopeTest}
 * is a POJO test that {@code new}s the controller and proves the SERVICE-layer
 * brand gate; it NEVER drives the Spring Security filter chain. This slice does:
 * every assertion runs through the REAL {@link SecurityConfig} at the
 * WebTestClient boundary.
 *
 * <p><b>The HTTP gate is a SINGLE point, not defence-in-depth.</b>
 * {@code /api/mod/**} is NOT in any {@code SecurityConfig} path matcher (it
 * falls to {@code anyExchange().permitAll()}); the ONLY HTTP-layer control is
 * the class-level {@code @PreAuthorize("hasAnyRole('MOD','ADMIN')")}. So a
 * plain authenticated player reaching {@code /api/mod/sessions/{id}/stop} is
 * stopped ONLY by method security — exactly the surface a POJO test cannot
 * exercise. {@link #stop_user_is403_andNeverActs} would PASS-as-200 if that
 * annotation were removed (fail-first evidence below), and the
 * {@code verify(never())} makes the DENY mutation-resistant: it fails if the
 * guard stops short-circuiting.
 *
 * <p><b>True cross-brand DENY at the HTTP layer.</b> Wave-2 only ran
 * single-tenant {@code DOMAIN_default}. Here the registry is multi-brand
 * (via {@code app.tenancy.issuers}) and a brand-X admin token (issuer ISS_X →
 * {@code DOMAIN_brandx}) is driven against a brand-Y session: 403, and the
 * moderation service is NEVER reached — the brand gate fires before the action.
 */
@WebFluxTest(controllers = ModerationController.class,
        properties = {"app.tenancy.issuers="
                + "https://casdoor.brandx.example=brandx,"
                + "https://casdoor.brandy.example=brandy",
                // Two issuers must say which is the games realm; each brand's is.
                "app.security.games-realm-issuers="
                + "https://casdoor.brandx.example,https://casdoor.brandy.example"},
        excludeFilters = @Filter(type = FilterType.ASSIGNABLE_TYPE, classes = {
                BanGateFilter.class, DomainGuardFilter.class,
                RateLimitFilter.class, SecurityHeadersFilter.class}))
@Import({SecurityConfig.class, SecuritySliceSupport.TenantBeans.class,
        ModerationControllerHttpAuthzTest.Beans.class})
class ModerationControllerHttpAuthzTest {

    @Autowired WebTestClient web;

    // Concrete service is mockable here (ByteBuddy inline mock-maker active on JDK 25).
    @MockitoBean ModerationService moderation;
    @MockitoBean ModGameScopeRepository scopes;
    // The DomainScopeService bean (below) is REAL, over these mock repos, so the
    // brand gate resolves session -> game -> domain genuinely.
    @MockitoBean GameRepository gameRepo;
    @MockitoBean GameSessionRepository sessionRepo;

    /** A real DomainScopeService over the mock repos — the brand gate is genuine. */
    @TestConfiguration(proxyBeanMethods = false)
    static class Beans {
        @Bean DomainScopeService domainScopeService(GameRepository g, GameSessionRepository s) {
            return new DomainScopeService(g, s);
        }
    }

    private static final String SID = "s-y";

    /** Wire session SID -> game gy -> domain. */
    private void stubSession(String gameSlug, String domain) {
        GameSession s = new GameSession();
        s.setId(SID);
        s.setGameSlug(gameSlug);
        when(sessionRepo.findById(eq(SID))).thenReturn(Mono.just(s));
        Game g = new Game();
        g.setDomain(domain);
        when(gameRepo.findBySlug(eq(gameSlug))).thenReturn(Mono.just(g));
    }

    // ── role gate (the ONLY HTTP control on /api/mod/**) ──────────────────────

    @Test
    void stop_anonymous_is401_andNeverActs() {
        web.mutateWith(csrf())
                .post().uri("/api/mod/sessions/" + SID + "/stop")
                .exchange()
                .expectStatus().isUnauthorized();
        verify(moderation, never()).stopSession(any(), any(), any());
    }

    @Test
    void stop_user_is403_andNeverActs() {
        // A plain authenticated player has ROLE_USER only — no MOD/ADMIN. The
        // class @PreAuthorize is the SOLE HTTP gate (no /api/mod/** matcher); if
        // it were removed this would 200 and the service would run. The
        // verify(never()) makes the 403 mutation-resistant.
        web.mutateWith(SecuritySliceSupport.user("eve"))
                .mutateWith(csrf())
                .post().uri("/api/mod/sessions/" + SID + "/stop")
                .exchange()
                .expectStatus().isForbidden();
        verify(moderation, never()).stopSession(any(), any(), any());
    }

    @Test
    void kick_user_is403_andNeverActs() {
        web.mutateWith(SecuritySliceSupport.user("eve"))
                .mutateWith(csrf())
                .post().uri("/api/mod/sessions/" + SID + "/kick")
                .bodyValue(new ModerationController.KickPlayerRequest("victim", "cheating"))
                .exchange()
                .expectStatus().isForbidden();
        verify(moderation, never()).kickPlayer(any(), any(), any(), any());
    }

    @Test
    void scopes_user_is403() {
        web.mutateWith(SecuritySliceSupport.user("eve"))
                .get().uri("/api/mod/scopes")
                .exchange()
                .expectStatus().isForbidden();
        verify(scopes, never()).findActiveByCasdoor(any());
    }

    @Test
    void scopes_mod_is200_scopedToJwtSubject() {
        when(scopes.findActiveByCasdoor(eq("m"))).thenReturn(Flux.empty());
        web.mutateWith(SecuritySliceSupport.mod("m"))
                .get().uri("/api/mod/scopes")
                .exchange()
                .expectStatus().isOk();
        // The scope read keys on the JWT subject, never a param.
        verify(scopes).findActiveByCasdoor(eq("m"));
        verify(scopes, never()).findActiveByCasdoor(eq("other"));
    }

    // ── TRUE cross-brand DENY at the HTTP layer ───────────────────────────────

    @Test
    void stop_brandXAdmin_onBrandYSession_is403_andNeverActs() {
        stubSession("gy", "brandy");          // session SID belongs to brand-Y
        web.mutateWith(SecuritySliceSupport.adminOn("admin-x",
                        SecuritySliceSupport.ISS_X, SecuritySliceSupport.BRAND_X))
                .mutateWith(csrf())
                .post().uri("/api/mod/sessions/" + SID + "/stop")
                .bodyValue(new ModerationController.StopSessionRequest("spam"))
                .exchange()
                .expectStatus().isForbidden();
        // Brand gate fires before the action — mutation-resistant DENY.
        verify(moderation, never()).stopSession(any(), any(), any());
    }

    @Test
    void kick_brandXMod_onBrandYSession_is403_andNeverActs() {
        stubSession("gy", "brandy");
        web.mutateWith(SecuritySliceSupport.userOn("mod-x",
                        SecuritySliceSupport.ISS_X, SecuritySliceSupport.BRAND_X)
                        .authorities(
                                new org.springframework.security.core.authority.SimpleGrantedAuthority("ROLE_USER"),
                                new org.springframework.security.core.authority.SimpleGrantedAuthority("ROLE_MOD"),
                                new org.springframework.security.core.authority.SimpleGrantedAuthority("DOMAIN_brandx")))
                .mutateWith(csrf())
                .post().uri("/api/mod/sessions/" + SID + "/kick")
                .bodyValue(new ModerationController.KickPlayerRequest("victim", "cheating"))
                .exchange()
                .expectStatus().isForbidden();
        verify(moderation, never()).kickPlayer(any(), any(), any(), any());
    }

    @Test
    void stop_brandXAdmin_onBrandXSession_is200_actorIsJwtSubject() {
        stubSession("gx", "brandx");          // session SID belongs to brand-X
        when(moderation.stopSession(eq("admin-x"), eq(SID), any())).thenReturn(Mono.empty());
        web.mutateWith(SecuritySliceSupport.adminOn("admin-x",
                        SecuritySliceSupport.ISS_X, SecuritySliceSupport.BRAND_X))
                .mutateWith(csrf())
                .post().uri("/api/mod/sessions/" + SID + "/stop")
                .bodyValue(new ModerationController.StopSessionRequest("spam"))
                .exchange()
                .expectStatus().isOk();
        // The acting mod is the JWT subject, never a body/param field.
        verify(moderation).stopSession(eq("admin-x"), eq(SID), any());
    }

    @Test
    void stop_brandYSuperadmin_onBrandXSession_is200_crossDomainBypass() {
        // SUPERADMIN reaches every brand's data — no session lookup needed.
        when(moderation.stopSession(eq("root"), eq(SID), any())).thenReturn(Mono.empty());
        web.mutateWith(SecuritySliceSupport.superadminOn("root",
                        SecuritySliceSupport.ISS_Y, SecuritySliceSupport.BRAND_Y))
                .mutateWith(csrf())
                .post().uri("/api/mod/sessions/" + SID + "/stop")
                .bodyValue(new ModerationController.StopSessionRequest("x"))
                .exchange()
                .expectStatus().isOk();
        verify(moderation).stopSession(eq("root"), eq(SID), any());
        // The superadmin bypass means the brand resolution never touched the repos.
        verify(sessionRepo, never()).findById(any(String.class));
    }
}
