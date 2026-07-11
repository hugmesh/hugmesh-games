package games.snapdragon.bff.service.realtime;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import games.snapdragon.bff.model.SessionMember;
import games.snapdragon.bff.model.realtime.RealtimeMatch;
import games.snapdragon.bff.repository.RealtimeMatchRepository;
import games.snapdragon.bff.repository.SessionMemberRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;
import reactor.core.scheduler.Scheduler;
import reactor.core.scheduler.Schedulers;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentSkipListSet;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * The server-authoritative tick loop for real-time multiplayer matches
 * (docs/realtime-server-authoritative-design.md, P2).
 *
 * <h2>Authority model</h2>
 *
 * <p>Exactly one pod owns the authoritative tick loop for a given
 * {@code sessionId} at any moment. That pod holds a live {@link MatchState}
 * in the {@link #running} map and fires one {@link #runTick} call per
 * interval from a {@code Flux.interval} subscription. No consensus protocol
 * is used; the authority is single-pod and pod-affine. If the pod dies, any
 * other pod can recover the match from the Cassandra truth store
 * ({@link RealtimeTruthStore}) via {@link #recoverMatch}.
 *
 * <h2>Tick cadence and the lag-buffer invariant</h2>
 *
 * <p>The interval period is {@code 1000 / tickHz} milliseconds
 * ({@code app.realtime.tick-hz}, default 30 — 30 Hz, ~33 ms/tick). Each
 * tick the service drains all {@link SimCommand} entries in the pending set
 * whose {@code forTick} is {@code <= currentTick + 1}, advances the sim by
 * one step, and emits a snapshot.
 *
 * <p>A submitted command is stamped {@code forTick = sim.tick() +
 * inputDelayTicks} ({@code app.realtime.input-delay-ticks}, default 2,
 * <em>minimum 1</em>). The minimum-1 invariant is critical:
 * <ul>
 *   <li>A stamp of {@code sim.tick() + 0} would target the <em>current</em>
 *       tick, which may already be draining inside {@code runTick}
 *       under the {@code synchronized} lock. A late-arriving command could
 *       race into a tick already sealed, reusing a {@code (forTick, seq)}
 *       pair that overwrites a command-log row and corrupts the replay.</li>
 *   <li>With {@code inputDelayTicks >= 1} the target is always strictly in
 *       the future; {@code runTick} can never drain a tick that hasn't been
 *       stamped yet, and the command-log {@code (for_tick, seq)} clustering
 *       key remains unique.</li>
 * </ul>
 *
 * <h2>Per-tick algorithm ({@link #runTick})</h2>
 *
 * <p>Inside {@code synchronized(ms)}:
 * <ol>
 *   <li>Return immediately if the match is terminal.</li>
 *   <li>Compute {@code target = sim.tick() + 1} — the tick this step
 *       will produce.</li>
 *   <li>Drain all pending {@link SimCommand} entries with
 *       {@code forTick <= target} (skip-list order guarantees the rest are
 *       future ticks).</li>
 *   <li>Reclaim the {@code nextSeq} counter for {@code target} — the tick
 *       is now sealed; no new submissions may target it.</li>
 *   <li>Call {@code sim.step(due)} to advance the sim.</li>
 *   <li>If Cassandra is present and {@code target % keyframeInterval == 0},
 *       fire a best-effort {@link RealtimeTruthStore#saveKeyframe} (errors
 *       swallowed; never stalls the loop).</li>
 *   <li>Emit the snapshot to {@code ms.snapshots} for connected subscribers.</li>
 *   <li>If {@code sim.isTerminal()}, call {@link #finishMatch}.</li>
 * </ol>
 * <p>Outside the lock: publish the keyframe to the decision-021 Kafka pipeline
 * via {@link games.snapdragon.bff.audit.GameEventPublisher} — Kafka's
 * {@code send()} can block up to {@code max.block.ms} and must never hold the
 * lock.
 *
 * <h2>Reactive pipeline — full startMatch → tick → snapshot flow</h2>
 *
 * <pre>
 *   Admin: startMatch(sessionId, kind, seed, playerIds, configJson)
 *      │
 *      ▼
 *   factory.create(seed, effectiveConfig)        ← sim core instantiated
 *      │
 *      ▼
 *   running.putIfAbsent(sessionId, ms)           ← 409 if already live
 *      │
 *      ▼
 *   matches.save(row)                            ← Postgres durable record
 *      │  doOnSuccess
 *      ▼
 *   startLoop(sessionId, ms)
 *      │
 *      └── Flux.interval(1000/tickHz ms)
 *               │
 *               │  every tick
 *               ▼
 *           runTick(sessionId)
 *               │  synchronized(ms)
 *               ├── drain pending commands (forTick ≤ target)
 *               ├── sim.step(due)
 *               ├── [every keyframeInterval ticks]
 *               │     saveKeyframe(sessionId, target, snapshot) ─► Cassandra
 *               ├── ms.snapshots.tryEmitNext(snapshot) ─────────► Sinks.Many
 *               │                                                     │
 *               │                                         ┌───────────┘
 *               │                                         ▼
 *               │                              snapshotStream subscribers
 *               │                              (Player WebSocket/SSE clients)
 *               │
 *               └── [if isTerminal] finishMatch
 *                       │
 *                       ├── matches.findById + save(endedAt, resultJson) ─► Postgres
 *                       ├── ms.snapshots.tryEmitComplete()
 *                       ├── ms.loop.dispose()
 *                       └── running.remove(sessionId)
 *
 *   [keyframe path] ── OUTSIDE synchronized ──► eventPublisher.publish(
 *                                                 "realtime-keyframe", ...)
 *                                                  └─► Kafka (decision-021)
 * </pre>
 *
 * <h2>Cross-pod recovery ({@link #liveOrRecovered})</h2>
 *
 * <p>Any operation that needs the live {@link MatchState} first calls
 * {@link #liveOrRecovered}. If the match is already running on this pod, it
 * returns immediately. Otherwise, if a {@link RealtimeTruthStore} is present
 * and a durable (un-ended) {@code realtime_matches} row exists, it
 * transparently calls {@link #recoverMatch}: loads the latest Cassandra
 * keyframe, re-queues logged commands after that keyframe, and resumes the
 * loop. Two concurrent recovery triggers for the same session are protected by
 * the registry {@code putIfAbsent}: the second attempt gets 409 CONFLICT and
 * re-reads the registry rather than building a duplicate loop.
 *
 * <h2>Reference sim vs future Rust cores</h2>
 *
 * <p>This service is genre-agnostic. The active {@link SimCore} implementation
 * is selected at match creation time by looking up the caller-supplied
 * {@code kind} in the factory registry. At P2, only
 * {@link ReferenceArenaSimFactory} ({@code "reference-arena"}) is registered.
 * At P4, Rust FFI wrappers register additional kinds ({@code "rts-core"},
 * {@code "fps-core"}) without any change to this class.
 *
 * <h2>Caller-role access matrix</h2>
 *
 * <pre>
 *  Caller            │ Permitted methods
 *  ──────────────────┼────────────────────────────────────────────────────────
 *  Admin/Superadmin  │ startMatch, recoverMatch (and terminate via
 *                    │ AdminRealtimeController)
 *  Player            │ submitInput (own playerId only), snapshotStream
 *  Mod               │ No access to the realtime package
 *  Publisher         │ No access to the realtime package
 * </pre>
 *
 * <h2>Testability</h2>
 *
 * <p>All per-tick work is in the package-visible {@link #runTick} method. The
 * production interval loop just calls it. Setting {@code app.realtime.tick-hz
 * <= 0} disables the interval entirely, letting tests drive ticks directly
 * with {@code service.runTick(sessionId)} — no sleeps, no virtual time needed.
 *
 * @see SimCore
 * @see SimCoreFactory
 * @see RealtimeTruthStore
 * @see ReferenceArenaSim
 */
@Service
public class AuthoritativeTickService {

    private static final Logger log = LoggerFactory.getLogger(AuthoritativeTickService.class);
    private static final ObjectMapper JSON = new ObjectMapper();

    /**
     * Comparator defining the deterministic total order of commands. Applied
     * to the per-match {@link MatchState#pending} skip-list so commands drain
     * in the same order on every pod.
     *
     * @see SimCommand
     */
    private static final Comparator<SimCommand> COMMAND_ORDER =
            Comparator.comparingLong(SimCommand::forTick).thenComparingInt(SimCommand::seq);

    // -------------------------------------------------------------------------
    // MatchState inner class
    // -------------------------------------------------------------------------

    /**
     * All mutable state for one running match, held in the {@link #running}
     * registry for the lifetime of the match.
     *
     * <p><strong>Thread safety:</strong> {@link #sim}, {@link #pending}, and
     * {@link #nextSeq} are the three fields mutated during gameplay. All three
     * are touched only inside {@code synchronized(this)} blocks in
     * {@link AuthoritativeTickService#runTick} (draining pending, advancing
     * the sim, clearing the seq counter) and in the stamp-and-insert section of
     * {@link AuthoritativeTickService#submitInput} (allocating seq, inserting
     * into pending). {@link #terminal} is written inside the lock in
     * {@link AuthoritativeTickService#finishMatch} and read optimistically
     * outside (benign: a stale false read causes at most one extra tick attempt
     * that immediately returns on re-check under the lock). {@link #loop} and
     * {@link #snapshots} are only written from the thread that called
     * {@link AuthoritativeTickService#startLoop} or
     * {@link AuthoritativeTickService#finishMatch}.
     */
    static final class MatchState {

        /**
         * The active simulation core for this match. All calls to
         * {@link SimCore#step} and {@link SimCore#snapshotJson} happen inside
         * {@code synchronized(this)}.
         */
        final SimCore sim;

        /**
         * The sim-kind identifier (e.g. {@code "reference-arena"}), stored so
         * {@link AuthoritativeTickService#recoverMatch} can look up the correct
         * {@link SimCoreFactory} without re-reading the Postgres row.
         */
        final String kind;

        /**
         * The game slug this match belongs to (e.g. {@code "three-wizards"}).
         * Passed through to the decision-021 Kafka pipeline as the game context
         * for audit / KPI events.
         */
        final String gameSlug;

        /**
         * Immutable set of Casdoor player ids that are authorised to submit
         * input to this match. Built from the caller-supplied roster at
         * {@link AuthoritativeTickService#startMatch} time and from the
         * Postgres session-member table at
         * {@link AuthoritativeTickService#recoverMatch} time.
         * Never changes after construction (a match's roster is fixed).
         */
        final Set<String> playerIds;

        /**
         * Pending commands not yet consumed by {@link AuthoritativeTickService#runTick}.
         * A {@link ConcurrentSkipListSet} sorted in {@link #COMMAND_ORDER} so
         * that iteration over the head of the set always yields the
         * lowest-{@code forTick} / lowest-{@code seq} command first — the drain
         * loop can stop early as soon as it sees a command with
         * {@code forTick > target}.
         * Mutations (insert in submitInput, remove in runTick) both happen
         * inside {@code synchronized(ms)}.
         */
        final ConcurrentSkipListSet<SimCommand> pending = new ConcurrentSkipListSet<>(COMMAND_ORDER);

        /**
         * Per-tick monotonic sequence counters, keyed by {@code forTick}. Each
         * {@code submitInput} call allocates the next seq for the target tick
         * via {@link AtomicInteger#getAndIncrement} under the match lock.
         * The counter for a tick is removed from this map in
         * {@link AuthoritativeTickService#runTick} when that tick is sealed
         * (reclaiming memory and preventing late submissions from reusing an
         * already-committed sequence number). During
         * {@link AuthoritativeTickService#resume}, the counters for replayed
         * ticks are primed to {@code max(logged.seq) + 1} so fresh submissions
         * stamp sequences that cannot collide with the recovered log rows.
         */
        final ConcurrentHashMap<Long, AtomicInteger> nextSeq = new ConcurrentHashMap<>();

        /**
         * Replay-latest sink: a subscriber that connects mid-match receives the
         * most recent snapshot immediately (never a blank or stale first frame),
         * then live frames as each tick completes. This models the reconnect
         * semantics clients need: the server truth is always current, never a
         * historical backlog.
         */
        final Sinks.Many<String> snapshots = Sinks.many().replay().latest();

        /**
         * The {@code Flux.interval} subscription driving {@link AuthoritativeTickService#runTick}.
         * Set by {@link AuthoritativeTickService#startLoop}; disposed by
         * {@link AuthoritativeTickService#finishMatch} or
         * {@link AuthoritativeTickService#abortMatch}. Null when
         * {@code app.realtime.tick-hz <= 0} (manual-tick test mode).
         */
        volatile Disposable loop;

        /**
         * True once the match has ended (normally via
         * {@link AuthoritativeTickService#finishMatch} or abnormally via
         * {@link AuthoritativeTickService#abortMatch}). Written under the
         * {@code synchronized(ms)} lock in runTick; read optimistically in
         * submitInput's fast path (a stale false read is benign — the
         * subsequent lock-guarded check inside runTick is authoritative).
         */
        volatile boolean terminal;

        /**
         * Constructs a fresh {@link MatchState} for a new or recovered match.
         *
         * @param sim       the sim core to drive; either freshly created via
         *                  {@link SimCoreFactory#create} or restored via
         *                  {@link SimCoreFactory#restore}
         * @param kind      the sim-kind identifier, as registered by the
         *                  factory's {@link SimCoreFactory#kind()}
         * @param gameSlug  the game slug for Kafka audit events
         * @param playerIds the authorised player roster; copied defensively into
         *                  an immutable set
         */
        MatchState(SimCore sim, String kind, String gameSlug, Collection<String> playerIds) {
            this.sim       = sim;
            this.kind      = kind;
            this.gameSlug  = gameSlug;
            this.playerIds = Set.copyOf(playerIds);
        }
    }

    // -------------------------------------------------------------------------
    // Fields
    // -------------------------------------------------------------------------

    /**
     * Immutable registry of all known {@link SimCoreFactory} beans, keyed by
     * {@link SimCoreFactory#kind()}. Built at construction time from the Spring
     * DI list; a duplicate kind throws at startup.
     */
    private final Map<String, SimCoreFactory> factories;

    /** Postgres repository for durable match records ({@code realtime_matches}). */
    private final RealtimeMatchRepository matches;

    /**
     * Postgres repository for session membership, used by
     * {@link #requireSessionMember} (the cold-path submitInput guard) and by
     * {@link #recoverMatch} (to rebuild the player roster on a recovering pod).
     */
    private final SessionMemberRepository members;

    /**
     * Optional Cassandra truth store — absent in dev / single-pod deployments
     * that have no Cassandra. When absent, commands are applied immediately
     * (no WAL) and cross-pod recovery is unavailable.
     */
    private final ObjectProvider<RealtimeTruthStore> truthStoreProvider;

    /**
     * Optional Kafka event publisher (decision-021). When present, input and
     * keyframe events are teed to the streaming pipeline. When absent, the tick
     * loop runs without any audit trail (acceptable in dev).
     */
    private final Optional<games.snapdragon.bff.audit.GameEventPublisher> eventPublisher;

    /**
     * Authoritative tick rate in Hz ({@code app.realtime.tick-hz}, default 30).
     * A value {@code <= 0} disables the production interval entirely — tests use
     * this mode to call {@link #runTick} directly without real time passing.
     */
    private final int tickHz;

    /**
     * Number of ticks into the future a submitted command is stamped
     * ({@code app.realtime.input-delay-ticks}, default 2, minimum 1).
     * Must be {@code >= 1} — see the class Javadoc for the invariant.
     */
    private final int inputDelayTicks;

    /**
     * Keyframe persistence interval: a keyframe is written to Cassandra every
     * {@code keyframeInterval} ticks ({@code app.realtime.keyframe-interval},
     * default 30). A value {@code <= 0} disables keyframe persistence entirely.
     */
    private final int keyframeInterval;

    /**
     * Registry of currently running matches on this pod, keyed by
     * {@code sessionId}. Mutations ({@code putIfAbsent}, {@code remove}) are
     * protected by the {@code ConcurrentHashMap} itself; per-match state
     * mutations are protected by {@code synchronized(ms)}.
     */
    private final ConcurrentHashMap<String, MatchState> running = new ConcurrentHashMap<>();

    /**
     * Scheduler backing the production {@link Flux#interval} tick loop in
     * {@link #startLoop}. Defaults to {@link Schedulers#parallel()} — exactly
     * what {@code Flux.interval(Duration)} resolves implicitly — so production
     * behaviour is unchanged. Package-visible and reassignable ONLY as a test
     * seam: tests substitute a
     * {@link reactor.test.scheduler.VirtualTimeScheduler} to drive the loop
     * (and its {@code catch(Throwable)} → {@link #abortMatch} failsafe) in
     * deterministic virtual time — no real clock, no sleeps. Same
     * test-visibility rationale as the package-visible {@link #runTick}; never
     * reassigned by production code.
     */
    Scheduler tickScheduler = Schedulers.parallel();

    // -------------------------------------------------------------------------
    // Construction
    // -------------------------------------------------------------------------

    /**
     * Spring-managed constructor. Collects all discovered {@link SimCoreFactory}
     * beans into the factory registry and validates the {@code inputDelayTicks}
     * invariant eagerly (fail-fast at startup rather than at the first match).
     *
     * @param simCoreFactories  all {@link SimCoreFactory} beans discovered by
     *                          Spring; must have unique {@link SimCoreFactory#kind()}
     *                          values
     * @param matches           Postgres match repository
     * @param members           Postgres session-member repository
     * @param truthStoreProvider provider for the optional Cassandra truth store
     * @param eventPublisher    optional Kafka event publisher (decision-021)
     * @param tickHz            tick rate in Hz; {@code <= 0} disables the
     *                          production interval (test mode)
     * @param inputDelayTicks   lag buffer in ticks; must be {@code >= 1}
     * @param keyframeInterval  ticks between Cassandra keyframe writes;
     *                          {@code <= 0} disables keyframes
     * @throws IllegalArgumentException if {@code inputDelayTicks < 1}
     */
    public AuthoritativeTickService(
            List<SimCoreFactory> simCoreFactories,
            RealtimeMatchRepository matches,
            SessionMemberRepository members,
            ObjectProvider<RealtimeTruthStore> truthStoreProvider,
            Optional<games.snapdragon.bff.audit.GameEventPublisher> eventPublisher,
            @Value("${app.realtime.tick-hz:30}")           int tickHz,
            @Value("${app.realtime.input-delay-ticks:2}")  int inputDelayTicks,
            @Value("${app.realtime.keyframe-interval:30}") int keyframeInterval) {
        // Index factories by kind; Collectors.toUnmodifiableMap throws on duplicate keys.
        this.factories = simCoreFactories.stream()
                .collect(Collectors.toUnmodifiableMap(SimCoreFactory::kind, Function.identity()));
        this.matches            = matches;
        this.members            = members;
        this.truthStoreProvider = truthStoreProvider;
        this.eventPublisher     = eventPublisher;
        if (inputDelayTicks < 1) {
            // With a zero/negative lag buffer a submit can stamp an already-
            // sealed tick, reusing a reclaimed (forTick, seq) pair — which
            // overwrites a command_log row and corrupts the replay.
            throw new IllegalArgumentException(
                    "app.realtime.input-delay-ticks must be >= 1, got " + inputDelayTicks);
        }
        this.tickHz             = tickHz;
        this.inputDelayTicks    = inputDelayTicks;
        this.keyframeInterval   = keyframeInterval;
    }

    // -------------------------------------------------------------------------
    // Admin / superadmin operations
    // -------------------------------------------------------------------------

    /**
     * Start the authoritative tick loop for {@code sessionId}.
     *
     * <p>Fail-closed: a second start for the same session is rejected 409 —
     * exactly one authoritative loop may ever own a session's truth at a time.
     * If the Postgres {@code save} fails after the match state is registered,
     * the registration is rolled back via {@code doOnError} and the
     * {@link MatchState} is discarded before the error propagates.
     *
     * <p>The sim is created by the factory registered for {@code kind} and
     * receives the genre-agnostic effective config (roster + caller-supplied
     * game section) assembled by {@link #effectiveConfig}. The loop is started
     * inside {@code doOnSuccess} so it never fires if the Postgres write fails.
     *
     * @param sessionId  the session (and Cassandra partition) key for this match
     * @param gameSlug   the game slug for Kafka audit events and the match row
     * @param kind       sim kind to instantiate, e.g. {@code "reference-arena"};
     *                   must be a key in the factory registry
     * @param seed       entropy seed passed verbatim to the factory
     * @param playerIds  the authorised player roster; must be non-empty
     * @param configJson caller-supplied sim-specific config JSON, or null /
     *                   blank for defaults; merged into the effective config
     * @return a {@code Mono<Void>} that completes when the Postgres record is
     *         saved and the loop is scheduled; errors on 400/409 or Postgres
     *         failure
     */
    public Mono<Void> startMatch(String sessionId, String gameSlug, String kind, long seed,
                                 List<String> playerIds, String configJson) {
        return Mono.defer(() -> {
            // Resolve the sim factory; 400 on an unknown kind rather than NPE.
            SimCoreFactory factory = factories.get(kind);
            if (factory == null) {
                return Mono.error(new ResponseStatusException(
                        HttpStatus.BAD_REQUEST, "unknown sim kind '" + kind + "'"));
            }
            // A roster-less match is not a valid realtime match.
            if (playerIds == null || playerIds.isEmpty()) {
                return Mono.error(new ResponseStatusException(
                        HttpStatus.BAD_REQUEST, "a match needs at least one player"));
            }
            // Create the sim and register the match state atomically.
            SimCore sim = factory.create(seed, effectiveConfig(playerIds, configJson));
            MatchState ms = new MatchState(sim, kind, gameSlug, playerIds);
            if (running.putIfAbsent(sessionId, ms) != null) {
                // Another loop is already live for this session — 409.
                return Mono.error(new ResponseStatusException(
                        HttpStatus.CONFLICT, "a match is already running for this session"));
            }
            // Build the Postgres match record.
            RealtimeMatch row = new RealtimeMatch();
            row.setSessionId(sessionId);
            row.setGameSlug(gameSlug);
            row.setKind(kind);
            row.setSeed(seed);
            row.setTickHz(tickHz);
            row.setStartedAt(Instant.now());
            row.markNew();                       // app-assigned id ⇒ INSERT, not 0-row UPDATE
            return matches.save(row)
                    // Start the tick loop only after the Postgres row is durable.
                    .doOnSuccess(saved -> startLoop(sessionId, ms))
                    // Roll back the registry entry if the Postgres save fails.
                    .doOnError(err -> running.remove(sessionId, ms))
                    .then();
        });
    }

    /**
     * Recover a match on this pod from the Cassandra truth store.
     *
     * <p>Recovery sequence:
     * <ol>
     *   <li>Fail 409 if the match is already live on this pod.</li>
     *   <li>Fail with {@link IllegalStateException} if the truth store is absent
     *       (no Cassandra — recovery is not possible).</li>
     *   <li>Load the durable {@code realtime_matches} row; 404 if absent.</li>
     *   <li>Load the latest keyframe from Cassandra; fail loudly if none exists
     *       (never produce a half-restored match from an unknown state).</li>
     *   <li>In parallel: collect every logged command after the keyframe tick
     *       ({@link RealtimeTruthStore#commandsAfter}) and re-derive the player
     *       roster from Postgres ({@link SessionMemberRepository#findActiveBySession})
     *       — the recovering pod has no in-memory roster to inherit.</li>
     *   <li>Call {@link #resume} to build a new {@link MatchState} from the
     *       keyframe, re-queue the logged commands, register in {@link #running},
     *       and start the loop.</li>
     * </ol>
     *
     * <p>Concurrent recovery triggers for the same session are protected by the
     * registry {@code putIfAbsent} in {@link #resume}: the second registrant
     * throws 409, which {@link #liveOrRecovered} resolves by re-reading the
     * registry entry deposited by the winner.
     *
     * @param sessionId the session to recover
     * @return a {@code Mono<Void>} that completes when the match is live on
     *         this pod; errors loudly on missing keyframe or absent truth store
     */
    public Mono<Void> recoverMatch(String sessionId) {
        return Mono.defer(() -> {
            // Fail fast if the match is already live on this pod.
            if (running.containsKey(sessionId)) {
                return Mono.error(new ResponseStatusException(
                        HttpStatus.CONFLICT, "a match is already running for this session"));
            }
            RealtimeTruthStore store = truthStoreProvider.getIfAvailable();
            if (store == null) {
                return Mono.error(new IllegalStateException(
                        "realtime truth store unavailable — cannot recover " + sessionId));
            }
            return matches.findById(sessionId)
                    // 404 when no match record exists at all.
                    .switchIfEmpty(Mono.error(new ResponseStatusException(
                            HttpStatus.NOT_FOUND, "no match record for this session")))
                    .flatMap(row -> {
                        // Resolve the factory for this match's sim kind.
                        SimCoreFactory factory = factories.get(row.getKind());
                        if (factory == null) {
                            return Mono.error(new IllegalStateException(
                                    "unknown sim kind '" + row.getKind() + "'"));
                        }
                        return store.latestKeyframe(sessionId)
                                // Refuse to produce a half-restored match from nothing.
                                .switchIfEmpty(Mono.error(new IllegalStateException(
                                        "no keyframe to recover " + sessionId + " from")))
                                .flatMap(kf -> Mono.zip(
                                        // Collect commands after the keyframe tick for replay.
                                        store.commandsAfter(sessionId, kf.tick()).collectList(),
                                        // Re-derive roster from Postgres (no in-memory roster
                                        // on the recovering pod).
                                        members.findActiveBySession(sessionId)
                                                .map(SessionMember::getCasdoorId)
                                                .collectList())
                                        // Build and register the recovered MatchState, start loop.
                                        .doOnNext(t -> resume(sessionId, row, factory,
                                                kf.stateJson(), t.getT1(), t.getT2())));
                    })
                    .then();
        });
    }

    // -------------------------------------------------------------------------
    // Player operations
    // -------------------------------------------------------------------------

    /**
     * Queue one player input for the authoritative tick loop.
     *
     * <p>The command is stamped {@code forTick = sim.tick() + inputDelayTicks}
     * (the lag buffer — never the present or past tick) and assigned a
     * monotonically increasing per-tick {@code seq} under the match lock.
     *
     * <p><strong>Write-ahead flow (Cassandra present):</strong>
     * <ol>
     *   <li>Stamp the command under the lock (so {@code forTick} and {@code seq}
     *       are finalised before the async log write begins).</li>
     *   <li>Publish the input event to Kafka (decision-021 tee — outside the
     *       lock, same reason as the keyframe tee in {@link #runTick}).</li>
     *   <li>Append the command to the Cassandra command log via
     *       {@link RealtimeTruthStore#appendCommand}. Failure propagates as 503
     *       — the command is NOT added to pending.</li>
     *   <li>Under the lock, verify that the match is not yet terminal and that
     *       the lag-buffer tick has not already elapsed ({@code sim.tick() <
     *       forTick}). If both hold, add to pending and return the scheduled
     *       tick. Otherwise, compensate the log row away via
     *       {@link RealtimeTruthStore#deleteCommand} and return 503.</li>
     * </ol>
     *
     * <p><strong>Store-less flow (Cassandra absent):</strong> the command is
     * added to pending immediately under the lock — no write-ahead log to stay
     * consistent with.
     *
     * <p>When this pod doesn't hold the match, {@link #liveOrRecovered}
     * transparently recovers it first. On the cold (recovery) path, the
     * {@link #requireSessionMember} guard verifies the caller is an active
     * session member BEFORE triggering recovery — a non-member cannot force a
     * recovery, yet an unknown session still 404s rather than 403s.
     *
     * @param sessionId   the session this input targets
     * @param playerId    the submitting player's Casdoor id; must be in the
     *                    match's {@link MatchState#playerIds} roster
     * @param payloadJson sim-specific command payload JSON
     * @return a {@code Mono<Long>} emitting the {@code forTick} at which the
     *         command is scheduled; 403 for a non-roster player, 404 for no
     *         running match, 503 when the command log is unavailable or the
     *         lag-buffer tick elapsed
     */
    public Mono<Long> submitInput(String sessionId, String playerId, String payloadJson) {
        // The cold (recovery) path verifies session membership BEFORE restoring
        // (the guard runs only after liveOrRecovered confirms a recovery is
        // warranted — an un-ended row + a truth store), so a non-member can't
        // TRIGGER a recovery for a session they aren't in, yet an unknown
        // session still 404s rather than 403s. The hot path (match live on this
        // pod) never subscribes the guard, so input stays a DB-free in-memory
        // path; the authoritative roster gate below (ms.playerIds) runs always.
        return liveOrRecovered(sessionId, requireSessionMember(sessionId, playerId))
                .flatMap(ms -> Mono.defer(() -> {
            // Check terminal flag before acquiring the lock (fast-path rejection).
            if (ms.terminal) {
                return Mono.error(noRunningMatch());
            }
            // Enforce the authoritative roster — the check inside the lock below
            // is the real gate; this is an early-exit for clarity.
            if (!ms.playerIds.contains(playerId)) {
                return Mono.error(new ResponseStatusException(
                        HttpStatus.FORBIDDEN, "player is not in this match"));
            }
            RealtimeTruthStore store = truthStoreProvider.getIfAvailable();
            SimCommand cmd;
            synchronized (ms) {
                // Stamp forTick and seq atomically under the lock.
                long execTick = ms.sim.tick() + inputDelayTicks;
                int seq = ms.nextSeq.computeIfAbsent(execTick, t -> new AtomicInteger())
                        .getAndIncrement();
                cmd = new SimCommand(execTick, seq, playerId, payloadJson);
                if (store == null) {
                    // No WAL: apply immediately (store-less dev / single-pod path).
                    ms.pending.add(cmd);
                }
            }
            // Publish the input event to Kafka OUTSIDE the lock — KafkaProducer.send
            // can block up to max.block.ms and must never hold the match lock.
            eventPublisher.ifPresent(pub -> pub.publish(
                    "realtime-input", ms.gameSlug, sessionId, playerId,
                    Map.of("forTick", cmd.forTick(), "seq", cmd.seq(),
                           "payloadJson", payloadJson == null ? "" : payloadJson)));
            if (store == null) {
                // Store-less path: command is already in pending; just return the tick.
                return Mono.just(cmd.forTick());
            }
            // WAL path: append to Cassandra first; only schedule if that succeeds.
            return store.appendCommand(sessionId, cmd)
                    // Wrap non-ResponseStatusException errors into a 503 for the caller.
                    .onErrorMap(err -> !(err instanceof ResponseStatusException),
                            err -> new ResponseStatusException(
                                    HttpStatus.SERVICE_UNAVAILABLE,
                                    "input rejected — command log unavailable", err))
                    // After durable log write, add to pending if the tick is still future.
                    .then(Mono.defer(() -> schedule(sessionId, ms, cmd, store)));
        }));
    }

    /**
     * Returns a live snapshot stream for {@code sessionId}'s match.
     *
     * <p>The {@link MatchState#snapshots} sink is configured as replay-latest: a
     * newly connecting subscriber receives the most recent snapshot immediately
     * (no blank first frame), then live snapshots as each tick completes. The
     * stream terminates normally when the match ends
     * ({@link Sinks.Many#tryEmitComplete()}) or with an error if the tick loop
     * aborts ({@link Sinks.Many#tryEmitError}).
     *
     * <p>Transparently recovers the match from the Cassandra truth when this pod
     * doesn't hold it (via {@link #liveOrRecovered}).
     *
     * @param sessionId the session whose snapshot stream the caller wants
     * @return a {@code Flux<String>} of JSON snapshot strings; 404 when no match
     *         is running and recovery is not possible
     */
    public Flux<String> snapshotStream(String sessionId) {
        // Recover if needed, then subscribe to the per-session snapshot sink.
        return liveOrRecovered(sessionId).flatMapMany(ms -> ms.snapshots.asFlux());
    }

    // -------------------------------------------------------------------------
    // Internal: live-or-recovered registry lookup
    // -------------------------------------------------------------------------

    /**
     * Returns the running {@link MatchState} for {@code sessionId}, or
     * transparently recovers it from the Cassandra truth when this pod doesn't
     * hold it. Delegates to {@link #liveOrRecovered(String, Mono)} with an
     * empty guard (no pre-recovery check needed for paths other than
     * {@link #submitInput}).
     *
     * <p>Recoverable means: a {@link RealtimeTruthStore} is present AND a
     * durable (un-ended) {@code realtime_matches} row exists. Anything else
     * is a 404. Two concurrent recovery triggers for the same session yield
     * ONE recovery: the second registrant gets 409 CONFLICT and re-reads the
     * registry entry deposited by the winner.
     *
     * @param sessionId the session to look up or recover
     * @return a {@code Mono<MatchState>} emitting the live or recovered state;
     *         404 when neither live nor recoverable
     */
    private Mono<MatchState> liveOrRecovered(String sessionId) {
        return liveOrRecovered(sessionId, Mono.empty());
    }

    /**
     * {@link #liveOrRecovered(String)} with an optional {@code preRecoveryGuard}
     * that fires ONLY on the cold (recovery) path, AFTER recovery is confirmed
     * warranted (an un-ended {@code realtime_matches} row + a truth store) but
     * BEFORE the actual restore.
     *
     * <p>This lets {@link #submitInput} verify session membership before
     * triggering any recovery work, without turning an unknown-session 404 into
     * a 403, and without charging the hot path (match live on this pod) a DB
     * round-trip — the guard's publisher is never subscribed when the match is
     * already in {@link #running}.
     *
     * <p>Recovery failure (no keyframe, Cassandra error) propagates loudly — the
     * method never returns a half-restored match. A concurrent recovery CONFLICT
     * is silently resolved by re-reading the registry.
     *
     * @param sessionId        the session to look up or recover
     * @param preRecoveryGuard a {@code Mono<Void>} that runs before recovery and
     *                         can reject the request; use {@code Mono.empty()} to
     *                         skip
     * @return a {@code Mono<MatchState>} emitting the live or recovered state;
     *         404 when neither live nor recoverable
     */
    private Mono<MatchState> liveOrRecovered(String sessionId, Mono<Void> preRecoveryGuard) {
        return Mono.defer(() -> {
            // Hot path: match is already live on this pod — return immediately.
            MatchState live = running.get(sessionId);
            if (live != null) {
                return Mono.just(live);
            }
            // Cold path: no truth store means no cross-pod recovery possible.
            if (truthStoreProvider.getIfAvailable() == null) {
                return Mono.error(noRunningMatch());
            }
            // Cold path: check for a durable un-ended match record in Postgres.
            return matches.findById(sessionId)
                    // Filter out ended matches (endedAt != null).
                    .filter(row -> row.getEndedAt() == null)
                    // No live or recoverable record → 404.
                    .switchIfEmpty(Mono.error(noRunningMatch()))
                    .flatMap(row -> preRecoveryGuard.then(recoverMatch(sessionId)
                            // Concurrent recovery: 409 → the other pod won; re-read the registry.
                            .onErrorResume(AuthoritativeTickService::isConcurrentRecovery,
                                    err -> Mono.empty())
                            // After recovery completes (or the concurrent winner registered first),
                            // read the match state the winner deposited.
                            .then(Mono.defer(() -> Mono.justOrEmpty(running.get(sessionId))))
                            // If the registry is still empty after recovery, something went wrong.
                            .switchIfEmpty(Mono.error(noRunningMatch()))));
        });
    }

    /**
     * Cold-path guard for {@link #submitInput}: the caller must be an active
     * member of {@code sessionId} before a recovery is triggered on their behalf.
     *
     * <p>Fail-closed: an empty roster or a playerId not present in the active
     * membership list is rejected 403. An unknown {@code sessionId} with no
     * member rows returns an empty {@code findActiveBySession} stream, which
     * resolves to {@code isMember = false} → 403. This is acceptable because
     * the outer {@link #liveOrRecovered} returns 404 for an unknown session
     * before this guard even runs (the guard fires only after confirming a
     * recoverable row exists).
     *
     * @param sessionId the session to check membership in
     * @param playerId  the Casdoor id to verify
     * @return a {@code Mono<Void>} that completes when membership is confirmed;
     *         403 when the player is not an active member
     */
    private Mono<Void> requireSessionMember(String sessionId, String playerId) {
        // Collect active members and check if the caller is among them.
        return members.findActiveBySession(sessionId)
                .any(m -> playerId.equals(m.getCasdoorId()))
                .flatMap(isMember -> isMember
                        ? Mono.empty()
                        : Mono.error(new ResponseStatusException(
                                HttpStatus.FORBIDDEN, "player is not in this match")));
    }

    // -------------------------------------------------------------------------
    // Internal: post-append scheduling
    // -------------------------------------------------------------------------

    /**
     * Post-{@link RealtimeTruthStore#appendCommand} scheduling step:
     * add the durably logged command to {@link MatchState#pending} iff the
     * lag-buffer tick has not yet been sealed by {@link #runTick}.
     *
     * <p>The applicability check ({@code sim.tick() < cmd.forTick()}) runs
     * inside the match lock so there is no race with the tick loop draining and
     * sealing the target tick concurrently. If the tick has already elapsed,
     * the orphaned log row is compensated away via
     * {@link RealtimeTruthStore#deleteCommand} and the caller gets 503 with a
     * "resubmit" hint.
     *
     * @param sessionId the session this command belongs to (for log compensation)
     * @param ms        the live match state (already verified non-null by the
     *                  caller)
     * @param cmd       the command whose log row was just successfully appended
     * @param store     the truth store to use for compensation on failure
     * @return a {@code Mono<Long>} emitting the scheduled {@code forTick}; 503
     *         when the tick already elapsed
     */
    private Mono<Long> schedule(String sessionId, MatchState ms, SimCommand cmd,
                                RealtimeTruthStore store) {
        boolean applicable;
        synchronized (ms) {
            // Check that the match is still running and the target tick is future.
            applicable = !ms.terminal && ms.sim.tick() < cmd.forTick();
            if (applicable) {
                ms.pending.add(cmd);
            }
        }
        if (applicable) {
            return Mono.just(cmd.forTick());
        }
        // Tick elapsed or match ended while the append was in flight —
        // compensate the orphaned log row and reject with 503.
        return store.deleteCommand(sessionId, cmd)
                .then(Mono.error(new ResponseStatusException(
                        HttpStatus.SERVICE_UNAVAILABLE,
                        "input could not be scheduled before its tick elapsed — resubmit")));
    }

    // -------------------------------------------------------------------------
    // Per-tick authoritative work
    // -------------------------------------------------------------------------

    /**
     * One tick of authoritative work — the unit the production interval
     * subscribes and tests can call directly (package-visible).
     *
     * <p>The full per-tick algorithm (executed inside {@code synchronized(ms)}):
     * <ol>
     *   <li>Return immediately if the match is not found in {@link #running} or
     *       is already terminal.</li>
     *   <li>Compute {@code target = sim.tick() + 1} — the tick this step
     *       produces.</li>
     *   <li>Drain all pending {@link SimCommand} entries with
     *       {@code forTick <= target} from the head of the skip-list; the
     *       sorted order means the first entry with {@code forTick > target}
     *       stops the drain early without scanning the whole set.</li>
     *   <li>Remove the {@link MatchState#nextSeq} counter for {@code target}
     *       — the tick is sealed; no new submission may target it.</li>
     *   <li>Call {@link SimCore#step} with the drained commands.</li>
     *   <li>If the truth store is present and
     *       {@code target % keyframeInterval == 0}, fire a best-effort
     *       {@link RealtimeTruthStore#saveKeyframe} (errors swallowed
     *       inside the store).</li>
     *   <li>Emit the snapshot to {@link MatchState#snapshots}.</li>
     *   <li>If {@link SimCore#isTerminal()}, call {@link #finishMatch}.</li>
     * </ol>
     *
     * <p><strong>Keyframe Kafka tee</strong> (outside the lock): when a
     * keyframe was written this tick, publish a {@code "realtime-keyframe"}
     * event to the decision-021 Kafka pipeline. This is deliberately done
     * outside {@code synchronized(ms)} because {@code KafkaProducer.send} can
     * block up to {@code max.block.ms} (metadata fetch or buffer-full
     * back-pressure) and must never stall the tick loop or serialize with
     * {@link #submitInput}. Only keyframes are teed to Kafka — 30 Hz per-tick
     * events are a non-goal.
     *
     * @param sessionId the session whose tick is being advanced
     */
    void runTick(String sessionId) {
        MatchState ms = running.get(sessionId);
        // No match found (already cleaned up) — nothing to do.
        if (ms == null) return;
        // Capture keyframe metadata outside the lock for the post-lock Kafka tee.
        boolean keyframe = false;
        long keyframeTick = 0;
        String keyframeSnapshot = null;
        synchronized (ms) {
            // Re-check terminal inside the lock (volatile read is the authority).
            if (ms.terminal) return;
            // The tick this step will produce.
            long target = ms.sim.tick() + 1;
            // Drain commands due for this tick from the sorted pending set.
            List<SimCommand> due = new ArrayList<>();
            for (Iterator<SimCommand> it = ms.pending.iterator(); it.hasNext(); ) {
                SimCommand c = it.next();
                // Skip-list ordering means once forTick > target, all remaining
                // entries are future ticks — stop draining early.
                if (c.forTick() > target) break;
                due.add(c);
                it.remove();
            }
            // Seal the tick: reclaim the nextSeq counter so late submissions
            // cannot reuse a (forTick, seq) pair that is now in the command log.
            ms.nextSeq.remove(target);
            // Advance the sim by one tick with the drained commands.
            ms.sim.step(due);
            String snapshot = ms.sim.snapshotJson();
            RealtimeTruthStore store = truthStoreProvider.getIfAvailable();
            if (store != null && keyframeInterval > 0 && target % keyframeInterval == 0) {
                // Error-swallowed inside the store; a Cassandra hiccup never
                // stalls the loop.
                store.saveKeyframe(sessionId, target, snapshot).subscribe();
                // Capture metadata for the post-lock Kafka tee.
                keyframe = true;
                keyframeTick = target;
                keyframeSnapshot = snapshot;
            }
            // Publish the snapshot to all connected subscribers (replay-latest sink).
            ms.snapshots.tryEmitNext(snapshot);
            // Detect terminal conditions and clean up.
            if (ms.sim.isTerminal()) {
                finishMatch(sessionId, ms);
            }
        }
        // Decision-021 keyframe tee — KEYFRAMES only, never per-tick (a 30 Hz
        // topic is a non-goal). Done OUTSIDE synchronized(ms): KafkaProducer.send
        // can block up to max.block.ms (metadata fetch / buffer full) and must
        // never stall the tick loop or serialize input submission — same reason
        // the input tee in submitInput is outside its lock. Null player = a
        // system event.
        if (keyframe) {
            long t = keyframeTick;
            String s = keyframeSnapshot;
            eventPublisher.ifPresent(pub -> pub.publish(
                    "realtime-keyframe", ms.gameSlug, sessionId, null,
                    Map.of("tick", t, "stateJson", s)));
        }
    }

    // -------------------------------------------------------------------------
    // Internal: resume (used by recoverMatch)
    // -------------------------------------------------------------------------

    /**
     * Builds a {@link MatchState} from a keyframe, re-queues logged commands,
     * registers it in {@link #running}, and starts the tick loop.
     *
     * <p>The {@link MatchState#nextSeq} counters for replayed ticks are primed
     * to {@code max(c.seq()) + 1} so that fresh submissions for those ticks
     * stamp sequences strictly greater than any in the recovered log — avoiding
     * {@code (forTick, seq)} collisions in the Cassandra command-log table.
     *
     * <p>The registry insert uses {@code putIfAbsent}: if a concurrent
     * {@link #recoverMatch} already won the race, this call throws 409 CONFLICT
     * (caught by {@link #liveOrRecovered}).
     *
     * @param sessionId    the session being recovered
     * @param row          the durable Postgres match record
     * @param factory      the sim factory for {@code row.getKind()}
     * @param keyframeJson the keyframe's {@link SimCore#snapshotJson()} string
     * @param logged       commands after the keyframe tick, in replay order
     * @param roster       the active session-member Casdoor ids
     */
    private void resume(String sessionId, RealtimeMatch row, SimCoreFactory factory,
                        String keyframeJson, List<SimCommand> logged, List<String> roster) {
        // Restore the sim from the keyframe snapshot.
        SimCore sim = factory.restore(keyframeJson);
        MatchState ms = new MatchState(sim, row.getKind(), row.getGameSlug(), roster);
        for (SimCommand c : logged) {
            ms.pending.add(c);
            // Prime the seq counter to strictly above the last logged seq for this
            // tick, so new submissions on the recovering pod never collide with
            // command-log rows already written by the originating pod.
            ms.nextSeq.computeIfAbsent(c.forTick(), t -> new AtomicInteger())
                    .accumulateAndGet(c.seq() + 1, Math::max);
        }
        // Fail-fast on concurrent recovery — the 409 is caught by liveOrRecovered.
        if (running.putIfAbsent(sessionId, ms) != null) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT, "a match is already running for this session");
        }
        startLoop(sessionId, ms);
    }

    // -------------------------------------------------------------------------
    // Internal: tick loop lifecycle
    // -------------------------------------------------------------------------

    /**
     * Start the production tick loop for {@code ms}: one {@link #runTick} call
     * per interval tick, with full {@code Throwable} catch so an
     * {@link Error} out of {@link SimCore#step} does not kill the interval
     * silently (which would leave the match entry in {@link #running} with a
     * hung sink and no running loop). A non-positive {@link #tickHz} disables
     * the interval (manual-tick test mode).
     *
     * @param sessionId the session this loop drives
     * @param ms        the match state to update on each tick
     */
    private void startLoop(String sessionId, MatchState ms) {
        // Tick-hz <= 0 = manual test mode; no interval is started.
        if (tickHz <= 0) return;
        // The imperative try/catch below is DELIBERATE, not a pending reactive
        // rewrite: a .subscribe(onNext) with a plain catch(Throwable) around the
        // synchronous runTick is a strictly stronger failsafe than an operator
        // chain, on two axes a naive rewrite would weaken.
        //  (1) Fatal errors: catch(Throwable) also catches the JVM-fatal subset
        //      (VirtualMachineError, LinkageError, ThreadDeath) that reactor-core
        //      3.8.6's reactor.core.Exceptions.throwIfFatal RE-THROWS rather than
        //      delivering to onError (verified: isJvmFatal covers exactly those
        //      three). So a reactive .onErrorResume(Throwable.class, ex ->
        //      abortMatch(...)) would silently NOT fire for e.g. an
        //      UnsatisfiedLinkError/NoClassDefFoundError out of a future Rust-FFI
        //      SimCore.step — the interval would die, the match would stay in
        //      `running`, and the replay-latest sink would never terminate (every
        //      WebSocket/SSE subscriber hangs forever). A non-JVM-fatal Error such
        //      as AssertionError IS delivered to onError, so the gap is exactly the
        //      throwIfFatal subset — which is precisely why the guard is Throwable.
        //  (2) Tick overlap: .subscribe(onNext) delivers ticks strictly one-at-a-
        //      time (serial onNext); a naive .flatMap(runTick) would run ticks
        //      concurrently the moment one tick outlasts the ~33ms period, mutating
        //      one MatchState off the single-threaded discipline runTick assumes (a
        //      .concatMap would keep order but change backpressure/buffering).
        // Coverage: AuthoritativeTickServiceTest > AuthoritativeLoopAbort.
        ms.loop = Flux.interval(Duration.ofMillis(Math.max(1, 1000 / tickHz)), tickScheduler)
                .subscribe(i -> {
                    // Throwable, not RuntimeException: an Error out of
                    // SimCore.step would otherwise kill the interval silently
                    // — match entry retained, sink never completed,
                    // subscribers hung forever.
                    try {
                        runTick(sessionId);
                    } catch (Throwable ex) {
                        abortMatch(sessionId, ex);
                    }
                });
    }

    /**
     * Terminal clean-up for a normally-ended match. Called inside
     * {@code synchronized(ms)} from {@link #runTick} when
     * {@link SimCore#isTerminal()} returns true.
     *
     * <p>Marks the match terminal, persists the end-time and result to Postgres
     * (best-effort: load + update to avoid a zero-row UPDATE), completes the
     * snapshot sink, disposes the loop subscription, and removes the session
     * from {@link #running}.
     *
     * @param sessionId the session that just ended
     * @param ms        the now-terminal match state (caller holds the lock)
     */
    private void finishMatch(String sessionId, MatchState ms) {
        ms.terminal = true;
        String result = ms.sim.resultJson();
        Instant endedAt = Instant.now();
        // Re-load instead of re-saving the start entity: a loaded row has
        // newRecord=false so save() UPDATEs (never markNew on an update).
        matches.findById(sessionId)
                .flatMap(row -> {
                    row.setEndedAt(endedAt);
                    row.setResultJson(result);
                    return matches.save(row);
                })
                .subscribe(r -> {}, err -> log.warn(
                        "[realtime] terminal update failed for {}: {}", sessionId, err.toString()));
        // Signal normal completion to all snapshot subscribers.
        ms.snapshots.tryEmitComplete();
        // Dispose the interval loop (no further ticks needed).
        if (ms.loop != null) ms.loop.dispose();
        // Remove from the registry so the session can be restarted if needed.
        running.remove(sessionId);
        log.info("[realtime] match {} ended at tick {}", sessionId, ms.sim.tick());
    }

    /**
     * Abnormal termination when the tick loop throws an uncaught
     * {@link Throwable}. Removes the match from the registry, marks it
     * terminal, and signals the error to all snapshot subscribers so
     * SSE/WebSocket clients receive an error signal rather than hanging
     * indefinitely.
     *
     * @param sessionId the session whose loop aborted
     * @param cause     the uncaught throwable from the loop body
     */
    private void abortMatch(String sessionId, Throwable cause) {
        log.warn("[realtime] tick loop aborted for {}: {}", sessionId, cause.toString());
        MatchState dead = running.remove(sessionId);
        if (dead != null) {
            dead.terminal = true;
            // Propagate the error to all downstream subscribers.
            dead.snapshots.tryEmitError(cause);
            if (dead.loop != null) dead.loop.dispose();
        }
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    /**
     * Compose the genre-agnostic effective config the {@link SimCoreFactory}
     * receives. Embeds the player roster in a top-level {@code playerIds} array
     * and nests the caller-supplied sim-specific fields under {@code game}:
     * <pre>
     *   {
     *     "playerIds": ["id1", "id2", ...],
     *     "game": { /* caller config or {} *{@literal /} }
     *   }
     * </pre>
     *
     * @param playerIds  the authorised player roster
     * @param configJson caller-supplied sim-specific config JSON; null or blank
     *                   is replaced with {@code {}}
     * @return the effective config JSON string
     * @throws ResponseStatusException 400 if {@code configJson} is non-blank
     *                                 but not valid JSON
     */
    private String effectiveConfig(List<String> playerIds, String configJson) {
        ObjectNode root = JSON.createObjectNode();
        // Embed the roster under playerIds so factories can read it generically.
        ArrayNode arr = root.putArray("playerIds");
        playerIds.forEach(arr::add);
        JsonNode game;
        try {
            // Null or blank configJson defaults to an empty game section.
            game = (configJson == null || configJson.isBlank())
                    ? JSON.createObjectNode()
                    : JSON.readTree(configJson);
        } catch (com.fasterxml.jackson.core.JacksonException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "malformed configJson");
        }
        root.set("game", game);
        return root.toString();
    }

    /**
     * Returns {@code true} iff {@code err} is a 409 CONFLICT response —
     * the sentinel for a concurrent {@link #recoverMatch} attempt that
     * succeeded while this attempt was in flight.
     *
     * @param err the error from {@link #recoverMatch}
     * @return {@code true} iff this is a concurrent-recovery 409
     */
    private static boolean isConcurrentRecovery(Throwable err) {
        return err instanceof ResponseStatusException rse
                && HttpStatus.CONFLICT.equals(rse.getStatusCode());
    }

    /**
     * Factory for the standard "no running match" 404 response, used on every
     * code path where a session cannot be resolved to a live or recoverable
     * match.
     *
     * @return a {@link ResponseStatusException} with status 404
     */
    private static ResponseStatusException noRunningMatch() {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, "no running match for this session");
    }
}
