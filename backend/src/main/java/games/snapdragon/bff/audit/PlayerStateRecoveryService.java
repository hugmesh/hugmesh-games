package games.snapdragon.bff.audit;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.time.Duration;

/**
 * Recovers a player's most-recent state by reading the ClickHouse
 * {@code snapdragon.player_state_current} ReplacingMergeTree (decision
 * 021). Used by {@link games.snapdragon.bff.service.GameStateService}'s
 * hydrate path when a player reconnects through a pod that didn't
 * originally serve their session — instead of waiting for the
 * Cassandra cache to roll forward or doing a cold Postgres lookup,
 * the BFF pulls the LATEST cross-pod state directly from the analytics
 * warehouse (which has it because every channel emission tees into
 * Kafka → ClickHouse via the player_state_current materialized view).
 *
 * <p>The recovery query:
 * <pre>
 *   SELECT payload_json FROM snapdragon.player_state_current FINAL
 *   WHERE game_slug = '...' AND session_id = '...' AND character_id = '...'
 *     AND channel = 'movement'
 *   LIMIT 1
 * </pre>
 *
 * <p>{@code FINAL} forces a merge of any un-merged ReplacingMergeTree
 * duplicates so the row returned is the freshest. {@code LIMIT 1}
 * because there's exactly one logical row per
 * {@code (game_slug, session_id, character_id, channel)} key.
 *
 * <p>Bean gated by {@code app.clickhouse.enabled} so vanilla compose-up
 * stays light. Callers inject as {@code Optional<PlayerStateRecoveryService>}
 * and fall back to the existing Cassandra → Postgres → defaults chain
 * when the bean is absent.
 */
@Service
@ConditionalOnProperty(name = "app.clickhouse.enabled", havingValue = "true")
public class PlayerStateRecoveryService {

    private static final Logger log = LoggerFactory.getLogger(PlayerStateRecoveryService.class);
    private static final Duration QUERY_TIMEOUT = Duration.ofMillis(500);

    private final WebClient ch;
    private final ObjectMapper mapper = new ObjectMapper();
    private final String table;

    public PlayerStateRecoveryService(
            WebClient clickHouseWebClient,
            @Value("${app.clickhouse.tables.player-state-current:snapdragon.player_state_current}")
                    String table) {
        this.ch = clickHouseWebClient;
        this.table = table;
    }

    /**
     * Recovered state snapshot. Held as a record of optional fields so
     * the caller can opt into "position only" without rebuilding a
     * full {@code PlayerState}.
     */
    public record RecoveredState(
            double posX,
            double posY,
            double posZ,
            double rotationY) {}

    /**
     * Query the warehouse for the most-recent movement payload for
     * {@code (gameSlug, sessionId, characterId)}. Returns
     * {@link Mono#empty()} when no row exists, the ClickHouse call
     * fails, or the payload doesn't have the expected position fields.
     *
     * <p>Best-effort throughout. The caller MUST treat a
     * {@code Mono.empty()} as "fall back to Cassandra / Postgres" —
     * never an error condition. The streaming pipeline is purely
     * additive over the legacy state-cache path.
     */
    public Mono<RecoveredState> recover(String gameSlug,
                                        String sessionId,
                                        long characterId) {
        if (gameSlug == null || gameSlug.isBlank() ||
                sessionId == null || sessionId.isBlank()) {
            return Mono.empty();
        }
        // Hand-rolled string-build for the SQL — the param values pass
        // through ClickHouse's `query_param_*` substitution to avoid
        // injection. The ?query= URL puts the SQL in the query string;
        // ClickHouse reads it from there in addition to the request body.
        String sql = "SELECT payload_json FROM " + table + " FINAL"
                + " WHERE game_slug = {gameSlug:String}"
                + " AND session_id = {sessionId:String}"
                + " AND character_id = {characterId:String}"
                + " AND channel = 'movement'"
                + " LIMIT 1"
                + " FORMAT JSONEachRow";

        return ch.post()
                .uri(uri -> uri.path("/")
                        .queryParam("param_gameSlug",    gameSlug)
                        .queryParam("param_sessionId",   sessionId)
                        .queryParam("param_characterId", String.valueOf(characterId))
                        .build())
                .bodyValue(sql)
                .retrieve()
                .bodyToMono(String.class)
                .timeout(QUERY_TIMEOUT)
                .flatMap(this::parsePosition)
                .onErrorResume(ex -> {
                    log.debug("[state-recovery] ClickHouse recover failed: {}", ex.getMessage());
                    return Mono.empty();
                });
    }

    /**
     * The JSONEachRow response is one JSON object per line. We expect
     * 0 or 1 lines; the single row's {@code payload_json} is a STRING
     * containing the inner JSON the publisher serialised.
     */
    private Mono<RecoveredState> parsePosition(String response) {
        if (response == null || response.isBlank()) return Mono.empty();
        String firstLine = response.split("\\R", 2)[0].trim();
        if (firstLine.isEmpty()) return Mono.empty();
        try {
            JsonNode outer = mapper.readTree(firstLine);
            String payloadJson = outer.path("payload_json").asText("");
            if (payloadJson.isEmpty()) return Mono.empty();
            JsonNode payload = mapper.readTree(payloadJson);
            // The movement payload was emitted via Map.of(..., "x", after.x(), ...) in
            // GameStateService.applyControl; these field names match.
            if (!payload.hasNonNull("x") || !payload.hasNonNull("y") || !payload.hasNonNull("z")) {
                return Mono.empty();
            }
            return Mono.just(new RecoveredState(
                    payload.path("x").asDouble(),
                    payload.path("y").asDouble(),
                    payload.path("z").asDouble(),
                    payload.path("rotationY").asDouble(0.0)));
        } catch (Exception ex) {
            log.debug("[state-recovery] parse failed: {}", ex.getMessage());
            return Mono.empty();
        }
    }
}
