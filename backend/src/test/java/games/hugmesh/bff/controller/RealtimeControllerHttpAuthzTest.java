package games.hugmesh.bff.controller;

import games.hugmesh.bff.config.BanGateFilter;
import games.hugmesh.bff.config.DomainGuardFilter;
import games.hugmesh.bff.config.RateLimitFilter;
import games.hugmesh.bff.config.SecurityConfig;
import games.hugmesh.bff.config.SecurityHeadersFilter;
import games.hugmesh.bff.controller.support.SecuritySliceSupport;
import games.hugmesh.bff.model.Game;
import games.hugmesh.bff.model.GameSession;
import games.hugmesh.bff.model.SessionMember;
import games.hugmesh.bff.repository.GameRepository;
import games.hugmesh.bff.repository.GameSessionRepository;
import games.hugmesh.bff.repository.SessionMemberRepository;
import games.hugmesh.bff.service.DomainScopeService;
import games.hugmesh.bff.service.realtime.AuthoritativeTickService;
import games.hugmesh.bff.service.realtime.RealtimeTruthStore;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
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

import java.time.Instant;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.reactive.server.SecurityMockServerConfigurers.csrf;

/**
 * HTTP/filter-layer authorization matrix for {@link RealtimeController}
 * (wave-2c target). The existing {@link RealtimeControllerTest} is a POJO test
 * proving the creator-only / brand gate via direct calls; this slice drives the
 * REAL {@link SecurityConfig} filter chain at the WebTestClient boundary.
 *
 * <p><b>Authenticated matcher — anonymous is 401.</b> {@code /api/realtime/**}
 * is in {@code SecurityConfig.PLAYER_PATHS} → {@code authenticated()}. Wave-2's
 * SecurityConfig comment notes that WITHOUT this entry an anonymous caller would
 * reach the snapshot stream / command log (the domain gate alone is a no-op
 * single-tenant). These tests pin that: anonymous → 401 on start / input /
 * stream / log, and the tick service is never reached.
 *
 * <p><b>In-controller authority gates at the HTTP layer:</b>
 * <ul>
 *   <li>start — creator-only; a non-creator authenticated player is 403 and the
 *       match never starts;</li>
 *   <li>stream / log — ACTIVE-member-or-admin; a same-domain non-member is 403
 *       (the inverse of session isolation), an admin observes.</li>
 * </ul>
 *
 * <p><b>True cross-brand DENY at the HTTP layer.</b> A brand-X player against a
 * brand-Y session is 403 before any side work — wave-2 only ran single-tenant.
 */
@WebFluxTest(controllers = RealtimeController.class,
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
        RealtimeControllerHttpAuthzTest.Beans.class})
class RealtimeControllerHttpAuthzTest {

    @Autowired WebTestClient web;

    @MockitoBean AuthoritativeTickService ticks;
    @MockitoBean GameSessionRepository sessionRepo;
    @MockitoBean SessionMemberRepository memberRepo;
    @MockitoBean GameRepository gameRepo;

    @TestConfiguration(proxyBeanMethods = false)
    static class Beans {
        @Bean DomainScopeService domainScopeService(GameRepository g, GameSessionRepository s) {
            return new DomainScopeService(g, s);
        }
        /** The controller injects an ObjectProvider<RealtimeTruthStore>; a
         *  Cassandra-less deploy has none. A null-yielding provider keeps the
         *  command-log path on its empty branch. */
        @Bean ObjectProvider<RealtimeTruthStore> truthStoreProvider() {
            return new NullProvider<>();
        }
    }

    private static final String SID = "s-y";

    /** session SID -> game gameSlug (domain) with creator + one active member. */
    private void stubSession(String gameSlug, String domain, String creator) {
        GameSession s = new GameSession();
        s.setId(SID);
        s.setGameSlug(gameSlug);
        s.setCreatorCasdoorId(creator);
        when(sessionRepo.findById(eq(SID))).thenReturn(Mono.just(s));
        Game g = new Game();
        g.setDomain(domain);
        when(gameRepo.findBySlug(eq(gameSlug))).thenReturn(Mono.just(g));
        SessionMember m = new SessionMember();
        m.setSessionId(SID);
        m.setCasdoorId(creator);
        m.setJoinedAt(Instant.now());
        lenient().when(memberRepo.findActiveBySession(eq(SID))).thenReturn(Flux.just(m));
    }

    private static RealtimeController.StartMatchRequest startReq() {
        return new RealtimeController.StartMatchRequest("reference-arena", 1L, null);
    }

    // ── authenticated() matcher : anonymous is 401 on every endpoint ──────────

    @Test
    void start_anonymous_is401_andNeverStarts() {
        web.mutateWith(csrf())
                .post().uri("/api/realtime/" + SID + "/start")
                .bodyValue(startReq())
                .exchange()
                .expectStatus().isUnauthorized();
        verify(ticks, never()).startMatch(any(), any(), any(), org.mockito.ArgumentMatchers.anyLong(), any(), any());
    }

    @Test
    void input_anonymous_is401() {
        web.mutateWith(csrf())
                .post().uri("/api/realtime/" + SID + "/input")
                .bodyValue(new RealtimeController.InputRequest("{}"))
                .exchange()
                .expectStatus().isUnauthorized();
        verify(ticks, never()).submitInput(any(), any(), any());
    }

    @Test
    void stream_anonymous_is401() {
        web.get().uri("/api/realtime/stream?sessionId=" + SID)
                .accept(org.springframework.http.MediaType.TEXT_EVENT_STREAM)
                .exchange()
                .expectStatus().isUnauthorized();
        verify(ticks, never()).snapshotStream(anyString());
    }

    @Test
    void log_anonymous_is401() {
        web.get().uri("/api/realtime/" + SID + "/log")
                .exchange()
                .expectStatus().isUnauthorized();
    }

    // ── creator-only start (owner==subject) ───────────────────────────────────

    @Test
    void start_nonCreator_is403_andNeverStarts() {
        stubSession("g", "default", "alice");   // alice owns it
        web.mutateWith(SecuritySliceSupport.user("bob"))   // bob does not
                .mutateWith(csrf())
                .post().uri("/api/realtime/" + SID + "/start")
                .bodyValue(startReq())
                .exchange()
                .expectStatus().isForbidden();
        verify(ticks, never()).startMatch(any(), any(), any(), org.mockito.ArgumentMatchers.anyLong(), any(), any());
    }

    @Test
    void start_creator_is200_andStarts() {
        stubSession("g", "default", "alice");
        when(ticks.startMatch(eq(SID), eq("g"), eq("reference-arena"), org.mockito.ArgumentMatchers.anyLong(), any(), any()))
                .thenReturn(Mono.empty());
        web.mutateWith(SecuritySliceSupport.user("alice"))
                .mutateWith(csrf())
                .post().uri("/api/realtime/" + SID + "/start")
                .bodyValue(startReq())
                .exchange()
                .expectStatus().isOk();
        verify(ticks).startMatch(eq(SID), eq("g"), eq("reference-arena"), org.mockito.ArgumentMatchers.anyLong(), any(), any());
    }

    // ── stream/log : ACTIVE-member-or-admin ──────────────────────────────────

    @Test
    void log_sameDomainNonMember_is403_andNeverReadsTruth() {
        // alice owns/joined; carol is an authenticated same-(default-)domain
        // user who learned the sessionId but is NOT a member. The match
        // visibility gate denies her — the inverse of session isolation.
        stubSession("g", "default", "alice");
        web.mutateWith(SecuritySliceSupport.user("carol"))
                .get().uri("/api/realtime/" + SID + "/log")
                .exchange()
                .expectStatus().isForbidden();
    }

    @Test
    void log_activeMember_is200() {
        stubSession("g", "default", "alice");
        web.mutateWith(SecuritySliceSupport.user("alice"))
                .get().uri("/api/realtime/" + SID + "/log")
                .exchange()
                .expectStatus().isOk();
    }

    @Test
    void log_admin_is200_observerBypass() {
        // An admin may observe without being a member (the admin observation
        // precedent). No member row needed.
        GameSession s = new GameSession();
        s.setId(SID);
        s.setGameSlug("g");
        s.setCreatorCasdoorId("alice");
        when(sessionRepo.findById(eq(SID))).thenReturn(Mono.just(s));
        Game g = new Game();
        g.setDomain("default");
        when(gameRepo.findBySlug(eq("g"))).thenReturn(Mono.just(g));
        web.mutateWith(SecuritySliceSupport.adminOn("root", SecuritySliceSupport.ISS_X, SecuritySliceSupport.BRAND_X))
                .get().uri("/api/realtime/" + SID + "/log")
                .exchange()
                .expectStatus().isOk();
        // admin observer never needs the member lookup
        verify(memberRepo, never()).findActiveBySession(any());
    }

    // ── TRUE cross-brand DENY at the HTTP layer ───────────────────────────────

    @Test
    void start_brandXPlayer_onBrandYSession_is403_andNeverStarts() {
        stubSession("gy", "brandy", "alice");
        web.mutateWith(SecuritySliceSupport.userOn("alice",
                        SecuritySliceSupport.ISS_X, SecuritySliceSupport.BRAND_X))
                .mutateWith(csrf())
                .post().uri("/api/realtime/" + SID + "/start")
                .bodyValue(startReq())
                .exchange()
                .expectStatus().isForbidden();
        // Brand gate fires before the creator check / any match work.
        verify(ticks, never()).startMatch(any(), any(), any(), org.mockito.ArgumentMatchers.anyLong(), any(), any());
    }

    @Test
    void input_brandXPlayer_onBrandYSession_is403_andNeverSubmits() {
        stubSession("gy", "brandy", "alice");
        web.mutateWith(SecuritySliceSupport.userOn("alice",
                        SecuritySliceSupport.ISS_X, SecuritySliceSupport.BRAND_X))
                .mutateWith(csrf())
                .post().uri("/api/realtime/" + SID + "/input")
                .bodyValue(new RealtimeController.InputRequest("{}"))
                .exchange()
                .expectStatus().isForbidden();
        verify(ticks, never()).submitInput(any(), any(), any());
    }

    /** ObjectProvider that always yields null/absent. */
    static final class NullProvider<T> implements ObjectProvider<T> {
        @Override public T getObject(Object... args) { return null; }
        @Override public T getObject() { return null; }
        @Override public T getIfAvailable() { return null; }
        @Override public T getIfUnique() { return null; }
    }
}
