package games.hugmesh.bff.audit;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link KafkaSessionSubscriber}. No EmbeddedKafka — we
 * call {@code onGameEvent} directly with hand-rolled {@link ConsumerRecord}s
 * so the test exercises the filter + dispatch logic without spinning up
 * a broker. The Kafka-side integration (consumer group + partition
 * assignment) is covered by the production wiring, not this unit test.
 */
class KafkaSessionSubscriberTest {

    private record Dispatched(String channel, String sessionId, String payload) {}

    @Test
    void skipsSelfPublishedEvents() {
        KafkaSessionSubscriber sub = new KafkaSessionSubscriber(new PodIdentity.PodId("pod-A"));
        List<Dispatched> dispatched = new ArrayList<>();
        sub.registerHandler("movement",
                (sid, json) -> dispatched.add(new Dispatched("movement", sid, json)));

        // pod_id matches this pod — should be skipped, NOT dispatched.
        sub.onGameEvent(recordWith("""
            {"pod_id":"pod-A","channel":"movement","session_id":"sess-1","payload_json":"{\\"x\\":1}"}"""));

        assertThat(sub.receivedCount()).isEqualTo(1L);
        assertThat(sub.selfSkippedCount()).isEqualTo(1L);
        assertThat(sub.dispatchedCount()).isEqualTo(0L);
        assertThat(dispatched).isEmpty();
    }

    @Test
    void dispatchesEventsFromOtherPods() {
        KafkaSessionSubscriber sub = new KafkaSessionSubscriber(new PodIdentity.PodId("pod-A"));
        List<Dispatched> dispatched = new ArrayList<>();
        sub.registerHandler("movement",
                (sid, json) -> dispatched.add(new Dispatched("movement", sid, json)));

        // pod_id is a different pod — should dispatch.
        sub.onGameEvent(recordWith("""
            {"pod_id":"pod-B","channel":"movement","session_id":"sess-1","payload_json":"{\\"x\\":1}"}"""));

        assertThat(sub.dispatchedCount()).isEqualTo(1L);
        assertThat(dispatched).hasSize(1);
        assertThat(dispatched.get(0).channel()).isEqualTo("movement");
        assertThat(dispatched.get(0).sessionId()).isEqualTo("sess-1");
        assertThat(dispatched.get(0).payload()).contains("\"x\":1");
    }

    @Test
    void countsUnhandledChannel() {
        KafkaSessionSubscriber sub = new KafkaSessionSubscriber(new PodIdentity.PodId("pod-A"));
        // No handler registered for any channel.

        sub.onGameEvent(recordWith("""
            {"pod_id":"pod-B","channel":"movement","session_id":"sess-1","payload_json":"{}"}"""));

        assertThat(sub.unhandledCount()).isEqualTo(1L);
        assertThat(sub.dispatchedCount()).isEqualTo(0L);
    }

    @Test
    void registerHandlerReplacesPrevious() {
        KafkaSessionSubscriber sub = new KafkaSessionSubscriber(new PodIdentity.PodId("pod-A"));
        List<String> first = new ArrayList<>();
        List<String> second = new ArrayList<>();

        sub.registerHandler("movement", (sid, json) -> first.add(sid));
        sub.registerHandler("movement", (sid, json) -> second.add(sid));

        sub.onGameEvent(recordWith("""
            {"pod_id":"pod-B","channel":"movement","session_id":"sess-X","payload_json":"{}"}"""));

        assertThat(first).isEmpty();
        assertThat(second).containsExactly("sess-X");
    }

    @Test
    void emptyPodIdInRecordDispatches() {
        // pod_id missing or empty should NOT match this pod's id, so
        // the event dispatches normally. This covers a producer that
        // pre-dates the pod_id stamp.
        KafkaSessionSubscriber sub = new KafkaSessionSubscriber(new PodIdentity.PodId("pod-A"));
        List<Dispatched> dispatched = new ArrayList<>();
        sub.registerHandler("scene", (sid, json) -> dispatched.add(new Dispatched("scene", sid, json)));

        sub.onGameEvent(recordWith("""
            {"channel":"scene","session_id":"","payload_json":"{\\"type\\":\\"spawn\\"}"}"""));

        assertThat(sub.selfSkippedCount()).isEqualTo(0L);
        assertThat(sub.dispatchedCount()).isEqualTo(1L);
        assertThat(dispatched).hasSize(1);
    }

    @Test
    void malformedJsonDoesNotBubbleUp() {
        KafkaSessionSubscriber sub = new KafkaSessionSubscriber(new PodIdentity.PodId("pod-A"));
        // Deliberately broken JSON. The subscriber's whole-record
        // try-catch swallows + logs; nothing should propagate.
        sub.onGameEvent(recordWith("not-json"));
        assertThat(sub.receivedCount()).isEqualTo(1L);
        // Doesn't matter whether it counts as unhandled or dropped;
        // the contract is "no exception bubbles up."
    }

    // ── helpers ───────────────────────────────────────────────────────────

    private static ConsumerRecord<String, String> recordWith(String value) {
        return new ConsumerRecord<>("game_events", 0, 0L, "", value);
    }
}
