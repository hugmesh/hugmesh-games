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
 * The server-authoritative tick loop (docs/realtime-server-authoritative-
 * design.md, P2). Per match: a deterministic {@link SimCore} advanced at a
 * fixed Hz, inputs queued behind a small input-delay lag buffer and drained
 * in {@code (forTick, seq)} order, authoritative snapshots fanned out on a
 * per-session sink, keyframes + the command log persisted to the Cassandra
 * truth ({@link RealtimeTruthStore}, decision-017 extension), and a durable
 * Postgres match record ({@code realtime_matches}).
 *
 * <p><strong>Tick semantics:</strong> a command stamped {@code forTick = T}
 * is incorporated into the step that PRODUCES tick {@code T} (the sim's
 * tick counter reads {@code T} afterwards). A keyframe labelled {@code T}
 * therefore contains every command with {@code forTick <= T};
 * {@link RealtimeTruthStore#commandsAfter} with the keyframe tick yields
 * exactly the unconsumed remainder — the recovery contract
 * {@link #recoverMatch} relies on.
 *
 * <p><strong>Testability:</strong> all per-tick work lives in the
 * package-visible {@link #runTick}; the production loop is just a
 * {@code Flux.interval} subscribing it. {@code app.realtime.tick-hz <= 0}
 * disables the interval entirely so tests drive {@code runTick} directly —
 * no sleeps, no virtual time.
 */
@Service
public class AuthoritativeTickService {

    private static final Logger log = LoggerFactory.getLogger(AuthoritativeTickService.class);
    private static final ObjectMapper JSON = new ObjectMapper();

    /** Deterministic total order — see {@link SimCommand}. */
    private static final Comparator<SimCommand> COMMAND_ORDER =
            Comparator.comparingLong(SimCommand::forTick).thenComparingInt(SimCommand::seq);

    /** In-memory state of one running match. All sim mutation is guarded by
     *  {@code synchronized(this)} inside {@link #runTick} / input stamping. */
    static final class MatchState {
        final SimCore sim;
        final String kind;
        final String gameSlug;
        final Set<String> playerIds;
        final ConcurrentSkipListSet<SimCommand> pending = new ConcurrentSkipListSet<>(COMMAND_ORDER);
        /** Per-tick monotonic seq stamps; entries reclaimed as ticks seal. */
        final ConcurrentHashMap<Long, AtomicInteger> nextSeq = new ConcurrentHashMap<>();
        /** Replay-latest: a (re)connecting subscriber receives the CURRENT
         *  authoritative snapshot immediately, then live frames — never a
         *  stale backlog (clients render server truth, not history). */
        final Sinks.Many<String> snapshots = Sinks.many().replay().latest();
        volatile Disposable loop;
        volatile boolean terminal;

        MatchState(SimCore sim, String kind, String gameSlug, Collection<String> playerIds) {
            this.sim       = sim;
            this.kind      = kind;
            this.gameSlug  = gameSlug;
            this.playerIds = Set.copyOf(playerIds);
        }
    }

    private final Map<String, SimCoreFactory> factories;
    private final RealtimeMatchRepository matches;
    private final SessionMemberRepository members;
    private final ObjectProvider<RealtimeTruthStore> truthStoreProvider;
    private final Optional<games.snapdragon.bff.audit.GameEventPublisher> eventPublisher;
    private final int tickHz;
    private final int inputDelayTicks;
    private final int keyframeInterval;

    private final ConcurrentHashMap<String, MatchState> running = new ConcurrentHashMap<>();

    public AuthoritativeTickService(
            List<SimCoreFactory> simCoreFactories,
            RealtimeMatchRepository matches,
            SessionMemberRepository members,
            ObjectProvider<RealtimeTruthStore> truthStoreProvider,
            Optional<games.snapdragon.bff.audit.GameEventPublisher> eventPublisher,
            @Value("${app.realtime.tick-hz:30}")           int tickHz,
            @Value("${app.realtime.input-delay-ticks:2}")  int inputDelayTicks,
            @Value("${app.realtime.keyframe-interval:30}") int keyframeInterval) {
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

    /**
     * Start the authoritative match for {@code sessionId}. Fail-closed: a
     * second start for the same session errors 409 — exactly one
     * authoritative loop may ever own a session's truth.
     */
    public Mono<Void> startMatch(String sessionId, String gameSlug, String kind, long seed,
                                 List<String> playerIds, String configJson) {
        return Mono.defer(() -> {
            SimCoreFactory factory = factories.get(kind);
            if (factory == null) {
                return Mono.error(new ResponseStatusException(
                        HttpStatus.BAD_REQUEST, "unknown sim kind '" + kind + "'"));
            }
            if (playerIds == null || playerIds.isEmpty()) {
                return Mono.error(new ResponseStatusException(
                        HttpStatus.BAD_REQUEST, "a match needs at least one player"));
            }
            SimCore sim = factory.create(seed, effectiveConfig(playerIds, configJson));
            MatchState ms = new MatchState(sim, kind, gameSlug, playerIds);
            if (running.putIfAbsent(sessionId, ms) != null) {
                return Mono.error(new ResponseStatusException(
                        HttpStatus.CONFLICT, "a match is already running for this session"));
            }
            RealtimeMatch row = new RealtimeMatch();
            row.setSessionId(sessionId);
            row.setGameSlug(gameSlug);
            row.setKind(kind);
            row.setSeed(seed);
            row.setTickHz(tickHz);
            row.setStartedAt(Instant.now());
            row.markNew();                       // app-assigned id ⇒ INSERT, not 0-row UPDATE
            return matches.save(row)
                    .doOnSuccess(saved -> startLoop(sessionId, ms))
                    .doOnError(err -> running.remove(sessionId, ms))
                    .then();
        });
    }

    /**
     * Queue one player input. Stamped {@code forTick = currentTick +
     * inputDelayTicks} (the lag buffer — never the past) with a per-tick
     * monotonic seq, and teed to the decision-021 Kafka pipeline. Returns
     * the scheduled tick.
     *
     * <p><strong>Write-ahead:</strong> when the Cassandra truth store is
     * present, the command becomes applicable (enters {@code pending}) ONLY
     * after its {@link RealtimeTruthStore#appendCommand} succeeds — an
     * applied-but-unlogged command would make the keyframe+log replay
     * diverge from the authoritative state the players saw. An append
     * failure rejects the input (503) with the sim untouched. If the lag
     * buffer elapses while the append is in flight (or the match goes
     * terminal), the logged row is compensated away
     * ({@link RealtimeTruthStore#deleteCommand}) and the input rejected —
     * a logged-but-never-applied command breaks replay the same way.
     * Store-less deploys keep the previous immediate-apply behaviour
     * (there is no log to stay consistent with).
     *
     * <p>When this pod doesn't hold the match, {@link #liveOrRecovered}
     * transparently recovers it from the cross-pod truth first.
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
            if (ms.terminal) {
                return Mono.error(noRunningMatch());
            }
            if (!ms.playerIds.contains(playerId)) {
                return Mono.error(new ResponseStatusException(
                        HttpStatus.FORBIDDEN, "player is not in this match"));
            }
            RealtimeTruthStore store = truthStoreProvider.getIfAvailable();
            SimCommand cmd;
            synchronized (ms) {
                long execTick = ms.sim.tick() + inputDelayTicks;
                int seq = ms.nextSeq.computeIfAbsent(execTick, t -> new AtomicInteger())
                        .getAndIncrement();
                cmd = new SimCommand(execTick, seq, playerId, payloadJson);
                if (store == null) {
                    ms.pending.add(cmd);     // no log to stay consistent with
                }
            }
            eventPublisher.ifPresent(pub -> pub.publish(
                    "realtime-input", ms.gameSlug, sessionId, playerId,
                    Map.of("forTick", cmd.forTick(), "seq", cmd.seq(),
                           "payloadJson", payloadJson == null ? "" : payloadJson)));
            if (store == null) {
                return Mono.just(cmd.forTick());
            }
            return store.appendCommand(sessionId, cmd)
                    .onErrorMap(err -> !(err instanceof ResponseStatusException),
                            err -> new ResponseStatusException(
                                    HttpStatus.SERVICE_UNAVAILABLE,
                                    "input rejected — command log unavailable", err))
                    .then(Mono.defer(() -> schedule(sessionId, ms, cmd, store)));
        }));
    }

    /** Post-append scheduling step of {@link #submitInput}: make the durably
     *  logged command applicable iff it can still run at EXACTLY its stamped
     *  tick (insertion under the lock with {@code sim.tick() < forTick}
     *  guarantees the step producing {@code forTick} drains it). Otherwise
     *  compensate the log row away and reject. */
    private Mono<Long> schedule(String sessionId, MatchState ms, SimCommand cmd,
                                RealtimeTruthStore store) {
        boolean applicable;
        synchronized (ms) {
            applicable = !ms.terminal && ms.sim.tick() < cmd.forTick();
            if (applicable) {
                ms.pending.add(cmd);
            }
        }
        if (applicable) {
            return Mono.just(cmd.forTick());
        }
        return store.deleteCommand(sessionId, cmd)
                .then(Mono.error(new ResponseStatusException(
                        HttpStatus.SERVICE_UNAVAILABLE,
                        "input could not be scheduled before its tick elapsed — resubmit")));
    }

    /** Authoritative snapshot JSON stream for one session's match: the
     *  CURRENT snapshot first (replay-latest — reconnect semantics), then
     *  live frames. Auto-recovers from the cross-pod truth when this pod
     *  doesn't hold the match. */
    public Flux<String> snapshotStream(String sessionId) {
        return liveOrRecovered(sessionId).flatMapMany(ms -> ms.snapshots.asFlux());
    }

    /**
     * The running {@link MatchState} for {@code sessionId}, transparently
     * recovering it from the cross-pod truth first when this pod doesn't
     * hold it — the "any pod can serve a match" contract. Recoverable means:
     * truth store present AND a durable {@code realtime_matches} row with
     * {@code endedAt == null}; anything else is the previous 404. Two
     * concurrent triggers yield ONE recovery — {@link #recoverMatch}'s
     * registry putIfAbsent protects, and the loser's CONFLICT is resolved by
     * re-reading the registry. A recovery FAILURE (no keyframe, store error)
     * propagates — fail-closed, never a half-restored match.
     */
    private Mono<MatchState> liveOrRecovered(String sessionId) {
        return liveOrRecovered(sessionId, Mono.empty());
    }

    /**
     * {@link #liveOrRecovered(String)} with a {@code preRecoveryGuard} that
     * runs ONLY on the cold path, AFTER a recovery is confirmed warranted (an
     * un-ended {@code realtime_matches} row + a truth store) and BEFORE the
     * actual restore. Lets {@link #submitInput} reject a non-member before any
     * recovery work without turning an unknown-session 404 into a 403, and
     * without costing the hot path (live match) a DB round-trip — the guard's
     * publisher is never subscribed when the match is already running here.
     */
    private Mono<MatchState> liveOrRecovered(String sessionId, Mono<Void> preRecoveryGuard) {
        return Mono.defer(() -> {
            MatchState live = running.get(sessionId);
            if (live != null) {
                return Mono.just(live);
            }
            if (truthStoreProvider.getIfAvailable() == null) {
                return Mono.error(noRunningMatch());
            }
            return matches.findById(sessionId)
                    .filter(row -> row.getEndedAt() == null)
                    .switchIfEmpty(Mono.error(noRunningMatch()))
                    .flatMap(row -> preRecoveryGuard.then(recoverMatch(sessionId)
                            .onErrorResume(AuthoritativeTickService::isConcurrentRecovery,
                                    err -> Mono.empty())
                            .then(Mono.defer(() -> Mono.justOrEmpty(running.get(sessionId))))
                            .switchIfEmpty(Mono.error(noRunningMatch()))));
        });
    }

    /** Cold-path guard for {@link #submitInput}: the caller must be an active
     *  member of the session before a recovery is triggered on their behalf
     *  (fail-closed — an empty roster or unknown caller is rejected). */
    private Mono<Void> requireSessionMember(String sessionId, String playerId) {
        return members.findActiveBySession(sessionId)
                .any(m -> playerId.equals(m.getCasdoorId()))
                .flatMap(isMember -> isMember
                        ? Mono.empty()
                        : Mono.error(new ResponseStatusException(
                                HttpStatus.FORBIDDEN, "player is not in this match")));
    }

    private static boolean isConcurrentRecovery(Throwable err) {
        return err instanceof ResponseStatusException rse
                && HttpStatus.CONFLICT.equals(rse.getStatusCode());
    }

    private static ResponseStatusException noRunningMatch() {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, "no running match for this session");
    }

    /**
     * One tick of authoritative work — the unit the production interval
     * subscribes and tests call directly. Package-visible on purpose.
     */
    void runTick(String sessionId) {
        MatchState ms = running.get(sessionId);
        if (ms == null) return;
        boolean keyframe = false;
        long keyframeTick = 0;
        String keyframeSnapshot = null;
        synchronized (ms) {
            if (ms.terminal) return;
            long target = ms.sim.tick() + 1;     // the tick this step produces
            List<SimCommand> due = new ArrayList<>();
            for (Iterator<SimCommand> it = ms.pending.iterator(); it.hasNext(); ) {
                SimCommand c = it.next();
                if (c.forTick() > target) break; // skip-list order ⇒ rest is future
                due.add(c);
                it.remove();
            }
            ms.nextSeq.remove(target);           // tick sealed; reclaim the counter
            ms.sim.step(due);
            String snapshot = ms.sim.snapshotJson();
            RealtimeTruthStore store = truthStoreProvider.getIfAvailable();
            if (store != null && keyframeInterval > 0 && target % keyframeInterval == 0) {
                // Error-swallowed inside the store; a Cassandra hiccup never
                // stalls the loop.
                store.saveKeyframe(sessionId, target, snapshot).subscribe();
                keyframe = true;
                keyframeTick = target;
                keyframeSnapshot = snapshot;
            }
            ms.snapshots.tryEmitNext(snapshot);
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

    /**
     * Recover a match on this pod from the Cassandra truth: restore the
     * latest keyframe, re-queue every logged command with
     * {@code forTick > keyframe.tick}, and resume the loop — the
     * persist→recover-on-another-pod path. Fails loudly (no half-restored
     * match) when the truth store is absent or holds no keyframe.
     */
    public Mono<Void> recoverMatch(String sessionId) {
        return Mono.defer(() -> {
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
                    .switchIfEmpty(Mono.error(new ResponseStatusException(
                            HttpStatus.NOT_FOUND, "no match record for this session")))
                    .flatMap(row -> {
                        SimCoreFactory factory = factories.get(row.getKind());
                        if (factory == null) {
                            return Mono.error(new IllegalStateException(
                                    "unknown sim kind '" + row.getKind() + "'"));
                        }
                        return store.latestKeyframe(sessionId)
                                .switchIfEmpty(Mono.error(new IllegalStateException(
                                        "no keyframe to recover " + sessionId + " from")))
                                .flatMap(kf -> Mono.zip(
                                        store.commandsAfter(sessionId, kf.tick()).collectList(),
                                        // Roster re-derived from the shared Postgres
                                        // membership — the recovering pod has no
                                        // in-memory roster to inherit.
                                        members.findActiveBySession(sessionId)
                                                .map(SessionMember::getCasdoorId)
                                                .collectList())
                                        .doOnNext(t -> resume(sessionId, row, factory,
                                                kf.stateJson(), t.getT1(), t.getT2())));
                    })
                    .then();
        });
    }

    private void resume(String sessionId, RealtimeMatch row, SimCoreFactory factory,
                        String keyframeJson, List<SimCommand> logged, List<String> roster) {
        SimCore sim = factory.restore(keyframeJson);
        MatchState ms = new MatchState(sim, row.getKind(), row.getGameSlug(), roster);
        for (SimCommand c : logged) {
            ms.pending.add(c);
            // New submissions for a replayed tick must stamp seq strictly
            // after the logged ones, or the (forTick, seq) order collides.
            ms.nextSeq.computeIfAbsent(c.forTick(), t -> new AtomicInteger())
                    .accumulateAndGet(c.seq() + 1, Math::max);
        }
        if (running.putIfAbsent(sessionId, ms) != null) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT, "a match is already running for this session");
        }
        startLoop(sessionId, ms);
    }

    /** Production loop: one {@code runTick} per interval tick. A non-positive
     *  tick-hz disables it (manual ticking — tests). */
    private void startLoop(String sessionId, MatchState ms) {
        if (tickHz <= 0) return;
        // TODO reactive: review this catch for onErrorResume — the Throwable guard
        // (not just RuntimeException) is intentional: an Error from SimCore.step
        // must trigger abortMatch or the interval keeps ticking into a dead match.
        // A full reactive rewrite would convert runTick to Mono<Void> and use
        // .onErrorResume(Throwable.class, ex -> { abortMatch(...); return Mono.empty(); })
        // on the flatMap inside the interval chain instead of subscribe.
        ms.loop = Flux.interval(Duration.ofMillis(Math.max(1, 1000 / tickHz)))
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

    /** Terminal path — runs inside the {@code synchronized(ms)} of runTick. */
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
        ms.snapshots.tryEmitComplete();
        if (ms.loop != null) ms.loop.dispose();
        running.remove(sessionId);
        log.info("[realtime] match {} ended at tick {}", sessionId, ms.sim.tick());
    }

    private void abortMatch(String sessionId, Throwable cause) {
        log.warn("[realtime] tick loop aborted for {}: {}", sessionId, cause.toString());
        MatchState dead = running.remove(sessionId);
        if (dead != null) {
            dead.terminal = true;
            dead.snapshots.tryEmitError(cause);
            if (dead.loop != null) dead.loop.dispose();
        }
    }

    /** Compose the genre-agnostic effective config the factories receive:
     *  the roster + the caller's sim-specific config. */
    private String effectiveConfig(List<String> playerIds, String configJson) {
        ObjectNode root = JSON.createObjectNode();
        ArrayNode arr = root.putArray("playerIds");
        playerIds.forEach(arr::add);
        JsonNode game;
        try {
            game = (configJson == null || configJson.isBlank())
                    ? JSON.createObjectNode()
                    : JSON.readTree(configJson);
        } catch (com.fasterxml.jackson.core.JacksonException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "malformed configJson");
        }
        root.set("game", game);
        return root.toString();
    }
}
