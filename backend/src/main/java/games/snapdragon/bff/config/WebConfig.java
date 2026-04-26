package games.snapdragon.bff.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.reactive.CorsWebFilter;
import org.springframework.web.cors.reactive.UrlBasedCorsConfigurationSource;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Stream;

/**
 * CORS allowlist for the Snapdragon Games portal.
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
 * so {@code https://*.snapdragon.games} matches every game subdomain
 * without listing them one by one.
 */
@Configuration
public class WebConfig {

    /**
     * Comma-separated list of additional origin patterns. Empty in dev.
     * Set by compose.staging.yml / compose.prod.yml to the *.snapdragon.games
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
                // Snapdragon Games — apex + wildcard subdomain on both
                // staging and production. The compose overlays narrow
                // these via spring.web.cors.allowed-origins for stricter
                // posture; they're listed here so a fresh dev run still
                // works when an engineer points their local browser at
                // a deployed BFF for debugging.
                "https://snapdragon.games",
                "https://*.snapdragon.games",
                "https://staging.snapdragon.games",
                "https://*.staging.snapdragon.games"
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
        return new CorsWebFilter(source);
    }
}
