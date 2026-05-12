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
 * Tees streaming SSE-channel events to Kafka in addition to the
 * in-process {@code Sinks.Many} that drives the live HUD. SSE remains
 * the gameplay-driving path (the existing web pattern stays untouched);
 * this publisher is purely additive — Kafka receives a copy of every
 * movement / world / character / scene / input / audio / view /
 * wasm-console emission so:
 *
 * <ul>
 *   <li>ClickHouse aggregates per-channel volume + latency + content
 *       without scraping SSE traffic.</li>
 *   <li>Cross-pod replay (decision 010) gets a durable log to recover
 *       from when a pod restart drops the per-process sink state.</li>
 *   <li>Future analytics consumers can fork the stream without coupling
 *       to the BFF.</li>
 * </ul>
 *
 * <p>Single Kafka topic {@code game_events} keeps the topic count
 * manageable; channel becomes a {@code LowCardinality(String)} column
 * on the ClickHouse side that gives the same query ergonomics as
 * one-topic-per-channel without the broker-side proliferation.
 *
 * <p>Producer wiring mirrors {@link GameAuditPublisher}: bean exists
 * only when {@code app.kafka.enabled=true}; injected as
 * {@code Optional<GameEventPublisher>} so the legacy in-process path
 * works when Kafka is off. Failures are logged + dropped — the
 * gameplay path doesn't depend on the analytics tee succeeding.
 */
@Service
@ConditionalOnProperty(name = "app.kafka.enabled", havingValue = "true")
public class GameEventPublisher {

    private static final Logger log = LoggerFactory.getLogger(GameEventPublisher.class);

    /** Same shape as {@link GameAuditPublisher.KafkaSender}; duplicated
     *  so the two publishers can be tested independently. */
    @FunctionalInterface
    public interface KafkaSender extends BiFunction<String, String, CompletableFuture<?>> {}

    private final KafkaSender sender;
    private final ObjectMapper mapper = new ObjectMapper();
    private final AtomicLong emitted = new AtomicLong();
    private final AtomicLong dropped = new AtomicLong();
    private final String podId;

    public GameEventPublisher(
            org.springframework.kafka.core.KafkaTemplate<String, String> auditKafkaTemplate,
            PodIdentity.PodId podIdBean,
            @Value("${app.kafka.topic.game-events:game_events}") String topic) {
        this((key, json) -> auditKafkaTemplate.send(topic, key, json), podIdBean.value(), topic);
    }

    /** Test-friendly constructor — see {@link GameAuditPublisher} for rationale. */
    public GameEventPublisher(KafkaSender sender, String podId, String topicLabel) {
        this.sender = sender;
        this.podId = podId;
        log.info("[events] GameEventPublisher → topic={} pod={}", topicLabel, podId);
    }

    /**
     * Tee one channel event to Kafka.
     *
     * @param channel    one of {@code movement | world | character | scene |
     *                          input | audio | view | wasm-console}.
     * @param gameSlug   game the event belongs to ({@code null} → empty string).
     * @param sessionId  per-session id; empty for the global / admin firehose.
     * @param casdoorId  player's Casdoor subject; {@code null} for non-player events.
     * @param payload    structured payload — serialised to {@code payload_json}.
     */
    public void publish(String channel,
                        String gameSlug,
                        String sessionId,
                        String casdoorId,
                        Object payload) {
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
            row.put("payload_json", "{}");
        }

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
                            long n = dropped.incrementAndGet();
                            if (n % 1_000 == 1) {
                                log.warn("[events] dropped n={} channel={} — {}",
                                        n, channel, ex.getMessage());
                            }
                        } else {
                            long n = emitted.incrementAndGet();
                            if (n % 100_000 == 0) {
                                log.info("[events] emitted={} dropped={}",
                                        n, dropped.get());
                            }
                        }
                    });
        } catch (RuntimeException ex) {
            dropped.incrementAndGet();
            if (dropped.get() % 1_000 == 1) {
                log.warn("[events] dropped synchronously channel={} — {}",
                        channel, ex.getMessage());
            }
        }
    }

    public long emittedCount() { return emitted.get(); }
    public long droppedCount() { return dropped.get(); }
}
