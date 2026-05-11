package games.snapdragon.rpgbuilder.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.convert.converter.Converter;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.config.annotation.method.configuration.EnableReactiveMethodSecurity;
import org.springframework.security.config.annotation.web.reactive.EnableWebFluxSecurity;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.security.oauth2.server.resource.authentication.ReactiveJwtAuthenticationConverterAdapter;
import org.springframework.security.web.server.SecurityWebFilterChain;
import reactor.core.publisher.Mono;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * Mirrors the BFF's auth posture: JWTs from Casdoor only, role claim
 * walked into Spring authorities so {@code @PreAuthorize("hasRole(...)")}
 * controllers work. The publisher/admin scope check is enforced
 * per-request inside controllers (we don't carry per-game scope here;
 * the build worker forwards the user's bearer token to the BFF, which
 * runs its own publisher_game_scopes check at upload time).
 */
@Configuration
@EnableWebFluxSecurity
@EnableReactiveMethodSecurity
public class SecurityConfig {

    @Bean
    public SecurityWebFilterChain springSecurityFilterChain(ServerHttpSecurity http) {
        return http
                .csrf(ServerHttpSecurity.CsrfSpec::disable)
                .authorizeExchange(auth -> auth
                        .pathMatchers("/actuator/health", "/actuator/info").permitAll()
                        .pathMatchers("/v3/api-docs/**", "/swagger-ui/**", "/swagger-ui.html").permitAll()
                        .anyExchange().authenticated())
                .oauth2ResourceServer(o -> o.jwt(jwt -> jwt.jwtAuthenticationConverter(jwtConverter())))
                .build();
    }

    private static Converter<Jwt, Mono<AbstractAuthenticationToken>> jwtConverter() {
        return new ReactiveJwtAuthenticationConverterAdapter(jwt -> {
            Collection<GrantedAuthority> authorities = new ArrayList<>();
            authorities.add(new SimpleGrantedAuthority("ROLE_USER"));
            // Casdoor mints `roles` as either a List<String> (custom JWT
            // template) or a List<Map> (default token shape — each map
            // carries name/owner/etc.). We accept both. For maps we
            // extract the `name` field; that's the role identifier the
            // Casdoor admin UI shows.
            for (String name : extractRoleNames(jwt)) {
                authorities.add(new SimpleGrantedAuthority("ROLE_" + name.toUpperCase()));
            }
            // Legacy Casdoor convention: a boolean isAdmin claim. The BFF
            // uses the same fallback to keep admin users functional even
            // when their `roles` claim isn't normalised to strings.
            Object isAdmin = jwt.getClaim("isAdmin");
            if (Boolean.TRUE.equals(isAdmin)) {
                authorities.add(new SimpleGrantedAuthority("ROLE_ADMIN"));
            }
            return new JwtAuthenticationToken(jwt, authorities);
        });
    }

    @SuppressWarnings("unchecked")
    private static List<String> extractRoleNames(Jwt jwt) {
        Object raw = jwt.getClaim("roles");
        if (!(raw instanceof List<?> list)) return List.of();
        List<String> out = new ArrayList<>();
        for (Object item : list) {
            if (item instanceof String s && !s.isBlank()) {
                out.add(s.trim());
            } else if (item instanceof Map<?, ?> m) {
                Object name = m.get("name");
                if (name instanceof String s && !s.isBlank()) {
                    out.add(s.trim());
                }
            }
        }
        return out;
    }
}
