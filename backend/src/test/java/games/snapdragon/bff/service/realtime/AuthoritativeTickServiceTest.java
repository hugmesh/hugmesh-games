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
        @Override public String kind() { return "recording"; }
        @Override public SimCore create(long seed, String configJson) { return sim; }
        @Override public SimCore restore(String snapshotJson) { return sim; }
    }

    // ── wiring helpers ──────────────────────────────────────────────────

    private RealtimeMatchRepository matchRepo;
    private SessionMemberRepository memberRepo;

    private AuthoritativeTickService newService(RealtimeTruthStore store,
                                                int inputDelay, int keyframeInterval,
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
                Optional.empty(), 0, inputDelay, keyframeInterval);
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
}
