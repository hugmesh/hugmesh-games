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
 * Produces per-game KPI events to the {@code game_kpi} Kafka topic
 * (decision 021). The TelemetryController exposes
 * {@code POST /api/telemetry/game/\{slug\}} for federated game remotes
 * to drop KPIs directly from the iframe; this publisher serialises
 * each entry into the JSON-Each-Row shape the ClickHouse
 * {@code game_kpi} table expects.
 *
 * <p>Same lambda-based KafkaSender pattern as
 * {@link GameAuditPublisher} so tests don't have to mock KafkaTemplate.
 */
@Service
@ConditionalOnProperty(name = "app.kafka.enabled", havingValue = "true")
public class GameKpiPublisher {

    private static final Logger log = LoggerFactory.getLogger(GameKpiPublisher.class);

    @FunctionalInterface
    public interface KafkaSender extends BiFunction<String, String, CompletableFuture<?>> {}

    private final KafkaSender sender;
    private final ObjectMapper mapper = new ObjectMapper();
    private final AtomicLong emitted = new AtomicLong();
    private final AtomicLong dropped = new AtomicLong();

    public GameKpiPublisher(
            org.springframework.kafka.core.KafkaTemplate<String, String> auditKafkaTemplate,
            @Value("${app.kafka.topic.game-kpi:game_kpi}") String topic) {
        this((key, json) -> auditKafkaTemplate.send(topic, key, json), topic);
    }

    /** Test-friendly constructor — same rationale as {@link GameAuditPublisher}. */
    public GameKpiPublisher(KafkaSender sender, String topicLabel) {
        this.sender = sender;
        log.info("[kpi] GameKpiPublisher → topic={}", topicLabel);
    }

    /**
     * Publish one KPI sample.
     *
     * @param gameSlug   game the KPI belongs to.
     * @param casdoorId  reporter's Casdoor subject; {@code null} for
     *                   anonymous game-level KPIs.
     * @param kpiName    short tag (e.g. {@code frame_time_ms},
     *                   {@code scene_load_ms}, {@code error_rate}).
     * @param kpiValue   numeric sample.
     * @param payload    extra structured fields; serialised to {@code payload_json}.
     */
    public void publish(String gameSlug,
                        String casdoorId,
                        String kpiName,
                        double kpiValue,
                        Map<String, ?> payload) {
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
            row.put("payload_json", "{}");
        }

        String json;
        try {
            json = mapper.writeValueAsString(row);
        } catch (JsonProcessingException ex) {
            dropped.incrementAndGet();
            return;
        }

        try {
            sender.apply(gameSlug == null ? "" : gameSlug, json)
                    .whenComplete((md, ex) -> {
                        if (ex != null) {
                            long n = dropped.incrementAndGet();
                            if (n % 1_000 == 1) {
                                log.warn("[kpi] dropped n={} kpi={} — {}",
                                        n, kpiName, ex.getMessage());
                            }
                        } else {
                            long n = emitted.incrementAndGet();
                            if (n % 100_000 == 0) {
                                log.info("[kpi] emitted={} dropped={}",
                                        n, dropped.get());
                            }
                        }
                    });
        } catch (RuntimeException ex) {
            dropped.incrementAndGet();
        }
    }

    public long emittedCount() { return emitted.get(); }
    public long droppedCount() { return dropped.get(); }
}
