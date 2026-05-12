package games.snapdragon.bff.audit;

import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.common.config.TopicConfig;
import org.apache.kafka.common.errors.TopicExistsException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.stereotype.Service;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

/**
 * Manages the per-session Kafka topics the streaming pipeline uses for
 * cross-publishing of game events. Each multiplayer session gets its
 * own topic — {@code session.<sessionId>.events} — so cross-pod
 * consumers can subscribe only to the sessions they serve, instead of
 * filtering a global firehose.
 *
 * <p>Lifecycle:
 * <ul>
 *   <li>{@code SessionService.create(...)} → calls {@link #createForSession}
 *       after persisting the row.</li>
 *   <li>{@code SessionService.close(...)} → calls {@link #deleteForSession}
 *       so orphan topics don't accumulate.</li>
 *   <li>Both operations are best-effort; the in-process SSE sinks remain
 *       the gameplay-driving path, so a topic-create failure logs +
 *       continues. The audit pipeline ({@link GameAuditPublisher}) still
 *       gets the session-lifecycle event via the global {@code state_audit}
 *       topic.</li>
 * </ul>
 *
 * <p>Topic config:
 * <ul>
 *   <li>1 partition (per-session ordering is preserved without
 *       partition-key gymnastics).</li>
 *   <li>1 replica in the single-broker dev cluster; the prod overlay
 *       overrides {@code app.kafka.session-topic.replication-factor}.</li>
 *   <li>6 h retention — sessions outlive an SSE reconnect but rarely
 *       a multi-hour outage, so a short retention prevents disk creep.</li>
 *   <li>{@code cleanup.policy=delete} — straightforward time-based
 *       prune; we don't want compaction's "last write per key" since
 *       every event is unique by event_id.</li>
 * </ul>
 *
 * <p>Bean gated by {@code app.kafka.enabled} so vanilla compose-up
 * (without the Kafka container) doesn't try to construct an AdminClient.
 */
@Service
@ConditionalOnProperty(name = "app.kafka.enabled", havingValue = "true")
public class SessionTopicManager {

    private static final Logger log = LoggerFactory.getLogger(SessionTopicManager.class);
    private static final String TOPIC_PREFIX = "session.";
    private static final String TOPIC_SUFFIX = ".events";

    private final AdminClient admin;
    private final int partitions;
    private final short replicationFactor;
    private final long retentionMs;

    public SessionTopicManager(
            AdminClient sessionTopicAdminClient,
            @Value("${app.kafka.session-topic.partitions:1}")        int partitions,
            @Value("${app.kafka.session-topic.replication-factor:1}") short replicationFactor,
            @Value("${app.kafka.session-topic.retention-ms:21600000}") long retentionMs) {
        this.admin = sessionTopicAdminClient;
        this.partitions = partitions;
        this.replicationFactor = replicationFactor;
        this.retentionMs = retentionMs;
        log.info("[session-topics] manager ready → partitions={} rf={} retention={}ms",
                partitions, replicationFactor, retentionMs);
    }

    /** Topic name for a session id. Public so consumers can construct
     *  it without re-deriving the convention. */
    public static String topicFor(String sessionId) {
        return TOPIC_PREFIX + sessionId + TOPIC_SUFFIX;
    }

    /**
     * Create the per-session topic. Idempotent — already-exists returns
     * without error so an admin call from a follower pod doesn't 500.
     * Returns the topic name on success, {@code null} on failure (the
     * caller logs at INFO level and proceeds; gameplay isn't blocked).
     */
    public String createForSession(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) return null;
        String topic = topicFor(sessionId);
        Map<String, String> config = new HashMap<>();
        config.put(TopicConfig.RETENTION_MS_CONFIG, Long.toString(retentionMs));
        config.put(TopicConfig.CLEANUP_POLICY_CONFIG, TopicConfig.CLEANUP_POLICY_DELETE);
        NewTopic newTopic = new NewTopic(topic, partitions, replicationFactor)
                .configs(config);
        try {
            admin.createTopics(Collections.singleton(newTopic))
                    .all().get(5, TimeUnit.SECONDS);
            log.info("[session-topics] created {}", topic);
            return topic;
        } catch (ExecutionException ee) {
            if (ee.getCause() instanceof TopicExistsException) {
                // Created by another pod — that's fine, idempotent.
                return topic;
            }
            log.warn("[session-topics] create failed for {}: {}", topic, ee.getMessage());
            return null;
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            return null;
        } catch (java.util.concurrent.TimeoutException te) {
            log.warn("[session-topics] create timed out for {}", topic);
            return null;
        }
    }

    /**
     * Delete the per-session topic. Called from {@code SessionService}'s
     * close path so the broker doesn't accumulate dead topics. Best-effort;
     * a failure here just means a stale topic lingers until manual
     * cleanup or until the broker's tombstone retention kicks in.
     */
    public void deleteForSession(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) return;
        String topic = topicFor(sessionId);
        try {
            admin.deleteTopics(Collections.singleton(topic))
                    .all().get(5, TimeUnit.SECONDS);
            log.info("[session-topics] deleted {}", topic);
        } catch (Exception ex) {
            log.warn("[session-topics] delete failed for {}: {}", topic, ex.getMessage());
        }
    }

    /** Configures the AdminClient bean used by the manager above. */
    @Configuration
    @ConditionalOnProperty(name = "app.kafka.enabled", havingValue = "true")
    public static class AdminConfig {
        @Bean(destroyMethod = "close")
        public AdminClient sessionTopicAdminClient(
                @Value("${app.kafka.bootstrap-servers:kafka:9092}") String bootstrap,
                @Value("${app.kafka.client-id:snapdragon-bff}")     String clientId) {
            Map<String, Object> cfg = new HashMap<>();
            cfg.put(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrap);
            cfg.put(AdminClientConfig.CLIENT_ID_CONFIG, clientId + "-admin");
            return AdminClient.create(cfg);
        }
    }
}
