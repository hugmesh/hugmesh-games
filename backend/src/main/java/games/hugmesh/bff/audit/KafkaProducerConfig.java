package games.hugmesh.bff.audit;

import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.annotation.EnableKafka;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.core.ProducerFactory;

import java.util.HashMap;
import java.util.Map;

/**
 * Configures the Kafka producer and consumer beans for the decision 021 streaming
 * telemetry pipeline.
 *
 * <h2>Responsibility</h2>
 *
 * <p>Wires the {@link KafkaTemplate} used by {@link GameAuditPublisher},
 * {@link GameEventPublisher}, and {@link GameKpiPublisher} (producer side), and the
 * {@link ConcurrentKafkaListenerContainerFactory} used by {@link KafkaSessionSubscriber}
 * (consumer side). Both sides share the same bootstrap-servers and client-id properties.
 *
 * <h2>Why an explicit config rather than Spring Boot auto-config</h2>
 *
 * <p>The codebase deliberately keeps Kafka opt-in via {@code app.kafka.enabled} (default
 * {@code false}). Spring Boot's auto-config would attempt to construct a producer on every
 * profile and log connection-refused warnings when Kafka isn't reachable. The explicit
 * {@code @ConditionalOnProperty} keeps boot quiet for the filesystem-only default deploy
 * (vanilla {@code docker compose up -d}) and integration-test profiles that don't start a
 * Kafka container.
 *
 * <h2>Producer settings</h2>
 *
 * <p>The producer is configured as an idempotent producer ({@code acks=all} +
 * {@code enable.idempotence=true}) so every state-transition write is durable and
 * exactly-once per broker attempt. Event volume is bounded by player actions (not
 * microsecond-latency HFT), so the latency cost of {@code acks=all} is acceptable.
 * LZ4 compression is enabled for network efficiency.
 *
 * <h2>Consumer settings</h2>
 *
 * <p>The consumer group is shared across all BFF pod instances so Kafka distributes
 * partitions automatically as pods scale. Offset reset is {@code latest} because the
 * cross-pod subscriber is not durable — replaying a 30-day backlog of {@code game_events}
 * after a restart would double-fire stale events into local {@code Sinks.Many}. Auto-commit
 * is enabled because the consume path ({@link KafkaSessionSubscriber#onGameEvent}) is
 * idempotent ({@code tryEmitNext} on a local sink is a cheap no-op when there are no
 * subscribers).
 *
 * @see GameAuditPublisher
 * @see GameEventPublisher
 * @see GameKpiPublisher
 * @see KafkaSessionSubscriber
 */
@Configuration
@ConditionalOnProperty(name = "app.kafka.enabled", havingValue = "true")
@EnableKafka
public class KafkaProducerConfig {

    private static final Logger log = LoggerFactory.getLogger(KafkaProducerConfig.class);

    // -------------------------------------------------------------------------
    // Producer beans
    // -------------------------------------------------------------------------

    /**
     * Creates the {@code ProducerFactory} used by {@link #auditKafkaTemplate}.
     *
     * <p>Idempotent producer settings applied:
     * <ul>
     *   <li>{@code acks=all} — wait for all in-sync replicas before ack</li>
     *   <li>{@code enable.idempotence=true} — exactly-once-per-attempt delivery</li>
     *   <li>{@code retries=3} — automatic retry on transient errors</li>
     *   <li>{@code max.in.flight.requests.per.connection=5} — maximum concurrency
     *       compatible with idempotence</li>
     *   <li>{@code compression.type=lz4} — LZ4 for network efficiency</li>
     * </ul>
     *
     * @param bootstrap the Kafka bootstrap servers; defaults to {@code kafka:9092}
     * @param clientId  the producer client id; defaults to {@code hugmesh-bff}
     * @return a configured {@code ProducerFactory} for String key/value messages
     */
    @Bean
    public ProducerFactory<String, String> auditProducerFactory(
            @Value("${app.kafka.bootstrap-servers:kafka:9092}") String bootstrap,
            @Value("${app.kafka.client-id:hugmesh-bff}")     String clientId) {
        log.info("[kafka] producer factory wired → bootstrap={} clientId={}", bootstrap, clientId);
        Map<String, Object> cfg = new HashMap<>();
        cfg.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrap);
        cfg.put(ProducerConfig.CLIENT_ID_CONFIG,         clientId);
        cfg.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG,   StringSerializer.class);
        cfg.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        // Idempotent producer + acks=all: every state-transition write
        // is durable + exactly-once-per-broker-attempt. We're producing
        // at the volume of player actions, not microseconds-per-event;
        // the latency cost is acceptable.
        cfg.put(ProducerConfig.ACKS_CONFIG,                "all");
        cfg.put(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG,  true);
        cfg.put(ProducerConfig.RETRIES_CONFIG,             3);
        cfg.put(ProducerConfig.MAX_IN_FLIGHT_REQUESTS_PER_CONNECTION, 5);
        cfg.put(ProducerConfig.COMPRESSION_TYPE_CONFIG,    "lz4");
        return new DefaultKafkaProducerFactory<>(cfg);
    }

    /**
     * Creates the {@code KafkaTemplate} injected into {@link GameAuditPublisher},
     * {@link GameEventPublisher}, and {@link GameKpiPublisher}.
     *
     * @param auditProducerFactory the producer factory wired by {@link #auditProducerFactory}
     * @return a {@code KafkaTemplate} for String key/value messages
     */
    @Bean
    public KafkaTemplate<String, String> auditKafkaTemplate(
            ProducerFactory<String, String> auditProducerFactory) {
        return new KafkaTemplate<>(auditProducerFactory);
    }

    // -------------------------------------------------------------------------
    // Consumer beans (cross-pod subscriber)
    // -------------------------------------------------------------------------

    /**
     * Creates the {@code ConsumerFactory} used by
     * {@link #kafkaListenerContainerFactory} for the cross-pod session subscriber.
     *
     * <p>Key settings:
     * <ul>
     *   <li>{@code auto.offset.reset=latest} — after a restart, do not replay the
     *       backlog; stale events would double-fire into local {@code Sinks.Many}.</li>
     *   <li>{@code enable.auto.commit=true} — the consume path is idempotent
     *       ({@code tryEmitNext} is a no-op when there are no subscribers), so
     *       at-least-once delivery with auto-commit keeps the consumer simple.</li>
     * </ul>
     *
     * @param bootstrap the Kafka bootstrap servers; defaults to {@code kafka:9092}
     * @param clientId  base client id; {@code -consumer} suffix appended
     * @param groupId   consumer group id; defaults to {@code hugmesh-bff-cross-pod}
     * @return a configured {@code ConsumerFactory} for String key/value messages
     */
    @Bean
    public ConsumerFactory<String, String> crossPodConsumerFactory(
            @Value("${app.kafka.bootstrap-servers:kafka:9092}") String bootstrap,
            @Value("${app.kafka.client-id:hugmesh-bff}")     String clientId,
            @Value("${app.kafka.consumer.group-id:hugmesh-bff-cross-pod}") String groupId) {
        log.info("[kafka] consumer factory wired → bootstrap={} group={} clientId={}",
                bootstrap, groupId, clientId);
        Map<String, Object> cfg = new HashMap<>();
        cfg.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG,  bootstrap);
        cfg.put(ConsumerConfig.CLIENT_ID_CONFIG,          clientId + "-consumer");
        cfg.put(ConsumerConfig.GROUP_ID_CONFIG,           groupId);
        cfg.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG,   StringDeserializer.class);
        cfg.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        // `latest` so a restart doesn't replay 30 days of game_events
        // into local sinks; catching up on stale events would
        // double-fire the in-process emission.
        cfg.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG,  "latest");
        // We're processing each record idempotently (a tryEmitNext on
        // an in-process sink) — at-least-once is fine; auto-commit
        // keeps the consumer simple.
        cfg.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, true);
        return new DefaultKafkaConsumerFactory<>(cfg);
    }

    /**
     * Creates the {@code ConcurrentKafkaListenerContainerFactory} used by
     * {@link KafkaSessionSubscriber}'s {@code @KafkaListener}.
     *
     * <p>Concurrency is set to {@code 1} — the cross-pod re-emit path is a non-blocking
     * {@code Sinks.Many.tryEmitNext} call; additional listener threads would only
     * complicate partition ordering guarantees without any throughput benefit.
     *
     * @param crossPodConsumerFactory the consumer factory wired by
     *                                {@link #crossPodConsumerFactory}
     * @return a listener container factory configured for single-threaded consumption
     */
    @Bean
    public ConcurrentKafkaListenerContainerFactory<String, String> kafkaListenerContainerFactory(
            ConsumerFactory<String, String> crossPodConsumerFactory) {
        ConcurrentKafkaListenerContainerFactory<String, String> factory =
                new ConcurrentKafkaListenerContainerFactory<>();
        factory.setConsumerFactory(crossPodConsumerFactory);
        // Single-threaded per listener — the cross-pod re-emit path is
        // a non-blocking Sinks.Many.tryEmitNext call; parallelism would
        // only complicate ordering guarantees without throughput win.
        factory.setConcurrency(1);
        return factory;
    }
}
