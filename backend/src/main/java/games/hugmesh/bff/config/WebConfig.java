package games.hugmesh.bff.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.reactive.CorsUtils;
import org.springframework.web.cors.reactive.CorsWebFilter;
import org.springframework.web.cors.reactive.DefaultCorsProcessor;
import org.springframework.web.cors.reactive.UrlBasedCorsConfigurationSource;
import org.springframework.web.server.ServerWebExchange;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

/**
 * CORS allowlist for the HugMesh Games portal.
 *
 * <p>The default development list covers every shape this BFF serves
 * browsers from in local + e2e:
 * <ul>
 *   <li>{@code http://localhost:*} / {@code http://127.0.0.1:*} — {@code ng serve}</li>
 *   <li>{@code https://localhost:*} / {@code https://127.0.0.1:*} — {@code make e2e}</li>
 *   <li>{@code https://frontend:*} — Playwright inside the compose network</li>
 *   <li>{@code https://host.docker.internal:*} — host-network browser hop</li>
 * </ul>
 *
 * <p>The deployed allowlist is supplied through
 * {@code spring.web.cors.allowed-origins} (or its env override
 * {@code SPRING_WEB_CORS_ALLOWED_ORIGINS}); both staging and production
 * compose overlays set it. The two values are MERGED — production runs
 * never accept localhost, but a staging container set up to debug a
 * production-shaped JWT issuer with a developer's local browser does.
 *
 * <p>Patterns flow through {@link CorsConfiguration#setAllowedOriginPatterns(List)}
 * so {@code https://*.hugmesh.games} matches every game subdomain
 * without listing them one by one.
 *
 * <p>The allowlist is for CROSS-origin callers only. A browser on the mesh calls
 * this BFF same-origin, through the gateway, from whatever name it was served on
 * (the cluster domain, a LAN name, an .onion, {@code make view}). The BFF itself is
 * addressed as {@code http://<service>:8080}, so Spring would count every such call
 * as cross-origin and 403 it; {@link ForwardedSameOriginCorsProcessor} recognises
 * them instead. See that class for why it reads only two forwarded headers.
 */
@Configuration
public class WebConfig {

    /**
     * Jackson 2 (com.fasterxml) ObjectMapper bean.
     *
     * Boot 4 auto-configures Jackson 3 (tools.jackson.*) via JacksonAutoConfiguration;
     * it does NOT register a Jackson 2 ObjectMapper bean. Several services in this
     * application were written against the Jackson 2 API (InputEventController,
     * GameAuditPublisher, GameEventPublisher, GameKpiPublisher, KafkaSessionSubscriber,
     * PlayerStateRecoveryService, ReferenceArenaSim, AuthoritativeTickService).
     * Migrating all of them to Jackson 3 is a separate effort; this bean bridges the
     * gap so Boot 4 spring-wiring succeeds without touching any business logic.
     */
    @Bean
    public ObjectMapper jackson2ObjectMapper() {
        return new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    }

    /**
     * Comma-separated list of additional origin patterns. Empty in dev.
     * Set by compose.staging.yml / compose.prod.yml to the *.hugmesh.games
     * apex + wildcard subdomains.
     */
    @Value("${spring.web.cors.allowed-origins:}")
    private String allowedOriginsCsv;

    @Bean
    public CorsWebFilter corsWebFilter() {
        CorsConfiguration config = new CorsConfiguration();

        List<String> defaults = List.of(
                // Local / e2e
                "http://localhost:*",  "http://127.0.0.1:*",
                "https://localhost:*", "https://127.0.0.1:*",
                "https://frontend:*",  "https://host.docker.internal:*",
                // HugMesh Games — apex + wildcard subdomain on both
                // staging and production. The compose overlays narrow
                // these via spring.web.cors.allowed-origins for stricter
                // posture; they're listed here so a fresh dev run still
                // works when an engineer points their local browser at
                // a deployed BFF for debugging.
                "https://hugmesh.games",
                "https://*.hugmesh.games",
                "https://staging.hugmesh.games",
                "https://*.staging.hugmesh.games"
        );

        List<String> extra = Stream.of(allowedOriginsCsv.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toList();

        List<String> merged = new ArrayList<>(defaults);
        merged.addAll(extra);
        config.setAllowedOriginPatterns(merged);

        config.setAllowedMethods(List.of("GET", "POST", "PUT", "DELETE", "OPTIONS"));
        config.setAllowedHeaders(List.of("*"));
        config.setExposedHeaders(List.of("Location", "Content-Type"));
        config.setAllowCredentials(true);
        config.setMaxAge(3600L);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/api/**", config);
        return new CorsWebFilter(source, new ForwardedSameOriginCorsProcessor());
    }

    /**
     * Lets a browser's same-origin call through the gateway pass as what it is.
     *
     * <p>Without it, pressing Play on a mesh answered 403 ("This game is not available
     * to your account") for every persona: the launch POST carries
     * {@code Origin: https://hugmesh.net}, the BFF sees {@code http://hugmesh-games-bff:8080},
     * and the origin is not on the allowlist. The compose door
     * ({@code https://localhost:8488}) only worked because localhost happens to be.
     *
     * <p>The browser-facing origin is the LEFTMOST {@code X-Forwarded-Proto} token plus the
     * LEFTMOST {@code X-Forwarded-Host} token: the outermost proxy (the TLS front, the
     * ingress) writes those, and the fe-gateway only appends its own hop after them. Two
     * other headers describe that inner hop and are ignored on purpose. This is what the BFF
     * actually receives from the fe-gateway on the compose lane:
     * <pre>
     *   X-Forwarded-Proto: https,http
     *   X-Forwarded-Host:  hugmesh.net,hugmesh.net
     *   X-Forwarded-Port:  80
     *   Forwarded:         proto=http;host=hugmesh.net;for="172.19.0.53:57444"
     * </pre>
     * So {@code server.forward-headers-strategy: framework} would not fix this: Spring
     * prefers {@code Forwarded} and gets {@code http://hugmesh.net}, and without it
     * {@code X-Forwarded-Port} gives {@code https://hugmesh.net:80}. Neither equals the
     * Origin. The host token carries the port whenever the browser used a non-default one,
     * because the front forwards the browser's own {@code Host}.
     *
     * <p>Trusting these headers here does not widen what a cross-origin page can do. A page
     * cannot set {@code X-Forwarded-Host} without a preflight, a preflight is still judged
     * by the allowlist, and the front overwrites the header with the {@code Host} the
     * browser connected to. A cross-origin page therefore still meets the allowlist, as
     * before. The allowlist keeps {@code allowCredentials(true)}, so it must never grow a
     * wildcard to paper over this.
     */
    static final class ForwardedSameOriginCorsProcessor extends DefaultCorsProcessor {

        private static final List<String> VARY = List.of(HttpHeaders.ORIGIN,
                HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, HttpHeaders.ACCESS_CONTROL_REQUEST_HEADERS);

        @Override
        public boolean process(CorsConfiguration config, ServerWebExchange exchange) {
            ServerHttpRequest request = exchange.getRequest();
            if (CorsUtils.isPreFlightRequest(request) || !isForwardedSameOrigin(request.getHeaders())) {
                return super.process(config, exchange);
            }
            // Not a CORS request: answer it like any other (Vary only, no ACAO).
            HttpHeaders out = exchange.getResponse().getHeaders();
            for (String header : VARY) {
                if (!out.getOrEmpty(HttpHeaders.VARY).contains(header)) {
                    out.add(HttpHeaders.VARY, header);
                }
            }
            return true;
        }
    }

    /** True when {@code Origin} names exactly the scheme, host and port the browser was served on. */
    static boolean isForwardedSameOrigin(HttpHeaders headers) {
        String origin = headers.getOrigin();
        String proto = leftmost(headers.getFirst("X-Forwarded-Proto"));
        String host = leftmost(headers.getFirst("X-Forwarded-Host"));
        if (origin == null || proto == null || host == null) {
            return false;
        }
        URI served = parseOrigin(proto + "://" + host);
        URI claimed = parseOrigin(origin);
        return served != null && claimed != null
                && served.getScheme().equals(claimed.getScheme())
                && served.getHost().equalsIgnoreCase(claimed.getHost())
                && port(served) == port(claimed);
    }

    private static String leftmost(String value) {
        if (value == null) {
            return null;
        }
        int comma = value.indexOf(',');
        String first = (comma >= 0 ? value.substring(0, comma) : value).trim();
        return first.isEmpty() ? null : first;
    }

    /** {@code scheme://host[:port]} and nothing else, scheme http or https; null otherwise. */
    private static URI parseOrigin(String value) {
        try {
            URI uri = new URI(value);
            String scheme = uri.getScheme() == null ? null : uri.getScheme().toLowerCase(Locale.ROOT);
            boolean bare = uri.getHost() != null && uri.getRawUserInfo() == null
                    && (uri.getRawPath() == null || uri.getRawPath().isEmpty())
                    && uri.getRawQuery() == null && uri.getRawFragment() == null;
            if (!bare || !("http".equals(scheme) || "https".equals(scheme))) {
                return null;
            }
            return new URI(scheme, null, uri.getHost(), uri.getPort(), null, null, null);
        } catch (URISyntaxException e) {
            return null;
        }
    }

    private static int port(URI uri) {
        return uri.getPort() != -1 ? uri.getPort() : ("https".equals(uri.getScheme()) ? 443 : 80);
    }
}
