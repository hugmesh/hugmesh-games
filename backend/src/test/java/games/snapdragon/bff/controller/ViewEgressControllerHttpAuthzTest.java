package games.snapdragon.bff.controller;

import games.snapdragon.bff.config.BanGateFilter;
import games.snapdragon.bff.config.DomainGuardFilter;
import games.snapdragon.bff.config.RateLimitFilter;
import games.snapdragon.bff.config.SecurityConfig;
import games.snapdragon.bff.config.SecurityHeadersFilter;
import games.snapdragon.bff.controller.support.SecuritySliceSupport;
import games.snapdragon.bff.model.SessionMember;
import games.snapdragon.bff.service.GameSessionService;
import games.snapdragon.bff.service.GameStateService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webflux.test.autoconfigure.WebFluxTest;
import org.springframework.context.annotation.ComponentScan.Filter;
import org.springframework.context.annotation.FilterType;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.reactive.server.SecurityMockServerConfigurers.csrf;

/**
 * HTTP/filter-layer authorization matrix for {@link ViewEgressController}
 * (wave-2c target — previously NO test at all). {@code /api/view} is in NO
 * {@code SecurityConfig} path matcher (it falls to {@code anyExchange().permitAll()}),
 * so the controller's OWN gate is the only control: the in-method
 * {@code jwt == null} guard returns 401, and {@code GameSessionService.authorizeControl}
 * enforces owner==subject (the JWT subject must be the active member owning the
 * posted {@code characterId}) returning 403 otherwise.
 *
 * <p>This slice drives those through the REAL filter chain at the WebTestClient
 * boundary:
 * <ul>
 *   <li>anonymous → 401, and the view is never emitted nor authorized;</li>
 *   <li>authenticated but NOT the character owner → 403, view never emitted
 *       (cross-user DENY, mutation-resistant via {@code verify(never())});</li>
 *   <li>the owning member → 202 Accepted, and the authorization keys on the JWT
 *       subject, never a body field.</li>
 * </ul>
 */
@WebFluxTest(controllers = ViewEgressController.class,
        excludeFilters = @Filter(type = FilterType.ASSIGNABLE_TYPE, classes = {
                BanGateFilter.class, DomainGuardFilter.class,
                RateLimitFilter.class, SecurityHeadersFilter.class}))
@Import({SecurityConfig.class, SecuritySliceSupport.TenantBeans.class})
class ViewEgressControllerHttpAuthzTest {

    @Autowired WebTestClient web;

    @MockitoBean GameStateService gameState;
    @MockitoBean GameSessionService sessions;

    private static ViewEgressController.ViewSampleRequest sample() {
        return new ViewEgressController.ViewSampleRequest(
                "sess-1", 0, 42L, 1, 2, 3, 0.1, 0.2, 75.0);
    }

    @Test
    void submit_anonymous_is401_andNeverAuthorizesOrEmits() {
        web.mutateWith(csrf())
                .post().uri("/api/view")
                .bodyValue(sample())
                .exchange()
                .expectStatus().isUnauthorized();
        verify(sessions, never()).authorizeControl(any(), anyLong());
        verify(gameState, never()).emitView(any());
    }

    @Test
    void submit_notCharacterOwner_is403_andNeverEmits() {
        // authorizeControl rejects a caller who does not own the character.
        when(sessions.authorizeControl(eq("mallory"), eq(42L)))
                .thenReturn(Mono.error(new ResponseStatusException(
                        HttpStatus.FORBIDDEN, "character not owned by caller")));
        web.mutateWith(SecuritySliceSupport.user("mallory"))
                .mutateWith(csrf())
                .post().uri("/api/view")
                .bodyValue(sample())
                .exchange()
                .expectStatus().isForbidden();
        // The view fan-out never fires for an unauthorized sample.
        verify(gameState, never()).emitView(any());
        // Authorization keyed on the JWT subject, never the body.
        verify(sessions).authorizeControl(eq("mallory"), eq(42L));
    }

    @Test
    void submit_owningMember_is202_andEmits_keyedToJwtSubject() {
        SessionMember m = new SessionMember();
        m.setSessionId("sess-1");
        m.setCasdoorId("alice");
        m.setCharacterId(42L);
        when(sessions.authorizeControl(eq("alice"), eq(42L))).thenReturn(Mono.just(m));
        web.mutateWith(SecuritySliceSupport.user("alice"))
                .mutateWith(csrf())
                .post().uri("/api/view")
                .bodyValue(sample())
                .exchange()
                .expectStatus().isAccepted();
        verify(sessions).authorizeControl(eq("alice"), eq(42L));
        verify(gameState).emitView(any());
    }
}
