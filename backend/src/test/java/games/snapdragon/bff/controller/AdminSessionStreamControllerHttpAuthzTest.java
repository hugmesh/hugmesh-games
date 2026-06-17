package games.snapdragon.bff.controller;

import games.snapdragon.bff.config.BanGateFilter;
import games.snapdragon.bff.config.DomainGuardFilter;
import games.snapdragon.bff.config.RateLimitFilter;
import games.snapdragon.bff.config.SecurityConfig;
import games.snapdragon.bff.config.SecurityHeadersFilter;
import games.snapdragon.bff.controller.support.SecuritySliceSupport;
import games.snapdragon.bff.service.GameSessionService;
import games.snapdragon.bff.service.GameStateService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.reactive.WebFluxTest;
import org.springframework.context.annotation.ComponentScan.Filter;
import org.springframework.context.annotation.FilterType;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.reactive.server.WebTestClient;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * HTTP/filter-layer role gate for {@link AdminSessionStreamController}
 * (mission target #4: admin SSE/streaming endpoints — role gate).
 *
 * <p>{@code /api/admin/sessions/{id}/stream} is a never-ending
 * {@code text/event-stream} that merges movement / world / character / view
 * sinks for one session — an admin observation surface that exposes every
 * player's live camera position. It is double-gated: the class-level
 * {@code @PreAuthorize('ADMIN')} AND the {@code /api/admin/**} →
 * {@code hasRole('ADMIN')} security matcher.
 *
 * <p>These tests assert the gate fires at the HTTP boundary BEFORE any session
 * lookup or sink subscription: anonymous → 401, USER/MOD → 403, and the
 * session is never touched for a denied caller (no information leak about
 * whether the session id exists).
 */
@WebFluxTest(controllers = AdminSessionStreamController.class,
        excludeFilters = @Filter(type = FilterType.ASSIGNABLE_TYPE, classes = {
                BanGateFilter.class, DomainGuardFilter.class,
                RateLimitFilter.class, SecurityHeadersFilter.class}))
@Import({SecurityConfig.class, SecuritySliceSupport.TenantBeans.class})
class AdminSessionStreamControllerHttpAuthzTest {

    @Autowired WebTestClient web;

    @MockitoBean GameStateService gameState;
    @MockitoBean GameSessionService sessions;

    private static final String URI = "/api/admin/sessions/abc123/stream";

    @Test
    void stream_anonymous_is401_andNeverTouchesSession() {
        web.get().uri(URI)
                .accept(MediaType.TEXT_EVENT_STREAM)
                .exchange()
                .expectStatus().isUnauthorized();
        verify(sessions, never()).get(any());
        verify(gameState, never()).movementStream(any());
    }

    @Test
    void stream_user_is403_andNeverTouchesSession() {
        web.mutateWith(SecuritySliceSupport.user("eve"))
                .get().uri(URI)
                .accept(MediaType.TEXT_EVENT_STREAM)
                .exchange()
                .expectStatus().isForbidden();
        verify(sessions, never()).get(any());
        verify(gameState, never()).movementStream(any());
    }

    @Test
    void stream_mod_is403() {
        web.mutateWith(SecuritySliceSupport.mod("m"))
                .get().uri(URI)
                .accept(MediaType.TEXT_EVENT_STREAM)
                .exchange()
                .expectStatus().isForbidden();
        verify(sessions, never()).get(any());
    }

    @Test
    void stream_publisher_is403() {
        // PUBLISHER is a privileged role but NOT admin — the admin observation
        // firehose must stay admin-only.
        web.mutateWith(SecuritySliceSupport.publisher("p"))
                .get().uri(URI)
                .accept(MediaType.TEXT_EVENT_STREAM)
                .exchange()
                .expectStatus().isForbidden();
        verify(sessions, never()).get(any());
    }
}
