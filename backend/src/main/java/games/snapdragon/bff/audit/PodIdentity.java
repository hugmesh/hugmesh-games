package games.snapdragon.bff.audit;

import games.snapdragon.bff.util.Crypto;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Provides a process-unique identifier for this BFF pod instance.
 *
 * <h2>Responsibility</h2>
 *
 * <p>The pod id is stamped on every Kafka-emitted row by {@link GameEventPublisher} so
 * the cross-pod subscriber ({@link KafkaSessionSubscriber}) can identify and skip events
 * produced by the same pod. Without this, a pod's own emission would loop back through
 * the Kafka topic and double-fire its local in-process {@code Sinks.Many}.
 *
 * <p>The same id is also used as the Kafka producer {@code client.id} (see
 * {@link KafkaProducerConfig}) and can appear in pod heartbeat rows in the
 * {@code pod_heartbeats} Cassandra table for cross-pod liveness tracking.
 *
 * <h2>Identity source</h2>
 *
 * <p>Two resolution strategies, in priority order:
 * <ol>
 *   <li>{@code APP_POD_ID} env var (or {@code app.pod-id} Spring property) — preferred
 *       in Kubernetes, where this should be set to the pod name so the value lines up with
 *       the orchestrator's own view of the pod topology.</li>
 *   <li>Auto-generated via {@link Crypto#mintUid()} (first 12 characters) — suitable for
 *       {@code docker compose} and single-host deploys where there is no orchestrator to
 *       supply a stable name.</li>
 * </ol>
 *
 * <h2>Availability</h2>
 *
 * <p>The bean is always registered (no {@code @ConditionalOnProperty}) because publishers,
 * subscribers, and admin logging all want the same value regardless of whether Kafka is
 * enabled. It is safe to inject {@link PodId} anywhere in the application context.
 *
 * @see GameEventPublisher
 * @see KafkaSessionSubscriber
 */
@Configuration
public class PodIdentity {

    // -------------------------------------------------------------------------
    // Bean
    // -------------------------------------------------------------------------

    /**
     * Resolves and provides this pod's unique identifier.
     *
     * <p>Reads the {@code app.pod-id} property (mapped from the {@code APP_POD_ID}
     * env var by Spring Boot). If the value is non-blank, it is used as-is (trimmed).
     * Otherwise a 12-character prefix of a fresh {@link Crypto#mintUid()} is used,
     * which is sufficient for uniqueness within a single compose or k8s cluster at
     * typical BFF replica counts.
     *
     * @param configured the value of {@code app.pod-id}; empty string when the env
     *                   var is unset
     * @return the typed {@link PodId} bean shared across the application context
     */
    @Bean
    public PodId podId(@Value("${app.pod-id:}") String configured) {
        if (configured != null && !configured.isBlank()) {
            // Orchestrator-supplied pod name — use verbatim so it matches kubectl output.
            return new PodId(configured.trim());
        }
        // No orchestrator — mint a short random id for this process lifetime.
        return new PodId(Crypto.mintUid().substring(0, 12));
    }

    // -------------------------------------------------------------------------
    // Value type
    // -------------------------------------------------------------------------

    /**
     * Typed wrapper around the raw pod-id string. Using a dedicated record instead
     * of a bare {@code String} bean prevents DI ambiguity with other {@code String}-typed
     * beans in the application context.
     *
     * @param value the pod's unique identifier string
     */
    public record PodId(String value) {}
}
