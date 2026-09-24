package games.hugmesh.bff.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import games.hugmesh.bff.model.InputEvent;
import games.hugmesh.bff.model.InputEventType;
import games.hugmesh.bff.repository.InputEventRepository;
import games.hugmesh.bff.repository.InputEventTypeRepository;
import games.hugmesh.bff.repository.InputGateLookup;
import games.hugmesh.bff.service.GameSessionService;
import games.hugmesh.bff.service.GameStateService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Pins gates 2 and 3 of {@link InputEventController#submit} (hugmesh-games#13,
 * decisions 103a = C, 103b = A, 103c = A).
 *
 * <p>Every refusal case below was ACCEPTED (202, persisted, fanned out) on
 * origin/main f46c4b4, where submit() ran gate 1 only. The acceptance cases
 * pin the other direction: the gates must not refuse what a shipped title
 * sends.
 *
 * <p>Driven directly (no Spring slice) so the gate-3 property can be flipped
 * per test; {@link InputEventControllerHttpAuthzTest} proves the controller
 * still wires into the WebFlux slice and that a 405 reaches the wire.
 */
@ExtendWith(MockitoExtension.class)
class InputEventControllerGatesTest {

    @Mock InputEventRepository events;
    @Mock InputEventTypeRepository types;
    @Mock InputGateLookup gates;
    @Mock GameStateService state;
    @Mock GameSessionService sessions;

    private static final Jwt JWT = Jwt.withTokenValue("t")
            .header("alg", "none").subject("alice").build();

    /** Every flag changeset 063 seeds for an ordinary title. */
    private static final Map<String, Boolean> ALL_ON = Map.ofEntries(
            Map.entry("instant_loadout", true), Map.entry("quests", true),
            Map.entry("items", true), Map.entry("char_progression", true),
            Map.entry("audio", true), Map.entry("gamepad", true),
            Map.entry("mouse", true), Map.entry("keyboard", true),
            Map.entry("touch", true), Map.entry("world", true),
            Map.entry("control", true));

    @BeforeEach
    void knownVocabularyAndWorkingPersistence() {
        lenient().when(types.findByKindAndType(anyString(), anyString()))
                .thenAnswer(inv -> Mono.just(new InputEventType(
                        inv.getArgument(0), inv.getArgument(1), null, null, null)));
        lenient().when(events.insert(any(), anyString()))
                .thenAnswer(inv -> Mono.just(inv.getArgument(0, InputEvent.class)));
    }

    private InputEventController controller(boolean gate3) {
        return new InputEventController(events, types, gates, state, sessions,
                new ObjectMapper(), gate3);
    }

    private static InputEvent ev(String slug, String kind, String type) {
        return InputEvent.inbound(slug, 1L, null, null, kind, type, Map.of());
    }

    private static Map<String, Boolean> with(String flag, boolean value) {
        var m = new java.util.HashMap<>(ALL_ON);
        m.put(flag, value);
        return Map.copyOf(m);
    }

    private static Map<String, Boolean> without(String flag) {
        var m = new java.util.HashMap<>(ALL_ON);
        m.remove(flag);
        return Map.copyOf(m);
    }

    private void assertRefused405(Mono<InputEvent> result, String reasonFragment) {
        StepVerifier.create(result)
                .expectErrorSatisfies(e -> {
                    assertThat(e).isInstanceOf(ResponseStatusException.class);
                    ResponseStatusException rse = (ResponseStatusException) e;
                    assertThat(rse.getStatusCode()).isEqualTo(HttpStatus.METHOD_NOT_ALLOWED);
                    assertThat(rse.getReason()).contains(reasonFragment);
                })
                .verify();
        verify(events, never()).insert(any(), anyString());
        verify(state, never()).emitInput(any());
    }

    private void assertAccepted(Mono<InputEvent> result) {
        StepVerifier.create(result).expectNextCount(1).verifyComplete();
        verify(events).insert(any(), anyString());
        verify(state).emitInput(any());
    }

    // ── gate 2a: the kind flag ────────────────────────────────────────────────

    @Test
    void kindFlagFalse_isRefused405_andNothingPersisted() {
        when(gates.features("dodecahedron")).thenReturn(Mono.just(with("gamepad", false)));
        assertRefused405(controller(false).submit(1L, ev("dodecahedron", "gamepad", "axis"), JWT),
                "games.features.gamepad");
    }

    @Test
    void kindFlagAbsent_isRefused405_absentIsNeverAnImplicitAllow() {
        when(gates.features("chess")).thenReturn(Mono.just(without("world")));
        assertRefused405(controller(false).submit(1L, ev("chess", "world", "state"), JWT),
                "games.features.world");
    }

    @Test
    void unknownSlug_hasNoFlags_isRefused405() {
        when(gates.features("unknown")).thenReturn(Mono.just(Map.of()));
        assertRefused405(controller(false).submit(1L, ev("unknown", "gamepad", "button_down"), JWT),
                "games.features.gamepad");
    }

    @Test
    void anEmptyLookupSignal_isRefused_notSkipped() {
        // Fail-closed pin: an empty Mono from the lookup must not let the
        // .then() chain fall through to persistence.
        when(gates.features("chess")).thenReturn(Mono.empty());
        assertRefused405(controller(false).submit(1L, ev("chess", "control", "move"), JWT),
                "games.features.control");
    }

    @Test
    void kindFlagTrue_relayEventIsAccepted() {
        when(gates.features("chess")).thenReturn(Mono.just(ALL_ON));
        assertAccepted(controller(false).submit(1L, ev("chess", "world", "state"), JWT));
    }

    // ── gate 2b: the (kind, type) -> subsystem flag table ─────────────────────

    @Test
    void controlCast_withInstantLoadoutFalse_isRefused405() {
        when(gates.features("dodecahedron")).thenReturn(Mono.just(with("instant_loadout", false)));
        assertRefused405(controller(false).submit(1L, ev("dodecahedron", "control", "cast"), JWT),
                "games.features.instant_loadout");
    }

    @Test
    void anotherControlType_withInstantLoadoutFalse_isStillAccepted() {
        // The same flags that refuse control:cast must let control:move through:
        // the subsystem flag narrows ONE event, not the whole kind.
        when(gates.features("dodecahedron")).thenReturn(Mono.just(with("instant_loadout", false)));
        assertAccepted(controller(false).submit(1L, ev("dodecahedron", "control", "move"), JWT));
    }

    @Test
    void controlCast_withInstantLoadoutAbsent_isRefused405() {
        when(gates.features("three-wizards")).thenReturn(Mono.just(without("instant_loadout")));
        assertRefused405(controller(false).submit(1L, ev("three-wizards", "control", "cast"), JWT),
                "games.features.instant_loadout");
    }

    @Test
    void controlCast_withStaleSpellLoadoutKeyOnly_isRefused405() {
        // 028-7 renamed spell_loadout -> instant_loadout; the gate reads only the
        // new key, so a row still carrying the old one is not silently honoured.
        var m = new java.util.HashMap<>(without("instant_loadout"));
        m.put("spell_loadout", true);
        when(gates.features("three-wizards")).thenReturn(Mono.just(Map.copyOf(m)));
        assertRefused405(controller(false).submit(1L, ev("three-wizards", "control", "cast"), JWT),
                "games.features.instant_loadout");
    }

    @Test
    void controlCast_withKindAndSubsystemFlagsOn_isAccepted() {
        when(gates.features("three-wizards")).thenReturn(Mono.just(ALL_ON));
        assertAccepted(controller(false).submit(1L, ev("three-wizards", "control", "cast"), JWT));
    }

    @Test
    void controlCast_withKindFlagOff_isRefusedOnTheKindFlagFirst() {
        when(gates.features("three-wizards")).thenReturn(Mono.just(with("control", false)));
        assertRefused405(controller(false).submit(1L, ev("three-wizards", "control", "cast"), JWT),
                "games.features.control");
    }

    @Test
    void subsystemTable_isExactlyTheDecidedRow() {
        // A row added here is a product statement; this pins the table to the
        // rule 04-data-model.md states, so the doc and code change together.
        assertThat(InputEventController.SUBSYSTEM_FLAGS)
                .containsExactlyEntriesOf(Map.of("control:cast", "instant_loadout"));
    }

    // ── gate 1 still runs first ───────────────────────────────────────────────

    @Test
    void unknownKindType_is400_beforeAnyFeatureLookup() {
        when(types.findByKindAndType(eq("gamepad"), eq("nope"))).thenReturn(Mono.empty());
        StepVerifier.create(controller(false).submit(1L, ev("chess", "gamepad", "nope"), JWT))
                .expectErrorSatisfies(e -> assertThat(((ResponseStatusException) e).getStatusCode())
                        .isEqualTo(HttpStatus.BAD_REQUEST))
                .verify();
        verify(gates, never()).features(anyString());
    }

    // ── gate 3: present, OFF by default ───────────────────────────────────────

    @Test
    void gate3Off_noSupportRow_isStillAccepted_andTheTableIsNeverRead() {
        when(gates.features("tetris")).thenReturn(Mono.just(ALL_ON));
        assertAccepted(controller(false).submit(1L, ev("tetris", "keyboard", "key_down"), JWT));
        verify(gates, never()).enabledEvents(anyString());
    }

    @Test
    void gate3On_unmatchedTriple_isRefused405() {
        when(gates.features("chess")).thenReturn(Mono.just(ALL_ON));
        when(gates.enabledEvents("chess")).thenReturn(Mono.just(Set.of("control:move", "world:state")));
        assertRefused405(controller(true).submit(1L, ev("chess", "gamepad", "axis"), JWT),
                "game_event_support");
    }

    @Test
    void gate3On_titleWithNoRowsAtAll_isRefused405() {
        when(gates.features("tetris")).thenReturn(Mono.just(ALL_ON));
        when(gates.enabledEvents("tetris")).thenReturn(Mono.just(Set.of()));
        assertRefused405(controller(true).submit(1L, ev("tetris", "keyboard", "key_down"), JWT),
                "game_event_support");
    }

    @Test
    void gate3On_matchedTriple_isAccepted() {
        when(gates.features("chess")).thenReturn(Mono.just(ALL_ON));
        when(gates.enabledEvents("chess")).thenReturn(Mono.just(Set.of("control:move", "world:state")));
        assertAccepted(controller(true).submit(1L, ev("chess", "control", "move"), JWT));
    }

    @Test
    void gate3On_stillRunsGate2First() {
        when(gates.features("chess")).thenReturn(Mono.just(with("control", false)));
        assertRefused405(controller(true).submit(1L, ev("chess", "control", "move"), JWT),
                "games.features.control");
        verify(gates, never()).enabledEvents(anyString());
    }
}
