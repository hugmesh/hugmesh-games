package games.hugmesh.bff.audit;

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
 * Creates and deletes per-session Kafka topics as sessions are opened and closed
 * (decision 021 streaming telemetry pipeline).
 *
 * <h2>Responsibility</h2>
 *
 * <p>Each multiplayer session gets its own dedicated Kafka topic —
 * {@code session.<sessionId>.events} — so cross-pod consumers can subscribe only to
 * the sessions they serve rather than filtering a global firehose. This reduces consumer
 * fan-out and keeps session-scoped event ordering simple (one partition per session).
 *
 * <h2>Session lifecycle integration</h2>
 *
 * <ul>
 *   <li>{@code SessionService.create(…)} calls {@link #createForSession} after persisting
 *       the session row to Postgres.</li>
 *   <li>{@code SessionService.close(…)} calls {@link #deleteForSession} so orphan topics
 *       do not accumulate on the broker.</li>
 *   <li>Both operations are best-effort. The in-process SSE sinks remain the
 *       gameplay-driving path; a topic-create failure logs and continues.
 *       {@link GameAuditPublisher} still receives the session-lifecycle event via the
 *       global {@code state_audit} topic.</li>
 * </ul>
 *
 * <h2>Topic configuration</h2>
 *
 * <ul>
 *   <li><b>Partitions:</b> 1 (per-session ordering is preserved without
 *       partition-key gymnastics; configurable via
 *       {@code app.kafka.session-topic.partitions}).</li>
 *   <li><b>Replication factor:</b> 1 for the single-broker dev cluster; the prod overlay
 *       overrides {@code app.kafka.session-topic.replication-factor}.</li>
 *   <li><b>Retention:</b> 6 h (21 600 000 ms default); sessions outlive an SSE reconnect
 *       but rarely a multi-hour outage, so a short retention prevents disk creep.
 *       Configurable via {@code app.kafka.session-topic.retention-ms}.</li>
 *   <li><b>Cleanup policy:</b> {@code delete} — straightforward time-based prune.
 *       Compaction's "last write per key" semantics are inappropriate because every
 *       event is unique by {@code event_id}.</li>
 * </ul>
 *
 * <h2>Caller roles</h2>
 *
 * <p>Internal infrastructure component — no direct player/mod/admin API surface. Called
 * only by {@code SessionService} on the session open/close path. Bean gated by
 * {@code app.kafka.enabled} so vanilla compose-up (without the Kafka container) does not
 * attempt to construct an {@code AdminClient}.
 *
 * @see GameEventPublisher
 * @see KafkaProducerConfig
 * @see AdminConfig
 */
@Service
@ConditionalOnProperty(name = "app.kafka.enabled", havingValue = "true")
public class SessionTopicManager {

    private static final Logger log = LoggerFactory.getLogger(SessionTopicManager.class);

    /** Prefix for all per-session topic names. */
    private static final String TOPIC_PREFIX = "session.";

    /** Suffix for all per-session topic names. */
    private static final String TOPIC_SUFFIX = ".events";

    private final AdminClient admin;
    private final int partitions;
    private final short replicationFactor;

    /** Retention duration in milliseconds applied to each created topic. */
    private final long retentionMs;

    // -------------------------------------------------------------------------
    // Construction
    // -------------------------------------------------------------------------

    /**
     * @param sessionTopicAdminClient the Kafka {@code AdminClient} provided by
     *                                {@link AdminConfig#sessionTopicAdminClient}
     * @param partitions              number of partitions per session topic; defaults to 1
     * @param replicationFactor       replication factor; defaults to 1 (single-broker dev)
     * @param retentionMs             topic retention in milliseconds; defaults to 21 600 000
     *                                (6 hours)
     */
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

    // -------------------------------------------------------------------------
    // Topic name convention
    // -------------------------------------------------------------------------

    /**
     * Derives the Kafka topic name for the given session id using the
     * {@code session.<sessionId>.events} convention.
     *
     * <p>Public so consumers can construct the topic name independently without
     * re-deriving the naming convention.
     *
     * @param sessionId the multiplayer session id
     * @return the canonical Kafka topic name for the session
     */
    public static String topicFor(String sessionId) {
        return TOPIC_PREFIX + sessionId + TOPIC_SUFFIX;
    }

    // -------------------------------------------------------------------------
    // Lifecycle operations
    // -------------------------------------------------------------------------

    /**
     * Creates the per-session Kafka topic for the given session id.
     *
     * <p>Idempotent — if the topic already exists (e.g. created by another pod racing
     * on the same session open event), the {@code TopicExistsException} is swallowed and
     * the topic name is returned normally. This prevents a spurious 500 from a follower
     * pod's redundant create call.
     *
     * <p>The admin call blocks with a 5-second timeout. A timeout or broker error logs a
     * warning and returns {@code null}; the caller logs at INFO level and continues —
     * the gameplay path is not blocked by topic-create failures.
     *
     * @param sessionId the multiplayer session id; {@code null} or blank is a no-op
     *                  and returns {@code null}
     * @return the topic name on success, or {@code null} if the topic could not be
     *         created (failure is non-fatal)
     */
    public String createForSession(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) return null;
        String topic = topicFor(sessionId);
        Map<String, String> config = new HashMap<>();
        // Apply the configured retention and a time-based cleanup policy; each event
        // carries a unique event_id so compaction would be counterproductive.
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
            // Restore the interrupted status and return null so the caller can decide
            // whether to retry; the gameplay path is not blocked.
            Thread.currentThread().interrupt();
            return null;
        } catch (java.util.concurrent.TimeoutException te) {
            log.warn("[session-topics] create timed out for {}", topic);
            return null;
        }
    }

    /**
     * Deletes the per-session Kafka topic for the given session id.
     *
     * <p>Called from {@code SessionService}'s close path so the broker does not
     * accumulate dead topics after sessions end. Best-effort — a failure here means
     * a stale topic lingers until manual cleanup or the broker's tombstone retention
     * expires it. The close path is not blocked by topic-delete failures.
     *
     * @param sessionId the multiplayer session id; {@code null} or blank is a no-op
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

    // -------------------------------------------------------------------------
    // AdminClient configuration
    // -------------------------------------------------------------------------

    /**
     * Inner {@code @Configuration} that registers the {@code AdminClient} bean used
     * by {@link SessionTopicManager}. Nested here because the AdminClient is only
     * needed by this manager; keeping it co-located avoids a separate config class for
     * a single bean.
     *
     * <p>The bean's {@code destroyMethod="close"} ensures the AdminClient's
     * network resources are released cleanly on application shutdown.
     */
    @Configuration
    @ConditionalOnProperty(name = "app.kafka.enabled", havingValue = "true")
    public static class AdminConfig {

        /**
         * Creates the Kafka {@code AdminClient} used by {@link SessionTopicManager}
         * to create and delete per-session topics.
         *
         * @param bootstrap the Kafka bootstrap servers; defaults to {@code kafka:9092}
         * @param clientId  base client id; {@code -admin} suffix appended to distinguish
         *                  the admin client from the producer client
         * @return a configured {@code AdminClient}; closed automatically on context shutdown
         */
        @Bean(destroyMethod = "close")
        public AdminClient sessionTopicAdminClient(
                @Value("${app.kafka.bootstrap-servers:kafka:9092}") String bootstrap,
                @Value("${app.kafka.client-id:hugmesh-bff}")     String clientId) {
            Map<String, Object> cfg = new HashMap<>();
            cfg.put(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrap);
            cfg.put(AdminClientConfig.CLIENT_ID_CONFIG, clientId + "-admin");
            return AdminClient.create(cfg);
        }
    }
}
