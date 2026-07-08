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
 * Tees streaming SSE-channel events to the {@code game_events} Kafka topic in addition to
 * the in-process {@code Sinks.Many} that drives the live HUD (decision 021 streaming
 * telemetry pipeline).
 *
 * <h2>Responsibility</h2>
 *
 * <p>SSE remains the gameplay-driving path — the existing web pattern stays untouched. This
 * publisher is purely additive: Kafka receives a copy of every movement / world / character /
 * scene / input / audio / view / wasm-console emission so that:
 * <ul>
 *   <li>ClickHouse can aggregate per-channel volume and latency without scraping SSE traffic.</li>
 *   <li>Cross-pod replay (decision 010) has a durable log to recover from when a pod restart
 *       drops the per-process sink state.</li>
 *   <li>Future analytics consumers can fork the stream without coupling to the BFF.</li>
 * </ul>
 *
 * <p>A single {@code game_events} topic keeps the topic count manageable; channel is stored as
 * a {@code LowCardinality(String)} column on the ClickHouse side, giving the same query
 * ergonomics as one-topic-per-channel without broker-side topic proliferation.
 *
 * <h2>Threading / reactive model</h2>
 *
 * <p>All methods are non-blocking. {@link #publish} assembles the JSON row on the calling
 * thread, delegates the send to the {@link KafkaSender} whose {@code CompletableFuture}
 * completes asynchronously on Kafka's network thread, and returns immediately. Callers
 * MUST NOT block on the returned future.
 *
 * <h2>Self-loop prevention</h2>
 *
 * <p>Every produced row carries a {@code pod_id} field stamped from {@link PodIdentity.PodId}.
 * The {@link KafkaSessionSubscriber} on the consuming side skips records whose {@code pod_id}
 * matches this pod — otherwise the pod would re-receive its own emissions through the Kafka
 * loop-back and double-fire its local {@code Sinks.Many}.
 *
 * <h2>Caller roles</h2>
 *
 * <p>Internal infrastructure component — no direct player/mod/admin API surface. The primary
 * caller is {@code AuthoritativeTickService} (tick loop and input path). The bean exists only
 * when {@code app.kafka.enabled=true}; callers inject as {@code Optional<GameEventPublisher>}
 * so the legacy in-process path continues working when Kafka is off. Failures are logged and
 * dropped — the gameplay path does not depend on the analytics tee succeeding.
 *
 * @see GameAuditPublisher
 * @see GameKpiPublisher
 * @see KafkaSessionSubscriber
 * @see KafkaProducerConfig
 */
@Service
@ConditionalOnProperty(name = "app.kafka.enabled", havingValue = "true")
public class GameEventPublisher {

    private static final Logger log = LoggerFactory.getLogger(GameEventPublisher.class);

    // -------------------------------------------------------------------------
    // Send abstraction
    // -------------------------------------------------------------------------

    /**
     * Same shape as {@link GameAuditPublisher.KafkaSender}; duplicated so the two
     * publishers can be tested independently without shared state in their sender lambdas.
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

    /**
     * This pod's unique identifier, stamped on every produced row so the cross-pod
     * subscriber ({@link KafkaSessionSubscriber}) can skip self-produced messages.
     */
    private final String podId;

    // -------------------------------------------------------------------------
    // Construction
    // -------------------------------------------------------------------------

    /**
     * Production constructor — wires the {@code KafkaSender} from the shared
     * {@code KafkaTemplate} and reads the pod identity for self-loop prevention.
     *
     * @param auditKafkaTemplate the Spring Kafka template provided by
     *                           {@link KafkaProducerConfig#auditKafkaTemplate}
     * @param podIdBean          this pod's typed identity bean from {@link PodIdentity}
     * @param topic              the Kafka topic name; defaults to {@code game_events}
     *                           via {@code app.kafka.topic.game-events}
     */
    public GameEventPublisher(
            org.springframework.kafka.core.KafkaTemplate<String, String> auditKafkaTemplate,
            PodIdentity.PodId podIdBean,
            @Value("${app.kafka.topic.game-events:game_events}") String topic) {
        this((key, json) -> auditKafkaTemplate.send(topic, key, json), podIdBean.value(), topic);
    }

    /**
     * Test-friendly constructor — see {@link GameAuditPublisher} for rationale.
     *
     * @param sender     the send hook to use for all publish calls
     * @param podId      this pod's identifier, stamped on each row
     * @param topicLabel label for the boot log line; does not affect routing
     */
    public GameEventPublisher(KafkaSender sender, String podId, String topicLabel) {
        this.sender = sender;
        this.podId = podId;
        log.info("[events] GameEventPublisher → topic={} pod={}", topicLabel, podId);
    }

    // -------------------------------------------------------------------------
    // Public publish API
    // -------------------------------------------------------------------------

    /**
     * Tees one SSE-channel event to the {@code game_events} Kafka topic.
     *
     * <p>The row schema written to Kafka (JSON object):
     * <pre>
     *   event_id     — fresh UID from {@link Crypto#mintUid()} (globally unique)
     *   occurred_at  — ISO-8601 UTC instant at call time
     *   pod_id       — this pod's identifier; used by {@link KafkaSessionSubscriber}
     *                  to skip self-produced messages and prevent loop-back double-fire
     *   channel      — SSE channel name (movement | world | character | scene |
     *                  input | audio | view | wasm-console)
     *   game_slug    — game the event belongs to (empty string if null)
     *   session_id   — per-session id; empty for the global / admin firehose
     *   casdoor_id   — player's Casdoor subject; empty for non-player events
     *   payload_json — JSON-serialised {@code payload}, or {@code "{}"}
     * </pre>
     *
     * <p>Partition key is {@code (gameSlug, sessionId)} concatenated with {@code "|"} so
     * all events for the same session land on the same partition and per-session ordering
     * is preserved.
     *
     * @param channel   one of {@code movement | world | character | scene |
     *                  input | audio | view | wasm-console}; {@code null} is coerced to
     *                  empty string
     * @param gameSlug  game the event belongs to; {@code null} is coerced to empty string
     * @param sessionId per-session id; empty for the global / admin firehose
     * @param casdoorId player's Casdoor subject; {@code null} for non-player events
     * @param payload   structured payload serialised to {@code payload_json};
     *                  {@code null} yields {@code "{}"}
     */
    public void publish(String channel,
                        String gameSlug,
                        String sessionId,
                        String casdoorId,
                        Object payload) {
        // Assemble the event row in insertion order.
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("event_id",    Crypto.mintUid());
        row.put("occurred_at", DateTimeFormatter.ISO_INSTANT.format(Instant.now()));
        // pod_id is the discriminator the cross-pod subscriber checks to
        // skip events this pod produced — prevents the in-process
        // Sinks.Many from re-receiving its own emissions through the
        // Kafka loop-back.
        row.put("pod_id",      podId == null ? "" : podId);
        row.put("channel",     channel == null ? "" : channel);
        row.put("game_slug",   gameSlug == null ? "" : gameSlug);
        row.put("session_id",  sessionId == null ? "" : sessionId);
        row.put("casdoor_id",  casdoorId == null ? "" : casdoorId);
        try {
            row.put("payload_json",
                    payload == null ? "{}" : mapper.writeValueAsString(payload));
        } catch (JsonProcessingException ex) {
            // Payload serialisation failed — emit the row with an empty payload rather
            // than dropping the event entirely; the channel + session metadata is still
            // useful for throughput accounting.
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

        // Key by (gameSlug, sessionId) so events for the same session
        // land on the same partition (preserves per-session ordering).
        String key = (gameSlug == null ? "" : gameSlug)
                   + "|"
                   + (sessionId == null ? "" : sessionId);
        try {
            sender.apply(key, json)
                    .whenComplete((md, ex) -> {
                        if (ex != null) {
                            // Broker error — log periodically to avoid flooding the log
                            // under sustained outages (every 1 000th drop).
                            long n = dropped.incrementAndGet();
                            if (n % 1_000 == 1) {
                                log.warn("[events] dropped n={} channel={} — {}",
                                        n, channel, ex.getMessage());
                            }
                        } else {
                            // Successful ack — log a throughput sample every 100 000 events.
                            long n = emitted.incrementAndGet();
                            if (n % 100_000 == 0) {
                                log.info("[events] emitted={} dropped={}",
                                        n, dropped.get());
                            }
                        }
                    });
        } catch (RuntimeException ex) {
            // Synchronous broker-unreachable throw — drop and log periodically.
            dropped.incrementAndGet();
            if (dropped.get() % 1_000 == 1) {
                log.warn("[events] dropped synchronously channel={} — {}",
                        channel, ex.getMessage());
            }
        }
    }

    // -------------------------------------------------------------------------
    // Test / monitoring hooks
    // -------------------------------------------------------------------------

    /**
     * Returns the count of events successfully acknowledged by the broker since boot.
     *
     * @return number of events broker-ack'd
     */
    public long emittedCount() { return emitted.get(); }

    /**
     * Returns the count of events dropped due to serialisation or broker errors since boot.
     *
     * @return number of events dropped
     */
    public long droppedCount() { return dropped.get(); }
}
