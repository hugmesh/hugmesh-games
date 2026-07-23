package games.hugmesh.bff.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.cassandra.core.cql.ReactiveCqlTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import reactor.core.publisher.Mono;

import jakarta.annotation.PostConstruct;
import java.time.Instant;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * BFF-side mirror of the gateway's revocation list. Both sides poll
 * the SAME Cassandra table on the same refresh cadence; the BFF
 * enforcement is a defence-in-depth check so a request that somehow
 * bypassed the gateway (e.g. service-to-service traffic on the
 * Compose network) is still blocked.
 *
 * <p>The schema + key shape match the gateway's
 * {@code systems.hugmesh.gateway.cache.RevocationListService}
 * exactly:
 *
 * <pre>{@code
 * CREATE TABLE revocations (
 *   revocation_key text PRIMARY KEY,
 *   reason text,
 *   revoked_at timestamp,
 *   expires_at timestamp
 * ) WITH default_time_to_live = 86400;
 *
 * key shapes:
 *   subject:<casdoor-id>            — internal-auth revocation
 *   external:<provider>:<id>        — external-OAuth revocation
 * }</pre>
 *
 * <p>Refresh cadence: 5 s by default — matches the gateway so a
 * revocation propagates everywhere within one tick. Lookups are
 * constant-time hash hits against the locally-cached snapshot; the
 * hot path never touches Cassandra.
 *
 * <p>Activation: gated on {@code app.revocation.enabled=true}
 * (default) AND a ReactiveCqlTemplate bean being present. In unit
 * tests that don't wire Cassandra the service is absent; callers
 * inject it via {@link org.springframework.beans.factory.ObjectProvider}
 * and skip the check when null.
 */
// Registered by CassandraBackedServicesAutoConfiguration (NOT component-scanned):
// @ConditionalOnBean on a scanned @Service evaluates before the Cassandra
// auto-configuration contributes the template beans, so it was always false
// and this service was silently absent from real deployments.
public class RevocationListService {

    private static final Logger log = LoggerFactory.getLogger(RevocationListService.class);

    private final ReactiveCqlTemplate cql;

    /** Snapshot of revoked keys. Atomically replaced on each refresh. */
    private volatile Set<String> revokedKeys = Collections.emptySet();

    /** key → reason, surfaced in the 403 response header so a debug
     *  pass can identify the revocation without leaking the full list. */
    private final ConcurrentMap<String, String> reasons = new ConcurrentHashMap<>();

    private volatile Instant lastRefreshAt = Instant.EPOCH;

    public RevocationListService(ReactiveCqlTemplate cql) {
        this.cql = cql;
    }

    @PostConstruct
    void init() {
        // Best-effort schema bootstrap. Idempotent; runs on every
        // boot. Avoids a separate migration step in the dev flow.
        cql.execute("CREATE TABLE IF NOT EXISTS revocations (" +
                "revocation_key text PRIMARY KEY, " +
                "reason text, " +
                "revoked_at timestamp, " +
                "expires_at timestamp" +
                ") WITH default_time_to_live = 86400")
            .doOnError(e -> log.warn("[revocation] schema bootstrap failed: {}", e.getMessage()))
            .onErrorReturn(false)
            .subscribe();
        // Prime the cache so the first request doesn't see an empty
        // snapshot and let a revoked subject through.
        refreshNow().subscribe();
    }

    /** True iff {@code key} is on the revocation list as of the last
     *  refresh. Constant-time after the cache is primed. */
    public boolean isRevoked(String key) {
        return key != null && revokedKeys.contains(key);
    }

    public String reasonFor(String key) {
        return reasons.getOrDefault(key, "");
    }

    public Instant lastRefreshAt() {
        return lastRefreshAt;
    }

    /** Periodic refresh on the {@code Scheduled} executor. Errors are
     *  logged and the previous snapshot is retained — a transient
     *  Cassandra blip doesn't gate every request. */
    @Scheduled(fixedDelayString = "${app.revocation.refresh-interval-ms:5000}")
    void refresh() {
        refreshNow().subscribe();
    }

    private Mono<Void> refreshNow() {
        Set<String> next = new HashSet<>();
        ConcurrentMap<String, String> nextReasons = new ConcurrentHashMap<>();
        return cql.queryForRows("SELECT revocation_key, reason FROM revocations")
                .doOnNext(row -> {
                    String k = row.getString("revocation_key");
                    if (k != null) {
                        next.add(k);
                        String r = row.getString("reason");
                        if (r != null) nextReasons.put(k, r);
                    }
                })
                .then(Mono.fromRunnable(() -> {
                    revokedKeys = Collections.unmodifiableSet(next);
                    reasons.clear();
                    reasons.putAll(nextReasons);
                    lastRefreshAt = Instant.now();
                    log.debug("[revocation] refreshed: {} keys", next.size());
                }))
                .onErrorResume(e -> {
                    log.warn("[revocation] refresh failed: {}", e.getMessage());
                    return Mono.empty();
                })
                .then();
    }
}
