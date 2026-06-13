package games.snapdragon.bff.service.realtime;

import games.snapdragon.bff.model.SessionMember;
import games.snapdragon.bff.model.realtime.RealtimeMatch;
import games.snapdragon.bff.repository.RealtimeMatchRepository;
import games.snapdragon.bff.repository.SessionMemberRepository;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentSkipListMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The P2 authoritative-loop contract for {@link AuthoritativeTickService}:
 * end-to-end tick determinism, (forTick, seq) input ordering + the lag
 * buffer, persist→recover-on-another-pod via a shared (fake) truth store,
 * per-session isolation, terminal handling, fail-closed inputs, and the
 * Cassandra-absent degradation.
 *
 * <p>No sleeps, no virtual time: services are built with {@code tickHz=0}
 * (production interval disabled) and tests drive the package-visible
 * {@code runTick} directly. The truth-store fakes are hand-rolled
 * subclasses, not Mockito mocks (Java-25 ByteBuddy can't mock the concrete
 * class — the {@code KafkaSessionSubscriberTest} style).
 */
class AuthoritativeTickServiceTest {

    private static final String MOVE  = "{\"op\":\"move\",\"dx\":1,\"dy\":0}";
    private static final String MOVE2 = "{\"op\":\"move\",\"dx\":0,\"dy\":1}";

    // ── hand-rolled fakes ───────────────────────────────────────────────

    /** In-memory truth store shared between "pods" in the recovery test.
     *  Non-final: the write-ahead tests subclass it to gate/fail appends. */
    static class FakeTruthStore extends RealtimeTruthStore {
        final Map<String, NavigableMap<Long, String>> keyframes = new ConcurrentHashMap<>();
        final Map<String, NavigableMap<Long, SimCommand>> logs = new ConcurrentHashMap<>();
        FakeTruthStore() { super(null, null); }
        private static long key(long forTick, int seq) { return (forTick << 20) | seq; }
        @Override public Mono<Void> saveKeyframe(String sid, long tick, String stateJson) {
            keyframes.computeIfAbsent(sid, s -> new ConcurrentSkipListMap<>()).put(tick, stateJson);
            return Mono.empty();
        }
        @Override public Mono<Void> appendCommand(String sid, SimCommand cmd) {
            logs.computeIfAbsent(sid, s -> new ConcurrentSkipListMap<>())
                .put(key(cmd.forTick(), cmd.seq()), cmd);
            return Mono.empty();
        }
        @Override public Mono<Void> deleteCommand(String sid, SimCommand cmd) {
            deleted.add(cmd);
            NavigableMap<Long, SimCommand> m = logs.get(sid);
            if (m != null) m.remove(key(cmd.forTick(), cmd.seq()));
            return Mono.empty();
        }
        final List<SimCommand> deleted = new CopyOnWriteArrayList<>();
        @Override public Mono<Keyframe> latestKeyframe(String sid) {
            NavigableMap<Long, String> m = keyframes.get(sid);
            if (m == null || m.isEmpty()) return Mono.empty();
            return Mono.just(new Keyframe(sid, m.lastKey(), m.lastEntry().getValue()));
        }
        @Override public Flux<SimCommand> commandsAfter(String sid, long tick) {
            NavigableMap<Long, SimCommand> m = logs.get(sid);
            if (m == null) return Flux.empty();
            return Flux.fromIterable(m.tailMap(key(tick + 1, 0), true).values());
        }
    }

    /** Records exactly which commands each step received, in order. */
    static final class RecordingSim implements SimCore {
        long tick;
        final List<List<SimCommand>> perStep = new ArrayList<>();
        @Override public long tick() { return tick; }
        @Override public void step(List<SimCommand> cmds) { perStep.add(List.copyOf(cmds)); tick++; }
        @Override public String snapshotJson() { return "{\"tick\":" + tick + "}"; }
        @Override public boolean isTerminal() { return false; }
        @Override public String resultJson() { return null; }
    }

    static final class RecordingFactory implements SimCoreFactory {
        final RecordingSim sim = new RecordingSim();
        final java.util.concurrent.atomic.AtomicInteger restores =
                new java.util.concurrent.atomic.AtomicInteger();
        @Override public String kind() { return "recording"; }
        @Override public SimCore create(long seed, String configJson) { return sim; }
        @Override public SimCore restore(String snapshotJson) { restores.incrementAndGet(); return sim; }
    }

    // ── wiring helpers ──────────────────────────────────────────────────

    private RealtimeMatchRepository matchRepo;
    private SessionMemberRepository memberRepo;

    private AuthoritativeTickService newService(RealtimeTruthStore store,
                                                int inputDelay, int keyframeInterval,
                                                SimCoreFactory... extraFactories) {
        return newService(store, inputDelay, keyframeInterval, Optional.empty(), extraFactories);
    }

    private AuthoritativeTickService newService(RealtimeTruthStore store,
                                                int inputDelay, int keyframeInterval,
                                                Optional<games.snapdragon.bff.audit.GameEventPublisher> publisher,
                                                SimCoreFactory... extraFactories) {
        matchRepo = mock(RealtimeMatchRepository.class);
        memberRepo = mock(SessionMemberRepository.class);
        lenient().when(matchRepo.save(any())).thenAnswer(inv -> Mono.just(inv.getArgument(0)));
        lenient().when(matchRepo.findById(anyString())).thenReturn(Mono.empty());
        lenient().when(memberRepo.findActiveBySession(anyString())).thenReturn(Flux.empty());
        @SuppressWarnings("unchecked")
        ObjectProvider<RealtimeTruthStore> provider = mock(ObjectProvider.class);
        lenient().when(provider.getIfAvailable()).thenReturn(store);
        List<SimCoreFactory> factories = new ArrayList<>(List.of(new ReferenceArenaSimFactory()));
        factories.addAll(List.of(extraFactories));
        // tickHz=0 disables the production Flux.interval — tests own the clock.
        return new AuthoritativeTickService(factories, matchRepo, memberRepo, provider,
                publisher, 0, inputDelay, keyframeInterval);
    }

    private static void start(AuthoritativeTickService svc, String sessionId,
                              String kind, long seed, List<String> players, String config) {
        StepVerifier.create(svc.startMatch(sessionId, "arena-game", kind, seed, players, config))
                .verifyComplete();
    }

    private static long submit(AuthoritativeTickService svc, String sessionId,
                               String playerId, String payload) {
        return svc.submitInput(sessionId, playerId, payload).block();
    }

    private static SessionMember member(String sessionId, String casdoorId) {
        SessionMember m = new SessionMember();
        m.setSessionId(sessionId);
        m.setCasdoorId(casdoorId);
        m.setJoinedAt(Instant.now());
        return m;
    }

    // ===================================================================
    // (a) tick determinism end-to-end
    // ===================================================================
    @Nested
    class TickDeterminism {

        @Test
        void twoServiceInstancesWithIdenticalInputsEmitIdenticalSnapshotStreams() {
            AuthoritativeTickService svc1 = newService(null, 2, 0);
            AuthoritativeTickService svc2 = newService(null, 2, 0);
            List<String> players = List.of("p1", "p2");
            start(svc1, "s", "reference-arena", 42L, players, null);
            start(svc2, "s", "reference-arena", 42L, players, null);

            List<String> seen1 = new CopyOnWriteArrayList<>();
            List<String> seen2 = new CopyOnWriteArrayList<>();
            svc1.snapshotStream("s").subscribe(seen1::add);
            svc2.snapshotStream("s").subscribe(seen2::add);

            for (int i = 0; i < 50; i++) {
                if (i % 2 == 0) {
                    assertThat(submit(svc1, "s", "p1", MOVE))
                            .isEqualTo(submit(svc2, "s", "p1", MOVE));
                } else {
                    String fire = "{\"op\":\"fire\",\"dir\":" + (i % 4) + "}";
                    assertThat(submit(svc1, "s", "p2", fire))
                            .isEqualTo(submit(svc2, "s", "p2", fire));
                }
                svc1.runTick("s");
                svc2.runTick("s");
            }

            assertThat(seen1).hasSize(50);
            assertThat(seen2).as("authoritative streams must be bit-exact").isEqualTo(seen1);
        }
    }

    // ===================================================================
    // (b) input ordering + lag buffer
    // ===================================================================
    @Nested
    class InputOrderingAndLagBuffer {

        @Test
        void inputsExecuteAtSubmissionTickPlusInputDelayNeverThePast() {
            RecordingFactory recording = new RecordingFactory();
            AuthoritativeTickService svc = newService(null, 2, 0, recording);
            start(svc, "s", "recording", 1L, List.of("p1", "p2"), null);

            // Submitted at tick 0 → scheduled for tick 2; same tick keeps a
            // monotonic seq in submission order.
            assertThat(submit(svc, "s", "p1", "a")).isEqualTo(2);
            assertThat(submit(svc, "s", "p2", "b")).isEqualTo(2);
            svc.runTick("s");                                  // produces tick 1: nothing due
            assertThat(submit(svc, "s", "p1", "c")).isEqualTo(3);
            svc.runTick("s");                                  // produces tick 2: a then b
            svc.runTick("s");                                  // produces tick 3: c

            List<List<SimCommand>> steps = recording.sim.perStep;
            assertThat(steps.get(0)).isEmpty();
            assertThat(steps.get(1)).extracting(SimCommand::payloadJson).containsExactly("a", "b");
            assertThat(steps.get(1)).extracting(SimCommand::seq).containsExactly(0, 1);
            assertThat(steps.get(2)).extracting(SimCommand::payloadJson).containsExactly("c");

            // Never the past: every command ran in the step that PRODUCED its
            // forTick (step index i produces tick i+1).
            for (int i = 0; i < steps.size(); i++) {
                for (SimCommand c : steps.get(i)) {
                    assertThat(c.forTick()).isEqualTo(i + 1);
                }
            }
        }

        @Test
        void scrambledCommandLogExecutesInForTickSeqOrder() {
            // The replay path feeds the queue from commandsAfter — hand it a
            // deliberately SCRAMBLED log and assert execution re-sorts into
            // the deterministic (forTick, seq) total order.
            List<SimCommand> scrambled = List.of(
                    new SimCommand(3, 0, "p1", "late"),
                    new SimCommand(2, 1, "p2", "second"),
                    new SimCommand(2, 0, "p1", "first"));
            RealtimeTruthStore store = new RealtimeTruthStore(null, null) {
                @Override public Mono<Void> saveKeyframe(String sid, long tick, String stateJson) {
                    return Mono.empty();
                }
                @Override public Mono<Void> appendCommand(String sid, SimCommand cmd) {
                    return Mono.empty();
                }
                @Override public Mono<Keyframe> latestKeyframe(String sid) {
                    return Mono.just(new Keyframe(sid, 0, "{}"));
                }
                @Override public Flux<SimCommand> commandsAfter(String sid, long tick) {
                    return Flux.fromIterable(scrambled);
                }
            };
            RecordingFactory recording = new RecordingFactory();
            AuthoritativeTickService svc = newService(store, 2, 0, recording);
            when(matchRepo.findById("s")).thenReturn(Mono.fromCallable(() -> {
                RealtimeMatch row = new RealtimeMatch();
                row.setSessionId("s");
                row.setKind("recording");
                row.setGameSlug("arena-game");
                return row;
            }));
            when(memberRepo.findActiveBySession("s"))
                    .thenReturn(Flux.just(member("s", "p1"), member("s", "p2")));

            StepVerifier.create(svc.recoverMatch("s")).verifyComplete();
            svc.runTick("s");
            svc.runTick("s");
            svc.runTick("s");

            List<SimCommand> executed = recording.sim.perStep.stream().flatMap(List::stream).toList();
            assertThat(executed).extracting(SimCommand::payloadJson)
                    .containsExactly("first", "second", "late");
            assertThat(executed).isSortedAccordingTo(
                    Comparator.comparingLong(SimCommand::forTick).thenComparingInt(SimCommand::seq));
        }
    }

    // ===================================================================
    // (c) persist → recover on another pod
    // ===================================================================
    @Nested
    class PersistAndRecover {

        @Test
        void recoveredServiceResimulatesBitExactlyAndStaysInLockstep() {
            FakeTruthStore shared = new FakeTruthStore();
            List<String> players = List.of("p1", "p2");

            AuthoritativeTickService pod1 = newService(shared, 2, 5);
            start(pod1, "s", "reference-arena", 7L, players, "{\"maxTicks\":100000}");
            List<String> pod1Snapshots = new CopyOnWriteArrayList<>();
            pod1.snapshotStream("s").subscribe(pod1Snapshots::add);

            // Ticks 1..12 with a few inputs; keyframes land at 5 and 10. The
            // input at tick 12 schedules for tick 14 — logged but UNEXECUTED
            // at "crash" time, so recovery must replay it.
            submit(pod1, "s", "p1", MOVE);                       // forTick 2
            for (int i = 0; i < 5; i++) pod1.runTick("s");       // → tick 5
            submit(pod1, "s", "p2", MOVE2);                      // forTick 7
            for (int i = 0; i < 5; i++) pod1.runTick("s");       // → tick 10
            submit(pod1, "s", "p1", MOVE);                       // forTick 12
            for (int i = 0; i < 2; i++) pod1.runTick("s");       // → tick 12
            submit(pod1, "s", "p2", MOVE2);                      // forTick 14 (pending)
            assertThat(shared.keyframes.get("s")).containsKeys(5L, 10L);

            // "Another pod": same shared truth store, fresh in-memory state.
            AuthoritativeTickService pod2 = newService(shared, 2, 5);
            when(matchRepo.findById("s")).thenReturn(Mono.fromCallable(() -> {
                RealtimeMatch row = new RealtimeMatch();
                row.setSessionId("s");
                row.setKind("reference-arena");
                row.setGameSlug("arena-game");
                row.setSeed(7L);
                return row;
            }));
            when(memberRepo.findActiveBySession("s"))
                    .thenReturn(Flux.just(member("s", "p1"), member("s", "p2")));
            StepVerifier.create(pod2.recoverMatch("s")).verifyComplete();

            List<String> pod2Snapshots = new CopyOnWriteArrayList<>();
            pod2.snapshotStream("s").subscribe(pod2Snapshots::add);

            // Catch up from the keyframe (tick 10) to the crash point (12):
            // the replayed forTick-12 command re-executes bit-exactly.
            pod2.runTick("s");
            pod2.runTick("s");
            assertThat(pod2Snapshots).containsExactlyElementsOf(pod1Snapshots.subList(10, 12));

            // Both pods now run M more ticks with identical new inputs.
            for (int i = 0; i < 8; i++) {
                if (i % 3 == 0) {
                    assertThat(submit(pod1, "s", "p1", MOVE))
                            .isEqualTo(submit(pod2, "s", "p1", MOVE));
                }
                pod1.runTick("s");
                pod2.runTick("s");
            }

            assertThat(pod1Snapshots).hasSize(20);
            assertThat(pod2Snapshots).hasSize(10);
            assertThat(pod2Snapshots)
                    .as("recovered pod must be in bit-exact lockstep")
                    .containsExactlyElementsOf(pod1Snapshots.subList(10, 20));
        }

        @Test
        void recoverWithoutTruthStoreFailsLoudly() {
            AuthoritativeTickService svc = newService(null, 2, 5);
            StepVerifier.create(svc.recoverMatch("s"))
                    .expectError(IllegalStateException.class)
                    .verify();
        }
    }

    // ===================================================================
    // (c2) cross-pod AUTO-recovery — submitInput / snapshotStream on a pod
    //      that doesn't hold the match recover it from the shared truth
    //      instead of 404ing ("any pod can serve a match").
    // ===================================================================
    @Nested
    class AutoRecovery {

        private FakeTruthStore shared;
        private RecordingFactory recording;
        private AuthoritativeTickService svc;

        /** A pod that doesn't hold session "s", with a shared truth holding
         *  a keyframe and an active roster — recoverable once the durable
         *  match row says the match hasn't ended. */
        private void recoverableSetup(RealtimeTruthStore store) {
            recording = new RecordingFactory();
            svc = newService(store, 2, 5, recording);
            when(memberRepo.findActiveBySession("s"))
                    .thenReturn(Flux.just(member("s", "p1"), member("s", "p2")));
        }

        private RealtimeMatch matchRow(Instant endedAt) {
            RealtimeMatch row = new RealtimeMatch();
            row.setSessionId("s");
            row.setKind("recording");
            row.setGameSlug("arena-game");
            row.setEndedAt(endedAt);
            return row;
        }

        private void stubMatchRow(Instant endedAt) {
            when(matchRepo.findById("s")).thenReturn(Mono.fromCallable(() -> matchRow(endedAt)));
        }

        private static void assert404(org.reactivestreams.Publisher<?> p) {
            StepVerifier.create(p)
                    .expectErrorSatisfies(err -> assertThat(((ResponseStatusException) err)
                            .getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND))
                    .verify();
        }

        @Test
        void submitInputAutoRecoversLandsTheInputAndSnapshotsFlow() {
            shared = new FakeTruthStore();
            shared.saveKeyframe("s", 5, "{}");
            recoverableSetup(shared);
            stubMatchRow(null);

            // No explicit recoverMatch — the submit itself recovers the match.
            assertThat(submit(svc, "s", "p1", MOVE)).isEqualTo(2);
            assertThat(recording.restores).hasValue(1);

            List<String> seen = new CopyOnWriteArrayList<>();
            svc.snapshotStream("s").subscribe(seen::add);
            svc.runTick("s");
            svc.runTick("s");
            assertThat(recording.sim.perStep.get(1))
                    .extracting(SimCommand::payloadJson).containsExactly(MOVE);
            assertThat(seen).hasSize(2);
        }

        @Test
        void snapshotStreamAutoRecoversAndStreams() {
            shared = new FakeTruthStore();
            shared.saveKeyframe("s", 5, "{}");
            recoverableSetup(shared);
            stubMatchRow(null);

            List<String> seen = new CopyOnWriteArrayList<>();
            svc.snapshotStream("s").subscribe(seen::add);   // subscription triggers recovery
            assertThat(recording.restores).hasValue(1);

            svc.runTick("s");
            assertThat(seen).containsExactly("{\"tick\":1}");

            // The registry now serves the live path — no second recovery.
            assertThat(submit(svc, "s", "p1", MOVE)).isEqualTo(3);
            assertThat(recording.restores).hasValue(1);
        }

        @Test
        void endedMatchRowIsNeverRecovered() {
            shared = new FakeTruthStore();
            shared.saveKeyframe("s", 5, "{}");
            recoverableSetup(shared);
            stubMatchRow(Instant.now());                    // endedAt set — match over

            assert404(svc.submitInput("s", "p1", MOVE));
            assert404(svc.snapshotStream("s"));
            assertThat(recording.restores).hasValue(0);
        }

        @Test
        void withoutATruthStoreThereIsNoRecoveryJust404() {
            recoverableSetup(null);
            stubMatchRow(null);                             // un-ended row, nothing to replay from

            assert404(svc.submitInput("s", "p1", MOVE));
            assert404(svc.snapshotStream("s"));
            assertThat(recording.restores).hasValue(0);
        }

        @Test
        void nonMemberCannotTriggerARecovery() {
            // A recoverable session (un-ended row + keyframe), but the caller
            // is NOT in the roster. The cold-path membership guard must 403
            // BEFORE any restore — a non-member can't force a recovery onto
            // this pod for a session they aren't in.
            shared = new FakeTruthStore();
            shared.saveKeyframe("s", 5, "{}");
            recoverableSetup(shared);                       // roster = p1, p2
            stubMatchRow(null);                             // un-ended → recoverable

            StepVerifier.create(svc.submitInput("s", "intruder", MOVE))
                    .expectErrorSatisfies(err -> assertThat(((ResponseStatusException) err)
                            .getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN))
                    .verify();
            assertThat(recording.restores)
                    .as("a non-member must not trigger a recovery").hasValue(0);
        }

        @Test
        void twoConcurrentSubmitsYieldExactlyOneRecoveryAndBothInputsLand() {
            shared = new FakeTruthStore();
            shared.saveKeyframe("s", 5, "{}");
            recoverableSetup(shared);
            // Gate the match-row lookup so BOTH submits pass the not-running
            // check and park BEFORE recovery; releasing the gate runs their
            // chains back-to-back — the first recovers, the second hits
            // recoverMatch's CONFLICT and resolves it by re-reading the
            // registry.
            reactor.core.publisher.Sinks.Empty<Void> gate = reactor.core.publisher.Sinks.empty();
            when(matchRepo.findById("s"))
                    .thenReturn(gate.asMono().then(Mono.fromCallable(() -> matchRow(null))));

            java.util.concurrent.atomic.AtomicReference<Long> ok1 =
                    new java.util.concurrent.atomic.AtomicReference<>();
            java.util.concurrent.atomic.AtomicReference<Long> ok2 =
                    new java.util.concurrent.atomic.AtomicReference<>();
            java.util.concurrent.atomic.AtomicReference<Throwable> err1 =
                    new java.util.concurrent.atomic.AtomicReference<>();
            java.util.concurrent.atomic.AtomicReference<Throwable> err2 =
                    new java.util.concurrent.atomic.AtomicReference<>();
            svc.submitInput("s", "p1", MOVE).subscribe(ok1::set, err1::set);
            svc.submitInput("s", "p2", MOVE2).subscribe(ok2::set, err2::set);
            assertThat(recording.restores).as("both parked pre-recovery").hasValue(0);
            gate.tryEmitEmpty();

            assertThat(err1.get()).isNull();
            assertThat(err2.get()).isNull();
            assertThat(ok1.get()).isEqualTo(2L);
            assertThat(ok2.get()).isEqualTo(2L);
            assertThat(recording.restores).as("exactly one recovery").hasValue(1);

            svc.runTick("s");
            svc.runTick("s");
            assertThat(recording.sim.perStep.get(1))
                    .extracting(SimCommand::payloadJson)
                    .containsExactlyInAnyOrder(MOVE, MOVE2);
        }
    }

    // ===================================================================
    // (d) session isolation
    // ===================================================================
    @Nested
    class SessionIsolation {

        @Test
        void inputsToOneMatchNeverAppearInTheOthersSnapshots() {
            AuthoritativeTickService svc = newService(null, 2, 0);
            start(svc, "A", "reference-arena", 1L, List.of("pa1", "pa2"), null);
            start(svc, "B", "reference-arena", 2L, List.of("pb1", "pb2"), null);

            List<String> seenA = new CopyOnWriteArrayList<>();
            List<String> seenB = new CopyOnWriteArrayList<>();
            svc.snapshotStream("A").subscribe(seenA::add);
            svc.snapshotStream("B").subscribe(seenB::add);

            submit(svc, "A", "pa1", MOVE);
            submit(svc, "B", "pb2", MOVE2);
            for (int i = 0; i < 5; i++) {
                svc.runTick("A");
                svc.runTick("B");
            }

            assertThat(seenA).hasSize(5).allSatisfy(s -> {
                assertThat(s).contains("pa1");
                assertThat(s).as("A must never carry B's players").doesNotContain("pb1", "pb2");
            });
            assertThat(seenB).hasSize(5).allSatisfy(s -> {
                assertThat(s).contains("pb1");
                assertThat(s).as("B must never carry A's players").doesNotContain("pa1", "pa2");
            });
        }

        @Test
        void sinksCompleteIndependently() {
            AuthoritativeTickService svc = newService(null, 2, 0);
            start(svc, "A", "reference-arena", 1L, List.of("pa1", "pa2"), "{\"maxTicks\":3}");
            start(svc, "B", "reference-arena", 2L, List.of("pb1", "pb2"), "{\"maxTicks\":100}");

            AtomicBoolean aComplete = new AtomicBoolean();
            AtomicBoolean bComplete = new AtomicBoolean();
            List<String> seenB = new CopyOnWriteArrayList<>();
            svc.snapshotStream("A").subscribe(s -> {}, err -> {}, () -> aComplete.set(true));
            svc.snapshotStream("B").subscribe(seenB::add, err -> {}, () -> bComplete.set(true));

            for (int i = 0; i < 3; i++) svc.runTick("A");      // A terminal at maxTicks
            assertThat(aComplete).isTrue();

            svc.runTick("B");                                   // B keeps ticking
            assertThat(bComplete).isFalse();
            assertThat(seenB).hasSize(1);
        }
    }

    // ===================================================================
    // (e) terminal handling
    // ===================================================================
    @Nested
    class TerminalHandling {

        @Test
        void terminalSimUpdatesThePostgresRowCompletesTheSinkAndRejectsFurtherInput() {
            AuthoritativeTickService svc = newService(null, 2, 0);
            start(svc, "s", "reference-arena", 3L, List.of("p1", "p2"), "{\"maxTicks\":2}");
            // The terminal path re-loads the row so save() UPDATEs (the row
            // is NOT marked new — the Persistable footgun).
            RealtimeMatch loaded = new RealtimeMatch();
            loaded.setSessionId("s");
            loaded.setKind("reference-arena");
            loaded.setGameSlug("arena-game");
            when(matchRepo.findById("s")).thenReturn(Mono.just(loaded));

            AtomicBoolean complete = new AtomicBoolean();
            List<String> seen = new CopyOnWriteArrayList<>();
            svc.snapshotStream("s").subscribe(seen::add, err -> {}, () -> complete.set(true));

            svc.runTick("s");
            svc.runTick("s");                                   // tick 2 == maxTicks → terminal

            ArgumentCaptor<RealtimeMatch> saved = ArgumentCaptor.forClass(RealtimeMatch.class);
            org.mockito.Mockito.verify(matchRepo, org.mockito.Mockito.times(2)).save(saved.capture());
            RealtimeMatch terminalRow = saved.getAllValues().get(1);
            assertThat(terminalRow.getEndedAt()).isNotNull();
            assertThat(terminalRow.getResultJson()).contains("max-ticks");
            assertThat(terminalRow.isNew()).as("terminal update must not markNew").isFalse();

            assertThat(seen).hasSize(2);                        // final snapshot included
            assertThat(complete).isTrue();

            StepVerifier.create(svc.submitInput("s", "p1", MOVE))
                    .expectErrorSatisfies(err -> assertThat(((ResponseStatusException) err)
                            .getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND))
                    .verify();
        }
    }

    // ===================================================================
    // (f) fail-closed
    // ===================================================================
    @Nested
    class FailClosed {

        @Test
        void submitToUnknownSessionErrors() {
            AuthoritativeTickService svc = newService(null, 2, 0);
            StepVerifier.create(svc.submitInput("nope", "p1", MOVE))
                    .expectErrorSatisfies(err -> assertThat(((ResponseStatusException) err)
                            .getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND))
                    .verify();
        }

        @Test
        void startingTwiceErrors() {
            AuthoritativeTickService svc = newService(null, 2, 0);
            start(svc, "s", "reference-arena", 1L, List.of("p1"), null);
            StepVerifier.create(svc.startMatch("s", "arena-game", "reference-arena", 1L,
                            List.of("p1"), null))
                    .expectErrorSatisfies(err -> assertThat(((ResponseStatusException) err)
                            .getStatusCode()).isEqualTo(HttpStatus.CONFLICT))
                    .verify();
        }

        @Test
        void nonMemberPlayerIdErrors() {
            AuthoritativeTickService svc = newService(null, 2, 0);
            start(svc, "s", "reference-arena", 1L, List.of("p1", "p2"), null);
            StepVerifier.create(svc.submitInput("s", "intruder", MOVE))
                    .expectErrorSatisfies(err -> assertThat(((ResponseStatusException) err)
                            .getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN))
                    .verify();
        }

        @Test
        void unknownSimKindErrors() {
            AuthoritativeTickService svc = newService(null, 2, 0);
            StepVerifier.create(svc.startMatch("s", "arena-game", "no-such-kind", 1L,
                            List.of("p1"), null))
                    .expectErrorSatisfies(err -> assertThat(((ResponseStatusException) err)
                            .getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST))
                    .verify();
        }

        @Test
        void snapshotStreamOfUnknownSessionErrors() {
            AuthoritativeTickService svc = newService(null, 2, 0);
            StepVerifier.create(svc.snapshotStream("nope"))
                    .expectError(ResponseStatusException.class)
                    .verify();
        }
    }

    // ===================================================================
    // (g) Cassandra-absent — ObjectProvider yields null, match still green
    // ===================================================================
    @Nested
    class CassandraAbsent {

        @Test
        void fullMatchRunsGreenWithNullTruthStore() {
            AuthoritativeTickService svc = newService(null, 2, 5);   // keyframes "due" but no store
            start(svc, "s", "reference-arena", 11L, List.of("p1", "p2"), "{\"maxTicks\":12}");
            when(matchRepo.findById("s")).thenReturn(Mono.fromCallable(() -> {
                RealtimeMatch row = new RealtimeMatch();
                row.setSessionId("s");
                return row;
            }));

            AtomicBoolean complete = new AtomicBoolean();
            List<String> seen = new CopyOnWriteArrayList<>();
            svc.snapshotStream("s").subscribe(seen::add, err -> {}, () -> complete.set(true));

            for (int i = 0; i < 12; i++) {
                if (i % 4 == 0) submit(svc, "s", "p1", MOVE);
                svc.runTick("s");
            }

            assertThat(seen).hasSize(12);
            assertThat(complete).as("match completes without Cassandra").isTrue();
        }
    }

    // ===================================================================
    // (h) write-ahead command log — a command may mutate the authoritative
    //     sim ONLY once it is durably in realtime_command_log (the
    //     bit-exact replay invariant), and a logged-but-unapplied command
    //     is compensated away.
    // ===================================================================
    @Nested
    class WriteAheadCommandLog {

        /** Append blocked behind a test-controlled gate; deletes recorded. */
        final class GatedAppendTruthStore extends FakeTruthStore {
            final reactor.core.publisher.Sinks.Empty<Void> gate =
                    reactor.core.publisher.Sinks.empty();
            @Override public Mono<Void> appendCommand(String sid, SimCommand cmd) {
                return gate.asMono().then(Mono.defer(() -> super.appendCommand(sid, cmd)));
            }
        }

        @Test
        void appendFailureRejectsTheInputAndNeverMutatesTheSim() {
            RealtimeTruthStore failing = new FakeTruthStore() {
                @Override public Mono<Void> appendCommand(String sid, SimCommand cmd) {
                    return Mono.error(new RuntimeException("cassandra down"));
                }
            };
            RecordingFactory recording = new RecordingFactory();
            AuthoritativeTickService svc = newService(failing, 2, 0, recording);
            start(svc, "s", "recording", 1L, List.of("p1"), null);

            StepVerifier.create(svc.submitInput("s", "p1", MOVE))
                    .expectErrorSatisfies(err -> assertThat(((ResponseStatusException) err)
                            .getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE))
                    .verify();

            // The rejected command must never reach the sim — applied-but-
            // unlogged is exactly the replay divergence the WAL forbids.
            for (int i = 0; i < 4; i++) svc.runTick("s");
            assertThat(recording.sim.perStep)
                    .as("no step may carry the unlogged command")
                    .allSatisfy(step -> assertThat(step).isEmpty());
        }

        @Test
        void inFlightAppendIsNotApplicableAndALateAppendIsCompensatedAway() {
            GatedAppendTruthStore store = new GatedAppendTruthStore();
            RecordingFactory recording = new RecordingFactory();
            // inputDelay=1: submitted at tick 0 ⇒ forTick 1 — the very next
            // step would have applied it under the old apply-before-append.
            AuthoritativeTickService svc = newService(store, 1, 0, recording);
            start(svc, "s", "recording", 1L, List.of("p1"), null);

            java.util.concurrent.atomic.AtomicReference<Throwable> submitErr =
                    new java.util.concurrent.atomic.AtomicReference<>();
            java.util.concurrent.atomic.AtomicReference<Long> submitOk =
                    new java.util.concurrent.atomic.AtomicReference<>();
            svc.submitInput("s", "p1", MOVE).subscribe(submitOk::set, submitErr::set);

            // Append still in flight ⇒ the command is NOT applicable.
            svc.runTick("s");                                  // produces tick 1 (== forTick)
            assertThat(recording.sim.perStep.get(0))
                    .as("an in-flight (unconfirmed) append must not execute").isEmpty();

            // Append completes AFTER its tick elapsed ⇒ compensating delete
            // + rejection, never a late/divergent application.
            store.gate.tryEmitEmpty();
            assertThat(submitOk.get()).isNull();
            assertThat(submitErr.get()).isInstanceOf(ResponseStatusException.class);
            assertThat(((ResponseStatusException) submitErr.get()).getStatusCode())
                    .isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
            assertThat(store.deleted).hasSize(1);
            assertThat(store.logs.getOrDefault("s", new ConcurrentSkipListMap<>()))
                    .as("the orphaned log row is removed").isEmpty();

            svc.runTick("s");
            assertThat(recording.sim.perStep.stream().flatMap(List::stream))
                    .as("the rejected command never reaches the sim").isEmpty();
        }

        @Test
        void confirmedAppendExecutesAtExactlyItsStampedTickAndStaysLogged() {
            FakeTruthStore store = new FakeTruthStore();
            RecordingFactory recording = new RecordingFactory();
            AuthoritativeTickService svc = newService(store, 2, 0, recording);
            start(svc, "s", "recording", 1L, List.of("p1"), null);

            assertThat(submit(svc, "s", "p1", MOVE)).isEqualTo(2);
            assertThat(store.logs.get("s")).as("logged before applicable").hasSize(1);

            svc.runTick("s");                                  // tick 1: not due
            svc.runTick("s");                                  // tick 2: applies
            assertThat(recording.sim.perStep.get(0)).isEmpty();
            assertThat(recording.sim.perStep.get(1))
                    .extracting(SimCommand::forTick).containsExactly(2L);
            assertThat(store.deleted).isEmpty();
        }
    }

    // ===================================================================
    // (i) replay-latest snapshot sink — a late subscriber gets exactly the
    //     CURRENT authoritative snapshot, then live frames (reconnect
    //     semantics), never a stale backlog.
    // ===================================================================
    @Nested
    class ReplayLatestSnapshots {

        @Test
        void lateSubscriberGetsTheCurrentSnapshotThenLiveFrames() {
            RecordingFactory recording = new RecordingFactory();
            AuthoritativeTickService svc = newService(null, 2, 0, recording);
            start(svc, "s", "recording", 1L, List.of("p1"), null);

            for (int i = 0; i < 5; i++) svc.runTick("s");   // snapshots 1..5 pre-subscribe

            List<String> seen = new CopyOnWriteArrayList<>();
            svc.snapshotStream("s").subscribe(seen::add);
            assertThat(seen)
                    .as("a late subscriber sees the CURRENT truth, not the tick-1 backlog")
                    .containsExactly("{\"tick\":5}");

            svc.runTick("s");
            svc.runTick("s");
            assertThat(seen).containsExactly("{\"tick\":5}", "{\"tick\":6}", "{\"tick\":7}");
        }

        @Test
        void lateSubscribersStayIsolatedPerSession() {
            // The SessionIsolation contract re-checked under replay-latest:
            // a late subscriber to B never sees A's latest frame.
            AuthoritativeTickService svc = newService(null, 2, 0);
            start(svc, "A", "reference-arena", 1L, List.of("pa1"), null);
            start(svc, "B", "reference-arena", 2L, List.of("pb1"), null);
            submit(svc, "A", "pa1", MOVE);
            for (int i = 0; i < 3; i++) {
                svc.runTick("A");
                svc.runTick("B");
            }

            List<String> lateB = new CopyOnWriteArrayList<>();
            svc.snapshotStream("B").subscribe(lateB::add);
            assertThat(lateB).hasSize(1);
            assertThat(lateB.get(0)).contains("pb1").doesNotContain("pa1");
        }
    }

    // ===================================================================
    // (j) keyframe Kafka tee — the decision-021 pipeline gets ONE event per
    //     keyframe interval, never per-tick.
    // ===================================================================
    @Nested
    class KeyframeKafkaTee {

        /** Hand-rolled recording publisher (the suite's no-Mockito-on-
         *  concrete-classes style) — captures publish() args verbatim. */
        final class RecordingPublisher extends games.snapdragon.bff.audit.GameEventPublisher {
            record Published(String channel, String gameSlug, String sessionId,
                             String casdoorId, Object payload) {}
            final List<Published> published = new CopyOnWriteArrayList<>();
            RecordingPublisher() {
                super((key, json) -> java.util.concurrent.CompletableFuture.completedFuture(null),
                      "test-pod", "test-topic");
            }
            @Override public void publish(String channel, String gameSlug, String sessionId,
                                          String casdoorId, Object payload) {
                published.add(new Published(channel, gameSlug, sessionId, casdoorId, payload));
            }
        }

        @Test
        void publishesExactlyOnKeyframeTicksNeverInBetween() {
            RecordingPublisher pub = new RecordingPublisher();
            FakeTruthStore store = new FakeTruthStore();
            AuthoritativeTickService svc = newService(store, 2, 3, Optional.of(pub));
            start(svc, "s", "reference-arena", 5L, List.of("p1"), null);

            for (int i = 0; i < 7; i++) svc.runTick("s");   // keyframes at ticks 3 and 6

            assertThat(pub.published).as("keyframe events only — no per-tick spam").hasSize(2);
            assertThat(pub.published).allSatisfy(p -> {
                assertThat(p.channel()).isEqualTo("realtime-keyframe");
                assertThat(p.gameSlug()).isEqualTo("arena-game");
                assertThat(p.sessionId()).isEqualTo("s");
                assertThat(p.casdoorId()).as("system event, no player").isNull();
            });
            @SuppressWarnings("unchecked")
            Map<String, Object> first = (Map<String, Object>) pub.published.get(0).payload();
            assertThat(first).containsEntry("tick", 3L);
            assertThat(first).containsEntry("stateJson", store.keyframes.get("s").get(3L));
            @SuppressWarnings("unchecked")
            Map<String, Object> second = (Map<String, Object>) pub.published.get(1).payload();
            assertThat(second).containsEntry("tick", 6L);
            assertThat(second).containsEntry("stateJson", store.keyframes.get("s").get(6L));
        }

        @Test
        void inputTeeStillFiresSeparatelyFromTheKeyframeTee() {
            RecordingPublisher pub = new RecordingPublisher();
            FakeTruthStore store = new FakeTruthStore();
            AuthoritativeTickService svc = newService(store, 2, 3, Optional.of(pub));
            start(svc, "s", "reference-arena", 5L, List.of("p1"), null);

            submit(svc, "s", "p1", MOVE);                   // realtime-input tee
            for (int i = 0; i < 3; i++) svc.runTick("s");   // keyframe at tick 3

            assertThat(pub.published).extracting(RecordingPublisher.Published::channel)
                    .containsExactly("realtime-input", "realtime-keyframe");
            assertThat(pub.published.get(0).casdoorId()).isEqualTo("p1");
        }

        @Test
        void absentPublisherKeyframeTickIsANoOpNotAnNpe() {
            FakeTruthStore store = new FakeTruthStore();
            AuthoritativeTickService svc = newService(store, 2, 3);   // Optional.empty()
            start(svc, "s", "reference-arena", 5L, List.of("p1"), null);

            for (int i = 0; i < 6; i++) svc.runTick("s");
            assertThat(store.keyframes.get("s"))
                    .as("keyframes still persist without the tee").containsKeys(3L, 6L);
        }
    }
}
