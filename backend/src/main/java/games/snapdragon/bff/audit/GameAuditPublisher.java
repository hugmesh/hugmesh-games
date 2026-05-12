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
 * Emits state-transition audit events to the {@code state_audit} Kafka
 * topic. ClickHouse consumes the topic via its built-in Kafka engine
 * table (see {@code docker/clickhouse-init/01-snapdragon-schema.sql});
 * a materialised view persists each row into the durable MergeTree
 * {@code snapdragon.state_audit} table for cheap aggregate reads.
 *
 * <p>Producer wiring:
 * <ul>
 *   <li>Bean exists only when {@code app.kafka.enabled=true} so vanilla
 *       compose-up (without the Kafka container) doesn't fail to boot.</li>
 *   <li>Send failures are logged + dropped — the audit pipeline is a
 *       best-effort observability surface, not a transactional dependency
 *       of any user-facing path. Cassandra remains the canonical audit
 *       log; this stream is the live-aggregate feeder.</li>
 *   <li>Wait counters surface to logs every 10 000 events so an operator
 *       can sanity-check throughput without DEBUG.</li>
 * </ul>
 *
 * <p>Replaces the analytics half of the nightly
 * {@code CassandraSyncService} batch. The batch stays running for now as
 * a backstop; the cut-over to drop it will happen after the streaming
 * pipeline has demonstrated stability under live load.
 */
@Service
@ConditionalOnProperty(name = "app.kafka.enabled", havingValue = "true")
public class GameAuditPublisher {

    private static final Logger log = LoggerFactory.getLogger(GameAuditPublisher.class);

    /**
     * Send hook — takes (key, json) and returns a future that completes
     * when the broker has ack'd (or fails on broker error). In production
     * this is {@code kafkaTemplate::send} bound to the configured topic;
     * in tests it's a no-op lambda. The indirection means we don't need
     * to mock {@code KafkaTemplate} directly (Mockito's inline mock-maker
     * has trouble with it under Java 21).
     */
    @FunctionalInterface
    public interface KafkaSender extends BiFunction<String, String, CompletableFuture<?>> {}

    private final KafkaSender sender;
    private final ObjectMapper mapper = new ObjectMapper();
    private final AtomicLong emitted = new AtomicLong();
    private final AtomicLong dropped = new AtomicLong();

    public GameAuditPublisher(org.springframework.kafka.core.KafkaTemplate<String, String> auditKafkaTemplate,
                              @Value("${app.kafka.topic.state-audit:state_audit}") String topic) {
        this((key, json) -> auditKafkaTemplate.send(topic, key, json), topic);
    }

    /**
     * Test-friendly constructor — the test passes a hand-rolled sender
     * lambda that records calls + decides whether to complete or fail
     * the returned future, instead of mocking KafkaTemplate.
     */
    public GameAuditPublisher(KafkaSender sender, String topicLabel) {
        this.sender = sender;
        log.info("[audit] GameAuditPublisher → topic={}", topicLabel);
    }

    /**
     * Emit a single audit row. Caller threads MUST NOT block on the
     * returned future — the producer ACK arrives off the calling
     * thread and we only care for logging-and-counter purposes.
     *
     * @param gameSlug   game the event belongs to (LowCardinality column on the ClickHouse side).
     * @param sessionId  multiplayer session id; pass empty string for single-player events.
     * @param casdoorId  the player's Casdoor subject; null when the event isn't player-scoped.
     * @param eventType  short tag — {@code session.join}, {@code chapter.transition}, etc.
     * @param payload    extra structured fields; serialised to {@code payload_json}.
     */
    public void emit(String gameSlug,
                     String sessionId,
                     String casdoorId,
                     String eventType,
                     Map<String, ?> payload) {
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
            row.put("payload_json", "{}");
        }

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
                            long n = dropped.incrementAndGet();
                            if (n % 100 == 1) {
                                log.warn("[audit] dropped n={} — {}", n, ex.getMessage());
                            }
                        } else {
                            long n = emitted.incrementAndGet();
                            if (n % 10_000 == 0) {
                                log.info("[audit] emitted={} dropped={}",
                                        n, dropped.get());
                            }
                        }
                    });
        } catch (RuntimeException ex) {
            // KafkaTemplate.send throws synchronously when the broker
            // is genuinely unreachable; treat the same as an async fail.
            dropped.incrementAndGet();
            log.warn("[audit] dropped synchronously: {}", ex.getMessage());
        }
    }

    /** Test hook: count of events emitted (broker ack'd) since boot. */
    public long emittedCount() { return emitted.get(); }

    /** Test hook: count of events dropped (serialise or broker error). */
    public long droppedCount() { return dropped.get(); }
}
