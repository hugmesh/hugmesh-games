package games.hugmesh.bff.controller;

import games.hugmesh.bff.audit.GameKpiPublisher;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * F2 federated-game telemetry endpoint (decision 021).
 *
 * <p>Federated game remotes — running inside the WASM iframe — POST
 * batched KPI samples here. Each entry lands as one row in the
 * ClickHouse {@code game_kpi} table via the Kafka pipeline:
 *
 * <pre>
 *   iframe → fetch('/api/telemetry/game/&lt;slug&gt;') → BFF → Kafka topic game_kpi
 *                                                              → ClickHouse Kafka-engine table
 *                                                              → MaterializedView → game_kpi (MergeTree)
 * </pre>
 *
 * <p>Collection model: the federated remote drives the request rate
 * itself (typically one POST per 30 s with a batched array of samples).
 * Per the infra decision, iframes call this endpoint directly rather
 * than relaying through the host shell via {@code postMessage} —
 * skips one hop, simpler control over rate, fewer moving parts. The
 * federated remote's origin is already in {@code WebConfig}'s
 * {@code /api/**} CORS allowlist (covers {@code https://*.hugmesh.games}
 * + the dev localhost patterns).
 *
 * <p>The endpoint is gated by the same {@code app.kafka.enabled} flag
 * as the publisher itself; when Kafka is disabled, the endpoint is
 * absent and POSTs return 404.
 */
@Tag(name = "Telemetry",
     description = "Federated-game KPI ingest. Iframes call this directly to drop frame-time / scene-load / error-rate samples; the BFF batches into the game_kpi Kafka topic which ClickHouse aggregates for the admin dashboard.")
@RestController
@RequestMapping("/api/telemetry")
@org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(
        name = "app.kafka.enabled", havingValue = "true")
public class TelemetryController {

    private static final Logger log = LoggerFactory.getLogger(TelemetryController.class);
    private static final int MAX_BATCH = 100;

    private final GameKpiPublisher publisher;

    public TelemetryController(GameKpiPublisher publisher) {
        this.publisher = publisher;
    }

    /** One KPI sample. */
    public record KpiEntry(
            String  name,
            double  value,
            Map<String, Object> meta) {}

    /** Batched POST body. */
    public record KpiBatch(
            List<KpiEntry> entries) {}

    @Operation(
        summary = "Drop a batch of KPI samples for a game",
        description = """
            Iframes call this from the federated-game remote with a small
            batch of samples (typically every 30 s). Each entry becomes
            one row in the ClickHouse `game_kpi` table; the admin
            dashboard's `kpi_rolling_avg_24h` view aggregates them.

            Caller is identified by their JWT subject (Casdoor id);
            anonymous calls produce rows with empty `casdoor_id`. The
            CORS allowlist for cross-origin POSTs from federated remote
            origins is in `app.telemetry.cors.allowed-origins`.

            Returns 202 (accepted, async fan-out) regardless of broker
            health — the publisher logs + drops on broker failure since
            telemetry is observability surface, not a transactional
            dependency of any user-facing path.
            """)
    @PostMapping(value = "/game/{slug}", consumes = MediaType.APPLICATION_JSON_VALUE)
    @ResponseStatus(org.springframework.http.HttpStatus.ACCEPTED)
    public Mono<Void> dropKpiBatch(
            @PathVariable String slug,
            @RequestBody KpiBatch body,
            @AuthenticationPrincipal Jwt jwt) {
        String casdoorId = Optional.ofNullable(jwt).map(Jwt::getSubject).orElse(null);
        if (body == null || body.entries() == null || body.entries().isEmpty()) {
            return Mono.empty();
        }
        // Bounded batch to keep one bad caller from flooding the broker.
        // Anything past MAX_BATCH is silently truncated; the caller
        // controls per-request volume.
        int n = Math.min(body.entries().size(), MAX_BATCH);
        for (int i = 0; i < n; i++) {
            KpiEntry e = body.entries().get(i);
            if (e == null || e.name() == null) continue;
            publisher.publish(slug, casdoorId, e.name(), e.value(), e.meta());
        }
        if (body.entries().size() > MAX_BATCH) {
            log.warn("[telemetry] truncated batch from {} to {} entries (slug={}, caller={})",
                    body.entries().size(), MAX_BATCH, slug, casdoorId);
        }
        return Mono.empty();
    }
}
