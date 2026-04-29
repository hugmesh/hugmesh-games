package games.snapdragon.bff.service;

import com.datastax.oss.driver.api.core.uuid.Uuids;
import games.snapdragon.bff.util.Crypto;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.cassandra.core.cql.ReactiveCqlTemplate;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

/**
 * Cross-pod rate-limit aggregator backed by Cassandra. Augments the
 * in-process {@link games.snapdragon.bff.config.RateLimitFilter} bucket
 * with a cluster-wide event log so a credential-stuffing attempt that
 * fans out across many pods still hits the configured cap.
 *
 * <p>Wire shape:
 * <pre>{@code
 * RateLimitFilter
 *   ├─ local in-memory bucket  (per-pod fast path; rejects obvious overshoot)
 *   └─ recordAndCount(bucket)  (cluster-wide check; only on local-allow)
 * }</pre>
 *
 * <p>Storage: append-row log keyed by {@code (bucket_key, event_id)}
 * with a 90-second TTL on every row. To decide whether to throttle we
 * count rows whose {@code event_id} TimeUUID falls inside the rolling
 * window. Cassandra's docs explicitly recommend this pattern over
 * counter tables for rate limiting because counters don't compose
 * with TTL and aren't idempotent on retry — both of which we need.
 *
 * <p>Failure mode: when Cassandra is unreachable, both the record and
 * count fall back to a graceful "allow" so a transient outage doesn't
 * gate every login. The local bucket is still doing its job; the
 * cluster check is best-effort defense in depth.
 *
 * <p>{@code @ConditionalOnBean(ReactiveCqlTemplate.class)} mirrors
 * {@link PositionCacheService} — the service no-ops when Cassandra
 * isn't wired (e.g. Postgres-only test profiles, the unit suite).
 *
 * <p>{@code app.rate-limit.distributed.enabled = false} disables the
 * cluster-wide layer at runtime so a deployment that wants in-process-
 * only throttling doesn't have to remove Cassandra wiring.
 */
@Service
@ConditionalOnProperty(name = "app.rate-limit.distributed.enabled", havingValue = "true", matchIfMissing = true)
@ConditionalOnBean(ReactiveCqlTemplate.class)
public class DistributedRateLimitService {

    private static final Logger log = LoggerFactory.getLogger(DistributedRateLimitService.class);

    /** Window size kept in lock-step with {@code RateLimitFilter.WINDOW}. */
    private static final Duration WINDOW = Duration.ofMinutes(1);

    private final ReactiveCqlTemplate cql;
    private final String podId = "pod-" + Crypto.mintShortUid(8);

    public DistributedRateLimitService(ReactiveCqlTemplate cql) {
        this.cql = cql;
    }

    /**
     * Append a row for {@code bucketKey} and return the cluster-wide
     * count of events for that bucket within the rolling window.
     *
     * <p>The insert is unconditional (the local in-process bucket
     * already pre-filtered obvious overshoot before we got here).
     * Pre-filtering keeps the Cassandra hop off the deny path — only
     * requests the local bucket allows reach this method.
     *
     * <p>If Cassandra is unreachable on either the insert or the count,
     * the method falls back to {@code 0} so the filter allows. The
     * local bucket is still in front; the cluster aggregate is
     * augmenting protection, not replacing the per-pod baseline.
     */
    public Mono<Long> recordAndCount(String bucketKey) {
        UUID eventId = Uuids.timeBased();
        Mono<Void> insert = cql.execute(
                "INSERT INTO rate_limit_events (bucket_key, event_id, pod_id) VALUES (?, ?, ?)",
                bucketKey, eventId, podId).then();
        // The window-bounded count uses minTimeuuid(now - WINDOW) as a
        // lower bound on event_id so we don't pay for rows that have
        // a few seconds left on the TTL grace.
        Instant windowStart = Instant.now().minus(WINDOW);
        Mono<Long> count = cql.queryForObject(
                "SELECT COUNT(*) FROM rate_limit_events " +
                "WHERE bucket_key = ? AND event_id >= minTimeuuid(?)",
                Long.class, bucketKey, windowStart);
        return insert
                .then(count)
                .defaultIfEmpty(0L)
                .onErrorResume(err -> {
                    log.debug("[rate-limit-distributed] degraded; falling back to local-only: {}", err.toString());
                    return Mono.just(0L);
                });
    }
}
