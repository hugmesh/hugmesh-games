package games.snapdragon.bff.service.realtime;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.cassandra.core.ReactiveCassandraTemplate;
import org.springframework.data.cassandra.core.cql.ReactiveCqlTemplate;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Cassandra truth for server-authoritative real-time matches — the
 * decision-017 cross-pod store extended per
 * docs/realtime-server-authoritative-design.md (P2): per-match
 * keyframes ({@code realtime_keyframes}) plus the ordered input/command
 * log ({@code realtime_command_log}, the authoritative replay), so ANY
 * pod can recover a match after a pod failure
 * ({@link AuthoritativeTickService#recoverMatch}).
 *
 * <p>Schema lives in {@code docker/cassandra-init.cql} (keyspace
 * threewizards, schema-action NONE). The tick-loop-adjacent operations
 * (keyframes, reads, the compensating delete) swallow errors into empty
 * signals, mirroring {@link
 * games.snapdragon.bff.service.PositionCacheService} — a Cassandra
 * hiccup must never poison the tick loop; Postgres keeps the durable
 * match record and the next keyframe retries. {@link #appendCommand} is
 * the EXCEPTION: it is the write-ahead log for the authoritative sim
 * (a command may only mutate state once it is durably logged — the
 * bit-exact replay invariant), so it PROPAGATES failures and the
 * submit path rejects the input instead of applying it unlogged.
 */
@Service
@ConditionalOnProperty(name = "app.realtime.truth-store.enabled", havingValue = "true", matchIfMissing = true)
@ConditionalOnBean(ReactiveCassandraTemplate.class)
public class RealtimeTruthStore {

    private static final Logger log = LoggerFactory.getLogger(RealtimeTruthStore.class);

    /** One persisted authoritative keyframe. */
    public record Keyframe(String sessionId, long tick, String stateJson) {}

    private final ReactiveCassandraTemplate cassandra;
    private final ReactiveCqlTemplate cql;

    public RealtimeTruthStore(ReactiveCassandraTemplate cassandra, ReactiveCqlTemplate cql) {
        this.cassandra = cassandra;
        this.cql       = cql;
    }

    /** Persist an authoritative keyframe (full sim snapshot) at {@code tick}. */
    public Mono<Void> saveKeyframe(String sessionId, long tick, String stateJson) {
        return cql.execute(
                "INSERT INTO realtime_keyframes (session_id, tick, state_json) VALUES (?, ?, ?)",
                sessionId, tick, stateJson)
                .onErrorResume(err -> {
                    log.debug("[realtime-truth] saveKeyframe failed: {}", err.toString());
                    return Mono.empty();
                })
                .then();
    }

    /** Append one command to the ordered per-match command log. WRITE-AHEAD
     *  semantics: errors PROPAGATE (no swallow) — the caller must not let an
     *  unlogged command mutate the authoritative sim, or a crash-recovery
     *  replay (keyframe + {@link #commandsAfter}) diverges from the state the
     *  players saw. */
    public Mono<Void> appendCommand(String sessionId, SimCommand cmd) {
        return cql.execute(
                "INSERT INTO realtime_command_log (session_id, for_tick, seq, player_id, payload_json) " +
                "VALUES (?, ?, ?, ?, ?)",
                sessionId, cmd.forTick(), cmd.seq(), cmd.playerId(), cmd.payloadJson())
                .then();
    }

    /** Compensating delete for a logged command that could NOT be applied
     *  (its tick elapsed while the append was in flight, or the match went
     *  terminal). A logged-but-never-applied command breaks replay exactly
     *  like an applied-but-never-logged one, so the submit path removes the
     *  orphan before rejecting the input. Best-effort (swallow + warn): if
     *  the delete also fails, the next keyframe re-bounds the replay window. */
    public Mono<Void> deleteCommand(String sessionId, SimCommand cmd) {
        return cql.execute(
                "DELETE FROM realtime_command_log WHERE session_id = ? AND for_tick = ? AND seq = ?",
                sessionId, cmd.forTick(), cmd.seq())
                .onErrorResume(err -> {
                    log.warn("[realtime-truth] deleteCommand({}, tick {}, seq {}) failed — orphaned log row until the next keyframe: {}",
                            sessionId, cmd.forTick(), cmd.seq(), err.toString());
                    return Mono.empty();
                })
                .then();
    }

    /** Most recent keyframe (clustering tick DESC ⇒ first row). Empty when
     *  none exists OR Cassandra is unreachable — recovery then fails loudly
     *  at the caller, never with a half-restored match. */
    public Mono<Keyframe> latestKeyframe(String sessionId) {
        return cql.queryForObject(
                "SELECT session_id, tick, state_json FROM realtime_keyframes WHERE session_id = ? LIMIT 1",
                (row, idx) -> new Keyframe(
                        row.getString("session_id"),
                        row.getLong("tick"),
                        row.getString("state_json")),
                sessionId)
                .onErrorResume(err -> {
                    log.debug("[realtime-truth] latestKeyframe failed: {}", err.toString());
                    return Mono.empty();
                });
    }

    /** The command log strictly after {@code tick}, in (for_tick, seq) ASC
     *  order (the table's natural clustering) — the deterministic replay feed. */
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
                    log.debug("[realtime-truth] commandsAfter failed: {}", err.toString());
                    return Flux.empty();
                });
    }
}
