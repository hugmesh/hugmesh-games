package games.snapdragon.bff.service;

import games.snapdragon.bff.model.AdminStatsDto;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.cassandra.core.ReactiveCassandraTemplate;
import org.springframework.data.cassandra.core.cql.ReactiveCqlTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.time.Instant;

/**
 * Keeps the Cassandra cache in step with the authoritative Postgres state and
 * maintains a per-pod heartbeat so the admin live-stream can display the active
 * pod count.
 *
 * <h2>Responsibility</h2>
 *
 * <p>Cassandra holds high-write ephemeral data for cross-pod synchronisation
 * (active sessions, controller leases, aggregate counters); Postgres holds the
 * authoritative game state (characters, levels, quests, session historical
 * records). This service performs two periodic tasks:
 *
 * <ol>
 *   <li><b>Heartbeat</b> ({@link #heartbeat()}) — every 15 seconds, inserts a
 *       row into {@code pod_heartbeats} with a 30-second TTL. Any pod that stops
 *       sending heartbeats drops off the live-pod list within one beat. Used by
 *       the admin stream to display the current running pod count.</li>
 *   <li><b>Daily snapshot</b> ({@link #syncDailySnapshot()}) — once on boot (after
 *       60 s) and every 24 hours thereafter, reads aggregate stats from Postgres
 *       via {@link SessionService#computeStats()} and writes a denormalised
 *       snapshot row into the {@code daily_snapshots} Cassandra table so any pod
 *       can answer admin queries without a per-request Postgres round-trip.</li>
 * </ol>
 *
 * <h2>Schedule cadence</h2>
 *
 * <pre>
 *   heartbeat      : fixed-delay 15 s, initial-delay 5 s
 *   daily snapshot : fixed-delay 24 h, initial-delay 60 s
 * </pre>
 *
 * <p>The snapshot initial delay gives Cassandra and Postgres time to complete
 * their own startup sequences before the first mirror attempt.
 *
 * <h2>Failure behaviour</h2>
 *
 * <p>Both schedules log errors and return without propagating them. A transient
 * Cassandra outage means the heartbeat row is absent (the pod appears offline in
 * the admin stream for up to 30 s) and the daily snapshot may be stale; neither
 * affects gameplay. The next scheduled run retries automatically.
 *
 * <h2>Activation</h2>
 *
 * <p>Registered by {@code CassandraBackedServicesAutoConfiguration} (not
 * component-scanned — see the CLAUDE.md note on {@code @ConditionalOnBean}
 * ordering). Absent in Postgres-only profiles and unit-test suites.
 *
 * <h2>Retirement note</h2>
 *
 * <p>The daily snapshot is a candidate for retirement once the Kafka streaming
 * pipeline ({@code GameAuditPublisher} / {@code GameKpiPublisher} → ClickHouse)
 * has soaked in production and provides equivalent or better aggregate stats.
 * See the CLAUDE.md known-gaps section.
 *
 * <h2>Caller-role access matrix</h2>
 *
 * <pre>
 *   Player      — not applicable (background task)
 *   Publisher   — not applicable
 *   Mod         — not applicable
 *   Admin       — pod-heartbeat data surfaced in the admin live-stream endpoint
 *   Superadmin  — same as Admin
 * </pre>
 *
 * @see SessionService#computeStats()
 * @see games.snapdragon.bff.service.PositionCacheService
 */
// Registered by CassandraBackedServicesAutoConfiguration (NOT component-scanned):
// @ConditionalOnBean on a scanned @Service evaluates before the Cassandra
// auto-configuration contributes the template beans, so it was always false
// and this service was silently absent from real deployments.
public class CassandraSyncService {

    private static final Logger log = LoggerFactory.getLogger(CassandraSyncService.class);

    private final SessionService sessionService;
    private final ReactiveCassandraTemplate cassandra;
    private final ReactiveCqlTemplate cql;

    /**
     * Stable identifier for this pod written into {@code pod_heartbeats}.
     * Randomly minted at construction time so each pod is distinguishable
     * in the admin console without requiring persistent storage.
     */
    private final String podId = "pod-" + games.snapdragon.bff.util.Crypto.mintShortUid(8);

    /** Spring Boot version string — stored in the heartbeat row for diagnostics. */
    @Value("${spring.boot.version:unknown}")
    private String version;

    public CassandraSyncService(SessionService sessionService,
                                ReactiveCassandraTemplate cassandra,
                                ReactiveCqlTemplate cql) {
        this.sessionService = sessionService;
        this.cassandra      = cassandra;
        this.cql            = cql;
    }

    // -------------------------------------------------------------------------
    // Heartbeat
    // -------------------------------------------------------------------------

    /**
     * Writes a liveness row to {@code pod_heartbeats} so the admin live-stream
     * can display the current number of running BFF pods.
     *
     * <p>The row carries {@code USING TTL 30} so a pod that stops heartbeating
     * (crash, scale-down) drops off the active-pod list within 30 seconds — one
     * heartbeat cycle. The 15-second fixed-delay keeps the TTL refreshed on
     * healthy pods with comfortable margin.
     *
     * <p>Errors are logged at DEBUG and not propagated — a transient Cassandra
     * hiccup must never crash the scheduler thread.
     */
    @Scheduled(fixedDelay = 15_000, initialDelay = 5_000)
    public void heartbeat() {
        if (cql == null) return;
        cql.execute(
                "INSERT INTO pod_heartbeats (pod_id, last_ping, started_at, version) VALUES (?, ?, ?, ?) USING TTL 30",
                podId, Instant.now(), Instant.now(), version
        ).subscribe(
                r -> {},
                err -> log.debug("[cassandra-sync] heartbeat failed (Cassandra not ready?): {}", err.toString())
        );
    }

    // -------------------------------------------------------------------------
    // Daily snapshot
    // -------------------------------------------------------------------------

    /**
     * Mirrors a denormalised aggregate-stats snapshot from Postgres into the
     * Cassandra {@code daily_snapshots} table.
     *
     * <p>Runs once on boot (after 60 s, giving Cassandra time to start) and
     * then every 24 hours. Each run:
     * <ol>
     *   <li>Ensures the {@code daily_snapshots} table exists (idempotent DDL via
     *       {@link #ensureSnapshotTable()}).</li>
     *   <li>Computes aggregate stats from Postgres via
     *       {@link SessionService#computeStats()} with a 30-second timeout.</li>
     *   <li>Writes the snapshot row via {@link #writeSnapshot(AdminStatsDto)}.</li>
     * </ol>
     *
     * <p>Errors are logged as WARNINGs and the scheduled run ends silently —
     * the next run retries automatically.
     */
    @Scheduled(initialDelay = 60_000, fixedDelay = 24 * 60 * 60 * 1000L)
    public void syncDailySnapshot() {
        if (cassandra == null) return;

        log.info("[cassandra-sync] starting daily Postgres → Cassandra mirror (pod {})", podId);

        ensureSnapshotTable()
                // Read the authoritative aggregate stats from Postgres.
                .then(sessionService.computeStats())
                // Abort if Postgres takes more than 30 s — we don't want to hold a
                // connection during a large snapshot query while Cassandra is waiting.
                .timeout(Duration.ofSeconds(30))
                // Write the snapshot to Cassandra.
                .flatMap(this::writeSnapshot)
                .subscribe(
                        ok -> log.info("[cassandra-sync] daily snapshot written"),
                        err -> log.warn("[cassandra-sync] daily snapshot failed: {}", err.toString())
                );
    }

    /**
     * Issues a {@code CREATE TABLE IF NOT EXISTS} for {@code daily_snapshots}.
     * Idempotent — subsequent calls after the table exists are silent no-ops.
     * Called at the start of each {@link #syncDailySnapshot()} run so the
     * first mirror after a fresh cluster deployment bootstraps the table
     * without a separate migration step.
     *
     * @return a {@link Mono} completing when the schema check is done
     */
    private Mono<Void> ensureSnapshotTable() {
        if (cql == null) return Mono.empty();
        return cql.execute("""
                CREATE TABLE IF NOT EXISTS daily_snapshots (
                    snapshot_date  date,
                    captured_at    timestamp,
                    total_users    bigint,
                    total_sessions bigint,
                    currently_active bigint,
                    avg_session_min double,
                    total_characters bigint,
                    PRIMARY KEY (snapshot_date)
                )
                """).then();
    }

    /**
     * Writes a single snapshot row for today's date. The {@code snapshot_date}
     * primary key means this is an upsert — running the sync more than once on
     * the same calendar day overwrites the previous row with fresher counts.
     *
     * @param stats the aggregate stats computed from Postgres
     * @return a {@link Mono} completing when the insert has been acknowledged
     */
    private Mono<Void> writeSnapshot(AdminStatsDto stats) {
        if (cql == null) return Mono.empty();
        return cql.execute(
                """
                INSERT INTO daily_snapshots
                    (snapshot_date, captured_at, total_users, total_sessions,
                     currently_active, avg_session_min, total_characters)
                VALUES (toDate(now()), ?, ?, ?, ?, ?, ?)
                """,
                Instant.now(),
                stats.totalUsers(),
                stats.totalSessions(),
                stats.currentlyActive(),
                stats.averageSessionMinutes(),
                stats.totalCharacters()
        ).then();
    }

    // -------------------------------------------------------------------------
    // Accessors
    // -------------------------------------------------------------------------

    /**
     * Returns the stable pod identifier written into {@code pod_heartbeats}.
     * Exposed for admin diagnostics and test assertions.
     *
     * @return the pod ID string
     */
    public String getPodId() { return podId; }
}
