package games.snapdragon.bff.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.reactive.CorsWebFilter;
import org.springframework.web.cors.reactive.UrlBasedCorsConfigurationSource;

import java.util.List;

@Configuration
public class WebConfig {

    @Bean
    public CorsWebFilter corsWebFilter() {
        CorsConfiguration config = new CorsConfiguration();
        // Cover every shape this BFF serves browsers from:
        //   • http://localhost:* / http://127.0.0.1:* — `ng serve` dev (proxy.conf.js)
        //   • https://localhost:* / https://127.0.0.1:* — `make e2e` from a host browser
        //   • https://frontend:* — `make e2e` from Playwright inside the compose network
        //   • https://host.docker.internal:* — same-network browser hop on Linux Docker
        config.setAllowedOriginPatterns(List.of(
                "http://localhost:*",  "http://127.0.0.1:*",
                "https://localhost:*", "https://127.0.0.1:*",
                "https://frontend:*",  "https://host.docker.internal:*"
        ));
        config.setAllowedMethods(List.of("GET", "POST", "PUT", "DELETE", "OPTIONS"));
        config.setAllowedHeaders(List.of("*"));
        config.setAllowCredentials(true);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/api/**", config);
        return new CorsWebFilter(source);
    }
}
