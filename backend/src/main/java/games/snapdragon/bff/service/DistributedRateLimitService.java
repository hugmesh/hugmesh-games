package games.snapdragon.bff.service;

import com.datastax.oss.driver.api.core.uuid.Uuids;
import games.snapdragon.bff.util.Crypto;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.cassandra.core.cql.ReactiveCqlTemplate;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

/**
 * Cross-pod rate-limit aggregator backed by the Cassandra {@code rate_limit_events} table.
 *
 * <h2>Responsibility</h2>
 *
 * <p>Augments the in-process per-pod bucket in {@code RateLimitFilter} with a
 * cluster-wide event log so a credential-stuffing attempt that fans out across
 * many BFF pods still hits the configured cap. Without this, an attacker with
 * enough source IPs can spread their requests across pods and stay below each
 * pod's local threshold indefinitely.
 *
 * <h2>Architecture — two-layer defence</h2>
 *
 * <pre>
 *   RateLimitFilter
 *     ├─ local in-process token bucket   (per-pod fast path; rejects obvious overshoot)
 *     └─ recordAndCount(bucketKey)       (cluster-wide check; only on local-allow)
 *           │
 *           ├─ INSERT INTO rate_limit_events (bucket_key, event_id, pod_id)
 *           │     (time-UUID event_id; 90-second TTL on the row)
 *           │
 *           └─ SELECT COUNT(*) … WHERE event_id >= minTimeuuid(now − 1 min)
 *                 → cluster-wide count within the rolling window
 * </pre>
 *
 * <p>Pre-filtering keeps the Cassandra hop off the deny path — only requests
 * that pass the local bucket reach this method.
 *
 * <h2>Storage shape</h2>
 *
 * <p>The {@code rate_limit_events} table uses an append-row log keyed by
 * {@code (bucket_key, event_id)} with a 90-second row TTL. The window-bounded
 * count uses {@code minTimeuuid(now - WINDOW)} as a lower bound on the TimeUUID
 * so only events within the rolling window are counted and the TTL grace does
 * not inflate the count.
 *
 * <p>Cassandra's documentation recommends this append-log pattern over counter
 * tables for rate limiting because counters lack TTL and are not idempotent on
 * retry — both of which this service needs.
 *
 * <h2>Failure mode</h2>
 *
 * <p>When Cassandra is unreachable, both the insert and the count fall back to
 * returning {@code 0L} so the filter allows the request. The local bucket
 * continues to function; the cluster-wide check is best-effort defence in depth,
 * not the primary gate.
 *
 * <h2>Activation</h2>
 *
 * <p>Registered by {@code CassandraBackedServicesAutoConfiguration} (not
 * component-scanned — see the CLAUDE.md note on {@code @ConditionalOnBean}
 * ordering). Absent in Postgres-only profiles and unit-test suites.
 * Disable the distributed layer at runtime with
 * {@code app.rate-limit.distributed.enabled=false} while keeping Cassandra wired.
 *
 * <h2>Caller-role access matrix</h2>
 *
 * <pre>
 *   Player      — subject to rate limiting; this service is called transparently
 *                 by RateLimitFilter on every allowed request
 *   Publisher   — same as Player (rate limiting is role-agnostic at this layer)
 *   Mod         — same as Player
 *   Admin       — same as Player
 *   Superadmin  — same as Player
 * </pre>
 *
 * @see games.snapdragon.bff.config.RateLimitFilter
 */
// Registered by CassandraBackedServicesAutoConfiguration (NOT component-scanned):
// @ConditionalOnBean on a scanned @Service evaluates before the Cassandra
// auto-configuration contributes the template beans, so it was always false
// and this service was silently absent from real deployments.
public class DistributedRateLimitService {

    private static final Logger log = LoggerFactory.getLogger(DistributedRateLimitService.class);

    /**
     * Rolling window size for the cluster-wide count — kept in lock-step with
     * the {@code WINDOW} constant in {@code RateLimitFilter} so the two layers
     * measure the same time horizon.
     */
    private static final Duration WINDOW = Duration.ofMinutes(1);

    private final ReactiveCqlTemplate cql;

    /**
     * Stable identifier for this pod within the {@code rate_limit_events} table.
     * Stored with each row so DBA queries can diagnose which pod is generating
     * the most traffic, but not used in the rate-limit decision itself.
     */
    private final String podId = "pod-" + Crypto.mintShortUid(8);

    public DistributedRateLimitService(ReactiveCqlTemplate cql) {
        this.cql = cql;
    }

    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------

    /**
     * Appends a rate-limit event for {@code bucketKey} and returns the
     * cluster-wide count of events for that bucket within the rolling window.
     *
     * <p>The insert is unconditional: the local in-process bucket has already
     * pre-filtered obvious overshoot before this method is called, so every
     * request reaching here is one the local bucket has allowed. Pre-filtering
     * keeps Cassandra off the deny path — only allowed requests pay the hop.
     *
     * <p>Reactive marble diagram:
     * <pre>
     *   INSERT INTO rate_limit_events (bucket_key, event_id, pod_id)
     *     → then
     *   SELECT COUNT(*) WHERE bucket_key=? AND event_id >= minTimeuuid(now − WINDOW)
     *     → defaultIfEmpty(0L)        ← Cassandra returned no rows (e.g. just-TTL'd table)
     *     → onErrorResume(→ 0L)       ← Cassandra unreachable; fall back to local-only
     * </pre>
     *
     * @param bucketKey the rate-limit bucket identifier (e.g. the caller's IP or
     *                  JWT subject); must not be null
     * @return a {@link Mono} emitting the cluster-wide event count within the rolling
     *         window, or {@code 0L} when Cassandra is unreachable
     */
    public Mono<Long> recordAndCount(String bucketKey) {
        // Mint a time-based UUID so Cassandra's minTimeuuid() window query works.
        UUID eventId = Uuids.timeBased();
        // Insert an event row for this request; 90 s TTL is set at table DDL level.
        Mono<Void> insert = cql.execute(
                "INSERT INTO rate_limit_events (bucket_key, event_id, pod_id) VALUES (?, ?, ?)",
                bucketKey, eventId, podId).then();
        // Count events within the rolling window by using minTimeuuid as a lower bound
        // on the time-UUID cluster column — avoids counting TTL-expired rows still
        // visible during the grace period.
        Instant windowStart = Instant.now().minus(WINDOW);
        Mono<Long> count = cql.queryForObject(
                "SELECT COUNT(*) FROM rate_limit_events " +
                "WHERE bucket_key = ? AND event_id >= minTimeuuid(?)",
                Long.class, bucketKey, windowStart);
        return insert
                // Wait for the insert to complete before counting so our own event
                // is included in the window count.
                .then(count)
                // Guard against an empty result (new table / all TTLs just expired).
                .defaultIfEmpty(0L)
                .onErrorResume(err -> {
                    // Cassandra hiccup — degrade gracefully to local-only throttling.
                    // The local bucket is still in front; this is defence in depth.
                    log.debug("[rate-limit-distributed] degraded; falling back to local-only: {}", err.toString());
                    return Mono.just(0L);
                });
    }
}
