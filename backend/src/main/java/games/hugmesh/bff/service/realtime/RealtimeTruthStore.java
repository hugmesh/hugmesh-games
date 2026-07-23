package games.hugmesh.bff.service.realtime;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.cassandra.core.ReactiveCassandraTemplate;
import org.springframework.data.cassandra.core.cql.ReactiveCqlTemplate;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Cassandra truth store for server-authoritative real-time matches —
 * the decision-017 cross-pod persistence layer extended per
 * docs/realtime-server-authoritative-design.md (P2).
 *
 * <h2>Responsibility</h2>
 *
 * <p>Maintains two Cassandra tables in the {@code threewizards} keyspace
 * (schema in {@code docker/cassandra-init.cql}):
 * <ul>
 *   <li>{@code realtime_keyframes} — one row per authoritative keyframe,
 *       written every {@code keyframeInterval} ticks by
 *       {@link AuthoritativeTickService#runTick}. Clustered by tick DESC so
 *       {@link #latestKeyframe} can fetch the newest checkpoint with
 *       {@code LIMIT 1}.</li>
 *   <li>{@code realtime_command_log} — one row per submitted player command,
 *       written by {@link AuthoritativeTickService#submitInput} BEFORE the
 *       command is allowed to mutate the authoritative sim. Clustered by
 *       {@code (for_tick, seq)} ASC — the deterministic total-order the replay
 *       path ({@link #commandsAfter}) must preserve.</li>
 * </ul>
 *
 * <h2>Write-ahead invariant</h2>
 *
 * <p>The {@code realtime_command_log} table is a write-ahead log (WAL) for the
 * authoritative sim state. The invariant is: <em>a command may only mutate the
 * authoritative sim after its {@link #appendCommand} has succeeded</em>. This
 * ensures that after a pod failure, any pod can replay the exact sequence of
 * commands that produced the last known state by reading the latest keyframe
 * plus the command log tail ({@link #commandsAfter}). Violating the invariant
 * — by applying a command without logging it first, or logging a command that
 * is never applied — makes the keyframe + log replay diverge from the state
 * the players saw.
 *
 * <p>Because of this invariant, {@link #appendCommand} is the ONLY method in
 * this class that <em>propagates</em> errors. All other operations
 * ({@link #saveKeyframe}, {@link #latestKeyframe}, {@link #commandsAfter},
 * {@link #deleteCommand}) swallow errors into empty signals so a Cassandra
 * hiccup never poisons the tick loop. A keyframe write failure is recoverable
 * because the next successful keyframe re-establishes the checkpoint window;
 * a command-log append failure is NOT recoverable in the same way, so the
 * submit path rejects the input with 503 rather than applying it unlogged.
 *
 * <h2>Registration</h2>
 *
 * <p>This class is NOT annotated with {@code @Component} or {@code @Service}.
 * It is registered as a conditional bean by
 * {@code games.hugmesh.autoconfigure.CassandraBackedServicesAutoConfiguration}
 * — a true {@code @AutoConfiguration} class listed in
 * {@code META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports}.
 * This registration pattern is required because {@code @ConditionalOnBean(
 * ReactiveCassandraTemplate.class)} on a component-scanned class evaluates
 * BEFORE Spring Boot's auto-configuration contributes the template beans, so
 * the condition would always be false and the service silently absent in real
 * deployments. Any new Cassandra-conditional bean must follow this pattern.
 *
 * <h2>Caller-role access matrix</h2>
 *
 * <p>This service is only invoked by {@link AuthoritativeTickService}; it is
 * never called from a controller directly.
 *
 * <pre>
 *  Caller                          │ Methods used
 *  ────────────────────────────────┼──────────────────────────────────────────
 *  AuthoritativeTickService        │ All methods
 *  (on admin/superadmin startMatch)│ saveKeyframe, latestKeyframe (via recover)
 *  (on player submitInput)         │ appendCommand, deleteCommand
 *  (on runTick)                    │ saveKeyframe
 *  (on recoverMatch)               │ latestKeyframe, commandsAfter
 * </pre>
 *
 * @see AuthoritativeTickService
 * @see SimCommand
 */
// Registered by CassandraBackedServicesAutoConfiguration (NOT component-scanned):
// @ConditionalOnBean on a scanned @Service evaluates before the Cassandra
// auto-configuration contributes the template beans, so it was always false
// and this service was silently absent from real deployments.
public class RealtimeTruthStore {

    private static final Logger log = LoggerFactory.getLogger(RealtimeTruthStore.class);

    // -------------------------------------------------------------------------
    // Inner types
    // -------------------------------------------------------------------------

    /**
     * One persisted authoritative keyframe row from {@code realtime_keyframes}.
     *
     * @param sessionId the match session id (partition key in Cassandra)
     * @param tick      the authoritative tick at which this keyframe was written;
     *                  every {@link SimCommand} with {@code forTick <= tick} is
     *                  already baked into {@code stateJson}
     * @param stateJson the full {@link SimCore#snapshotJson()} output at the
     *                  keyframe tick; passed directly to
     *                  {@link SimCoreFactory#restore} during recovery
     */
    public record Keyframe(String sessionId, long tick, String stateJson) {}

    // -------------------------------------------------------------------------
    // Fields
    // -------------------------------------------------------------------------

    /** High-level Cassandra template (entity-mapping operations). */
    private final ReactiveCassandraTemplate cassandra;

    /** Low-level CQL template (raw statement execution and row mapping). */
    private final ReactiveCqlTemplate cql;

    // -------------------------------------------------------------------------
    // Construction
    // -------------------------------------------------------------------------

    /**
     * Constructed by {@code CassandraBackedServicesAutoConfiguration} when
     * a {@code ReactiveCassandraTemplate} bean is available.
     *
     * @param cassandra high-level Cassandra template (available via
     *                  Spring Boot Cassandra auto-configuration)
     * @param cql       low-level CQL template contributed by the same
     *                  auto-configuration (Boot does not provide this bean
     *                  directly; it is contributed by the auto-config)
     */
    public RealtimeTruthStore(ReactiveCassandraTemplate cassandra, ReactiveCqlTemplate cql) {
        this.cassandra = cassandra;
        this.cql       = cql;
    }

    // -------------------------------------------------------------------------
    // Keyframe operations (swallow errors — never stall the tick loop)
    // -------------------------------------------------------------------------

    /**
     * Persist an authoritative keyframe (the full sim snapshot) at
     * {@code tick} in {@code realtime_keyframes}.
     *
     * <p>Cassandra errors are swallowed and logged at DEBUG level — a keyframe
     * write failure is recoverable because the next successful keyframe
     * re-establishes the recovery window. A Cassandra hiccup must never stall
     * the tick loop.
     *
     * @param sessionId the match session id (Cassandra partition key)
     * @param tick      the authoritative tick this keyframe covers; all
     *                  commands with {@code forTick <= tick} are baked in
     * @param stateJson the {@link SimCore#snapshotJson()} output at this tick
     * @return a {@code Mono<Void>} that completes when the write is acknowledged
     *         (or empty on error)
     */
    public Mono<Void> saveKeyframe(String sessionId, long tick, String stateJson) {
        return cql.execute(
                "INSERT INTO realtime_keyframes (session_id, tick, state_json) VALUES (?, ?, ?)",
                sessionId, tick, stateJson)
                .onErrorResume(err -> {
                    // Swallow write errors — a missed keyframe is not fatal; the
                    // next successful keyframe re-bounds the recovery window.
                    log.debug("[realtime-truth] saveKeyframe failed: {}", err.toString());
                    return Mono.empty();
                })
                .then();
    }

    // -------------------------------------------------------------------------
    // Command-log operations (appendCommand PROPAGATES errors; delete swallows)
    // -------------------------------------------------------------------------

    /**
     * Append one command to the ordered per-match command log
     * ({@code realtime_command_log}).
     *
     * <p><strong>Write-ahead semantics: errors PROPAGATE.</strong> Unlike all
     * other methods in this class, this method does NOT swallow errors. The
     * caller ({@link AuthoritativeTickService#submitInput}) must not allow an
     * unlogged command to mutate the authoritative sim — if the log write
     * fails, the input must be rejected (503) rather than applied, or a
     * crash-recovery replay (keyframe + {@link #commandsAfter}) will diverge
     * from the state the players saw.
     *
     * @param sessionId the match session id (Cassandra partition key)
     * @param cmd       the command to log; its {@code (forTick, seq)} pair is
     *                  the clustering key and must be unique within the session
     * @return a {@code Mono<Void>} that completes when the command is durably
     *         logged; errors propagate to the caller (no swallow)
     */
    public Mono<Void> appendCommand(String sessionId, SimCommand cmd) {
        return cql.execute(
                "INSERT INTO realtime_command_log (session_id, for_tick, seq, player_id, payload_json) " +
                "VALUES (?, ?, ?, ?, ?)",
                sessionId, cmd.forTick(), cmd.seq(), cmd.playerId(), cmd.payloadJson())
                .then();
        // No onErrorResume here: errors propagate to enforce the WAL contract.
    }

    /**
     * Compensating delete for a logged command that could NOT be applied — for
     * example, when the lag-buffer tick elapsed while the {@link #appendCommand}
     * was in flight, or when the match went terminal before the command could
     * run.
     *
     * <p>A logged-but-never-applied command breaks replay exactly as badly as
     * an applied-but-unlogged one: a recovery pod would replay the phantom row,
     * diverging from the state the players saw. The submit path therefore
     * removes the orphan row before rejecting the input to the caller.
     *
     * <p>This operation is best-effort (errors are swallowed with a WARN log).
     * If the delete also fails, the orphaned row persists until the next
     * keyframe re-bounds the replay window: only commands with
     * {@code for_tick > keyframe.tick} are replayed, so a keyframe written after
     * the orphaned tick automatically renders the orphan unreachable.
     *
     * @param sessionId the match session id
     * @param cmd       the command whose log row should be removed; identified
     *                  by its clustering key {@code (for_tick, seq)}
     * @return a {@code Mono<Void>} that completes (or emits empty on error)
     */
    public Mono<Void> deleteCommand(String sessionId, SimCommand cmd) {
        return cql.execute(
                "DELETE FROM realtime_command_log WHERE session_id = ? AND for_tick = ? AND seq = ?",
                sessionId, cmd.forTick(), cmd.seq())
                .onErrorResume(err -> {
                    // Log at WARN level — an orphaned log row is non-fatal but
                    // requires operator attention (it persists until the next keyframe
                    // re-bounds the recovery window).
                    log.warn("[realtime-truth] deleteCommand({}, tick {}, seq {}) failed — orphaned log row until the next keyframe: {}",
                            sessionId, cmd.forTick(), cmd.seq(), err.toString());
                    return Mono.empty();
                })
                .then();
    }

    // -------------------------------------------------------------------------
    // Recovery reads (swallow errors — never yield a half-restored match)
    // -------------------------------------------------------------------------

    /**
     * Retrieve the most recent keyframe for {@code sessionId} from
     * {@code realtime_keyframes}.
     *
     * <p>The table clusters by {@code tick DESC}, so {@code LIMIT 1} returns
     * the latest checkpoint without a client-side sort. An empty result (no
     * keyframe exists yet, or Cassandra is unreachable) signals a recoverable
     * error to the caller: {@link AuthoritativeTickService#recoverMatch} fails
     * loudly on an empty {@code Mono} rather than producing a half-restored
     * match.
     *
     * <p>Cassandra errors are swallowed and logged at DEBUG level and the
     * stream completes empty, triggering the caller's
     * {@code switchIfEmpty(Mono.error(...))} path.
     *
     * @param sessionId the match session id
     * @return a {@code Mono<Keyframe>} emitting the latest checkpoint, or empty
     *         when none exists or Cassandra is unreachable
     */
    public Mono<Keyframe> latestKeyframe(String sessionId) {
        return cql.queryForObject(
                "SELECT session_id, tick, state_json FROM realtime_keyframes WHERE session_id = ? LIMIT 1",
                (row, idx) -> new Keyframe(
                        row.getString("session_id"),
                        row.getLong("tick"),
                        row.getString("state_json")),
                sessionId)
                .onErrorResume(err -> {
                    // Swallow Cassandra errors — the caller's switchIfEmpty will
                    // fail the recovery loudly rather than producing a partial match.
                    log.debug("[realtime-truth] latestKeyframe failed: {}", err.toString());
                    return Mono.empty();
                });
    }

    /**
     * Stream the command log for {@code sessionId} strictly after {@code tick}
     * (i.e. rows where {@code for_tick > tick}), in {@code (for_tick, seq)}
     * ascending order.
     *
     * <p>This is the deterministic replay feed used by
     * {@link AuthoritativeTickService#recoverMatch}: load the latest keyframe,
     * then pass its {@code tick} here to obtain every command that was applied
     * after it. Feeding those commands into {@link SimCoreFactory#restore}
     * followed by successive {@link SimCore#step} calls reproduces the exact
     * state the originating pod held at failure time.
     *
     * <p>The natural Cassandra clustering order ({@code for_tick ASC, seq ASC})
     * is the correct replay order; no client-side sort is needed.
     *
     * <p>Cassandra errors are swallowed into an empty {@code Flux}. If the
     * command-log read fails during recovery, the recovering pod will not
     * re-queue any pending commands, which may lag the recovered state behind
     * the last keyframe — the next tick will advance the sim forward.
     *
     * @param sessionId the match session id
     * @param tick      the keyframe tick; only commands with
     *                  {@code for_tick > tick} are returned (the keyframe
     *                  itself already incorporates everything up to and
     *                  including {@code tick})
     * @return a {@code Flux<SimCommand>} streaming the post-keyframe command
     *         log in deterministic replay order; empty on error or when no
     *         commands exist after the keyframe tick
     */
    public Flux<SimCommand> commandsAfter(String sessionId, long tick) {
        return cql.query(
                "SELECT for_tick, seq, player_id, payload_json FROM realtime_command_log " +
                "WHERE session_id = ? AND for_tick > ?",
                (row, idx) -> new SimCommand(
                        row.getLong("for_tick"),
                        row.getInt("seq"),
                        row.getString("player_id"),
                        row.getString("payload_json")),
                sessionId, tick)
                .onErrorResume(err -> {
                    // Swallow read errors — a missing command-log tail during
                    // recovery is non-fatal; the sim resumes from the keyframe
                    // without the tail and the next tick advances it forward.
                    log.debug("[realtime-truth] commandsAfter failed: {}", err.toString());
                    return Flux.empty();
                });
    }
}
