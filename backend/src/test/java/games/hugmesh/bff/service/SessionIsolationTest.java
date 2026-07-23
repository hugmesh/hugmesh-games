package games.hugmesh.bff.service;

import games.hugmesh.bff.model.ControlEvent;
import games.hugmesh.bff.model.InputEvent;
import games.hugmesh.bff.model.PlayerMovement;
import games.hugmesh.bff.model.PlayerViewEvent;
import games.hugmesh.bff.model.WorldState;
import games.hugmesh.bff.repository.PlayerCharacterRepository;
import games.hugmesh.bff.repository.PlayerPositionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;

/**
 * Per-session SSE isolation contract for {@link GameStateService}.
 *
 * <p><strong>The invariant under test:</strong> events emitted on
 * session A's per-session sinks NEVER reach a subscriber of session B's
 * sinks, and vice versa. This is the load-bearing correctness check for
 * the multiplayer overhaul — without it, two players in different
 * sessions could see each other's movement / world / view events and
 * the experience would be incoherent.
 *
 * <p>The legacy global sinks ({@code movementStream()} with no
 * argument, etc.) intentionally see <em>both</em> sessions because the
 * admin firehose at {@code /api/admin/stream} relies on that behaviour.
 * Per-session subscribers via {@code /api/game/stream?sessionId=…} only
 * see their own session — that's the user-facing isolation.
 */
class SessionIsolationTest {

    private GameStateService state;
    private PlayerPositionRepository positionRepo;
    private PgNotificationService pgNotify;

    @BeforeEach
    void setUp() {
        PlayerCharacterRepository characterRepo = mock(PlayerCharacterRepository.class);
        positionRepo = mock(PlayerPositionRepository.class);
        GameTimeService gameTimeService = mock(GameTimeService.class);
        pgNotify = mock(PgNotificationService.class);
        @SuppressWarnings("unchecked")
        ObjectProvider<PositionCacheService> cacheProvider = mock(ObjectProvider.class);

        // The PostConstruct init() and applyControl persistence path both
        // hit the repos; stub everything to harmless empties so the unit
        // can construct without a live DB.
        lenient().when(characterRepo.findById((Long) any())).thenReturn(Mono.empty());
        lenient().when(positionRepo.findByCharacterIdAndChapterId(any(), any())).thenReturn(Mono.empty());
        lenient().when(positionRepo.save(any())).thenAnswer(inv -> Mono.just(inv.getArgument(0)));
        lenient().when(pgNotify.publish(anyString(), anyString())).thenReturn(Mono.empty());
        lenient().when(cacheProvider.getIfAvailable()).thenReturn(null);

        state = new GameStateService(characterRepo, positionRepo, gameTimeService, pgNotify, cacheProvider,
                java.util.Optional.empty(), java.util.Optional.empty(), java.util.Optional.empty());
    }

    /**
     * Emit a movement on session A. Session B's per-session movement
     * stream must remain silent for the duration. The legacy global
     * stream (no argument) sees the event because admins rely on it.
     */
    @Test
    void movementEmittedOnSessionAIsNotSeenOnSessionB() {
        List<PlayerMovement> seenOnA = new CopyOnWriteArrayList<>();
        List<PlayerMovement> seenOnB = new CopyOnWriteArrayList<>();
        List<PlayerMovement> seenGlobally = new CopyOnWriteArrayList<>();

        state.movementStream("session-A").subscribe(seenOnA::add);
        state.movementStream("session-B").subscribe(seenOnB::add);
        state.movementStream().subscribe(seenGlobally::add);

        ControlEvent ev = new ControlEvent("MOVE_FORWARD", true, System.currentTimeMillis(), 1L);
        state.applyControl(ev, /* characterId */ 1L, /* sessionId */ "session-A");

        // applyControl emits synchronously; give StepVerifier no virtual
        // time, just assert directly.
        assertThat(seenOnA).hasSize(1)
                .first()
                .extracting(PlayerMovement::action).isEqualTo("MOVE_FORWARD");
        assertThat(seenOnB).as("session B must not see session A's events").isEmpty();
        assertThat(seenGlobally).as("admin firehose still sees the event").hasSize(1);
    }

    @Test
    void movementEmittedOnSessionBIsNotSeenOnSessionA() {
        List<PlayerMovement> seenOnA = new CopyOnWriteArrayList<>();
        List<PlayerMovement> seenOnB = new CopyOnWriteArrayList<>();

        state.movementStream("session-A").subscribe(seenOnA::add);
        state.movementStream("session-B").subscribe(seenOnB::add);

        ControlEvent ev = new ControlEvent("MOVE_LEFT", true, System.currentTimeMillis(), 1L);
        state.applyControl(ev, 2L, "session-B");

        assertThat(seenOnB).hasSize(1);
        assertThat(seenOnA).as("session A must not see session B's events").isEmpty();
    }

    /**
     * World snapshots are recomputed inside applyControl; the per-session
     * world stream of B must not see snapshots triggered by an emit on A.
     */
    @Test
    void worldSnapshotsAreSessionScoped() {
        List<WorldState> seenOnA = new CopyOnWriteArrayList<>();
        List<WorldState> seenOnB = new CopyOnWriteArrayList<>();

        state.worldStream("session-A").subscribe(seenOnA::add);
        state.worldStream("session-B").subscribe(seenOnB::add);

        ControlEvent ev = new ControlEvent("MOVE_RIGHT", true, System.currentTimeMillis(), 1L);
        state.applyControl(ev, 1L, "session-A");

        // The world snapshot is fired async via the Cassandra-roster Mono;
        // stepVerifier with a small wait covers the gap when the cache
        // provider is null (the err-path branch in emitWorldStateForChapter).
        StepVerifier.create(state.worldStream("session-A").take(1))
                .expectNextCount(0)            // already received via the eager subscriber
                .thenCancel().verify(Duration.ofMillis(50));

        // Either the eager subscriber or the StepVerifier above sees the
        // emission — what matters is that B sees nothing.
        assertThat(seenOnB).as("session B must not see session A's world snapshots").isEmpty();
    }

    /**
     * The view channel — the new per-player camera/orientation stream —
     * must enforce isolation just as strictly because admins watch one
     * session at a time and a leak would render another session's player
     * inside the wrong session's frustum overlay.
     */
    @Test
    void viewEventsAreSessionScoped() {
        List<PlayerViewEvent> seenOnA = new CopyOnWriteArrayList<>();
        List<PlayerViewEvent> seenOnB = new CopyOnWriteArrayList<>();

        state.viewStream("session-A").subscribe(seenOnA::add);
        state.viewStream("session-B").subscribe(seenOnB::add);

        PlayerViewEvent forA = new PlayerViewEvent(
                "session-A", 0, 1L, 1.0, 1.0, 1.0, 0.0, 0.0, 90.0, Instant.now());
        state.emitView(forA);

        assertThat(seenOnA).hasSize(1).first().extracting(PlayerViewEvent::sessionId).isEqualTo("session-A");
        assertThat(seenOnB).as("session B view stream must not see session A's view events").isEmpty();
    }

    /**
     * The {@code input} channel — the host-authoritative relay path the
     * web-native chess game uses ({@code /api/input} → {@code emitInput}) —
     * must be session-scoped too. A guest's {@code control/move} or the
     * host's {@code world/state} board echo emitted in session A must never
     * reach session B's per-session input subscriber, or two unrelated games
     * would see each other's moves. The legacy global input firehose still
     * sees every event (the admin diagnostics surface relies on it).
     */
    @Test
    void inputEventsAreSessionScoped() {
        List<InputEvent> seenOnA = new CopyOnWriteArrayList<>();
        List<InputEvent> seenOnB = new CopyOnWriteArrayList<>();
        List<InputEvent> seenGlobally = new CopyOnWriteArrayList<>();

        state.inputStream("session-A").subscribe(seenOnA::add);
        state.inputStream("session-B").subscribe(seenOnB::add);
        state.inputStream().subscribe(seenGlobally::add);

        InputEvent moveInA = InputEvent.inbound(
                "chess", 1L, "session-A", "casdoor-host",
                "control", "move", java.util.Map.of("uci", "e2e4"));
        state.emitInput(moveInA);

        assertThat(seenOnA).hasSize(1)
                .first()
                .extracting(InputEvent::sessionId).isEqualTo("session-A");
        assertThat(seenOnB).as("session B must not see session A's relayed moves").isEmpty();
        assertThat(seenGlobally).as("admin input firehose still sees the event").hasSize(1);
    }

    /**
     * An input event with no sessionId (legacy single-player ingestion)
     * must not leak into any per-session input sink. Mirrors the movement
     * legacy-path test for the relay channel.
     */
    @Test
    void inputWithNoSessionDoesNotEmitOnAnySessionSink() {
        List<InputEvent> seenOnAnySession = new CopyOnWriteArrayList<>();
        List<InputEvent> seenGlobally = new CopyOnWriteArrayList<>();

        state.inputStream("session-A").subscribe(seenOnAnySession::add);
        state.inputStream("session-B").subscribe(seenOnAnySession::add);
        state.inputStream().subscribe(seenGlobally::add);

        InputEvent globalOnly = InputEvent.inbound(
                "three-wizards", 1L, /* sessionId */ null, "casdoor-x",
                "control", "interact", java.util.Map.of());
        state.emitInput(globalOnly);

        assertThat(seenOnAnySession).as("no-session input must not leak into per-session sinks").isEmpty();
        assertThat(seenGlobally).hasSize(1);
    }

    /**
     * Bidirectional belt-and-suspenders: emit on each session, assert
     * each subscriber only sees its own. Catches any future regression
     * where a refactor accidentally fans out to all keyed sinks instead
     * of the single keyed sink.
     */
    @Test
    void bidirectionalIsolation() {
        List<PlayerMovement> seenOnA = new CopyOnWriteArrayList<>();
        List<PlayerMovement> seenOnB = new CopyOnWriteArrayList<>();

        state.movementStream("session-A").subscribe(seenOnA::add);
        state.movementStream("session-B").subscribe(seenOnB::add);

        state.applyControl(new ControlEvent("MOVE_FORWARD",  true, 0L, 1L), 1L, "session-A");
        state.applyControl(new ControlEvent("MOVE_BACKWARD", true, 0L, 1L), 2L, "session-B");
        state.applyControl(new ControlEvent("MOVE_LEFT",     true, 0L, 1L), 1L, "session-A");
        state.applyControl(new ControlEvent("MOVE_RIGHT",    true, 0L, 1L), 2L, "session-B");

        assertThat(seenOnA).hasSize(2);
        assertThat(seenOnA).extracting(PlayerMovement::characterId).containsExactly(1L, 1L);
        assertThat(seenOnB).hasSize(2);
        assertThat(seenOnB).extracting(PlayerMovement::characterId).containsExactly(2L, 2L);
    }

    /**
     * applyControl with sessionId=null must not allocate or emit on any
     * per-session sink. Legacy single-player path stays untouched.
     */
    @Test
    void legacySinglePlayerPathDoesNotEmitOnAnySessionSink() {
        List<PlayerMovement> seenOnAnySession = new CopyOnWriteArrayList<>();
        List<PlayerMovement> seenGlobally = new CopyOnWriteArrayList<>();

        state.movementStream("session-A").subscribe(seenOnAnySession::add);
        state.movementStream("session-B").subscribe(seenOnAnySession::add);
        state.movementStream().subscribe(seenGlobally::add);

        state.applyControl(new ControlEvent("JUMP", true, 0L, 1L), 1L);

        assertThat(seenOnAnySession).as("legacy applyControl must not leak into per-session sinks").isEmpty();
        assertThat(seenGlobally).hasSize(1);
    }
}
