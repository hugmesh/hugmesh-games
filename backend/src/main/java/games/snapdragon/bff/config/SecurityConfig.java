package games.snapdragon.bff.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.core.convert.converter.Converter;
import org.springframework.security.config.annotation.method.configuration.EnableReactiveMethodSecurity;
import org.springframework.security.config.annotation.web.reactive.EnableWebFluxSecurity;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.ReactiveJwtAuthenticationConverter;
import org.springframework.security.web.server.SecurityWebFilterChain;
import reactor.core.publisher.Flux;

import java.util.ArrayList;
import java.util.List;

@Configuration
@EnableWebFluxSecurity
@EnableReactiveMethodSecurity
public class SecurityConfig {

    private static final String[] PLAYER_PATHS = {
            "/api/characters/**", "/api/skills/**",
            "/api/quests/**", "/api/items/**", "/api/sessions/**"
    };

    private static final String[] SPEC_PATHS = {
            "/v3/api-docs/**", "/swagger-ui/**", "/swagger-ui.html"
    };

    /**
     * Dev profile: OpenAPI spec is public so ng-openapi-gen can fetch it
     * without authentication. Run with SPRING_PROFILES_ACTIVE=dev locally.
     */
    /**
     * Infra / observability paths — restricted to ROLE_GAME-MASTER
     * (platform admin issued out of snapdragon.systems Casdoor).
     * Same policy as the snapdragon-gateway + id-gateway, so leaking
     * cluster topology / Cassandra reachability / Liquibase
     * changelog state is impossible across the platform regardless
     * of which Spring service is reached first.
     *
     * <p>{@code /actuator/health} + {@code /actuator/info} are
     * deliberately PUBLIC — Kubernetes / Docker healthchecks need
     * them and they return only the bare green/red status, not
     * topology details (which require the `roles: GAME-MASTER`
     * configured on the health endpoint's show-details policy).
     */
    private static final String[] INFRA_PROBES_PUBLIC = {
            "/actuator/health", "/actuator/health/**",
            "/actuator/info",
    };

    private static final String[] INFRA_GATED = {
            "/actuator/**",
    };

    @Bean
    @Profile("dev")
    SecurityWebFilterChain devFilterChain(ServerHttpSecurity http) {
        return http
                .authorizeExchange(ex -> {
                    ex.pathMatchers("/api/auth/**").permitAll();
                    ex.pathMatchers(INFRA_PROBES_PUBLIC).permitAll();
                    ex.pathMatchers(INFRA_GATED).hasRole("GAME-MASTER");
                    ex.pathMatchers("/api/admin/**").hasRole("ADMIN");
                    ex.pathMatchers(PLAYER_PATHS).authenticated();
                    ex.pathMatchers(SPEC_PATHS).permitAll();
                    ex.anyExchange().permitAll();
                })
                .oauth2ResourceServer(o -> o.jwt(j -> j.jwtAuthenticationConverter(jwtConverter())))
                .csrf(csrf -> csrf.disable())
                // Spring Security defaults X-Frame-Options to DENY which blocks even
                // same-origin iframe embedding. The Angular game-canvas mounts the
                // Godot WASM bundle in an iframe, so we relax it to SAMEORIGIN.
                .headers(h -> h.frameOptions(fo ->
                        fo.mode(org.springframework.security.web.server.header
                                .XFrameOptionsServerHttpHeadersWriter.Mode.SAMEORIGIN)))
                .build();
    }

    /**
     * Production profile (default when dev is not active): OpenAPI spec
     * requires ROLE_ADMIN so it is never publicly reachable.
     */
    @Bean
    @Profile("!dev")
    SecurityWebFilterChain prodFilterChain(ServerHttpSecurity http) {
        return http
                .authorizeExchange(ex -> {
                    ex.pathMatchers("/api/auth/**").permitAll();
                    ex.pathMatchers(INFRA_PROBES_PUBLIC).permitAll();
                    ex.pathMatchers(INFRA_GATED).hasRole("GAME-MASTER");
                    ex.pathMatchers("/api/admin/**").hasRole("ADMIN");
                    ex.pathMatchers(SPEC_PATHS).hasRole("ADMIN");
                    ex.pathMatchers(PLAYER_PATHS).authenticated();
                    ex.anyExchange().permitAll();
                })
                .oauth2ResourceServer(o -> o.jwt(j -> j.jwtAuthenticationConverter(jwtConverter())))
                .csrf(csrf -> csrf.disable())
                // Spring Security defaults X-Frame-Options to DENY which blocks even
                // same-origin iframe embedding. The Angular game-canvas mounts the
                // Godot WASM bundle in an iframe, so we relax it to SAMEORIGIN.
                .headers(h -> h.frameOptions(fo ->
                        fo.mode(org.springframework.security.web.server.header
                                .XFrameOptionsServerHttpHeadersWriter.Mode.SAMEORIGIN)))
                .build();
    }

    private ReactiveJwtAuthenticationConverter jwtConverter() {
        ReactiveJwtAuthenticationConverter converter = new ReactiveJwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(casdoorAuthoritiesConverter());
        return converter;
    }

    /**
     * Maps Casdoor JWT claims to Spring Security authorities. Three sources, in
     * precedence order:
     * <ol>
     *   <li>{@code roles} — array of role names assigned to the user via the
     *       Casdoor admin console (Roles → Members). Each entry produces a
     *       {@code ROLE_<UPPER>} authority. This is the primary mechanism;
     *       new roles can be added in Casdoor without touching this code.</li>
     *   <li>{@code permissions} — array of fine-grained permission strings.
     *       Each becomes a non-prefixed authority so it doesn't collide with
     *       role-prefixed checks (e.g. {@code @PreAuthorize("hasAuthority('upload:games')")}).</li>
     *   <li>{@code isAdmin} (legacy boolean) — kept for backward-compatibility
     *       with users created before role assignment was introduced.</li>
     * </ol>
     * Every authenticated principal also gets {@code ROLE_USER} so
     * {@code .authenticated()} matchers work even with no roles assigned.
     */
    private Converter<Jwt, Flux<GrantedAuthority>> casdoorAuthoritiesConverter() {
        return new Converter<>() {
            @Override
            public Flux<GrantedAuthority> convert(@org.springframework.lang.NonNull Jwt jwt) {
                List<GrantedAuthority> authorities = new ArrayList<>();
                authorities.add(new SimpleGrantedAuthority("ROLE_USER"));

                // Primary: Casdoor-managed `roles` claim. The Casdoor token
                // template can be configured to embed assigned roles as a JSON
                // array of strings (Application → Token Format → JWT-Custom).
                Object rolesClaim = jwt.getClaim("roles");
                if (rolesClaim instanceof List<?> list) {
                    for (Object r : list) {
                        if (r != null) {
                            authorities.add(new SimpleGrantedAuthority(
                                    "ROLE_" + r.toString().trim().toUpperCase()));
                        }
                    }
                }

                // Optional: fine-grained permissions. Stored alongside roles in
                // Casdoor (Permissions tab). Useful when a single endpoint
                // gates on a verb-noun pair rather than a role.
                Object permsClaim = jwt.getClaim("permissions");
                if (permsClaim instanceof List<?> list) {
                    for (Object p : list) {
                        if (p != null) {
                            authorities.add(new SimpleGrantedAuthority(p.toString().trim()));
                        }
                    }
                }

                // Legacy: pre-roles users with the boolean isAdmin flag still
                // get ROLE_ADMIN. Safe to remove once everyone is migrated to
                // Casdoor-assigned roles.
                Object isAdmin = jwt.getClaim("isAdmin");
                if (Boolean.TRUE.equals(isAdmin)) {
                    authorities.add(new SimpleGrantedAuthority("ROLE_ADMIN"));
                }

                return Flux.fromIterable(authorities);
            }
        };
    }
}
