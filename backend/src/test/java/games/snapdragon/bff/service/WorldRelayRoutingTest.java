package games.snapdragon.bff.service;

import games.snapdragon.bff.model.InputEvent;
import games.snapdragon.bff.repository.PlayerCharacterRepository;
import games.snapdragon.bff.repository.PlayerPositionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.Duration;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;

/**
 * Pins the host-authoritative WEB-NATIVE world relay routing in {@link GameStateService}.
 *
 * <p>Regression cover for a real, previously-shipped bug: a web-native game's
 * {@code sendWorld()} posts a {@code kind:'world'} event to {@code POST /api/input},
 * which reaches {@link GameStateService#emitInput}. Before the fix, {@code emitInput}
 * fanned the event ONLY to the session {@code input} sink — but the guest bridge
 * ({@code ApiBridgeService}) subscribes only to the {@code world} SSE channel, never
 * {@code input}, so the host's authoritative state never reached the guest and the
 * whole web-native multiplayer relay was a no-op guest-side.
 *
 * <p>The fix routes a {@code kind:'world'} event's raw payload onto a distinct
 * per-session {@code worldNative} sink, which {@code GameStreamController} merges into
 * the {@code world} SSE event. These tests assert:
 * <ul>
 *   <li>a {@code kind:'world'} event's payload DOES reach {@code worldNativeStream};</li>
 *   <li>it is NOT pushed onto the typed Godot {@code worldStream} (no type corruption);</li>
 *   <li>it STILL reaches {@code inputStream} (back-compat for admin observers);</li>
 *   <li>a non-world event (e.g. {@code kind:'control'}) is NOT routed to
 *       {@code worldNativeStream}.</li>
 * </ul>
 */
class WorldRelayRoutingTest {

    private static final String SID = "sess-relay-1";

    private GameStateService state;

    @BeforeEach
    void setUp() {
        PlayerCharacterRepository characterRepo = mock(PlayerCharacterRepository.class);
        PlayerPositionRepository positionRepo = mock(PlayerPositionRepository.class);
        GameTimeService gameTimeService = mock(GameTimeService.class);
        PgNotificationService pgNotify = mock(PgNotificationService.class);
        PositionCacheService positionCache = mock(PositionCacheService.class);
        @SuppressWarnings("unchecked")
        ObjectProvider<PositionCacheService> cacheProvider = mock(ObjectProvider.class);
        lenient().when(cacheProvider.getIfAvailable()).thenReturn(positionCache);
        lenient().when(characterRepo.findById((Long) any())).thenReturn(Mono.empty());
        lenient().when(positionRepo.findByCharacterIdAndChapterId(any(), any())).thenReturn(Mono.empty());
        lenient().when(pgNotify.publish(anyString(), anyString())).thenReturn(Mono.empty());
        lenient().when(positionCache.findOne(anyLong(), anyLong())).thenReturn(Mono.empty());
        lenient().when(positionCache.charactersInChapter(anyLong())).thenReturn(Flux.empty());

        state = new GameStateService(characterRepo, positionRepo, gameTimeService, pgNotify, cacheProvider,
                Optional.empty(), Optional.empty(), Optional.empty());
    }

    private static InputEvent worldEvent(Map<String, Object> payload) {
        return InputEvent.inbound("chess", 1L, SID, "casdoor-host", "world", "state", payload);
    }

    @Test
    void worldKindEvent_payloadReachesWorldNativeStream() {
        Map<String, Object> world = Map.of("fen", "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1",
                "status", "ongoing");
        StepVerifier.create(state.worldNativeStream(SID).next())
                .then(() -> state.emitInput(worldEvent(world)))
                .assertNext(payload -> assertThat(payload).isEqualTo(world))
                // Bounded so a broken fix (payload never routed to worldNative) FAILS
                // FAST here rather than hanging the whole suite.
                .expectComplete().verify(Duration.ofSeconds(3));
    }

    @Test
    void worldKindEvent_alsoStillReachesInputStream_forAdminObservers() {
        Map<String, Object> world = Map.of("fen", "8/8/8/8/8/8/8/8 w - - 0 1", "status", "ongoing");
        StepVerifier.create(state.inputStream(SID).next())
                .then(() -> state.emitInput(worldEvent(world)))
                .assertNext(ev -> {
                    assertThat(ev.kind()).isEqualTo("world");
                    assertThat(ev.payload()).isEqualTo(world);
                })
                .expectComplete().verify(Duration.ofSeconds(3));
    }

    @Test
    void worldKindEvent_isNotPushedOntoTypedGodotWorldStream() {
        // The typed WorldState `world` sink is the Godot roster path; a web-native
        // world payload (a Map, not a WorldState) must never be forced onto it.
        StepVerifier.create(state.worldStream(SID))
                .then(() -> state.emitInput(worldEvent(Map.of("fen", "x", "status", "ongoing"))))
                .expectTimeout(Duration.ofMillis(250))
                .verify();
    }

    @Test
    void nonWorldEvent_isNotRoutedToWorldNativeStream() {
        // Emit a control event first (must be ignored by worldNative), then a world
        // event; worldNative's first element must be the WORLD payload, proving the
        // control event was filtered out rather than passed through.
        Map<String, Object> control = Map.of("uci", "e2e4");
        Map<String, Object> world = Map.of("fen", "after-move", "status", "ongoing");
        StepVerifier.create(state.worldNativeStream(SID).next())
                .then(() -> {
                    state.emitInput(InputEvent.inbound("chess", 1L, SID, "casdoor-guest", "control", "move", control));
                    state.emitInput(worldEvent(world));
                })
                .assertNext(payload -> assertThat(payload).isEqualTo(world))
                .expectComplete().verify(Duration.ofSeconds(3));
    }
}
