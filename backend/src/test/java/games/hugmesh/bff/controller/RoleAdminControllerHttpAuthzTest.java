package games.hugmesh.bff.controller;

import games.hugmesh.bff.config.BanGateFilter;
import games.hugmesh.bff.config.DomainGuardFilter;
import games.hugmesh.bff.config.RateLimitFilter;
import games.hugmesh.bff.config.SecurityConfig;
import games.hugmesh.bff.config.SecurityHeadersFilter;
import games.hugmesh.bff.controller.support.SecuritySliceSupport;
import games.hugmesh.bff.model.ModGameScope;
import games.hugmesh.bff.model.PublisherGameScope;
import games.hugmesh.bff.repository.GameRepository;
import games.hugmesh.bff.repository.GameSessionRepository;
import games.hugmesh.bff.repository.ModGameScopeRepository;
import games.hugmesh.bff.repository.PublisherGameScopeRepository;
import games.hugmesh.bff.service.DomainScopeService;
import games.hugmesh.bff.service.PublisherService;
import org.junit.jupiter.api.BeforeEach;
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

import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.reactive.server.SecurityMockServerConfigurers.csrf;

/**
 * HTTP/filter-layer privilege-escalation DENY matrix for
 * {@link RoleAdminController} (mission target #3: a USER/MOD cannot grant
 * themselves admin/role).
 *
 * <p>The whole controller is {@code @PreAuthorize("hasRole('ADMIN')")} at the
 * class level AND sits under the {@code /api/admin/**} →
 * {@code hasRole('ADMIN')} security matcher. These tests prove BOTH gates at
 * the WebTestClient boundary:
 * <ul>
 *   <li>anonymous → 401 on every grant/revoke/list path;</li>
 *   <li>a plain USER → 403 and NO {@code *_game_scopes} row is ever written
 *       (a USER cannot self-grant publisher/mod scope);</li>
 *   <li>a MOD → 403 (holding a mod scope does NOT confer the admin authority to
 *       mint more scopes — the canonical privilege-escalation attempt);</li>
 *   <li>a PUBLISHER → 403 on the admin grant path;</li>
 *   <li>an ADMIN → 200 and the grant lands (the legitimate path still works).</li>
 * </ul>
 */
@WebFluxTest(controllers = RoleAdminController.class,
        excludeFilters = @Filter(type = FilterType.ASSIGNABLE_TYPE, classes = {
                BanGateFilter.class, DomainGuardFilter.class,
                RateLimitFilter.class, SecurityHeadersFilter.class}))
@Import({SecurityConfig.class, SecuritySliceSupport.TenantBeans.class,
        RoleAdminControllerHttpAuthzTest.Beans.class})
class RoleAdminControllerHttpAuthzTest {

    @Autowired WebTestClient web;

    @MockitoBean PublisherService publisher;
    @MockitoBean PublisherGameScopeRepository publisherScopes;
    @MockitoBean ModGameScopeRepository modScopes;
    @MockitoBean GameRepository gameRepo;
    @MockitoBean GameSessionRepository sessionRepo;

    /** RoleAdminController now injects a DomainScopeService (BUG-D brand gate);
     *  @WebFluxTest does not contribute services, so build one over the mocked
     *  repos. The gate is inert on the default issuer (unknown slug ⇒ empty ⇒
     *  allow), so these single-tenant authz tests are unaffected. */
    @TestConfiguration(proxyBeanMethods = false)
    static class Beans {
        @Bean DomainScopeService domainScopeService(GameRepository g, GameSessionRepository s) {
            return new DomainScopeService(g, s);
        }
    }

    /** Default: an unknown game slug ⇒ DomainScopeService completes empty ⇒ the
     *  brand gate passes (single-tenant inert). Individual cross-brand DENY tests
     *  override with a real branded game row. */
    @BeforeEach
    void defaultGateInert() {
        lenient().when(gameRepo.findBySlug(anyString())).thenReturn(Mono.empty());
    }

    private static final String GRANT_PUBLISHER = "/api/admin/role-grants/grant-publisher";
    private static final String GRANT_MOD       = "/api/admin/role-grants/grant-mod";
    private static final String REVOKE_MOD      = "/api/admin/role-grants/revoke-mod";
    private static final String LIST_MODS       = "/api/admin/role-grants/mods";

    private static Map<String, String> grantBody(String target, String slug) {
        return Map.of("targetCasdoorId", target, "gameSlug", slug);
    }

    // ── anonymous → 401, no side effects ──────────────────────────────────────

    @Test
    void grantPublisher_anonymous_is401() {
        web.mutateWith(csrf())
                .post().uri(GRANT_PUBLISHER).bodyValue(grantBody("self", "rival-game"))
                .exchange()
                .expectStatus().isUnauthorized();
        verify(publisher, never()).adminGrantPublisher(any(), any(), any());
    }

    @Test
    void listMods_anonymous_is401() {
        web.get().uri(LIST_MODS).exchange().expectStatus().isUnauthorized();
        verify(modScopes, never()).findAll();
    }

    // ── plain USER → 403, never self-grants ───────────────────────────────────

    @Test
    void grantPublisher_user_is403_andNeverGrants() {
        web.mutateWith(SecuritySliceSupport.user("eve"))
                .mutateWith(csrf())
                .post().uri(GRANT_PUBLISHER).bodyValue(grantBody("eve", "rival-game"))
                .exchange()
                .expectStatus().isForbidden();
        // The self-escalation attempt must NEVER reach the scope write.
        verify(publisher, never()).adminGrantPublisher(any(), any(), any());
    }

    @Test
    void grantMod_user_is403_andNeverWritesScopeRow() {
        web.mutateWith(SecuritySliceSupport.user("eve"))
                .mutateWith(csrf())
                .post().uri(GRANT_MOD).bodyValue(grantBody("eve", "any-game"))
                .exchange()
                .expectStatus().isForbidden();
        verify(modScopes, never()).save(any());
        verify(modScopes, never()).findActive(anyString(), anyString());
    }

    // ── MOD → 403 (the canonical escalation: a mod minting more scopes) ───────

    @Test
    void grantMod_mod_is403_modCannotMintScopes() {
        web.mutateWith(SecuritySliceSupport.mod("m"))
                .mutateWith(csrf())
                .post().uri(GRANT_MOD).bodyValue(grantBody("m", "any-game"))
                .exchange()
                .expectStatus().isForbidden();
        verify(modScopes, never()).save(any());
    }

    @Test
    void revokeMod_mod_is403() {
        web.mutateWith(SecuritySliceSupport.mod("m"))
                .mutateWith(csrf())
                .post().uri(REVOKE_MOD).bodyValue(grantBody("rival", "g"))
                .exchange()
                .expectStatus().isForbidden();
        verify(modScopes, never()).save(any());
    }

    // ── PUBLISHER → 403 on the ADMIN grant path ───────────────────────────────

    @Test
    void grantPublisher_publisher_is403() {
        web.mutateWith(SecuritySliceSupport.publisher("p"))
                .mutateWith(csrf())
                .post().uri(GRANT_PUBLISHER).bodyValue(grantBody("p", "another-game"))
                .exchange()
                .expectStatus().isForbidden();
        verify(publisher, never()).adminGrantPublisher(any(), any(), any());
    }

    @Test
    void listMods_user_is403() {
        web.mutateWith(SecuritySliceSupport.user("eve"))
                .get().uri(LIST_MODS)
                .exchange()
                .expectStatus().isForbidden();
        verify(modScopes, never()).findAll();
    }

    // ── ADMIN → 200, legitimate grant lands ───────────────────────────────────

    @Test
    void grantPublisher_admin_is200_andGrants() {
        when(publisher.adminGrantPublisher(eq("root"), eq("alice"), eq("game-x")))
                .thenReturn(Mono.just(new PublisherGameScope()));

        web.mutateWith(SecuritySliceSupport.admin("root"))
                .mutateWith(csrf())
                .post().uri(GRANT_PUBLISHER).bodyValue(grantBody("alice", "game-x"))
                .exchange()
                .expectStatus().isOk();
        verify(publisher).adminGrantPublisher(eq("root"), eq("alice"), eq("game-x"));
    }

    @Test
    void grantMod_admin_is200_andWritesScope() {
        when(modScopes.findActive(eq("alice"), eq("game-x"))).thenReturn(Mono.empty());
        when(modScopes.save(any(ModGameScope.class)))
                .thenAnswer(inv -> Mono.just(inv.getArgument(0)));

        web.mutateWith(SecuritySliceSupport.admin("root"))
                .mutateWith(csrf())
                .post().uri(GRANT_MOD).bodyValue(grantBody("alice", "game-x"))
                .exchange()
                .expectStatus().isOk();
        verify(modScopes).save(any(ModGameScope.class));
    }

    @Test
    void listMods_admin_is200() {
        lenient().when(modScopes.findAll()).thenReturn(Flux.empty());

        web.mutateWith(SecuritySliceSupport.admin("root"))
                .get().uri(LIST_MODS)
                .exchange()
                .expectStatus().isOk();
    }
}
