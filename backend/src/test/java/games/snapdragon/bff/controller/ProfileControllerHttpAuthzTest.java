package games.snapdragon.bff.controller;

import games.snapdragon.bff.config.BanGateFilter;
import games.snapdragon.bff.config.DomainGuardFilter;
import games.snapdragon.bff.config.RateLimitFilter;
import games.snapdragon.bff.config.SecurityConfig;
import games.snapdragon.bff.config.SecurityHeadersFilter;
import games.snapdragon.bff.controller.support.SecuritySliceSupport;
import games.snapdragon.bff.model.accounts.ModProfile;
import games.snapdragon.bff.model.accounts.PlayerProfile;
import games.snapdragon.bff.model.accounts.ProducerProfile;
import games.snapdragon.bff.repository.accounts.ModProfileRepository;
import games.snapdragon.bff.repository.accounts.PlayerProfileRepository;
import games.snapdragon.bff.repository.accounts.ProducerProfileRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webflux.test.autoconfigure.WebFluxTest;
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
 * HTTP/filter-layer authorization matrix for {@link ProfileController}
 * (wave-2c target — previously NO test at all). Each endpoint is method-gated
 * by {@code @PreAuthorize}; {@code /api/profile/**} is in NO {@code SecurityConfig}
 * path matcher, so method security is the SOLE HTTP control. This slice drives
 * the REAL filter chain at the WebTestClient boundary.
 *
 * <p><b>Per-role gates + owner==subject:</b>
 * <ul>
 *   <li>player — {@code isAuthenticated()}; anonymous is 401; the read/write
 *       keys on the JWT subject (never a param), so a caller only ever touches
 *       their OWN row;</li>
 *   <li>producer — {@code hasRole('PUBLISHER') or hasRole('PRODUCER')}; a plain
 *       player is 403 and the producer repo is never read;</li>
 *   <li>mod — {@code hasRole('MOD')}; a plain player / a producer is 403.</li>
 * </ul>
 * Each DENY pairs the 403 with {@code verify(never())} so it fails if the gate
 * is removed.
 */
@WebFluxTest(controllers = ProfileController.class,
        excludeFilters = @Filter(type = FilterType.ASSIGNABLE_TYPE, classes = {
                BanGateFilter.class, DomainGuardFilter.class,
                RateLimitFilter.class, SecurityHeadersFilter.class}))
@Import({SecurityConfig.class, SecuritySliceSupport.TenantBeans.class})
class ProfileControllerHttpAuthzTest {

    @Autowired WebTestClient web;

    @MockitoBean PlayerProfileRepository players;
    @MockitoBean ProducerProfileRepository producers;
    @MockitoBean ModProfileRepository mods;

    // ── player : isAuthenticated + owner==subject ─────────────────────────────

    @Test
    void getPlayer_anonymous_is401() {
        web.get().uri("/api/profile/me/player")
                .exchange()
                .expectStatus().isUnauthorized();
        verify(players, never()).findById(any(String.class));
    }

    @Test
    void getPlayer_authenticated_is200_keyedToJwtSubject() {
        PlayerProfile p = new PlayerProfile();
        p.setCasdoorUserId("alice");
        p.setDisplayName("Alice");
        when(players.findById(eq("alice"))).thenReturn(Mono.just(p));
        web.mutateWith(SecuritySliceSupport.user("alice"))
                .get().uri("/api/profile/me/player")
                .exchange()
                .expectStatus().isOk();
        // The profile read keys on the subject — a caller cannot read another's row.
        verify(players).findById(eq("alice"));
        verify(players, never()).findById(eq("bob"));
    }

    @Test
    void updatePlayer_anonymous_is401_andNeverSaves() {
        web.mutateWith(csrf())
                .put().uri("/api/profile/me/player")
                .bodyValue(new ProfileController.PlayerUpdate("New Name"))
                .exchange()
                .expectStatus().isUnauthorized();
        verify(players, never()).save(any());
    }

    // ── producer : PUBLISHER or PRODUCER ──────────────────────────────────────

    @Test
    void getProducer_plainPlayer_is403_andNeverReads() {
        web.mutateWith(SecuritySliceSupport.user("eve"))
                .get().uri("/api/profile/me/producer")
                .exchange()
                .expectStatus().isForbidden();
        verify(producers, never()).findById(any(String.class));
    }

    @Test
    void getProducer_publisher_is200_keyedToSubject() {
        ProducerProfile pr = new ProducerProfile();
        pr.setCasdoorUserId("pubby");
        when(producers.findById(eq("pubby"))).thenReturn(Mono.just(pr));
        web.mutateWith(SecuritySliceSupport.publisher("pubby"))
                .get().uri("/api/profile/me/producer")
                .exchange()
                .expectStatus().isOk();
        verify(producers).findById(eq("pubby"));
    }

    @Test
    void updateProducer_plainPlayer_is403_andNeverSaves() {
        web.mutateWith(SecuritySliceSupport.user("eve"))
                .mutateWith(csrf())
                .put().uri("/api/profile/me/producer")
                .bodyValue(new ProfileController.ProducerUpdate("N", "n@e.com", "US"))
                .exchange()
                .expectStatus().isForbidden();
        verify(producers, never()).save(any());
    }

    // ── mod : MOD ─────────────────────────────────────────────────────────────

    @Test
    void getMod_plainPlayer_is403_andNeverReads() {
        web.mutateWith(SecuritySliceSupport.user("eve"))
                .get().uri("/api/profile/me/mod")
                .exchange()
                .expectStatus().isForbidden();
        verify(mods, never()).findById(any(String.class));
    }

    @Test
    void getMod_producer_is403() {
        // A producer is NOT a mod — cross-role DENY.
        web.mutateWith(SecuritySliceSupport.publisher("pubby"))
                .get().uri("/api/profile/me/mod")
                .exchange()
                .expectStatus().isForbidden();
        verify(mods, never()).findById(any(String.class));
    }

    @Test
    void getMod_mod_is200_keyedToSubject() {
        ModProfile m = new ModProfile();
        when(mods.findById(eq("m"))).thenReturn(Mono.just(m));
        web.mutateWith(SecuritySliceSupport.mod("m"))
                .get().uri("/api/profile/me/mod")
                .exchange()
                .expectStatus().isOk();
        verify(mods).findById(eq("m"));
    }
}
