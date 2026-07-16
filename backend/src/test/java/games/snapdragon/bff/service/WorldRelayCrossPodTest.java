package games.snapdragon.bff.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import games.snapdragon.bff.audit.GameEventPublisher;
import games.snapdragon.bff.audit.KafkaSessionSubscriber;
import games.snapdragon.bff.audit.PodIdentity;
import games.snapdragon.bff.model.InputEvent;
import games.snapdragon.bff.repository.PlayerCharacterRepository;
import games.snapdragon.bff.repository.PlayerPositionRepository;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;

/**
 * Cross-pod (Kafka) parity for the web-native host-authoritative world relay
 * (PR #116 blocker 3 — the follow-up the in-pod fix explicitly deferred).
 *
 * <p>The in-pod fix routes a {@code kind:'world'} event's raw payload onto the
 * per-session {@code worldNative} sink so a guest whose SSE landed on THIS pod
 * receives the host's authoritative state. But a guest whose SSE subscription
 * landed on a DIFFERENT pod never sees that in-process emit. The cross-pod leg
 * closes the gap the exact same way the other 8 channels already do (decision
 * 021): {@link GameStateService#emitInput} tees the raw world payload to a
 * dedicated {@code world-native} Kafka channel, and the peer pod's subscriber
 * re-emits it onto ITS {@code worldNative} sink.
 *
 * <p>Two legs are proven here without a broker (the actual Kafka transport is
 * already covered by {@code TwoPodKafkaCrossPodIT}):
 * <ul>
 *   <li><b>Pod A (publish):</b> a {@code kind:'world'} {@code emitInput} publishes
 *       a {@code world-native} envelope carrying ONLY the raw world blob (no
 *       {@code casdoorUserId} leak), while the full event still ships on
 *       {@code input} for the admin firehose; a non-world event publishes no
 *       {@code world-native} row.</li>
 *   <li><b>Pod B (re-emit):</b> a {@code world-native} {@code game_events} row from
 *       a PEER pod reaches the local {@code worldNativeStream}; a row stamped with
 *       THIS pod's id is self-filtered (no double-fire).</li>
 * </ul>
 */
class WorldRelayCrossPodTest {

    private static final String SID = "sess-xpod-1";
    private final ObjectMapper mapper = new ObjectMapper();

    private GameStateService newState(Optional<GameEventPublisher> publisher,
                                      Optional<KafkaSessionSubscriber> subscriber) {
        PlayerCharacterRepository characterRepo = mock(PlayerCharacterRepository.class);
        PlayerPositionRepository positionRepo = mock(PlayerPositionRepository.class);
        GameTimeService gameTimeService = mock(GameTimeService.class);
        PgNotificationService pgNotify = mock(PgNotificationService.class);
        PositionCacheService positionCache = mock(PositionCacheService.class);
        @SuppressWarnings("unchecked")
        ObjectProvider<PositionCacheService> cacheProvider = mock(ObjectProvider.class);
        lenient().when(cacheProvider.getIfAvailable()).thenReturn(positionCache);
        lenient().when(characterRepo.findById((Long) any())).thenReturn(Mono.empty());
        lenient().when(positionRepo.findByCharacterIdAndChapterId(any(), any())).thenReturn(Mono.empty());
        lenient().when(pgNotify.publish(anyString(), anyString())).thenReturn(Mono.empty());
        lenient().when(positionCache.findOne(anyLong(), anyLong())).thenReturn(Mono.empty());
        lenient().when(positionCache.charactersInChapter(anyLong())).thenReturn(Flux.empty());
        return new GameStateService(characterRepo, positionRepo, gameTimeService, pgNotify, cacheProvider,
                publisher, subscriber, Optional.empty());
    }

    private static InputEvent worldEvent(Map<String, Object> payload) {
        return InputEvent.inbound("chess", 1L, SID, "casdoor-host", "world", "state", payload);
    }

    // ── Pod A: emitInput tees the raw world payload to the `world-native` Kafka channel ──

    @Test
    void emitInput_worldKind_publishesRawPayloadOnWorldNativeChannel() throws Exception {
        List<String> rows = new ArrayList<>();
        GameEventPublisher publisher = new GameEventPublisher(
                (key, json) -> { rows.add(json); return CompletableFuture.completedFuture(null); },
                "pod-A", "game_events");
        GameStateService podA = newState(Optional.of(publisher), Optional.empty());

        Map<String, Object> world = Map.of("fen", "8/8/8/8/8/8/8/8 w - - 0 1", "status", "ongoing");
        podA.emitInput(worldEvent(world));

        List<JsonNode> worldNativeRows = new ArrayList<>();
        List<JsonNode> inputRows = new ArrayList<>();
        for (String r : rows) {
            JsonNode node = mapper.readTree(r);
            if ("world-native".equals(node.path("channel").asText())) worldNativeRows.add(node);
            if ("input".equals(node.path("channel").asText())) inputRows.add(node);
        }

        assertThat(worldNativeRows).as("exactly one world-native channel row was published").hasSize(1);
        JsonNode wn = worldNativeRows.get(0);
        assertThat(wn.path("session_id").asText()).isEqualTo(SID);
        // payload_json is the RAW world blob (a Map), NOT the whole InputEvent.
        JsonNode payload = mapper.readTree(wn.path("payload_json").asText());
        assertThat(payload.path("fen").asText()).isEqualTo("8/8/8/8/8/8/8/8 w - - 0 1");
        assertThat(payload.path("status").asText()).isEqualTo("ongoing");
        // Privacy: the guest-facing world-native payload carries no casdoorUserId /
        // kind / type — it is the payload map only, mirroring the in-pod worldNative sink.
        assertThat(wn.path("payload_json").asText())
                .doesNotContain("casdoor-host")
                .doesNotContain("\"kind\"")
                .doesNotContain("\"casdoorUserId\"");
        // Back-compat: the full event still ships on `input` for the admin firehose.
        assertThat(inputRows).as("the input firehose row is still published").hasSize(1);
    }

    @Test
    void emitInput_nonWorldKind_doesNotPublishWorldNativeChannel() throws Exception {
        List<String> rows = new ArrayList<>();
        GameEventPublisher publisher = new GameEventPublisher(
                (key, json) -> { rows.add(json); return CompletableFuture.completedFuture(null); },
                "pod-A", "game_events");
        GameStateService podA = newState(Optional.of(publisher), Optional.empty());

        podA.emitInput(InputEvent.inbound("chess", 1L, SID, "casdoor-guest", "control", "move", Map.of("uci", "e2e4")));

        for (String r : rows) {
            assertThat(mapper.readTree(r).path("channel").asText())
                    .as("a control event must not produce a world-native row")
                    .isNotEqualTo("world-native");
        }
    }

    // ── Pod B: a world-native game_events row from a PEER pod reaches the local worldNative sink ──

    @Test
    void peerPodWorldNativeRow_reachesLocalWorldNativeSink() {
        KafkaSessionSubscriber podB = new KafkaSessionSubscriber(new PodIdentity.PodId("pod-B"));
        GameStateService podBState = newState(Optional.empty(), Optional.of(podB));
        podBState.init(); // @PostConstruct isn't auto-invoked in a unit test — registers the channel handlers.

        String envelope = "{\"pod_id\":\"pod-A\",\"channel\":\"world-native\",\"session_id\":\"" + SID + "\","
                + "\"payload_json\":\"{\\\"fen\\\":\\\"after-move\\\",\\\"status\\\":\\\"ongoing\\\"}\"}";

        StepVerifier.create(podBState.worldNativeStream(SID).next())
                .then(() -> podB.onGameEvent(new ConsumerRecord<>("game_events", 0, 0L, SID, envelope)))
                .assertNext(payload -> {
                    assertThat(payload).isInstanceOf(Map.class);
                    @SuppressWarnings("unchecked")
                    Map<String, Object> m = (Map<String, Object>) payload;
                    assertThat(m).containsEntry("fen", "after-move").containsEntry("status", "ongoing");
                })
                // Bounded so a broken re-emit (nothing reaches worldNative) FAILS FAST here.
                .expectComplete().verify(Duration.ofSeconds(3));
    }

    @Test
    void ownPodWorldNativeRow_isSelfFiltered_notReemitted() {
        // A row stamped with THIS pod's id must be self-filtered (the local sink already
        // received the in-process emit) — it must NOT double-fire onto worldNative.
        KafkaSessionSubscriber podSelf = new KafkaSessionSubscriber(new PodIdentity.PodId("pod-SELF"));
        GameStateService state = newState(Optional.empty(), Optional.of(podSelf));
        state.init();

        String ownEnvelope = "{\"pod_id\":\"pod-SELF\",\"channel\":\"world-native\",\"session_id\":\"" + SID + "\","
                + "\"payload_json\":\"{\\\"fen\\\":\\\"x\\\",\\\"status\\\":\\\"ongoing\\\"}\"}";

        StepVerifier.create(state.worldNativeStream(SID))
                .then(() -> podSelf.onGameEvent(new ConsumerRecord<>("game_events", 0, 0L, SID, ownEnvelope)))
                .expectTimeout(Duration.ofMillis(250))
                .verify();
    }
}
