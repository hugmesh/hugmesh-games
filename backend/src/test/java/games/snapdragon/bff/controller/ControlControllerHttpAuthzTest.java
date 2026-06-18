package games.snapdragon.bff.controller;

import games.snapdragon.bff.config.BanGateFilter;
import games.snapdragon.bff.config.DomainGuardFilter;
import games.snapdragon.bff.config.RateLimitFilter;
import games.snapdragon.bff.config.SecurityConfig;
import games.snapdragon.bff.config.SecurityHeadersFilter;
import games.snapdragon.bff.controller.support.SecuritySliceSupport;
import games.snapdragon.bff.model.ControlEvent;
import games.snapdragon.bff.service.GameSessionService;
import games.snapdragon.bff.service.GameStateService;
import games.snapdragon.bff.service.InstantService;
import games.snapdragon.bff.service.SessionService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.reactive.WebFluxTest;
import org.springframework.context.annotation.ComponentScan.Filter;
import org.springframework.context.annotation.FilterType;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.reactive.server.WebTestClient;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.reactive.server.SecurityMockServerConfigurers.csrf;

/**
 * BUG-A regression: HTTP/filter-layer authorization for {@link ControlController}.
 *
 * <p>Two parts of the same hole:
 * <ol>
 *   <li>{@code /api/controls/**} was MISSING from {@code SecurityConfig.PLAYER_PATHS},
 *       so it fell through to {@code anyExchange().permitAll()} — an UNAUTHENTICATED
 *       POST reached the controller. These tests pin anonymous → 401 on the legacy AND
 *       the multiplayer header path, and that {@code applyControl} (the shared-state
 *       mutation + SSE/Kafka fan-out) is never reached.</li>
 *   <li>The legacy single-session branch's guard
 *       {@code if (casdoorId != null && !isController(casdoorId))} was INVERTED for the
 *       no-auth case — a null subject SKIPPED the check and dispatched. The
 *       authenticated-but-not-controller caller is still 403; the legitimate controller
 *       still gets 204.</li>
 * </ol>
 *
 * <p>FAIL-FIRST: against the unfixed code the anonymous POST returns 204 (the request
 * reaches dispatch via permitAll + the inverted null-subject skip), so the
 * {@code isUnauthorized()} assertions below fail.
 */
@WebFluxTest(controllers = ControlController.class,
        excludeFilters = @Filter(type = FilterType.ASSIGNABLE_TYPE, classes = {
                BanGateFilter.class, DomainGuardFilter.class,
                RateLimitFilter.class, SecurityHeadersFilter.class}))
@Import({SecurityConfig.class, SecuritySliceSupport.TenantBeans.class})
class ControlControllerHttpAuthzTest {

    @Autowired WebTestClient web;

    @MockitoBean GameStateService gameStateService;
    @MockitoBean SessionService sessionService;
    @MockitoBean GameSessionService gameSessionService;
    @MockitoBean InstantService instantService;

    private static final String URI = "/api/controls/1";

    private static ControlEvent move() {
        return new ControlEvent("MOVE_FORWARD", true, 0L, null);
    }

    // ── PLAYER_PATHS authenticated() matcher : anonymous is 401 ───────────────

    @Test
    void legacy_anonymous_is401_andNeverDispatches() {
        web.mutateWith(csrf())
                .post().uri(URI).bodyValue(move())
                .exchange()
                .expectStatus().isUnauthorized();
        // The shared-state mutation + SSE/Kafka fan-out must never run unauthenticated.
        verify(gameStateService, never()).applyControl(any(), anyLong());
        verify(gameStateService, never()).applyControl(any(), anyLong(), any());
        // The inverted legacy guard must not have been consulted with a null subject.
        verify(sessionService, never()).isController(anyString());
    }

    @Test
    void multiplayer_anonymous_is401_andNeverAuthorizes() {
        web.mutateWith(csrf())
                .post().uri(URI).header("X-Session-Id", "s-1").bodyValue(move())
                .exchange()
                .expectStatus().isUnauthorized();
        verify(gameSessionService, never()).authorizeControl(anyString(), anyLong(), anyString());
        verify(gameStateService, never()).applyControl(any(), anyLong(), any());
    }

    // ── legacy single-session : authenticated authority gate ──────────────────

    @Test
    void legacy_authenticatedNonController_is403_andNeverDispatches() {
        when(sessionService.isController(eq("eve"))).thenReturn(false);
        web.mutateWith(SecuritySliceSupport.user("eve"))
                .mutateWith(csrf())
                .post().uri(URI).bodyValue(move())
                .exchange()
                .expectStatus().isForbidden();
        verify(gameStateService, never()).applyControl(any(), anyLong(), any());
    }

    @Test
    void legacy_authenticatedController_is204_andDispatches() {
        when(sessionService.isController(eq("alice"))).thenReturn(true);
        web.mutateWith(SecuritySliceSupport.user("alice"))
                .mutateWith(csrf())
                .post().uri(URI).bodyValue(move())
                .exchange()
                .expectStatus().isNoContent();
        verify(gameStateService).applyControl(any(), eq(1L), eq(null));
    }
}
