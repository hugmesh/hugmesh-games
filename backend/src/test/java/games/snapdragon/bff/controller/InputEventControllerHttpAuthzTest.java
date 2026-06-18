package games.snapdragon.bff.controller;

import games.snapdragon.bff.config.BanGateFilter;
import games.snapdragon.bff.config.DomainGuardFilter;
import games.snapdragon.bff.config.RateLimitFilter;
import games.snapdragon.bff.config.SecurityConfig;
import games.snapdragon.bff.config.SecurityHeadersFilter;
import games.snapdragon.bff.controller.support.SecuritySliceSupport;
import games.snapdragon.bff.model.SessionMember;
import games.snapdragon.bff.repository.InputEventRepository;
import games.snapdragon.bff.repository.InputEventTypeRepository;
import games.snapdragon.bff.service.GameSessionService;
import games.snapdragon.bff.service.GameStateService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.reactive.WebFluxTest;
import org.springframework.context.annotation.ComponentScan.Filter;
import org.springframework.context.annotation.FilterType;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * BUG-C regression: HTTP/filter-layer authorization for {@link InputEventController}'s
 * GET history endpoints.
 *
 * <p>{@code /api/input/**} was MISSING from {@code SecurityConfig.PLAYER_PATHS}, so the
 * two GETs ({@code /character/{id}}, {@code /session/{id}}) fell through to
 * {@code anyExchange().permitAll()} AND carried no owner / member / domain check — an
 * UNAUTHENTICATED IDOR letting anyone read any character's / session's recent input
 * history (de-anonymising the owning Casdoor subject + leaking event payloads).
 *
 * <p>These tests pin:
 * <ul>
 *   <li>anonymous → 401 on both GETs, history repo never reached;</li>
 *   <li>authenticated non-owner → 403 on the character GET (authorizeControl miss);</li>
 *   <li>authenticated owner → 200;</li>
 *   <li>authenticated non-member → 403 on the session GET;</li>
 *   <li>authenticated active member → 200.</li>
 * </ul>
 *
 * <p>FAIL-FIRST: against the unfixed code anonymous reaches the controller (permitAll)
 * and the GET reads + returns history with NO auth ⇒ 200; the 401/403 assertions fail.
 */
@WebFluxTest(controllers = InputEventController.class,
        excludeFilters = @Filter(type = FilterType.ASSIGNABLE_TYPE, classes = {
                BanGateFilter.class, DomainGuardFilter.class,
                RateLimitFilter.class, SecurityHeadersFilter.class}))
@Import({SecurityConfig.class, SecuritySliceSupport.TenantBeans.class})
class InputEventControllerHttpAuthzTest {

    @Autowired WebTestClient web;

    @MockitoBean InputEventRepository events;
    @MockitoBean InputEventTypeRepository types;
    @MockitoBean GameStateService state;
    @MockitoBean GameSessionService sessions;

    private static final String CHAR_URI = "/api/input/character/1";
    private static final String SESS_URI = "/api/input/session/s-1";

    // ── anonymous → 401, history repo never reached ──────────────────────────

    @Test
    void recentForCharacter_anonymous_is401_andNeverReadsHistory() {
        web.get().uri(CHAR_URI).exchange().expectStatus().isUnauthorized();
        verify(events, never()).findRecentByCharacterId(anyLong(), anyInt());
        verify(sessions, never()).authorizeControl(anyString(), anyLong(), any());
    }

    @Test
    void recentForSession_anonymous_is401_andNeverReadsHistory() {
        web.get().uri(SESS_URI).exchange().expectStatus().isUnauthorized();
        verify(events, never()).findRecentBySessionId(anyString(), anyInt());
        verify(sessions, never()).activeMembership(anyString(), anyString());
    }

    // ── character GET : ownership gate ────────────────────────────────────────

    @Test
    void recentForCharacter_authenticatedNonOwner_is403_andNeverReadsHistory() {
        // authorizeControl errors 403 when the caller does not own the character.
        when(sessions.authorizeControl(eq("eve"), eq(1L), eq(null)))
                .thenReturn(Mono.error(new ResponseStatusException(HttpStatus.FORBIDDEN)));
        web.mutateWith(SecuritySliceSupport.user("eve"))
                .get().uri(CHAR_URI)
                .exchange()
                .expectStatus().isForbidden();
        verify(events, never()).findRecentByCharacterId(anyLong(), anyInt());
    }

    @Test
    void recentForCharacter_authenticatedOwner_is200() {
        when(sessions.authorizeControl(eq("alice"), eq(1L), eq(null)))
                .thenReturn(Mono.just(new SessionMember()));
        lenient().when(events.findRecentByCharacterId(eq(1L), anyInt())).thenReturn(Flux.empty());
        web.mutateWith(SecuritySliceSupport.user("alice"))
                .get().uri(CHAR_URI)
                .exchange()
                .expectStatus().isOk();
        verify(events).findRecentByCharacterId(eq(1L), anyInt());
    }

    // ── session GET : active-membership gate ──────────────────────────────────

    @Test
    void recentForSession_authenticatedNonMember_is403_andNeverReadsHistory() {
        // activeMembership empty ⇒ not a member ⇒ controller 403s.
        when(sessions.activeMembership(eq("s-1"), eq("eve"))).thenReturn(Mono.empty());
        web.mutateWith(SecuritySliceSupport.user("eve"))
                .get().uri(SESS_URI)
                .exchange()
                .expectStatus().isForbidden();
        verify(events, never()).findRecentBySessionId(anyString(), anyInt());
    }

    @Test
    void recentForSession_authenticatedMember_is200() {
        when(sessions.activeMembership(eq("s-1"), eq("alice")))
                .thenReturn(Mono.just(new SessionMember()));
        lenient().when(events.findRecentBySessionId(eq("s-1"), anyInt())).thenReturn(Flux.empty());
        web.mutateWith(SecuritySliceSupport.user("alice"))
                .get().uri(SESS_URI)
                .exchange()
                .expectStatus().isOk();
        verify(events).findRecentBySessionId(eq("s-1"), anyInt());
    }
}
