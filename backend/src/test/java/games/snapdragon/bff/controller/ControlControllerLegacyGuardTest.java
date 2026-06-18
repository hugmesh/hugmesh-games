package games.snapdragon.bff.controller;

import games.snapdragon.bff.model.ControlEvent;
import games.snapdragon.bff.service.GameSessionService;
import games.snapdragon.bff.service.GameStateService;
import games.snapdragon.bff.service.InstantService;
import games.snapdragon.bff.service.SessionService;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;
import reactor.test.StepVerifier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * BUG-A (controller-layer half): the legacy single-session branch must FAIL CLOSED on a
 * missing subject. A direct POJO call mirrors a request that reached the controller with
 * NO JWT and NO {@code X-Casdoor-Id} (e.g. a future routing change, or defence-in-depth
 * if the security matcher were ever loosened).
 *
 * <p>FAIL-FIRST: the unfixed guard {@code if (casdoorId != null && !isController(...))}
 * short-circuits when {@code casdoorId == null} and falls through to {@code dispatch()},
 * which calls {@code applyControl} (mutating shared state, fanning out SSE/Kafka) and
 * returns 204. The asserts below — 401 AND {@code applyControl} never called — fail
 * against that code. The fix 401s before any dispatch.
 */
class ControlControllerLegacyGuardTest {

    private final GameStateService gameState = mock(GameStateService.class);
    private final SessionService sessionService = mock(SessionService.class);
    private final GameSessionService gameSessionService = mock(GameSessionService.class);
    private final InstantService instantService = mock(InstantService.class);

    private final ControlController controller =
            new ControlController(gameState, sessionService, gameSessionService, instantService);

    private static ControlEvent move() {
        return new ControlEvent("MOVE_FORWARD", true, 0L, null);
    }

    @Test
    void legacyNullSubject_failsClosedWith401_andNeverDispatches() {
        // No JWT principal, no X-Session-Id, no X-Casdoor-Id ⇒ casdoorId resolves null.
        StepVerifier.create(controller.receiveControl(1L, move(), null, null, null))
                .expectErrorSatisfies(err -> {
                    assertThat(err).isInstanceOf(ResponseStatusException.class);
                    assertThat(((ResponseStatusException) err).getStatusCode().value()).isEqualTo(401);
                })
                .verify();
        verify(gameState, never()).applyControl(any(), anyLong());
        verify(gameState, never()).applyControl(any(), anyLong(), any());
        // The inverted guard must not have been able to skip the controller check —
        // we 401 before ever consulting isController.
        verify(sessionService, never()).isController(anyString());
    }

    @Test
    void legacyNonController_is403() {
        when(sessionService.isController("eve")).thenReturn(false);
        StepVerifier.create(controller.receiveControl(1L, move(), null, null, "eve"))
                .expectErrorSatisfies(err ->
                        assertThat(((ResponseStatusException) err).getStatusCode().value()).isEqualTo(403))
                .verify();
        verify(gameState, never()).applyControl(any(), anyLong(), any());
    }

    @Test
    void legacyControllerViaXCasdoorId_dispatches() {
        when(sessionService.isController("alice")).thenReturn(true);
        StepVerifier.create(controller.receiveControl(1L, move(), null, null, "alice"))
                .expectNextCount(1).verifyComplete();
        verify(gameState).applyControl(any(), anyLong(), any());
    }
}
