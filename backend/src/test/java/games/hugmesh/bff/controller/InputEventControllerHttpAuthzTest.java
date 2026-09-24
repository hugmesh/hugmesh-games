package games.hugmesh.bff.controller;

import games.hugmesh.bff.config.BanGateFilter;
import games.hugmesh.bff.config.DomainGuardFilter;
import games.hugmesh.bff.config.RateLimitFilter;
import games.hugmesh.bff.config.SecurityConfig;
import games.hugmesh.bff.config.SecurityHeadersFilter;
import games.hugmesh.bff.controller.support.SecuritySliceSupport;
import com.fasterxml.jackson.databind.ObjectMapper;
import games.hugmesh.bff.model.InputEvent;
import games.hugmesh.bff.model.InputEventType;
import games.hugmesh.bff.model.SessionMember;
import games.hugmesh.bff.repository.InputEventRepository;
import games.hugmesh.bff.repository.InputEventTypeRepository;
import games.hugmesh.bff.controller.InputEventController.InputGateLookup;
import games.hugmesh.bff.service.GameSessionService;
import games.hugmesh.bff.service.GameStateService;
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
    @MockitoBean InputGateLookup gates;
    @MockitoBean GameStateService state;
    @MockitoBean GameSessionService sessions;
    // Boot 4 auto-configures Jackson 3 (tools.jackson.*) not Jackson 2;
    // InputEventController injects com.fasterxml.jackson.databind.ObjectMapper
    // so we supply it explicitly in the @WebFluxTest slice.
    @MockitoBean ObjectMapper objectMapper;

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

    // ── POST submit : authority-spoofing gate (kind:'world' ⇒ creator-only) ──────
    //
    // A session-scoped kind:'world' event is the host-authoritative relay: it
    // carries the canonical engine state every guest re-applies verbatim. Only the
    // session CREATOR runs the authoritative wasm engine, so only the creator may
    // POST kind:'world'. A non-creator member who owns their character in the
    // session still passes authorizeControl (ownership) but MUST be rejected here.
    //
    // FAIL-FIRST: against the unfixed controller authz was `.then()`-away'd, so the
    // SessionMember (and thus isCreator()) was discarded and a non-creator's forged
    // kind:'world' POST was accepted (202) and emitted — authority spoof.

    private static final String SUBMIT_URI = "/api/input/1";

    private static SessionMember member(String casdoorId, boolean creator) {
        SessionMember m = new SessionMember();
        m.setCasdoorId(casdoorId);
        m.setSessionId("s-1");
        m.setCharacterId(1L);
        m.setCreator(creator);
        return m;
    }

    private static String worldBody() {
        return """
            {"gameSlug":"chess","sessionId":"s-1","kind":"world","type":"state",
             "payload":{"fen":"8/8/8/8/8/8/8/8 w - - 0 1","status":"ongoing"}}
            """;
    }

    @Test
    void submitWorld_sessionScopedNonCreator_is403_andNothingEmitted() {
        // Non-creator member owns the character (authorizeControl succeeds) but is
        // NOT the session host — the creator-only world gate must 403.
        when(sessions.authorizeControl(eq("guest"), eq(1L), eq("s-1")))
                .thenReturn(Mono.just(member("guest", false)));
        // The (kind,type) lookup Mono is ASSEMBLED eagerly as the `.then(...)`
        // argument, but the creator gate 403s before it is ever SUBSCRIBED, so no
        // row is loaded, persisted, or fanned out. Stub leniently to mirror the
        // real repo (which returns a Mono, never null) during assembly.
        lenient().when(types.findByKindAndType(anyString(), anyString()))
                .thenReturn(Mono.just(new InputEventType("world", "state", null, null, null)));
        web.mutateWith(SecuritySliceSupport.user("guest"))
                .post().uri(SUBMIT_URI)
                .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                .bodyValue(worldBody())
                .exchange()
                .expectStatus().isForbidden();
        // Rejected BEFORE persistence + fan-out: no insert, no SSE emit.
        verify(events, never()).insert(any(), anyString());
        verify(state, never()).emitInput(any());
    }

    @Test
    void submitWorld_sessionScopedCreator_is202_andEmitted() throws Exception {
        // The creator runs the authoritative engine — the world submission proceeds
        // through the (kind,type) gate, persists, and fans out.
        when(sessions.authorizeControl(eq("host"), eq(1L), eq("s-1")))
                .thenReturn(Mono.just(member("host", true)));
        when(types.findByKindAndType(eq("world"), eq("state")))
                .thenReturn(Mono.just(new InputEventType("world", "state", null, null, null)));
        // Gate 2 (hugmesh-games#13): chess carries world:true since changeset 063.
        when(gates.features(eq("chess"))).thenReturn(Mono.just(java.util.Map.of("world", true)));
        when(objectMapper.writeValueAsString(any())).thenReturn("{}");
        when(events.insert(any(), anyString()))
                .thenAnswer(inv -> Mono.just(inv.getArgument(0, InputEvent.class)));
        web.mutateWith(SecuritySliceSupport.user("host"))
                .post().uri(SUBMIT_URI)
                .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                .bodyValue(worldBody())
                .exchange()
                .expectStatus().isAccepted();
        verify(events).insert(any(), anyString());
        verify(state).emitInput(any());
    }

    // ── POST submit : gate 2 refusal reaches the wire as 405 ─────────────────────
    //
    // hugmesh-games#13: a kind whose games.features flag is false (or absent) is
    // refused 405 before persistence. InputEventControllerGatesTest pins the rule
    // table; this pins that the refusal status survives the WebFlux slice.

    @Test
    void submitWorld_creatorButWorldFlagOff_is405_andNothingEmitted() {
        when(sessions.authorizeControl(eq("host"), eq(1L), eq("s-1")))
                .thenReturn(Mono.just(member("host", true)));
        when(types.findByKindAndType(eq("world"), eq("state")))
                .thenReturn(Mono.just(new InputEventType("world", "state", null, null, null)));
        when(gates.features(eq("chess"))).thenReturn(Mono.just(java.util.Map.of("world", false)));
        web.mutateWith(SecuritySliceSupport.user("host"))
                .post().uri(SUBMIT_URI)
                .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                .bodyValue(worldBody())
                .exchange()
                .expectStatus().isEqualTo(HttpStatus.METHOD_NOT_ALLOWED);
        verify(events, never()).insert(any(), anyString());
        verify(state, never()).emitInput(any());
    }
}
