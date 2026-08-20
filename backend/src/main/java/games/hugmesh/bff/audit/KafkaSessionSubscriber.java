package games.hugmesh.bff.audit;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Service;

import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BiConsumer;

/**
 * Cross-pod Kafka subscriber — the consumer half of the decision 021 streaming
 * telemetry pipeline.
 *
 * <h2>Responsibility</h2>
 *
 * <p>Closes the cross-pod fan-out loop so that a player connected to Pod B receives
 * game events emitted by Pod A:
 *
 * <pre>
 *  Pod A: applyControl(…) → Sinks.Many → GameEventPublisher → game_events topic
 *  Pod B: KafkaSessionSubscriber ← game_events topic
 *              → sessionSinksFor(sessionId).&lt;channel&gt;.tryEmitNext(…)
 * </pre>
 *
 * <p>Subscribes to the unscoped {@code game_events} topic. The consumer group is shared
 * across all BFF instances so Kafka distributes partitions across pods; each pod processes
 * a fraction of the firehose and re-emits into its own local {@code SessionSinks}. If a
 * pod has no live subscribers for a given session, the {@code tryEmitNext} on the empty
 * sink is a cheap no-op.
 *
 * <h2>Self-loop prevention</h2>
 *
 * <p>Every produced row carries a {@code pod_id} field (set by {@link GameEventPublisher}
 * from {@link PodIdentity.PodId}). This subscriber skips any record whose {@code pod_id}
 * matches this pod's own id — otherwise Pod A's emission would loop back through Kafka
 * and double-fire its local in-process sinks.
 *
 * <h2>Dispatch model</h2>
 *
 * <p>Deserialisation is intentionally minimal: the outer JSON envelope is parsed into a
 * {@link JsonNode}, and {@code channel}, {@code session_id}, and {@code payload_json} are
 * extracted as strings. A typed {@link ChannelHandler} registered per channel (via
 * {@link #registerHandler}) is responsible for parsing the payload string into the
 * matching record type and emitting into the local sink. This deferred wiring keeps the
 * subscriber stateless; {@code GameStateService} owns sink lifecycle and registers its
 * handlers at boot.
 *
 * <h2>Threading / reactive model</h2>
 *
 * <p>The {@code @KafkaListener} method ({@link #onGameEvent}) runs on the Kafka consumer
 * thread managed by Spring's {@code ConcurrentMessageListenerContainer} (configured with
 * {@code concurrency=1} in {@link KafkaProducerConfig}). The {@link ChannelHandler}
 * accept call in turn calls {@code Sinks.Many.tryEmitNext}, which is non-blocking. The
 * entire dispatch path is non-blocking and should never park the consumer thread.
 *
 * <h2>Caller roles</h2>
 *
 * <p>Internal infrastructure component — no direct player/mod/admin API surface. Bean
 * gated by {@code app.kafka.enabled} so vanilla compose-up (without the Kafka container)
 * does not attempt to subscribe.
 *
 * @see GameEventPublisher
 * @see KafkaProducerConfig
 * @see PodIdentity
 */
@Service
@ConditionalOnProperty(name = "app.kafka.enabled", havingValue = "true")
public class KafkaSessionSubscriber {

    private static final Logger log = LoggerFactory.getLogger(KafkaSessionSubscriber.class);

    // -------------------------------------------------------------------------
    // Handler contract
    // -------------------------------------------------------------------------

    /**
     * Per-channel dispatch handler. Accepts {@code (sessionId, payloadJsonString)}.
     * The handler is responsible for parsing the payload string into the correct typed
     * record and emitting it into the appropriate {@code SessionSinks} channel.
     * Registered per channel name via {@link #registerHandler}.
     */
    @FunctionalInterface
    public interface ChannelHandler extends BiConsumer<String, String> {}

    // -------------------------------------------------------------------------
    // State
    // -------------------------------------------------------------------------

    /**
     * This pod's identifier, read from {@link PodIdentity.PodId}. Used to detect and
     * skip self-produced records before dispatching to registered handlers.
     */
    private final String podId;
    private final ObjectMapper mapper = new ObjectMapper();

    /** Registered channel-name → handler mapping; populated at boot by {@code GameStateService}. */
    private final java.util.concurrent.ConcurrentHashMap<String, ChannelHandler> handlers
            = new java.util.concurrent.ConcurrentHashMap<>();

    /** Total Kafka records received by this pod since boot. */
    private final AtomicLong received = new AtomicLong();

    /** Records skipped because they were produced by this pod (self-loop prevention). */
    private final AtomicLong selfSkipped = new AtomicLong();

    /** Records successfully dispatched to a registered channel handler. */
    private final AtomicLong dispatched = new AtomicLong();

    /** Records received for a channel with no registered handler. */
    private final AtomicLong unhandled  = new AtomicLong();

    // -------------------------------------------------------------------------
    // Construction
    // -------------------------------------------------------------------------

    /**
     * @param podIdBean this pod's typed identity bean from {@link PodIdentity}; used
     *                  for self-loop detection in {@link #onGameEvent}
     */
    public KafkaSessionSubscriber(PodIdentity.PodId podIdBean) {
        this.podId = podIdBean.value();
        log.info("[kafka-sub] subscriber wired → pod={}", podId);
    }

    // -------------------------------------------------------------------------
    // Handler registration
    // -------------------------------------------------------------------------

    /**
     * Registers a dispatch handler for the named SSE channel. Called at boot by
     * {@code GameStateService} — one call per channel ({@code movement}, {@code world},
     * {@code character}, {@code scene}, {@code view}, {@code input}, {@code audio},
     * {@code wasm-console}, {@code world-native}).
     *
     * <p>Re-registering the same channel name replaces the previous handler atomically
     * in the underlying {@code ConcurrentHashMap}.
     *
     * @param channel the SSE channel name to register the handler for
     * @param handler the handler that accepts {@code (sessionId, payloadJsonString)}
     *                and emits into the local sink
     */
    public void registerHandler(String channel, ChannelHandler handler) {
        handlers.put(channel, handler);
        log.info("[kafka-sub] handler registered for channel={}", channel);
    }

    // -------------------------------------------------------------------------
    // Kafka listener
    // -------------------------------------------------------------------------

    /**
     * Processes one record from the {@code game_events} topic.
     *
     * <p>Processing steps:
     * <ol>
     *   <li>Parse the JSON envelope from the record value.</li>
     *   <li>Extract {@code pod_id}; skip the record if it matches this pod (self-loop
     *       prevention — the local sinks already received the event directly).</li>
     *   <li>Extract {@code channel}, {@code session_id}, and {@code payload_json}.</li>
     *   <li>Look up the registered {@link ChannelHandler} for the channel; log and skip
     *       if none is found.</li>
     *   <li>Invoke the handler with {@code (sessionId, payloadJsonString)}.</li>
     * </ol>
     *
     * <p>The group-id is derived from {@code app.kafka.client-id} so each BFF deployment
     * has a single shared consumer group; horizontal scaling distributes partitions
     * automatically across pods.
     *
     * @param record the raw Kafka consumer record from the {@code game_events} topic
     */
    @KafkaListener(
            topics = "${app.kafka.topic.game-events:game_events}",
            groupId = "${app.kafka.consumer.group-id:hugmesh-bff-cross-pod}",
            properties = {
                    "spring.json.use.type.headers=false",
                    "auto.offset.reset=latest",
                    // We're not durable — falling behind isn't a problem,
                    // so start from `latest` after a restart. Catching up
                    // on a 30-day backlog of game_events would just
                    // double-fire stale events into local sinks.
            })
    public void onGameEvent(ConsumerRecord<String, String> record) {
        // Log a throughput sample every 100 000 records so operators can
        // verify pipeline health without DEBUG-level logging.
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

            // Extract the routing envelope fields.
            String channel    = textOrEmpty(root, "channel");
            String sessionId  = textOrEmpty(root, "session_id");
            String payload    = textOrEmpty(root, "payload_json");

            ChannelHandler handler = handlers.get(channel);
            if (handler == null) {
                // No handler registered for this channel — log periodically to surface
                // misconfiguration without flooding the log.
                long u = unhandled.incrementAndGet();
                if (u % 1_000 == 1) {
                    log.warn("[kafka-sub] no handler for channel={} (count={})", channel, u);
                }
                return;
            }
            // Dispatch the payload string to the channel handler, which parses it and
            // calls tryEmitNext on the appropriate local SessionSinks channel.
            handler.accept(sessionId, payload);
            dispatched.incrementAndGet();
        } catch (Exception ex) {
            log.warn("[kafka-sub] failed to dispatch record: {}", ex.getMessage());
        }
    }

    // -------------------------------------------------------------------------
    // Internal helpers
    // -------------------------------------------------------------------------

    /**
     * Null-safe JSON field extractor. Returns the text value of the named field,
     * or an empty string if the field is absent or null in the given node.
     *
     * @param node  the JSON node to extract from
     * @param field the field name to look up
     * @return the field's text value, or {@code ""} if absent / null
     */
    private static String textOrEmpty(JsonNode node, String field) {
        JsonNode child = node.get(field);
        return child == null || child.isNull() ? "" : child.asText("");
    }

    // -------------------------------------------------------------------------
    // Test / monitoring hooks
    // -------------------------------------------------------------------------

    /**
     * Returns the total number of Kafka records received by this pod since boot.
     *
     * @return total records received
     */
    public long receivedCount()    { return received.get(); }

    /**
     * Returns the number of records skipped because they were produced by this pod.
     *
     * @return self-loop records skipped
     */
    public long selfSkippedCount() { return selfSkipped.get(); }

    /**
     * Returns the number of records successfully dispatched to a registered channel handler.
     *
     * @return records dispatched
     */
    public long dispatchedCount()  { return dispatched.get(); }

    /**
     * Returns the number of records received for a channel with no registered handler.
     *
     * @return records with no handler
     */
    public long unhandledCount()   { return unhandled.get(); }
}
