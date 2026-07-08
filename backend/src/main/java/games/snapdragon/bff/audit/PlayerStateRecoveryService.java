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
 * Recovers a player's most-recent movement state from the ClickHouse analytics warehouse
 * after a pod restart (decision 021 streaming telemetry pipeline — cross-pod state
 * recovery leg).
 *
 * <h2>Responsibility</h2>
 *
 * <p>Used by {@code GameStateService}'s hydrate path when a player reconnects through a
 * pod that did not originally serve their session. Instead of waiting for the Cassandra
 * cache to roll forward or performing a cold Postgres lookup, the BFF queries the
 * {@code snapdragon.player_state_current} ReplacingMergeTree table in ClickHouse. That
 * table has up-to-date state because every channel emission in {@link GameEventPublisher}
 * tees into Kafka, and ClickHouse materialises the rows via the
 * {@code player_state_current} materialised view.
 *
 * <h2>Recovery query</h2>
 *
 * <pre>
 *   SELECT payload_json FROM snapdragon.player_state_current FINAL
 *   WHERE game_slug = '...' AND session_id = '...' AND character_id = '...'
 *     AND channel = 'movement'
 *   LIMIT 1
 * </pre>
 *
 * <p>{@code FINAL} forces a merge of any un-merged ReplacingMergeTree duplicates so the
 * row returned is always the freshest version. {@code LIMIT 1} is sufficient because
 * there is exactly one logical row per
 * {@code (game_slug, session_id, character_id, channel)} key.
 *
 * <p>SQL parameter values pass through ClickHouse's {@code query_param_*} URL substitution
 * to avoid injection. The query is sent as a {@code POST} body with parameters on the
 * query string, which is ClickHouse's standard HTTP API shape.
 *
 * <h2>Reactive / threading model</h2>
 *
 * <p>Fully non-blocking: the {@link WebClient} call is reactive throughout. The 500 ms
 * timeout ({@link #QUERY_TIMEOUT}) ensures that a slow or unreachable ClickHouse instance
 * never blocks the hydrate path for more than half a second. All errors are caught by
 * {@code onErrorResume} and collapsed to {@code Mono.empty()}.
 *
 * <h2>Failure model</h2>
 *
 * <p>Best-effort throughout. Callers MUST treat a {@code Mono.empty()} result as "fall
 * back to Cassandra / Postgres / defaults" — it is never an error condition. The streaming
 * pipeline is purely additive over the legacy state-cache path.
 *
 * <h2>Caller roles</h2>
 *
 * <p>Internal infrastructure component — no direct player/mod/admin API surface. Callers
 * (e.g. {@code GameStateService}) inject as {@code Optional<PlayerStateRecoveryService>}
 * and fall back to the existing chain when the bean is absent. Bean gated by
 * {@code app.clickhouse.enabled} (default {@code false}) so vanilla compose-up stays
 * light.
 *
 * @see GameEventPublisher
 * @see ClickHouseClientConfig
 */
@Service
@ConditionalOnProperty(name = "app.clickhouse.enabled", havingValue = "true")
public class PlayerStateRecoveryService {

    private static final Logger log = LoggerFactory.getLogger(PlayerStateRecoveryService.class);

    /**
     * Maximum time to wait for a ClickHouse response. A slow warehouse must never
     * block the player reconnect hydrate path.
     */
    private static final Duration QUERY_TIMEOUT = Duration.ofMillis(500);

    /** WebClient pre-configured with the ClickHouse HTTP endpoint, credentials, and database. */
    private final WebClient ch;
    private final ObjectMapper mapper = new ObjectMapper();

    /** Fully-qualified table name, e.g. {@code snapdragon.player_state_current}. */
    private final String table;

    // -------------------------------------------------------------------------
    // Construction
    // -------------------------------------------------------------------------

    /**
     * @param clickHouseWebClient the pre-configured ClickHouse HTTP client from
     *                            {@link ClickHouseClientConfig#clickHouseWebClient}
     * @param table               the fully-qualified ReplacingMergeTree table name;
     *                            defaults to {@code snapdragon.player_state_current}
     *                            via {@code app.clickhouse.tables.player-state-current}
     */
    public PlayerStateRecoveryService(
            WebClient clickHouseWebClient,
            @Value("${app.clickhouse.tables.player-state-current:snapdragon.player_state_current}")
                    String table) {
        this.ch = clickHouseWebClient;
        this.table = table;
    }

    // -------------------------------------------------------------------------
    // Result type
    // -------------------------------------------------------------------------

    /**
     * Snapshot of a player's last known movement state recovered from ClickHouse.
     * Held as a record of optional-precision fields so callers can opt into
     * "position only" without rebuilding a full {@code PlayerState}.
     *
     * @param posX      last known X-axis world position
     * @param posY      last known Y-axis world position
     * @param posZ      last known Z-axis world position
     * @param rotationY last known Y-axis rotation (yaw); {@code 0.0} when absent
     */
    public record RecoveredState(
            double posX,
            double posY,
            double posZ,
            double rotationY) {}

    // -------------------------------------------------------------------------
    // Public recovery API
    // -------------------------------------------------------------------------

    /**
     * Queries the warehouse for the most-recent {@code movement} channel payload for
     * the given {@code (gameSlug, sessionId, characterId)} key.
     *
     * <p>Returns {@link Mono#empty()} when:
     * <ul>
     *   <li>any of the required parameters are null or blank,</li>
     *   <li>no matching row exists in {@code player_state_current},</li>
     *   <li>the ClickHouse HTTP call fails or times out, or</li>
     *   <li>the returned payload does not contain the expected position fields
     *       ({@code x}, {@code y}, {@code z}).</li>
     * </ul>
     *
     * <p>Callers MUST treat {@code Mono.empty()} as a signal to fall back to the
     * Cassandra / Postgres / defaults chain. This is never an error condition.
     *
     * @param gameSlug    the game slug to scope the query to; must be non-null and non-blank
     * @param sessionId   the multiplayer session id; must be non-null and non-blank
     * @param characterId the character id whose state to recover
     * @return a {@code Mono} emitting the recovered position snapshot, or empty if the
     *         state is unavailable or unparseable
     */
    public Mono<RecoveredState> recover(String gameSlug,
                                        String sessionId,
                                        long characterId) {
        // Guard: required parameters — null/blank means there is no state to recover.
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
                // Enforce the 500 ms SLA — a slow warehouse must not stall reconnect hydration.
                .timeout(QUERY_TIMEOUT)
                // Parse the JSONEachRow response body into a RecoveredState.
                .flatMap(this::parsePosition)
                .onErrorResume(ex -> {
                    // Any error (timeout, HTTP error, parse failure bubbling from flatMap) is
                    // treated as "state unavailable" — fall back to the legacy recovery path.
                    log.debug("[state-recovery] ClickHouse recover failed: {}", ex.getMessage());
                    return Mono.empty();
                });
    }

    // -------------------------------------------------------------------------
    // Response parsing
    // -------------------------------------------------------------------------

    /**
     * Parses the ClickHouse {@code JSONEachRow} response body into a {@link RecoveredState}.
     *
     * <p>The response is one JSON object per line. Exactly 0 or 1 lines are expected:
     * 0 lines means no state is stored, 1 line contains the outer row object whose
     * {@code payload_json} field is a string holding the inner movement JSON originally
     * serialised by {@code GameStateService.applyControl}.
     *
     * <p>Returns {@link Mono#empty()} if the response is blank, the first line is empty,
     * the outer row lacks a {@code payload_json} field, the inner payload lacks the
     * required position fields ({@code x}, {@code y}, {@code z}), or any parse step
     * throws.
     *
     * @param response the raw HTTP response body from ClickHouse
     * @return a {@code Mono} emitting the parsed state, or empty if unavailable
     */
    private Mono<RecoveredState> parsePosition(String response) {
        if (response == null || response.isBlank()) return Mono.empty();
        // JSONEachRow: one JSON object per line — take only the first line.
        String firstLine = response.split("\\R", 2)[0].trim();
        if (firstLine.isEmpty()) return Mono.empty();
        // fromCallable wraps checked Jackson exceptions as onError signals so the
        // reactive chain handles them without a try/catch inside the lambda body.
        return Mono.fromCallable(() -> {
                    JsonNode outer = mapper.readTree(firstLine);
                    String payloadJson = outer.path("payload_json").asText("");
                    if (payloadJson.isEmpty()) return null;
                    JsonNode payload = mapper.readTree(payloadJson);
                    // The movement payload was emitted via Map.of(..., "x", after.x(), ...) in
                    // GameStateService.applyControl; these field names match.
                    if (!payload.hasNonNull("x") || !payload.hasNonNull("y") || !payload.hasNonNull("z")) {
                        return null;
                    }
                    return new RecoveredState(
                            payload.path("x").asDouble(),
                            payload.path("y").asDouble(),
                            payload.path("z").asDouble(),
                            payload.path("rotationY").asDouble(0.0));
                })
                // null return from fromCallable means no data — filter to empty
                .flatMap(state -> state == null ? Mono.empty() : Mono.just(state))
                // log parse failures and fall back to empty so the recovery caller
                // treats them the same as "no row found"
                .doOnError(ex -> log.debug("[state-recovery] parse failed: {}", ex.getMessage()))
                .onErrorResume(ex -> Mono.empty());
    }
}
