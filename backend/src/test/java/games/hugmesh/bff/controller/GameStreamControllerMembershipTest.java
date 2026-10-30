package games.hugmesh.bff.controller;

import games.hugmesh.bff.model.SessionMember;
import games.hugmesh.bff.repository.SessionMemberRepository;
import games.hugmesh.bff.service.GameStateService;
import games.hugmesh.bff.service.GameTimeService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * OWASP BOLA fix #3: session-scoped SSE stream must reject callers who are
 * not active members of the requested session.
 *
 * <ul>
 *   <li>Unscoped global stream (no sessionId) remains open to spectators.</li>
 *   <li>Session-scoped stream requires a valid JWT (401 otherwise).</li>
 *   <li>USER-SITUATIONAL: active member of the session can subscribe.</li>
 *   <li>CROSS-USER DENY: caller not in session_members receives 403 —
 *       they must not receive any events from that session.</li>
 *   <li>Admins bypass the membership check (moderation / observation).</li>
 * </ul>
 */
class GameStreamControllerMembershipTest {

    private GameStateService gameStateService;
    private GameTimeService gameTimeService;
    private SessionMemberRepository sessionMemberRepo;

    private GameStreamController ctl;

    @BeforeEach
    void setUp() {
        gameStateService  = mock(GameStateService.class);
        gameTimeService   = mock(GameTimeService.class);
        sessionMemberRepo = mock(SessionMemberRepository.class);

        // All streams return Flux.never() — realistic (they never terminate)
        // and ensures any test that reaches the merged stream can cancel safely.
        when(gameStateService.movementStream()).thenReturn(Flux.never());
        when(gameStateService.movementStream(org.mockito.ArgumentMatchers.anyString())).thenReturn(Flux.never());
        when(gameStateService.worldStream()).thenReturn(Flux.never());
        when(gameStateService.worldStream(org.mockito.ArgumentMatchers.anyString())).thenReturn(Flux.never());
        when(gameStateService.charStatStream()).thenReturn(Flux.never());
        when(gameStateService.charStatStream(org.mockito.ArgumentMatchers.anyString())).thenReturn(Flux.never());
        when(gameStateService.sceneStream()).thenReturn(Flux.never());
        when(gameStateService.inputStream()).thenReturn(Flux.never());
        when(gameStateService.inputStream(org.mockito.ArgumentMatchers.anyString())).thenReturn(Flux.never());
        when(gameStateService.audioStream()).thenReturn(Flux.never());
        when(gameStateService.wasmConsoleStream()).thenReturn(Flux.never());
        when(gameStateService.sessionControlStream()).thenReturn(Flux.never());
        when(gameStateService.sessionControlStream(org.mockito.ArgumentMatchers.anyString())).thenReturn(Flux.never());
        when(gameTimeService.timeStream()).thenReturn(Flux.never());

        ctl = new GameStreamController(gameStateService, gameTimeService, sessionMemberRepo,
                games.hugmesh.bff.security.GamesAdminPolicy.gamesRealm());
    }

    private static Jwt jwt(String subject, String... roles) {
        return Jwt.withTokenValue("token")
                .header("alg", "RS256")
                .subject(subject)
                .claim("roles", List.of(roles))
                .build();
    }

    private static SessionMember member(String sessionId, String casdoorId) {
        SessionMember m = new SessionMember();
        m.setSessionId(sessionId);
        m.setCasdoorId(casdoorId);
        return m;
    }

    @Test
    void noSessionId_openToSpectators_noAuthRequired() {
        // The unscoped global stream (no session context) is intentionally open
        // so unauthenticated spectators can display the live game state.
        StepVerifier.create(ctl.stream(null, null))
                .thenCancel()
                .verify();
    }

    @Test
    void sessionId_noJwt_isUnauthorized() {
        // A session-scoped subscription without a JWT must be rejected.
        StepVerifier.create(ctl.stream("session-abc", null))
                .expectErrorSatisfies(err -> {
                    assertThat(err).isInstanceOf(ResponseStatusException.class);
                    assertThat(((ResponseStatusException) err).getStatusCode())
                            .isEqualTo(HttpStatus.UNAUTHORIZED);
                })
                .verify();
    }

    @Test
    void sessionId_activeMember_canSubscribe() {
        // USER-SITUATIONAL: an active session member may subscribe to that
        // session's stream and receive its events.
        when(sessionMemberRepo.findActive("session-abc", "player-uid"))
                .thenReturn(Mono.just(member("session-abc", "player-uid")));

        StepVerifier.create(ctl.stream("session-abc", jwt("player-uid", "player")))
                .thenCancel()
                .verify();
    }

    @Test
    void sessionId_notMember_isForbidden() {
        // CROSS-USER DENY: a caller whose casdoor_id is not in session_members
        // for the requested session must receive 403 and no stream events.
        when(sessionMemberRepo.findActive("session-abc", "intruder-uid"))
                .thenReturn(Mono.empty());

        StepVerifier.create(ctl.stream("session-abc", jwt("intruder-uid", "player")))
                .expectErrorSatisfies(err -> {
                    assertThat(err).isInstanceOf(ResponseStatusException.class);
                    assertThat(((ResponseStatusException) err).getStatusCode())
                            .isEqualTo(HttpStatus.FORBIDDEN);
                })
                .verify();
    }

    @Test
    void sessionId_adminBypassesMembershipCheck() {
        // Admins (and superadmins) may observe any session for moderation.
        // findActive must NOT be called — the admin bypass short-circuits first.
        StepVerifier.create(ctl.stream("session-abc", jwt("admin-uid", "admin")))
                .thenCancel()
                .verify();
    }
}
