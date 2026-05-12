package games.snapdragon.bff.audit;

import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.StringSerializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.core.ProducerFactory;

import java.util.HashMap;
import java.util.Map;

/**
 * Wires the Kafka producer used by {@link GameAuditPublisher} (state-transition
 * audit log) and, in a follow-up, by the F2 game-KPI publisher. Gated behind
 * {@code app.kafka.enabled} (default {@code false}) so vanilla compose-up keeps
 * working when the Kafka container isn't running. Enable in production +
 * integration-test profiles.
 *
 * <p>Why two beans rather than letting Spring Boot's auto-config do it: the
 * codebase deliberately keeps Kafka opt-in (the rest of the platform works
 * without it). Auto-config would attempt to construct a producer on every
 * profile and log warnings when Kafka isn't reachable; the explicit
 * {@code @ConditionalOnProperty} keeps boot quiet for the filesystem-only
 * default deploy.
 */
@Configuration
@ConditionalOnProperty(name = "app.kafka.enabled", havingValue = "true")
public class KafkaProducerConfig {

    private static final Logger log = LoggerFactory.getLogger(KafkaProducerConfig.class);

    @Bean
    public ProducerFactory<String, String> auditProducerFactory(
            @Value("${app.kafka.bootstrap-servers:kafka:9092}") String bootstrap,
            @Value("${app.kafka.client-id:snapdragon-bff}")     String clientId) {
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

    @Bean
    public KafkaTemplate<String, String> auditKafkaTemplate(
            ProducerFactory<String, String> auditProducerFactory) {
        return new KafkaTemplate<>(auditProducerFactory);
    }
}
