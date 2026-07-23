package games.hugmesh.bff.audit;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import games.hugmesh.bff.util.Crypto;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BiFunction;

/**
 * Publishes player state-transition audit events to the {@code state_audit} Kafka topic
 * (decision 021 streaming telemetry pipeline).
 *
 * <h2>Responsibility</h2>
 *
 * <p>Emits one JSON row per audit event to Kafka. ClickHouse consumes the topic via its
 * built-in Kafka-engine table (see {@code docker/clickhouse-init/01-snapdragon-schema.sql});
 * a materialised view fans the rows into the durable {@code snapdragon.state_audit}
 * MergeTree table for cheap aggregate reads without scraping the SSE stream.
 *
 * <p>This publisher replaces the analytics half of the nightly {@code CassandraSyncService}
 * batch. The batch continues running as a backstop; the cut-over will happen after the
 * streaming pipeline has demonstrated stability under live load.
 *
 * <h2>Threading / reactive model</h2>
 *
 * <p>All methods are non-blocking. {@link #emit} assembles the JSON row on the calling
 * thread (cheap, CPU-only), hands the serialised string to the {@link KafkaSender} whose
 * {@code CompletableFuture} completes asynchronously on the Kafka producer's network
 * thread, and returns immediately. Callers MUST NOT block on the returned future — the
 * result is used only for telemetry counters and warn-level logging.
 *
 * <h2>Caller roles</h2>
 *
 * <p>This is an internal infrastructure component — no direct player/mod/admin API surface.
 * Callers are other service-layer beans (e.g. {@code AuthoritativeTickService}) that emit
 * on the tick loop or input path. The bean exists only when {@code app.kafka.enabled=true}
 * so vanilla compose-up (without the Kafka container) doesn't fail to boot; callers inject
 * as {@code Optional<GameAuditPublisher>} when Kafka is optional for their path.
 *
 * <h2>Failure model</h2>
 *
 * <p>Send failures are logged and dropped. The audit pipeline is a best-effort
 * observability surface — it is NOT a transactional dependency of any user-facing path.
 * Cassandra remains the canonical audit log; this stream is the live-aggregate feeder.
 * Wait counters surface to logs every 10 000 events so an operator can sanity-check
 * throughput without {@code DEBUG} logging.
 *
 * @see GameEventPublisher
 * @see GameKpiPublisher
 * @see KafkaProducerConfig
 */
@Service
@ConditionalOnProperty(name = "app.kafka.enabled", havingValue = "true")
public class GameAuditPublisher {

    private static final Logger log = LoggerFactory.getLogger(GameAuditPublisher.class);

    // -------------------------------------------------------------------------
    // Send abstraction
    // -------------------------------------------------------------------------

    /**
     * Send hook — takes {@code (key, json)} and returns a future that completes
     * when the broker has acknowledged (or fails on broker error). In production
     * this is {@code kafkaTemplate::send} bound to the configured topic; in tests
     * it is a no-op or capturing lambda. The indirection avoids mocking
     * {@code KafkaTemplate} directly (Mockito's inline mock-maker has trouble with
     * it under Java 21).
     */
    @FunctionalInterface
    public interface KafkaSender extends BiFunction<String, String, CompletableFuture<?>> {}

    // -------------------------------------------------------------------------
    // State
    // -------------------------------------------------------------------------

    private final KafkaSender sender;
    private final ObjectMapper mapper = new ObjectMapper();

    /** Count of events successfully acknowledged by the broker since boot. */
    private final AtomicLong emitted = new AtomicLong();

    /** Count of events dropped due to serialisation or broker errors since boot. */
    private final AtomicLong dropped = new AtomicLong();

    // -------------------------------------------------------------------------
    // Construction
    // -------------------------------------------------------------------------

    /**
     * Production constructor — wires the {@code KafkaSender} directly from a
     * {@code KafkaTemplate} and the configured topic name.
     *
     * @param auditKafkaTemplate the Spring Kafka template provided by
     *                           {@link KafkaProducerConfig#auditKafkaTemplate}
     * @param topic              the Kafka topic name; defaults to {@code state_audit}
     *                           via {@code app.kafka.topic.state-audit}
     */
    public GameAuditPublisher(org.springframework.kafka.core.KafkaTemplate<String, String> auditKafkaTemplate,
                              @Value("${app.kafka.topic.state-audit:state_audit}") String topic) {
        this((key, json) -> auditKafkaTemplate.send(topic, key, json), topic);
    }

    /**
     * Test-friendly constructor — the test passes a hand-rolled sender lambda that
     * records calls and decides whether to complete or fail the returned future,
     * instead of needing to mock {@code KafkaTemplate}.
     *
     * @param sender     the send hook to use for all emit calls
     * @param topicLabel label for the boot log line; does not affect routing
     */
    public GameAuditPublisher(KafkaSender sender, String topicLabel) {
        this.sender = sender;
        log.info("[audit] GameAuditPublisher → topic={}", topicLabel);
    }

    // -------------------------------------------------------------------------
    // Public emit API
    // -------------------------------------------------------------------------

    /**
     * Emits a single audit row to the {@code state_audit} topic.
     *
     * <p>The row schema written to Kafka (JSON object):
     * <pre>
     *   event_id     — fresh UID from {@link Crypto#mintUid()} (globally unique)
     *   occurred_at  — ISO-8601 UTC instant at call time
     *   game_slug    — game the event belongs to (LowCardinality on the ClickHouse side)
     *   session_id   — multiplayer session id (empty string for single-player events)
     *   casdoor_id   — player's Casdoor subject (empty string when not player-scoped)
     *   event_type   — short tag, e.g. {@code session.join}, {@code chapter.transition}
     *   payload_json — JSON-serialised {@code payload} map, or {@code "{}"}
     * </pre>
     *
     * <p>Partition key is {@code gameSlug} so all events for the same game land on the
     * same partition and ordering within a game is preserved. The idempotent producer
     * ({@code acks=all + enable.idempotence}) guards against broker-side duplicates on
     * retry (see {@link KafkaProducerConfig}).
     *
     * <p>Callers MUST NOT block on the send result — the producer ACK arrives
     * asynchronously and is used only for logging and telemetry counters.
     *
     * @param gameSlug  game the event belongs to; {@code null} is coerced to empty string
     * @param sessionId multiplayer session id; pass empty string for single-player events
     * @param casdoorId the player's Casdoor subject; {@code null} when the event is not
     *                  player-scoped
     * @param eventType short tag identifying the event kind (e.g. {@code session.join})
     * @param payload   extra structured fields serialised to {@code payload_json};
     *                  {@code null} yields {@code "{}"}
     */
    public void emit(String gameSlug,
                     String sessionId,
                     String casdoorId,
                     String eventType,
                     Map<String, ?> payload) {
        // Assemble the audit row in insertion order so the JSON fields are
        // consistently positioned for ClickHouse's positional column mapping.
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("event_id",     Crypto.mintUid());
        row.put("occurred_at",  DateTimeFormatter.ISO_INSTANT.format(Instant.now()));
        row.put("game_slug",    gameSlug == null ? "" : gameSlug);
        row.put("session_id",   sessionId == null ? "" : sessionId);
        row.put("casdoor_id",   casdoorId == null ? "" : casdoorId);
        row.put("event_type",   eventType == null ? "" : eventType);
        try {
            row.put("payload_json", payload == null ? "{}" : mapper.writeValueAsString(payload));
        } catch (JsonProcessingException ex) {
            // Payload serialisation failed — emit the row with an empty payload rather
            // than dropping the audit event entirely.
            row.put("payload_json", "{}");
        }

        // Serialise the complete row; a failure here drops the event and increments
        // the dropped counter (the outer row map is always well-formed so this path
        // is unlikely in practice).
        String json;
        try {
            json = mapper.writeValueAsString(row);
        } catch (JsonProcessingException ex) {
            dropped.incrementAndGet();
            log.warn("[audit] dropped — serialise failed: {}", ex.getMessage());
            return;
        }

        // Key by gameSlug so events for the same game land on the same
        // partition (ordering within a game is preserved). The
        // idempotent producer (acks=all + enable.idempotence) guards
        // against broker-side dupes on retry.
        try {
            sender.apply(gameSlug == null ? "" : gameSlug, json)
                    .whenComplete((md, ex) -> {
                        if (ex != null) {
                            // Broker error — increment dropped counter and log periodically
                            // to avoid flooding the log under sustained broker outages.
                            long n = dropped.incrementAndGet();
                            if (n % 100 == 1) {
                                log.warn("[audit] dropped n={} — {}", n, ex.getMessage());
                            }
                        } else {
                            // Successful broker ack — log a throughput sample every 10 000 events
                            // so operators can sanity-check pipeline health without DEBUG logging.
                            long n = emitted.incrementAndGet();
                            if (n % 10_000 == 0) {
                                log.info("[audit] emitted={} dropped={}",
                                        n, dropped.get());
                            }
                        }
                    });
        } catch (RuntimeException ex) {
            // KafkaTemplate.send throws synchronously when the broker is genuinely
            // unreachable; treat this the same as an async failure and drop the event.
            dropped.incrementAndGet();
            log.warn("[audit] dropped synchronously: {}", ex.getMessage());
        }
    }

    // -------------------------------------------------------------------------
    // Test / monitoring hooks
    // -------------------------------------------------------------------------

    /**
     * Returns the count of events successfully acknowledged by the broker since boot.
     * Intended for test assertions and health-check endpoints.
     *
     * @return number of events broker-ack'd
     */
    public long emittedCount() { return emitted.get(); }

    /**
     * Returns the count of events dropped due to serialisation or broker errors since
     * boot. Intended for test assertions and health-check endpoints.
     *
     * @return number of events dropped
     */
    public long droppedCount() { return dropped.get(); }
}
