package games.hugmesh.bff.config;

import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

/**
 * OWASP-recommended response headers applied to every BFF response.
 *
 * <p>nginx fronting the BFF also sets a subset of these (COOP, COEP,
 * CORP, X-Content-Type-Options) but we re-apply them at the
 * application tier so a direct hit on the BFF (curl, e2e harness)
 * still gets the same posture. Idempotent — duplicate values from
 * nginx + BFF are de-duplicated by the browser.
 *
 * <p>What each one does:
 * <ul>
 *   <li><b>Strict-Transport-Security</b> — opt into HSTS so browsers
 *       refuse plaintext for the next year. Safe in dev because the
 *       header only matters once the user has visited via HTTPS.</li>
 *   <li><b>X-Content-Type-Options: nosniff</b> — disables the
 *       legacy MIME-sniffing fallback that's caused stored-XSS via
 *       e.g. user-uploaded SVG.</li>
 *   <li><b>X-Frame-Options: DENY</b> — UI redress / clickjacking
 *       defence. The BFF NEVER serves embeddable HTML; the WASM
 *       iframes load from wasm-fragment, not the BFF.</li>
 *   <li><b>Referrer-Policy: strict-origin-when-cross-origin</b> —
 *       limits Referer leak across origins to the bare scheme+host.</li>
 *   <li><b>Permissions-Policy</b> — opt out of every browser feature
 *       the BFF doesn't need so a future XSS can't enable a
 *       camera / microphone / geolocation prompt.</li>
 *   <li><b>Cross-Origin-Resource-Policy: same-site</b> — restricts
 *       embedding of BFF responses to documents on the same site.
 *       The wasm-fragment proxy overrides with cross-origin where it
 *       actually needs to be embeddable.</li>
 * </ul>
 *
 * <p>Order is HIGHEST_PRECEDENCE+10 so the headers are added before
 * any application-level filter (e.g. BanGateFilter) gets a chance
 * to short-circuit the response and skip the chain.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class SecurityHeadersFilter implements WebFilter {

    private static final String HSTS_VALUE =
            "max-age=31536000; includeSubDomains";

    private static final String PERMISSIONS_VALUE = String.join(", ",
            "accelerometer=()",
            "ambient-light-sensor=()",
            "autoplay=(self)",
            "battery=()",
            "camera=()",
            "display-capture=()",
            "document-domain=()",
            "encrypted-media=()",
            "fullscreen=(self)",
            "geolocation=()",
            "gyroscope=()",
            "magnetometer=()",
            "microphone=()",
            "midi=()",
            "payment=()",
            "picture-in-picture=()",
            "publickey-credentials-get=()",
            "screen-wake-lock=()",
            "sync-xhr=()",
            "usb=()",
            "xr-spatial-tracking=()"
    );

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        HttpHeaders h = exchange.getResponse().getHeaders();
        // Don't overwrite headers nginx already set — addIfAbsent
        // semantics via setIfAbsent().
        addIfAbsent(h, "Strict-Transport-Security",        HSTS_VALUE);
        addIfAbsent(h, "X-Content-Type-Options",           "nosniff");
        addIfAbsent(h, "X-Frame-Options",                  "DENY");
        addIfAbsent(h, "Referrer-Policy",                  "strict-origin-when-cross-origin");
        addIfAbsent(h, "Permissions-Policy",               PERMISSIONS_VALUE);
        addIfAbsent(h, "Cross-Origin-Resource-Policy",     "same-site");
        addIfAbsent(h, "Cross-Origin-Opener-Policy",       "same-origin");
        addIfAbsent(h, "Cross-Origin-Embedder-Policy",     "require-corp");
        return chain.filter(exchange);
    }

    private static void addIfAbsent(HttpHeaders headers, String name, String value) {
        if (!headers.containsHeader(name)) {
            headers.set(name, value);
        }
    }
}
