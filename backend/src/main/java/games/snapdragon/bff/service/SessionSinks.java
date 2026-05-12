package games.snapdragon.bff.service;

import games.snapdragon.bff.model.CharacterDto;
import games.snapdragon.bff.model.PlayerMovement;
import games.snapdragon.bff.model.PlayerViewEvent;
import games.snapdragon.bff.model.SessionControlEvent;
import games.snapdragon.bff.model.WorldState;
import reactor.core.publisher.Sinks;

/**
 * Per-session sink bundle. Lazy-allocated by {@link GameStateService} on the
 * first subscriber or emit for a given session, kept in a
 * {@code ConcurrentHashMap<String, SessionSinks>}.
 *
 * <p><strong>Isolation invariant:</strong> events emitted on session A's
 * sinks never reach a subscriber of session B. The
 * {@link GameStateService#applyControl(games.snapdragon.bff.model.ControlEvent, Long, String)}
 * write path routes to the matching {@code SessionSinks} only — see the
 * {@code SessionIsolationTest} integration test for the assertion.
 *
 * <p>Every sink uses {@code autoCancel=false} so a subscriber dropping out
 * does not terminate the producer-side sink. See decision 003 in the wiki.
 */
final class SessionSinks {

    final Sinks.Many<PlayerMovement>      movement;
    final Sinks.Many<WorldState>          world;
    final Sinks.Many<CharacterDto>        character;
    final Sinks.Many<PlayerViewEvent>     view;
    final Sinks.Many<SessionControlEvent> sessionControl;

    SessionSinks() {
        this.movement       = Sinks.many().multicast().onBackpressureBuffer(64,  false);
        this.world          = Sinks.many().multicast().onBackpressureBuffer(64,  false);
        this.character      = Sinks.many().multicast().onBackpressureBuffer(16,  false);
        this.view           = Sinks.many().multicast().onBackpressureBuffer(128, false);
        this.sessionControl = Sinks.many().multicast().onBackpressureBuffer(16,  false);
    }
}
