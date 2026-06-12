package games.snapdragon.bff.config;

import games.snapdragon.bff.security.DomainRegistry;
import games.snapdragon.bff.service.UserDirectoryService;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.core.convert.converter.Converter;
import org.springframework.security.authentication.ReactiveAuthenticationManagerResolver;
import org.springframework.security.config.annotation.method.configuration.EnableReactiveMethodSecurity;
import org.springframework.security.config.annotation.web.reactive.EnableWebFluxSecurity;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.ReactiveJwtAuthenticationConverter;
import org.springframework.security.web.server.SecurityWebFilterChain;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Flux;

@Configuration
@EnableWebFluxSecurity
@EnableReactiveMethodSecurity
public class SecurityConfig {

    /**
     * Lazy provider so we don't create a bootstrap-time dependency from the
     * security filter chain to the user directory service. The converter
     * captures the provider in a closure and resolves on first JWT validation.
     */
    private final ObjectProvider<UserDirectoryService> directoryProvider;

    /** Issuer -&gt; brand/domain registry — the single source of truth for the
     *  issuer-derived {@code DOMAIN_<x>} authority the shared
     *  {@link CasdoorAuthorities} converter stamps, and the multi-issuer gate. */
    private final DomainRegistry domainRegistry;

    /** OPTIONAL per-brand multi-issuer resolver, contributed by
     *  {@link TenancyResourceServerConfig} ONLY when {@code app.tenancy.issuers}
     *  genuinely carries issuer=domain pairs. Absent on a single-issuer cluster
     *  (the games realm's default — only a jwk-set-uri is configured). */
    private final ObjectProvider<ReactiveAuthenticationManagerResolver<ServerWebExchange>>
            tenantAuthManagerResolver;

    public SecurityConfig(ObjectProvider<UserDirectoryService> directoryProvider,
                          DomainRegistry domainRegistry,
                          ObjectProvider<ReactiveAuthenticationManagerResolver<ServerWebExchange>>
                                  tenantAuthManagerResolver) {
        this.directoryProvider = directoryProvider;
        this.domainRegistry = domainRegistry;
        this.tenantAuthManagerResolver = tenantAuthManagerResolver;
    }

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
                .oauth2ResourceServer(this::configureResourceServer)
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
                .oauth2ResourceServer(this::configureResourceServer)
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
     * Wire the resource server. Multi-issuer (per-brand Casdoor) is OPT-IN +
     * backward-compatible: swap to the per-issuer authentication-manager
     * resolver ONLY when BOTH a resolver was contributed by
     * {@link TenancyResourceServerConfig} AND the registry is genuinely
     * multi-issuer ({@link DomainRegistry#isMultiIssuer()} — more than the lone
     * back-compat entry). Gating on {@code isMultiIssuer()} — not merely
     * {@code resolver != null} — keeps a single-issuer cluster on the EXISTING
     * {@code jwk-set-uri} {@code o.jwt(...)} decoder path even if the tenancy
     * conditional ever activates with just the lone back-compat issuer. In true
     * multi-brand mode each trusted per-brand Casdoor validates its own tokens
     * (unknown iss -&gt; 401, fail-closed).
     *
     * <p>The games realm ships a single Casdoor (only {@code jwk-set-uri} is
     * configured, no {@code issuer-uri} / {@code app.tenancy.issuers}), so this
     * always takes the single-issuer branch today and the existing decoder path
     * is byte-for-byte unchanged — the converter merely additionally stamps
     * {@code DOMAIN_default}.
     */
    private void configureResourceServer(ServerHttpSecurity.OAuth2ResourceServerSpec o) {
        ReactiveAuthenticationManagerResolver<ServerWebExchange> resolver =
                tenantAuthManagerResolver.getIfAvailable();
        if (resolver != null && domainRegistry.isMultiIssuer()) {
            o.authenticationManagerResolver(resolver);
        } else {
            o.jwt(j -> j.jwtAuthenticationConverter(jwtConverter()));
        }
    }

    private ReactiveJwtAuthenticationConverter jwtConverter() {
        ReactiveJwtAuthenticationConverter converter = new ReactiveJwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(casdoorAuthoritiesConverter());
        return converter;
    }

    /**
     * Maps Casdoor JWT claims to Spring Security authorities. The AUTHORITY
     * mapping itself is delegated to the shared {@link CasdoorAuthorities}
     * converter (COPIED VERBATIM from snapdragon-gateway) so the single-issuer
     * path here and the optional multi-issuer path
     * ({@link TenancyResourceServerConfig}) emit BYTE-IDENTICAL authorities.
     * Three claim sources, in precedence order:
     * <ol>
     *   <li>{@code roles} — array of role names (strings OR {@code {name}}
     *       objects) assigned via the Casdoor admin console. Each entry produces
     *       a {@code ROLE_<UPPER>} authority.</li>
     *   <li>{@code permissions} — fine-grained permission strings, each a
     *       non-prefixed authority — EXCEPT any entry beginning with a RESERVED
     *       prefix ({@code ROLE_}/{@code DOMAIN_}) which is DROPPED. Those
     *       authorities come ONLY from the converter's own controlled logic, so
     *       a token carrying {@code permissions:["ROLE_SUPERADMIN"]} or
     *       {@code permissions:["DOMAIN_other"]} can NEVER inject a forged
     *       super-role or cross-tenant scope (authority-injection defense).</li>
     *   <li>{@code isAdmin} (legacy boolean) — kept for back-compat with users
     *       created before role assignment.</li>
     * </ol>
     * Every authenticated principal also gets the {@code ROLE_USER} floor PLUS
     * exactly one issuer-derived {@code DOMAIN_<x>} authority
     * ({@code DOMAIN_default} on this single-issuer cluster).
     *
     * <p>This wrapper additionally preserves the games-BFF-specific
     * username→casdoorId {@code cacheLogin} side-effect (best-effort cache
     * warm-up, NOT a security control) — it runs on every authenticated request
     * but never alters the emitted authorities.
     */
    private Converter<Jwt, Flux<GrantedAuthority>> casdoorAuthoritiesConverter() {
        Converter<Jwt, Flux<GrantedAuthority>> delegate =
                CasdoorAuthorities.converter(domainRegistry);
        return jwt -> {
            // Side-effect: populate the username→casdoorId cache on every
            // authenticated request. Without this, the first
            // /api/relationships/blocks/add { other: "player2" } call misses the
            // cache and falls back to a Casdoor get-user round-trip that can
            // transiently 404, silently no-opping the block. The Javadoc on
            // UserDirectoryService.cacheLogin documents this hook explicitly.
            UserDirectoryService directory = directoryProvider.getIfAvailable();
            if (directory != null) {
                String name = jwt.getClaimAsString("name");
                String sub  = jwt.getSubject();
                if (name != null && sub != null) {
                    directory.cacheLogin(name, sub);
                }
            }
            return delegate.convert(jwt);
        };
    }
}
