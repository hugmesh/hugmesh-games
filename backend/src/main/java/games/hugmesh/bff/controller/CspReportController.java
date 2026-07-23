package games.hugmesh.bff.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Map;

/**
 * CSP violation report sink (decision 023 follow-up).
 *
 * <p>The {@code Content-Security-Policy} header on the frontend nginx
 * sets a {@code report-to} directive pointing at this endpoint. When
 * a browser refuses to load or execute something that violates the
 * CSP, it POSTs a structured violation report here so we can:
 *
 * <ul>
 *   <li>Detect regressions when a new federated remote needs a
 *       directive token nobody added (e.g. a new fetch origin)</li>
 *   <li>Detect actual exploitation attempts (a real XSS shows up as
 *       hundreds of {@code blocked-uri} entries from the wild
 *       client population)</li>
 *   <li>Drive the path-to-strict-CSP follow-up — tightening tokens
 *       only after the report stream is quiet for that token</li>
 * </ul>
 *
 * <p>The endpoint is intentionally:
 * <ul>
 *   <li><b>Unauthenticated</b> — browsers send these reports
 *       on their own schedule, with no opportunity to attach a JWT
 *       to the navigation that caused the violation. It's allow-
 *       listed in {@code SecurityConfig.PUBLIC_PATHS}.</li>
 *   <li><b>Rate-limited</b> — a XSS firing thousands of violations
 *       could flood the log. The nginx {@code api} zone covers this
 *       same as any other {@code /api/*} call.</li>
 *   <li><b>Log-only</b> — no persistence. The report stream is
 *       inherently spammy and the value is in the aggregate
 *       (visible via standard log-aggregation tooling), not in
 *       single rows. A follow-up can add ClickHouse persistence the
 *       same shape as {@code game_kpi}.</li>
 * </ul>
 *
 * <p>Two browser delivery formats are supported:
 *
 * <ol>
 *   <li>{@code Content-Type: application/csp-report} — legacy
 *       {@code report-uri} format. Payload is
 *       {@code {"csp-report": {...}}}.</li>
 *   <li>{@code Content-Type: application/reports+json} — modern
 *       {@code report-to} / Reporting API format. Payload is a
 *       JSON array of reports.</li>
 * </ol>
 *
 * <p>Both are accepted as opaque maps and logged at WARN; the
 * structural difference doesn't matter for the log-only sink.
 */
@Tag(name = "Security",
     description = "CSP violation reports posted directly by the browser when a Content-Security-Policy directive blocks something.")
@RestController
@RequestMapping("/api/security")
public class CspReportController {

    private static final Logger log = LoggerFactory.getLogger(CspReportController.class);

    /** Modern Reporting-API format: an array of report objects. */
    @Operation(
        summary = "Receive a CSP violation report (Reporting API format).",
        description = """
            Posted by the browser when the page's CSP `report-to`
            directive points here and a directive fires. Payload is
            an array of `{ type: "csp-violation", body: { … } }`
            objects. Logged at WARN; no persistence.
            """)
    @PostMapping(value = "/csp-report",
                 consumes = { "application/reports+json", MediaType.APPLICATION_JSON_VALUE })
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public Mono<Void> reportingApi(@RequestBody List<Map<String, Object>> reports) {
        if (reports == null || reports.isEmpty()) {
            return Mono.empty();
        }
        for (Map<String, Object> r : reports) {
            log.warn("[csp-violation] {}", r);
        }
        return Mono.empty();
    }

    /** Legacy report-uri format: a single {csp-report: {...}} envelope. */
    @Operation(
        summary = "Receive a CSP violation report (legacy report-uri format).",
        description = """
            Older browsers / older CSP setups POST a single
            `{"csp-report": {...}}` envelope. Same logging treatment
            as the Reporting-API path; supported so a transition
            period of mixed `report-uri` + `report-to` directives
            still funnels through one endpoint.
            """)
    @PostMapping(value = "/csp-report",
                 consumes = "application/csp-report")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public Mono<Void> legacyReportUri(@RequestBody Map<String, Object> envelope) {
        Object body = envelope.get("csp-report");
        log.warn("[csp-violation legacy] {}", body != null ? body : envelope);
        return Mono.empty();
    }
}
