package com.threewizards.config;

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
    @Bean
    @Profile("dev")
    SecurityWebFilterChain devFilterChain(ServerHttpSecurity http) {
        return http
                .authorizeExchange(ex -> {
                    ex.pathMatchers("/api/auth/**").permitAll();
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

    private Converter<Jwt, Flux<GrantedAuthority>> casdoorAuthoritiesConverter() {
        return new Converter<>() {
            @Override
            public Flux<GrantedAuthority> convert(@org.springframework.lang.NonNull Jwt jwt) {
                List<GrantedAuthority> authorities = new ArrayList<>();
                authorities.add(new SimpleGrantedAuthority("ROLE_USER"));
                Object isAdmin = jwt.getClaim("isAdmin");
                if (Boolean.TRUE.equals(isAdmin)) {
                    authorities.add(new SimpleGrantedAuthority("ROLE_ADMIN"));
                }
                return Flux.fromIterable(authorities);
            }
        };
    }
}
