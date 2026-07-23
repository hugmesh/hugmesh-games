package games.hugmesh.bff.controller;

import games.hugmesh.bff.config.BanGateFilter;
import games.hugmesh.bff.config.DomainGuardFilter;
import games.hugmesh.bff.config.RateLimitFilter;
import games.hugmesh.bff.config.SecurityConfig;
import games.hugmesh.bff.config.SecurityHeadersFilter;
import games.hugmesh.bff.controller.support.SecuritySliceSupport;
import games.hugmesh.bff.model.SessionLogEntry;
import games.hugmesh.bff.repository.ModGameScopeRepository;
import games.hugmesh.bff.repository.PublisherGameScopeRepository;
import games.hugmesh.bff.repository.SessionLogRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webflux.test.autoconfigure.WebFluxTest;
import org.springframework.context.annotation.ComponentScan.Filter;
import org.springframework.context.annotation.FilterType;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Flux;

import java.time.Instant;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.reactive.server.SecurityMockServerConfigurers.csrf;

/**
 * HTTP/filter-layer authorization matrix for {@link SessionLogController}
 * (mission target #2: session-log scope to owner==subject).
 *
 * <p>Asserts at the WebTestClient boundary — through the real
 * {@code SecurityConfig} filter chain + {@code @EnableReactiveMethodSecurity}
 * {@code @PreAuthorize}, which the existing POJO controller tests never
 * exercise:
 * <ul>
 *   <li>{@code GET /me} requires authentication (401 anonymous) and the rows
 *       are scoped to the JWT subject — the caller cannot pass another user's
 *       casdoor id (there is no param; the subject is the only key);</li>
 *   <li>{@code GET /admin/{id}} is {@code hasRole('ADMIN')} — a plain
 *       USER and a MOD are 403, an ADMIN is 200;</li>
 *   <li>{@code GET /scoped/{id}} is {@code hasAnyRole('MOD','PUBLISHER','ADMIN')}
 *       — a plain USER is 403.</li>
 * </ul>
 */
@WebFluxTest(controllers = SessionLogController.class,
        // The custom @Component WebFilters (ban / domain-guard / rate-limit /
        // security-headers) carry their own DB/service deps + are covered by
        // their own tests; this slice targets the Spring Security filter chain +
        // @PreAuthorize, so they are excluded to keep the slice DB-free.
        excludeFilters = @Filter(type = FilterType.ASSIGNABLE_TYPE, classes = {
                BanGateFilter.class, DomainGuardFilter.class,
                RateLimitFilter.class, SecurityHeadersFilter.class}))
@Import({SecurityConfig.class, SecuritySliceSupport.TenantBeans.class})
class SessionLogControllerHttpAuthzTest {

    @Autowired WebTestClient web;

    @MockitoBean SessionLogRepository logs;
    @MockitoBean ModGameScopeRepository modScopes;
    @MockitoBean PublisherGameScopeRepository publisherScopes;

    private static SessionLogEntry entry(String memberCasdoorId) {
        return new SessionLogEntry("s1", "name", "g", "public", "creator",
                memberCasdoorId, 1L, 0, false,
                Instant.now(), null, null, null, null);
    }

    // ── GET /me : authenticated + scoped to the JWT subject ───────────────────

    @Test
    void me_anonymous_is401() {
        web.get().uri("/api/session-logs/me")
                .exchange()
                .expectStatus().isUnauthorized();
        verify(logs, never()).findByCasdoor(any(), anyInt());
    }

    @Test
    void me_scopesToJwtSubjectNotAnyParam() {
        when(logs.findByCasdoor(eq("alice"), anyInt())).thenReturn(Flux.just(entry("alice")));

        web.mutateWith(SecuritySliceSupport.user("alice"))
                .get().uri("/api/session-logs/me?limit=10")
                .exchange()
                .expectStatus().isOk()
                .expectBodyList(SessionLogEntry.class).hasSize(1);

        // The lookup key is the validated subject, never a request-supplied id.
        verify(logs).findByCasdoor(eq("alice"), anyInt());
        verify(logs, never()).findByCasdoor(eq("bob"), anyInt());
    }

    // ── GET /admin/{id} : hasRole('ADMIN') ────────────────────────────────────

    @Test
    void adminFor_anonymous_is401() {
        web.get().uri("/api/session-logs/admin/victim")
                .exchange()
                .expectStatus().isUnauthorized();
        verify(logs, never()).findByCasdoor(any(), anyInt());
    }

    @Test
    void adminFor_plainUser_is403_andNeverQueries() {
        web.mutateWith(SecuritySliceSupport.user("eve"))
                .get().uri("/api/session-logs/admin/victim")
                .exchange()
                .expectStatus().isForbidden();
        verify(logs, never()).findByCasdoor(any(), anyInt());
    }

    @Test
    void adminFor_mod_is403() {
        web.mutateWith(SecuritySliceSupport.mod("m"))
                .get().uri("/api/session-logs/admin/victim")
                .exchange()
                .expectStatus().isForbidden();
        verify(logs, never()).findByCasdoor(any(), anyInt());
    }

    @Test
    void adminFor_admin_is200_readsAnyUsersHistory() {
        when(logs.findByCasdoor(eq("victim"), anyInt())).thenReturn(Flux.just(entry("victim")));

        web.mutateWith(SecuritySliceSupport.admin("root"))
                .get().uri("/api/session-logs/admin/victim")
                .exchange()
                .expectStatus().isOk()
                .expectBodyList(SessionLogEntry.class).hasSize(1);
        verify(logs).findByCasdoor(eq("victim"), anyInt());
    }

    // ── GET /scoped/{id} : hasAnyRole('MOD','PUBLISHER','ADMIN') ───────────────

    @Test
    void scopedFor_plainUser_is403() {
        web.mutateWith(SecuritySliceSupport.user("eve"))
                .get().uri("/api/session-logs/scoped/victim")
                .exchange()
                .expectStatus().isForbidden();
        verify(modScopes, never()).findActiveByCasdoor(any());
    }

    @Test
    void scopedFor_modWithNoScopes_is403FromController() {
        // A MOD passes the role gate but the controller denies (403) when the
        // caller has zero game scopes — proving the scoped read is empty-deny.
        when(modScopes.findActiveByCasdoor(eq("m"))).thenReturn(Flux.empty());
        when(publisherScopes.findActiveByCasdoor(eq("m"))).thenReturn(Flux.empty());

        web.mutateWith(SecuritySliceSupport.mod("m"))
                .get().uri("/api/session-logs/scoped/victim")
                .exchange()
                .expectStatus().isForbidden();
    }

    @Test
    void scopedFor_admin_csrfNotRequiredOnGet() {
        // An ADMIN short-circuits the scope union and reads everything.
        when(logs.findByCasdoor(eq("victim"), anyInt())).thenReturn(Flux.just(entry("victim")));

        web.mutateWith(SecuritySliceSupport.admin("root"))
                .mutateWith(csrf())
                .get().uri("/api/session-logs/scoped/victim")
                .exchange()
                .expectStatus().isOk();
    }
}
