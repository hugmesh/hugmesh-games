package games.snapdragon.bff.audit;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Mirror of {@link GameAuditPublisherTest} for the KPI publisher.
 * Same lambda-based KafkaSender pattern so tests don't have to mock
 * KafkaTemplate.
 */
class GameKpiPublisherTest {

    private record Sent(String key, String json) {}

    @Test
    void publish_sendsKeyedByGameSlug() {
        List<Sent> sent = new ArrayList<>();
        GameKpiPublisher publisher = new GameKpiPublisher(
                (key, json) -> {
                    sent.add(new Sent(key, json));
                    return CompletableFuture.completedFuture(null);
                },
                "game_kpi");

        publisher.publish("three-wizards", "casdoor-1", "frame_time_ms",
                16.7, Map.of("renderer", "wasm"));

        assertThat(sent).hasSize(1);
        assertThat(sent.get(0).key()).isEqualTo("three-wizards");
        String json = sent.get(0).json();
        assertThat(json).contains("\"game_slug\":\"three-wizards\"");
        assertThat(json).contains("\"kpi_name\":\"frame_time_ms\"");
        assertThat(json).contains("\"kpi_value\":16.7");
        assertThat(json).contains("\\\"renderer\\\":\\\"wasm\\\"");
        assertThat(publisher.emittedCount()).isEqualTo(1L);
    }

    @Test
    void publish_droppedOnSyncSenderException() {
        GameKpiPublisher publisher = new GameKpiPublisher(
                (key, json) -> { throw new RuntimeException("unreachable"); },
                "game_kpi");

        publisher.publish("dodecahedron", null, "scene_load_ms", 312.0, null);

        assertThat(publisher.droppedCount()).isEqualTo(1L);
        assertThat(publisher.emittedCount()).isEqualTo(0L);
    }

    @Test
    void publish_droppedOnAsyncBrokerError() {
        GameKpiPublisher publisher = new GameKpiPublisher(
                (key, json) -> {
                    CompletableFuture<Object> failed = new CompletableFuture<>();
                    failed.completeExceptionally(new RuntimeException("acks=all timeout"));
                    return failed;
                },
                "game_kpi");

        publisher.publish("three-wizards", null, "error_rate", 0.02, Map.of());

        assertThat(publisher.droppedCount()).isEqualTo(1L);
        assertThat(publisher.emittedCount()).isEqualTo(0L);
    }

    @Test
    void publish_emptyMetaCollapsesToEmptyObject() {
        List<Sent> sent = new ArrayList<>();
        GameKpiPublisher publisher = new GameKpiPublisher(
                (key, json) -> { sent.add(new Sent(key, json)); return CompletableFuture.completedFuture(null); },
                "game_kpi");

        publisher.publish("three-wizards", null, "fps", 60.0, null);

        assertThat(sent).hasSize(1);
        assertThat(sent.get(0).json()).contains("\"payload_json\":\"{}\"");
    }
}
