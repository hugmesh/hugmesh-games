package games.snapdragon.bff.audit;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import games.snapdragon.bff.util.Crypto;
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
 * Publishes per-game KPI samples to the {@code game_kpi} Kafka topic
 * (decision 021 streaming telemetry pipeline).
 *
 * <h2>Responsibility</h2>
 *
 * <p>{@code TelemetryController} exposes {@code POST /api/telemetry/game/{slug}}
 * (admin-gatekept) for federated game remotes to drop KPI batches directly from the
 * iframe. This publisher serialises each entry into the JSON-Each-Row shape that the
 * ClickHouse {@code game_kpi} Kafka-engine table expects. ClickHouse materialises the
 * rows into a durable MergeTree table and provides a {@code kpi_rolling_avg_24h} view
 * for admin dashboard queries.
 *
 * <h2>Threading / reactive model</h2>
 *
 * <p>All methods are non-blocking. {@link #publish} assembles the JSON row on the
 * calling thread and delegates the send to the {@link KafkaSender} whose
 * {@code CompletableFuture} completes asynchronously. Callers MUST NOT block on the
 * returned future — it is used only for telemetry counters and periodic warn logging.
 *
 * <h2>Caller roles</h2>
 *
 * <p>Internal infrastructure component. The only direct caller is {@code TelemetryController},
 * which is itself admin-gatekept ({@code ROLE_ADMIN} required). The bean exists only when
 * {@code app.kafka.enabled=true}; {@code TelemetryController} injects as
 * {@code Optional<GameKpiPublisher>} so the endpoint continues to return an appropriate
 * response when Kafka is disabled. Same lambda-based {@link KafkaSender} pattern as
 * {@link GameAuditPublisher} so tests do not have to mock {@code KafkaTemplate}.
 *
 * @see GameAuditPublisher
 * @see GameEventPublisher
 * @see KafkaProducerConfig
 */
@Service
@ConditionalOnProperty(name = "app.kafka.enabled", havingValue = "true")
public class GameKpiPublisher {

    private static final Logger log = LoggerFactory.getLogger(GameKpiPublisher.class);

    // -------------------------------------------------------------------------
    // Send abstraction
    // -------------------------------------------------------------------------

    /**
     * Send hook — same shape as {@link GameAuditPublisher.KafkaSender}; duplicated
     * so the KPI publisher can be tested independently.
     */
    @FunctionalInterface
    public interface KafkaSender extends BiFunction<String, String, CompletableFuture<?>> {}

    // -------------------------------------------------------------------------
    // State
    // -------------------------------------------------------------------------

    private final KafkaSender sender;
    private final ObjectMapper mapper = new ObjectMapper();

    /** Count of KPI samples successfully acknowledged by the broker since boot. */
    private final AtomicLong emitted = new AtomicLong();

    /** Count of KPI samples dropped due to serialisation or broker errors since boot. */
    private final AtomicLong dropped = new AtomicLong();

    // -------------------------------------------------------------------------
    // Construction
    // -------------------------------------------------------------------------

    /**
     * Production constructor — wires the {@code KafkaSender} from the shared
     * {@code KafkaTemplate} and the configured topic name.
     *
     * @param auditKafkaTemplate the Spring Kafka template provided by
     *                           {@link KafkaProducerConfig#auditKafkaTemplate}
     * @param topic              the Kafka topic name; defaults to {@code game_kpi}
     *                           via {@code app.kafka.topic.game-kpi}
     */
    public GameKpiPublisher(
            org.springframework.kafka.core.KafkaTemplate<String, String> auditKafkaTemplate,
            @Value("${app.kafka.topic.game-kpi:game_kpi}") String topic) {
        this((key, json) -> auditKafkaTemplate.send(topic, key, json), topic);
    }

    /**
     * Test-friendly constructor — same rationale as {@link GameAuditPublisher}.
     *
     * @param sender     the send hook to use for all publish calls
     * @param topicLabel label for the boot log line; does not affect routing
     */
    public GameKpiPublisher(KafkaSender sender, String topicLabel) {
        this.sender = sender;
        log.info("[kpi] GameKpiPublisher → topic={}", topicLabel);
    }

    // -------------------------------------------------------------------------
    // Public publish API
    // -------------------------------------------------------------------------

    /**
     * Publishes one KPI sample to the {@code game_kpi} topic.
     *
     * <p>The row schema written to Kafka (JSON object):
     * <pre>
     *   event_id     — fresh UID from {@link Crypto#mintUid()} (globally unique)
     *   occurred_at  — ISO-8601 UTC instant at call time
     *   game_slug    — game the KPI belongs to (LowCardinality on ClickHouse side)
     *   casdoor_id   — reporter's Casdoor subject; empty for anonymous/game-level KPIs
     *   kpi_name     — short tag, e.g. {@code frame_time_ms}, {@code scene_load_ms},
     *                  {@code error_rate}
     *   kpi_value    — numeric sample value
     *   payload_json — JSON-serialised {@code payload} map, or {@code "{}"}
     * </pre>
     *
     * <p>Partition key is {@code gameSlug} so all KPI samples for the same game land on
     * the same partition and per-game ordering is preserved.
     *
     * @param gameSlug  game the KPI belongs to; {@code null} is coerced to empty string
     * @param casdoorId reporter's Casdoor subject; {@code null} for anonymous / game-level KPIs
     * @param kpiName   short KPI tag (e.g. {@code frame_time_ms}); {@code null} is coerced
     *                  to empty string
     * @param kpiValue  numeric KPI sample value
     * @param payload   extra structured fields serialised to {@code payload_json};
     *                  {@code null} yields {@code "{}"}
     */
    public void publish(String gameSlug,
                        String casdoorId,
                        String kpiName,
                        double kpiValue,
                        Map<String, ?> payload) {
        // Assemble the KPI row in insertion order.
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("event_id",    Crypto.mintUid());
        row.put("occurred_at", DateTimeFormatter.ISO_INSTANT.format(Instant.now()));
        row.put("game_slug",   gameSlug == null ? "" : gameSlug);
        row.put("casdoor_id",  casdoorId == null ? "" : casdoorId);
        row.put("kpi_name",    kpiName == null ? "" : kpiName);
        row.put("kpi_value",   kpiValue);
        try {
            row.put("payload_json",
                    payload == null ? "{}" : mapper.writeValueAsString(payload));
        } catch (JsonProcessingException ex) {
            // Payload serialisation failed — emit the row with an empty payload so the
            // core kpi_name/kpi_value measurement is not lost.
            row.put("payload_json", "{}");
        }

        // Serialise the complete row to a flat JSON string for the Kafka value.
        String json;
        try {
            json = mapper.writeValueAsString(row);
        } catch (JsonProcessingException ex) {
            dropped.incrementAndGet();
            return;
        }

        // Key by gameSlug — all KPI samples for the same game land on the same
        // partition, preserving per-game chronological ordering in ClickHouse.
        try {
            sender.apply(gameSlug == null ? "" : gameSlug, json)
                    .whenComplete((md, ex) -> {
                        if (ex != null) {
                            // Broker error — log periodically (every 1 000th drop).
                            long n = dropped.incrementAndGet();
                            if (n % 1_000 == 1) {
                                log.warn("[kpi] dropped n={} kpi={} — {}",
                                        n, kpiName, ex.getMessage());
                            }
                        } else {
                            // Successful ack — log a throughput sample every 100 000 events.
                            long n = emitted.incrementAndGet();
                            if (n % 100_000 == 0) {
                                log.info("[kpi] emitted={} dropped={}",
                                        n, dropped.get());
                            }
                        }
                    });
        } catch (RuntimeException ex) {
            // Synchronous broker-unreachable throw — drop silently (no extra log;
            // the emitted/dropped counters are sufficient for dashboards).
            dropped.incrementAndGet();
        }
    }

    // -------------------------------------------------------------------------
    // Test / monitoring hooks
    // -------------------------------------------------------------------------

    /**
     * Returns the count of KPI samples successfully acknowledged by the broker since boot.
     *
     * @return number of samples broker-ack'd
     */
    public long emittedCount() { return emitted.get(); }

    /**
     * Returns the count of KPI samples dropped due to serialisation or broker errors
     * since boot.
     *
     * @return number of samples dropped
     */
    public long droppedCount() { return dropped.get(); }
}
