package games.snapdragon.bff.audit;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Service;

import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BiConsumer;

/**
 * Cross-pod Kafka subscriber — the consumer half of the streaming
 * pipeline introduced in decision 021. Closes the loop:
 *
 * <pre>
 *  Pod A: applyControl(...) → Sinks.Many → GameEventPublisher → game_events topic
 *  Pod B: KafkaSessionSubscriber ← game_events topic → sessionSinksFor(sessionId).&lt;channel&gt;.tryEmitNext(...)
 * </pre>
 *
 * <p>Subscribes to the unscoped {@code game_events} topic; the consumer
 * group ID is the same across all BFF instances, so Kafka distributes
 * partitions across pods. Each pod processes a fraction of the firehose
 * and re-emits into its local {@code SessionSinks}. If a pod has no
 * subscribers for a given session, the {@code tryEmitNext} on the empty
 * sink is a cheap no-op.
 *
 * <p><strong>Self-loop prevention.</strong> Every produced row carries
 * a {@code pod_id} field (set by {@link GameEventPublisher} from
 * {@link PodIdentity.PodId}). The subscriber skips messages whose
 * {@code pod_id} matches this pod's id — otherwise Pod A's emission
 * would loop back through Kafka and double-fire its own local sinks.
 *
 * <p>The deserialisation is intentionally minimal: we parse the outer
 * JSON envelope into a {@link JsonNode}, extract {@code channel} +
 * {@code session_id} + {@code payload_json}, and hand the
 * payload-string to a typed handler registered for the channel. The
 * handler is responsible for parsing the payload into the matching
 * record (e.g. {@code PlayerMovement} for {@code channel=movement})
 * and emitting into the local sink. This deferred wiring keeps the
 * subscriber stateless and lets {@code GameStateService} own the
 * sink ownership.
 *
 * <p>Bean gated by {@code app.kafka.enabled} so vanilla compose-up
 * (without the Kafka container) doesn't try to subscribe.
 */
@Service
@ConditionalOnProperty(name = "app.kafka.enabled", havingValue = "true")
public class KafkaSessionSubscriber {

    private static final Logger log = LoggerFactory.getLogger(KafkaSessionSubscriber.class);

    /**
     * Handler signature: takes {@code (sessionId, payloadJsonString)}.
     * The handler is responsible for parsing the payload and emitting
     * into the right {@code SessionSinks} channel. Registered per
     * channel via {@link #registerHandler}.
     */
    @FunctionalInterface
    public interface ChannelHandler extends BiConsumer<String, String> {}

    private final String podId;
    private final ObjectMapper mapper = new ObjectMapper();
    private final java.util.concurrent.ConcurrentHashMap<String, ChannelHandler> handlers
            = new java.util.concurrent.ConcurrentHashMap<>();
    private final AtomicLong received = new AtomicLong();
    private final AtomicLong selfSkipped = new AtomicLong();
    private final AtomicLong dispatched = new AtomicLong();
    private final AtomicLong unhandled  = new AtomicLong();

    public KafkaSessionSubscriber(PodIdentity.PodId podIdBean) {
        this.podId = podIdBean.value();
        log.info("[kafka-sub] subscriber wired → pod={}", podId);
    }

    /**
     * Register a handler for one channel. {@code GameStateService}
     * calls this at boot (one registration per channel: movement,
     * world, character, scene, view, input, audio, wasm-console).
     * Re-registering the same channel replaces the previous handler.
     */
    public void registerHandler(String channel, ChannelHandler handler) {
        handlers.put(channel, handler);
        log.info("[kafka-sub] handler registered for channel={}", channel);
    }

    /**
     * Listener method — consumes from the {@code game_events} topic
     * across all pods (consumer-group sharded). The group-id is
     * derived from {@code app.kafka.client-id} so each BFF deployment
     * has a single shared group; horizontal scaling distributes
     * partitions automatically.
     */
    @KafkaListener(
            topics = "${app.kafka.topic.game-events:game_events}",
            groupId = "${app.kafka.consumer.group-id:snapdragon-bff-cross-pod}",
            properties = {
                    "spring.json.use.type.headers=false",
                    "auto.offset.reset=latest",
                    // We're not durable — falling behind isn't a problem,
                    // so start from `latest` after a restart. Catching up
                    // on a 30-day backlog of game_events would just
                    // double-fire stale events into local sinks.
            })
    public void onGameEvent(ConsumerRecord<String, String> record) {
        long n = received.incrementAndGet();
        if (n % 100_000 == 0) {
            log.info("[kafka-sub] received={} selfSkipped={} dispatched={} unhandled={}",
                    n, selfSkipped.get(), dispatched.get(), unhandled.get());
        }
        try {
            JsonNode root = mapper.readTree(record.value());
            String eventPodId = textOrEmpty(root, "pod_id");
            if (podId.equals(eventPodId)) {
                // Our own emission — skip; the local Sinks.Many already
                // received it directly.
                selfSkipped.incrementAndGet();
                return;
            }

            String channel    = textOrEmpty(root, "channel");
            String sessionId  = textOrEmpty(root, "session_id");
            String payload    = textOrEmpty(root, "payload_json");

            ChannelHandler handler = handlers.get(channel);
            if (handler == null) {
                long u = unhandled.incrementAndGet();
                if (u % 1_000 == 1) {
                    log.warn("[kafka-sub] no handler for channel={} (count={})", channel, u);
                }
                return;
            }
            handler.accept(sessionId, payload);
            dispatched.incrementAndGet();
        } catch (Exception ex) {
            log.warn("[kafka-sub] failed to dispatch record: {}", ex.getMessage());
        }
    }

    private static String textOrEmpty(JsonNode node, String field) {
        JsonNode child = node.get(field);
        return child == null || child.isNull() ? "" : child.asText("");
    }

    public long receivedCount()    { return received.get(); }
    public long selfSkippedCount() { return selfSkipped.get(); }
    public long dispatchedCount()  { return dispatched.get(); }
    public long unhandledCount()   { return unhandled.get(); }
}
