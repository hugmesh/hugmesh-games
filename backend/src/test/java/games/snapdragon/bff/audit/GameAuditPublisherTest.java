package games.snapdragon.bff.audit;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link GameAuditPublisher}. Uses the lambda-friendly
 * constructor that accepts a {@code KafkaSender} so the test can capture
 * sent (key, json) pairs and decide per-call whether to ack or fail —
 * avoiding Mockito's class-mocking limitation on Spring Kafka's
 * {@code KafkaTemplate}.
 */
class GameAuditPublisherTest {

    /** Records every send call so assertions can pick over the shape. */
    private record Sent(String key, String json) {}

    @Test
    void emit_sendsJsonShapeAndKeysByGameSlug() {
        List<Sent> sent = new ArrayList<>();
        GameAuditPublisher publisher = new GameAuditPublisher(
                (key, json) -> {
                    sent.add(new Sent(key, json));
                    return CompletableFuture.completedFuture(null);
                },
                "state_audit");

        publisher.emit("three-wizards", "sess-A", "casdoor-id-1",
                "session.join", Map.of("slot", 0));

        assertThat(sent).hasSize(1);
        assertThat(sent.get(0).key()).isEqualTo("three-wizards");
        String json = sent.get(0).json();
        assertThat(json).contains("\"event_id\"");
        assertThat(json).contains("\"game_slug\":\"three-wizards\"");
        assertThat(json).contains("\"event_type\":\"session.join\"");
        // payload_json is a STRING-typed column on the ClickHouse side, so
        // the inner object lands escaped inside the parent JSON.
        assertThat(json).contains("\\\"slot\\\":0");
        assertThat(publisher.emittedCount()).isEqualTo(1L);
        assertThat(publisher.droppedCount()).isEqualTo(0L);
    }

    @Test
    void emit_droppedOnSyncSenderException() {
        GameAuditPublisher publisher = new GameAuditPublisher(
                (key, json) -> { throw new RuntimeException("broker unreachable"); },
                "state_audit");

        publisher.emit("dodecahedron", "", null, "session.leave", Map.of());

        assertThat(publisher.droppedCount()).isEqualTo(1L);
        assertThat(publisher.emittedCount()).isEqualTo(0L);
    }

    @Test
    void emit_droppedOnAsyncBrokerError() {
        GameAuditPublisher publisher = new GameAuditPublisher(
                (key, json) -> {
                    CompletableFuture<Object> failed = new CompletableFuture<>();
                    failed.completeExceptionally(new RuntimeException("acks=all timeout"));
                    return failed;
                },
                "state_audit");

        publisher.emit("three-wizards", "sess-Q", "casdoor-1", "chapter.transition",
                Map.of("from", "cave", "to", "field"));

        assertThat(publisher.droppedCount()).isEqualTo(1L);
        assertThat(publisher.emittedCount()).isEqualTo(0L);
    }

    @Test
    void emit_acceptsNullSessionAndCasdoorId() {
        AtomicReference<String> lastJson = new AtomicReference<>();
        GameAuditPublisher publisher = new GameAuditPublisher(
                (key, json) -> { lastJson.set(json); return CompletableFuture.completedFuture(null); },
                "state_audit");

        publisher.emit("three-wizards", null, null, "boot", null);

        assertThat(publisher.emittedCount()).isEqualTo(1L);
        String json = lastJson.get();
        // Nulls collapse to empty strings + empty-object payload, never
        // serialised as literal `null` (the ClickHouse schema's columns
        // are non-nullable strings).
        assertThat(json).contains("\"session_id\":\"\"");
        assertThat(json).contains("\"casdoor_id\":\"\"");
        assertThat(json).contains("\"payload_json\":\"{}\"");
    }
}
