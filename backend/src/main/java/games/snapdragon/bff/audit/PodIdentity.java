package games.snapdragon.bff.audit;

import games.snapdragon.bff.util.Crypto;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Process-unique identifier for this BFF instance. Stamped on every
 * Kafka-emitted row so the cross-pod subscriber can filter out events
 * this pod produced (preventing the in-process Sinks.Many from
 * re-receiving its own emissions through the Kafka loop-back).
 *
 * <p>Two ways to set it:
 * <ul>
 *   <li>{@code APP_POD_ID} env var (preferred in k8s — set to the pod
 *       name so the value lines up with the orchestrator's view).</li>
 *   <li>Auto-generated via {@link Crypto#mintUid()} when the env var
 *       isn't set; suitable for compose-up and single-host deploys.</li>
 * </ul>
 *
 * <p>The bean is always available (no {@code @ConditionalOnProperty})
 * since publishers + subscribers + admin logging all want the same
 * value regardless of whether Kafka is enabled.
 */
@Configuration
public class PodIdentity {

    @Bean
    public PodId podId(@Value("${app.pod-id:}") String configured) {
        if (configured != null && !configured.isBlank()) {
            return new PodId(configured.trim());
        }
        return new PodId(Crypto.mintUid().substring(0, 12));
    }

    /**
     * Wrapper record so DI can inject a typed {@code PodId} instead of
     * a bare {@code String} (which would clash with every other
     * String-typed bean in the context).
     */
    public record PodId(String value) {}
}
