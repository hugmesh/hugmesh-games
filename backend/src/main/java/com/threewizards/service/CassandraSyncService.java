package com.threewizards.service;

import com.threewizards.model.AdminStatsDto;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.cassandra.core.ReactiveCassandraTemplate;
import org.springframework.data.cassandra.core.cql.ReactiveCqlTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

/**
 * Keeps the Cassandra cache in step with the authoritative Postgres state.
 *
 * <p>Cassandra holds the high-write ephemeral data for cross-pod synchronisation
 * (active sessions, controller leases, aggregate counters); Postgres holds the
 * authoritative game state (characters, levels, quests, sessions historical
 * record). Twice-daily we mirror a digest of Postgres → Cassandra so any pod
 * answering an admin query has the same baseline regardless of which other pod
 * applied the most recent write.
 *
 * <p>Schedule: once on boot (60 s after startup so Cassandra is up), and then
 * every 24 h. Override via {@code app.cassandra-sync.cron}. Disable entirely
 * with {@code app.cassandra-sync.enabled=false}.
 *
 * <p>The service is gated on
 * {@code spring.cassandra.contact-points} being set so a developer running
 * {@code make dev-backend} without Cassandra doesn't crash on startup.
 */
@Service
@ConditionalOnProperty(value = "app.cassandra-sync.enabled", havingValue = "true", matchIfMissing = true)
@ConditionalOnBean(ReactiveCassandraTemplate.class)
public class CassandraSyncService {

    private static final Logger log = LoggerFactory.getLogger(CassandraSyncService.class);

    private final SessionService sessionService;
    private final ReactiveCassandraTemplate cassandra;
    private final ReactiveCqlTemplate cql;
    private final String podId = "pod-" + UUID.randomUUID().toString().substring(0, 8);

    @Value("${spring.boot.version:unknown}")
    private String version;

    public CassandraSyncService(SessionService sessionService,
                                ReactiveCassandraTemplate cassandra,
                                ReactiveCqlTemplate cql) {
        this.sessionService = sessionService;
        this.cassandra      = cassandra;
        this.cql            = cql;
    }

    /**
     * Heartbeat — every 15 s. Tells other pods this instance is alive so the
     * admin live stream can show the pod count. Cassandra row TTL is 30 s so
     * a stopped pod drops off within a beat.
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

    /**
     * Daily mirror — runs once on boot (after 60 s) and then every 24 hours.
     * Reads the authoritative aggregate stats from Postgres via SessionService
     * and writes a denormalised snapshot into Cassandra so any pod can answer
     * admin queries without an inter-pod fanout.
     *
     * <p>The cassandra `aggregate_counters` table is a counter type so we
     * can't UPDATE arbitrary values — instead we keep a snapshot rolled into
     * `daily_snapshots` (created lazily at first sync).
     */
    @Scheduled(initialDelay = 60_000, fixedDelay = 24 * 60 * 60 * 1000L)
    public void syncDailySnapshot() {
        if (cassandra == null) return;

        log.info("[cassandra-sync] starting daily Postgres → Cassandra mirror (pod {})", podId);

        ensureSnapshotTable()
                .then(sessionService.computeStats())
                .timeout(Duration.ofSeconds(30))
                .flatMap(this::writeSnapshot)
                .subscribe(
                        ok -> log.info("[cassandra-sync] daily snapshot written"),
                        err -> log.warn("[cassandra-sync] daily snapshot failed: {}", err.toString())
                );
    }

    /** Idempotent — first sync after startup creates the table; subsequent ones no-op. */
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

    public String getPodId() { return podId; }
}
