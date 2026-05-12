package games.snapdragon.bff.audit;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.kafka.test.EmbeddedKafkaBroker;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.test.annotation.DirtiesContext;

import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Two-pod cross-pod Kafka integration test (decision-021 follow-up).
 *
 * <p>Uses {@link EmbeddedKafka} to spin up an in-process Kafka broker
 * and wires two {@link KafkaSessionSubscriber} instances (Pod A +
 * Pod B, each with its own consumer-group + its own pod-id). Pod A
 * publishes a {@code game_events} record stamped with
 * {@code pod_id="pod-A"}; the test asserts:
 *
 * <ol>
 *   <li>Pod B's subscriber receives the message and DISPATCHES it
 *       to the registered channel handler — proving the cross-pod
 *       fan-out path works end-to-end.</li>
 *   <li>Pod A's subscriber also receives the message (own consumer
 *       group, sees its own writes) but SELF-FILTERS by pod_id and
 *       does NOT dispatch — proving the self-loop prevention
 *       prevents Pod A from double-firing into its own sinks.</li>
 * </ol>
 *
 * <p>Two distinct consumer-group IDs is the correct production
 * pattern for cross-pod state sync — each pod needs to see every
 * event, so each pod must be its own consumer group. With a shared
 * group, Kafka would route each event to ONE consumer and the other
 * pods would never see it.
 *
 * <p>Why an IT, not a unit test: the existing
 * {@code KafkaSessionSubscriberTest} drives {@code onGameEvent}
 * directly with hand-rolled {@link ConsumerRecord}s, which covers
 * the filter + dispatch logic but doesn't exercise:
 * <ul>
 *   <li>The actual Kafka consumer group + partition assignment.</li>
 *   <li>The producer-consumer JSON round-trip.</li>
 *   <li>The two-pod self-loop filter under real broker semantics.</li>
 * </ul>
 *
 * <p>Filename ends in {@code IT} so the standard Maven Surefire
 * scan still picks it up in {@code ./mvnw test} (Spring Boot's
 * {@code @SpringBootTest} + {@code @EmbeddedKafka} are well-trodden
 * patterns and don't need failsafe split-out).
 */
@SpringBootTest(classes = TwoPodKafkaCrossPodIT.TestConfig.class)
@EmbeddedKafka(partitions = 1, topics = { "game_events" })
@DirtiesContext
class TwoPodKafkaCrossPodIT {

    /**
     * Minimal Spring config — {@link EmbeddedKafka} needs SOME
     * application context to register the broker bean into. We use
     * an empty config because the test wires its own producer +
     * consumers directly off the broker.
     */
    @org.springframework.context.annotation.Configuration
    static class TestConfig {}

    @Autowired
    private EmbeddedKafkaBroker broker;

    private KafkaProducer<String, String> producer;

    private KafkaSessionSubscriber podA;
    private KafkaSessionSubscriber podB;
    private ConcurrentLinkedQueue<String> podADispatches;
    private ConcurrentLinkedQueue<String> podBDispatches;
    private CountDownLatch podBReceived;

    private KafkaConsumer<String, String> consumerA;
    private KafkaConsumer<String, String> consumerB;
    private Thread pumpA;
    private Thread pumpB;
    private final AtomicBoolean shuttingDown = new AtomicBoolean(false);

    @BeforeEach
    void setUp() {
        producer = new KafkaProducer<>(producerProps());

        podA = new KafkaSessionSubscriber(new PodIdentity.PodId("pod-A"));
        podB = new KafkaSessionSubscriber(new PodIdentity.PodId("pod-B"));
        podADispatches = new ConcurrentLinkedQueue<>();
        podBDispatches = new ConcurrentLinkedQueue<>();
        podBReceived = new CountDownLatch(1);

        podA.registerHandler("movement", (sessionId, payloadJson) ->
                podADispatches.add(sessionId + ":" + payloadJson));
        podB.registerHandler("movement", (sessionId, payloadJson) -> {
            podBDispatches.add(sessionId + ":" + payloadJson);
            podBReceived.countDown();
        });

        consumerA = new KafkaConsumer<>(consumerProps("pod-A-group"));
        consumerB = new KafkaConsumer<>(consumerProps("pod-B-group"));
        consumerA.subscribe(List.of("game_events"));
        consumerB.subscribe(List.of("game_events"));

        pumpA = pump(consumerA, podA);
        pumpB = pump(consumerB, podB);
        pumpA.start();
        pumpB.start();
    }

    @AfterEach
    void tearDown() {
        shuttingDown.set(true);
        try { producer.close(Duration.ofSeconds(1)); } catch (Exception ignored) {}
        try { pumpA.interrupt(); pumpA.join(2_000); } catch (Exception ignored) {}
        try { pumpB.interrupt(); pumpB.join(2_000); } catch (Exception ignored) {}
        try { consumerA.close(Duration.ofSeconds(1)); } catch (Exception ignored) {}
        try { consumerB.close(Duration.ofSeconds(1)); } catch (Exception ignored) {}
    }

    @Test
    void podAEmits_thenPodBDispatchesAndPodASelfFilters() throws Exception {
        String payload = "{\"pod_id\":\"pod-A\",\"channel\":\"movement\","
                       + "\"session_id\":\"sess-99\","
                       + "\"payload_json\":\"{\\\"x\\\":12,\\\"y\\\":34}\"}";

        producer.send(new ProducerRecord<>("game_events", "sess-99", payload)).get(5, TimeUnit.SECONDS);
        producer.flush();

        // Pod B should see + dispatch the event.
        boolean podBSawIt = podBReceived.await(20, TimeUnit.SECONDS);
        assertThat(podBSawIt).as("Pod B never received the cross-pod event from Pod A").isTrue();

        // Allow Pod A's pump a moment to consume + self-filter.
        Thread.sleep(500);

        // Pod B dispatched it.
        assertThat(podBDispatches)
                .as("Pod B dispatched the cross-pod event to its local handler")
                .hasSize(1);
        assertThat(podBDispatches.peek()).contains("sess-99").contains("\"x\":12");

        // Pod A also received it (own consumer group → sees every record)
        // but self-filtered.
        assertThat(podA.receivedCount())
                .as("Pod A consumed the record from its own consumer group")
                .isEqualTo(1L);
        assertThat(podA.selfSkippedCount())
                .as("Pod A's self-loop filter skipped the event it produced")
                .isEqualTo(1L);
        assertThat(podADispatches)
                .as("Pod A did NOT dispatch the event it produced (self-loop)")
                .isEmpty();
    }

    // ── helpers ──────────────────────────────────────────────────────────

    private Properties producerProps() {
        Properties p = new Properties();
        p.put("bootstrap.servers", broker.getBrokersAsString());
        p.put("key.serializer",   StringSerializer.class.getName());
        p.put("value.serializer", StringSerializer.class.getName());
        p.put("acks", "all");
        return p;
    }

    private Map<String, Object> consumerProps(String groupId) {
        Map<String, Object> p = new HashMap<>();
        p.put("bootstrap.servers", broker.getBrokersAsString());
        p.put("group.id", groupId);
        p.put("key.deserializer",   StringDeserializer.class.getName());
        p.put("value.deserializer", StringDeserializer.class.getName());
        p.put("auto.offset.reset", "earliest");
        p.put("enable.auto.commit", "true");
        return p;
    }

    /**
     * Tiny consumer-loop pump that delivers each polled record to the
     * subscriber's {@code onGameEvent} method — same shape as the
     * production Spring {@code @KafkaListener} delivery, just without
     * the Spring container.
     */
    private Thread pump(KafkaConsumer<String, String> consumer, KafkaSessionSubscriber sub) {
        Thread t = new Thread(() -> {
            try {
                while (!shuttingDown.get() && !Thread.currentThread().isInterrupted()) {
                    var records = consumer.poll(Duration.ofMillis(200));
                    for (ConsumerRecord<String, String> r : records) {
                        sub.onGameEvent(r);
                    }
                }
            } catch (org.apache.kafka.common.errors.WakeupException | org.apache.kafka.common.errors.InterruptException ignored) {
                // graceful shutdown
            } catch (RuntimeException e) {
                if (!shuttingDown.get()) throw e;
            }
        }, "kafka-pump-" + sub.toString());
        t.setDaemon(true);
        return t;
    }
}
