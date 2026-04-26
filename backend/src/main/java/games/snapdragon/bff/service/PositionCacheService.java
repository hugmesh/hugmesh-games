package games.snapdragon.bff.service;

import games.snapdragon.bff.model.CharacterPositionCache;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.cassandra.core.ReactiveCassandraTemplate;
import org.springframework.data.cassandra.core.cql.ReactiveCqlTemplate;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.UUID;

/**
 * Cross-pod position cache backed by Cassandra. Pod A writes through
 * here on every position change so pod B can read the latest pose
 * without round-tripping through Postgres.
 *
 * <p>Postgres remains the durable source of truth (and where
 * analytics / replay queries land); Cassandra is the hot read path
 * for SSE fan-out — it tolerates the cluster losing a row (5-minute
 * TTL takes care of stale entries, durable row in Postgres backs the
 * cold restart).
 *
 * <p>{@code @ConditionalOnBean(ReactiveCassandraTemplate.class)}
 * follows the project's established pattern — the cache becomes a
 * no-op (returns empty Mono / Flux) if Cassandra isn't wired in,
 * which keeps unit-test and Postgres-only configurations working.
 */
@Service
@ConditionalOnProperty(name = "app.position-cache.enabled", havingValue = "true", matchIfMissing = true)
@ConditionalOnBean(ReactiveCassandraTemplate.class)
public class PositionCacheService {

    private static final Logger log = LoggerFactory.getLogger(PositionCacheService.class);

    private final ReactiveCassandraTemplate cassandra;
    private final ReactiveCqlTemplate cql;
    private final String podId = "pod-" + UUID.randomUUID().toString().substring(0, 8);

    public PositionCacheService(ReactiveCassandraTemplate cassandra,
                                ReactiveCqlTemplate cql) {
        this.cassandra = cassandra;
        this.cql       = cql;
    }

    /** Write-through: persist the position for (characterId, levelId). */
    public Mono<Void> upsert(long characterId, long levelId,
                             double x, double y, double z, double rotationY,
                             String lastAction, Boolean lastPressed, long tick) {
        CharacterPositionCache row = new CharacterPositionCache(
                characterId, levelId, x, y, z, rotationY,
                lastAction, lastPressed, podId, Instant.now(), tick);
        return cassandra.insert(row)
                .doOnNext(r -> markPresent(characterId, levelId).subscribe())
                .onErrorResume(err -> {
                    // Don't poison the SSE / control chain when Cassandra
                    // hiccups. Log at debug; the next write retries.
                    log.debug("[position-cache] upsert failed: {}", err.toString());
                    return Mono.empty();
                })
                .then();
    }

    /** Read the cached position for (characterId, levelId). Returns
     *  empty when no row exists OR Cassandra is unreachable — the
     *  caller falls through to the durable Postgres lookup. */
    public Mono<CharacterPositionCache> findOne(long characterId, long levelId) {
        return cql.queryForObject(
                "SELECT character_id, level_id, pos_x, pos_y, pos_z, rotation_y, " +
                "       last_action, last_pressed, pod_id, updated_at, tick " +
                "FROM character_position_cache WHERE character_id = ? AND level_id = ?",
                (row, idx) -> {
                    CharacterPositionCache c = new CharacterPositionCache();
                    c.setCharacterId(row.getLong("character_id"));
                    c.setLevelId(row.getLong("level_id"));
                    c.setPosX(row.getDouble("pos_x"));
                    c.setPosY(row.getDouble("pos_y"));
                    c.setPosZ(row.getDouble("pos_z"));
                    c.setRotationY(row.getDouble("rotation_y"));
                    c.setLastAction(row.getString("last_action"));
                    Object lp = row.getObject("last_pressed");
                    c.setLastPressed(lp instanceof Boolean ? (Boolean) lp : null);
                    c.setPodId(row.getString("pod_id"));
                    Instant ts = row.getInstant("updated_at");
                    c.setUpdatedAt(ts);
                    c.setTick(row.getLong("tick"));
                    return c;
                },
                characterId, levelId)
                .onErrorResume(err -> {
                    log.debug("[position-cache] findOne failed: {}", err.toString());
                    return Mono.empty();
                });
    }

    /** All characters known to be present in {@code levelId} — used by
     *  emitWorldState to fan out a snapshot of every co-player on the
     *  active level when a new SSE subscriber connects. Membership is
     *  TTL'd at 60s so a player who disconnected from a previous pod
     *  drops off membership without needing an explicit DELETE. */
    public Flux<Long> charactersInLevel(long levelId) {
        return cql.queryForFlux(
                "SELECT character_id FROM level_membership WHERE level_id = ?",
                Long.class, levelId)
                .onErrorResume(err -> {
                    log.debug("[position-cache] charactersInLevel failed: {}", err.toString());
                    return Flux.empty();
                });
    }

    /** Mark a (level, character) tuple as present. Called once per
     *  movement tick so a TTL'd row stays alive for active players. */
    private Mono<Void> markPresent(long characterId, long levelId) {
        return cql.execute(
                "INSERT INTO level_membership (level_id, character_id, pod_id, last_seen) " +
                "VALUES (?, ?, ?, ?) USING TTL 60",
                levelId, characterId, podId, Instant.now())
                .onErrorResume(err -> {
                    log.debug("[position-cache] markPresent failed: {}", err.toString());
                    return Mono.empty();
                })
                .then();
    }

    /** Explicitly drop membership for a (level, character) tuple — used
     *  when a player intentionally leaves a level (level-transition
     *  postMessage handler). The TTL fallback handles missed deletes. */
    public Mono<Void> markDeparted(long characterId, long levelId) {
        return cql.execute(
                "DELETE FROM level_membership WHERE level_id = ? AND character_id = ?",
                levelId, characterId)
                .onErrorResume(err -> {
                    log.debug("[position-cache] markDeparted failed: {}", err.toString());
                    return Mono.empty();
                })
                .then();
    }
}
