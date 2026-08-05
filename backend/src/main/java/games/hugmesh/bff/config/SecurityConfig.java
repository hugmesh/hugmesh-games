package games.hugmesh.bff.config;

import games.hugmesh.bff.security.DomainRegistry;
import games.hugmesh.bff.service.UserDirectoryService;
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

/**
 * Spring Security resource-server configuration for the games BFF.
 *
 * <h2>Responsibility</h2>
 *
 * <p>Wires the WebFlux security filter chain: authorization rules, the JWT
 * resource-server decoder, and the Casdoor-to-Spring authority converter.
 * Two filter chain beans are produced — one for the {@code dev} profile
 * (OpenAPI spec public) and one for production (OpenAPI spec admin-only) —
 * selected by {@code @Profile}. All other rules are identical.
 *
 * <h2>Authorization rule summary</h2>
 *
 * <pre>
 *  Path pattern            │ Dev         │ Prod
 *  ────────────────────────┼─────────────┼──────────────────────────
 *  /api/auth/**            │ permitAll   │ permitAll
 *  /actuator/health/**     │ permitAll   │ permitAll
 *  /actuator/info          │ permitAll   │ permitAll
 *  /actuator/**            │ GAME-MASTER │ GAME-MASTER
 *  /api/admin/games/upload │ ADMIN|PUBLISHER │ ADMIN|PUBLISHER
 *  /api/admin/games/{slug}/uploads (glob) │ ADMIN|PUBLISHER │ ADMIN|PUBLISHER
 *  /api/admin/**           │ ADMIN       │ ADMIN
 *  /api/realtime/**        │ authenticated│ authenticated
 *  /api/characters/**      │ authenticated│ authenticated
 *  /api/skills/**          │ authenticated│ authenticated
 *  /api/quests/**          │ authenticated│ authenticated
 *  /api/items/**           │ authenticated│ authenticated
 *  /api/sessions/**        │ authenticated│ authenticated
 *  /api/relationships/**   │ authenticated│ authenticated
 *  /v3/api-docs/**         │ permitAll   │ ADMIN
 *  /swagger-ui/**          │ permitAll   │ ADMIN
 *  anyExchange             │ authenticated│ authenticated
 * </pre>
 *
 * <h2>JWT resource-server wiring</h2>
 *
 * <p>The resource server is configured via {@link #configureResourceServer}.
 * Single-issuer (the games realm's default) and multi-issuer (opt-in via
 * {@code app.tenancy.issuers}) are both supported with backward-compatible
 * auto-detection: the multi-issuer path is only activated when BOTH a
 * {@link TenancyResourceServerConfig}-contributed resolver is present AND
 * {@link DomainRegistry#isMultiIssuer()} confirms more than the lone
 * back-compat entry is registered.
 *
 * <h2>Authority mapping</h2>
 *
 * <p>Every authenticated principal receives authorities derived from the
 * JWT's {@code roles} claim (prefixed {@code ROLE_<UPPER>}), the
 * {@code permissions} claim (verbatim, with reserved-prefix injection
 * defense), the legacy {@code isAdmin} boolean, and exactly one
 * issuer-derived {@code DOMAIN_<x>} authority (see {@link CasdoorAuthorities}).
 * A {@code cacheLogin} side-effect is also applied per request to warm the
 * username-to-casdoorId cache.
 *
 * <h2>CSRF note</h2>
 *
 * <p>CSRF protection is disabled on bearer-token resource servers because the
 * bearer credential in the {@code Authorization} header is not automatically
 * forwarded by a cross-site browser form (unlike a cookie), so the CSRF attack
 * surface does not apply. This is the correct-by-design setting for JWT-bearer
 * APIs. See the security-hardening standard in MEMORY.md.
 *
 * @see CasdoorAuthorities
 * @see TenancyResourceServerConfig
 * @see DomainRegistry
 */
@Configuration
@EnableWebFluxSecurity
@EnableReactiveMethodSecurity
public class SecurityConfig {

    // -------------------------------------------------------------------------
    // Path constant groups
    // -------------------------------------------------------------------------

    /**
     * Game-player API paths that require an authenticated principal. See inline
     * comments for the rationale behind each entry — several were added explicitly
     * to close permissive fall-through holes.
     */
    private static final String[] PLAYER_PATHS = {
            "/api/characters/**", "/api/skills/**",
            "/api/quests/**", "/api/items/**", "/api/sessions/**",
            // Server-authoritative realtime matches (P2). Without this entry
            // the realtime endpoints fall through to anyExchange().permitAll()
            // — an unauthenticated caller would reach the snapshot stream and
            // command log (the domain gate alone is a no-op single-tenant).
            "/api/realtime/**",
            // JRPG control input (ControlController). Without this entry the
            // endpoint fell through to anyExchange().permitAll() — an
            // UNAUTHENTICATED caller reached dispatch(), mutating shared game
            // state and fanning the move out over the per-session SSE sinks /
            // Kafka. The controller's multiplayer branch already 401s a null
            // subject, but the legacy single-session branch short-circuited the
            // controller-role check on a null subject (no auth ⇒ no check),
            // so requiring authentication HERE is the load-bearing control.
            "/api/controls/**",
            // Generalised input + audio event ingestion (InputEventController).
            // Without this entry the controller fell through to
            // anyExchange().permitAll() — an unauthenticated caller could read
            // ANY character's / session's recent input history (an IDOR that
            // de-anonymises Casdoor subjects and leaks event payloads) and the
            // submit() 401-on-null only guarded the write path. Requiring
            // authentication here closes the read IDOR; the controller then
            // gates each read by session-membership / character ownership.
            "/api/input/**",
            // Friend graph + blocklist (UserRelationshipController, base
            // /api/relationships, with /friends/** + /blocks/** nested under
            // it). Without this entry the endpoints fell through to
            // anyExchange().permitAll() — an unauthenticated caller reached the
            // controller, whose me(jwt) keyed the read/write to the literal
            // "anonymous" subject instead of returning 401. Owner==subject is
            // enforced inside the controller (the acting user is always
            // jwt.getSubject(); only the `other` target is body-supplied), so
            // requiring authentication here is the missing control.
            "/api/relationships/**"
    };

    /**
     * OpenAPI / Swagger UI paths. Public in dev (so {@code ng-openapi-gen} can
     * fetch without a token); gated to {@code ROLE_ADMIN} in prod.
     */
    private static final String[] SPEC_PATHS = {
            "/v3/api-docs/**", "/swagger-ui/**", "/swagger-ui.html"
    };

    /**
     * Paths that are intentionally public — no authentication required.
     * The catch-all is now .authenticated() so every path NOT in this list
     * (and not already handled by a more-specific rule above it) requires a JWT.
     * Add here only when a path MUST work for unauthenticated browsers:
     * - /api/game/**         SSE game stream (unscoped/spectator) + game state
     * - /api/games/**        game catalog — browse before logging in
     * - /api/assets/**       Godot WASM bundles delivered to the iframe
     * - /api/federation/**   Angular shell federation manifest (needed pre-login)
     * - /api/chapters/**     chapter metadata + scene data for game loading
     * - /api/levels/**       legacy /api/chapters alias kept for old WASM bundles
     * - /api/time/**         game clock (spectators see the in-game time)
     * - /api/telemetry/**    KPI ingest from iframes — explicitly allows anonymous
     * - /api/security/**     CSP violation reports + appeal-contact for banned users
     */
    private static final String[] PUBLIC_PATHS = {
            "/api/game/**",
            "/api/games/**",
            "/api/assets/**",
            "/api/federation/**",
            "/api/chapters/**",
            "/api/levels/**",
            "/api/time/**",
            "/api/telemetry/**",
            "/api/security/**",
    };

    /**
     * Infra/observability paths that are always public. Kubernetes and Docker
     * healthchecks hit {@code /actuator/health} without a token. The {@code info}
     * endpoint returns only metadata, not topology details.
     *
     * <p>Dev profile: OpenAPI spec is public so ng-openapi-gen can fetch it
     * without authentication. Run with SPRING_PROFILES_ACTIVE=dev locally.
     *
     * <p>Infra / observability paths — restricted to ROLE_GAME-MASTER
     * (platform admin issued out of snapdragon.systems Casdoor).
     * Same policy as the hugmesh-gateway + id-gateway, so leaking
     * cluster topology / Cassandra reachability / Liquibase
     * changelog state is impossible across the platform regardless
     * of which Spring service is reached first.
     *
     * <p>All other {@code /actuator/**} paths (metrics, loggers, Liquibase
     * changelog state) are restricted to {@code ROLE_GAME-MASTER} to prevent
     * cluster topology / connection-pool state leaking across the platform.
     * Same policy as hugmesh-gateway and id-gateway.
     */
    private static final String[] INFRA_PROBES_PUBLIC = {
            "/actuator/health", "/actuator/health/**",
            "/actuator/info",
    };

    /**
     * Actuator paths that require the platform-admin {@code GAME-MASTER} role.
     * The catch-all here is intentional: any new actuator endpoint is gated by
     * default, not exposed by accident.
     */
    private static final String[] INFRA_GATED = {
            "/actuator/**",
    };

    // -------------------------------------------------------------------------
    // Dependencies
    // -------------------------------------------------------------------------

    /**
     * Lazy provider so we don't create a bootstrap-time dependency from the
     * security filter chain to the user directory service. The converter
     * captures the provider in a closure and resolves on first JWT validation.
     */
    private final ObjectProvider<UserDirectoryService> directoryProvider;

    /**
     * Issuer-to-brand/domain registry — the single source of truth for the
     * issuer-derived {@code DOMAIN_<x>} authority the shared
     * {@link CasdoorAuthorities} converter stamps, and the multi-issuer gate.
     */
    private final DomainRegistry domainRegistry;

    /**
     * OPTIONAL per-brand multi-issuer resolver, contributed by
     * {@link TenancyResourceServerConfig} ONLY when {@code app.tenancy.issuers}
     * genuinely carries issuer=domain pairs. Absent on a single-issuer cluster
     * (the games realm's default — only a {@code jwk-set-uri} is configured).
     */
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

    // -------------------------------------------------------------------------
    // Filter chain beans
    // -------------------------------------------------------------------------

    /**
     * Dev-profile filter chain: OpenAPI spec is public so {@code ng-openapi-gen}
     * can fetch it without a token. Run with
     * {@code SPRING_PROFILES_ACTIVE=dev} locally.
     *
     * <p>{@code X-Frame-Options} is relaxed to {@code SAMEORIGIN} because the
     * Angular game-canvas mounts the Godot WASM bundle in an iframe on the same
     * origin; Spring Security's default of {@code DENY} would block it.
     *
     * @param http the Spring Security server HTTP security customizer
     * @return the configured {@link SecurityWebFilterChain}
     */
    @Bean
    @Profile("dev")
    SecurityWebFilterChain devFilterChain(ServerHttpSecurity http) {
        return http
                .authorizeExchange(ex -> {
                    ex.pathMatchers("/api/auth/**").permitAll();
                    ex.pathMatchers(INFRA_PROBES_PUBLIC).permitAll();
                    ex.pathMatchers(INFRA_GATED).hasRole("GAME-MASTER");
                    // Publisher-reachable carve-outs under /api/admin — MUST precede
                    // the blanket ADMIN rule (first matching pathMatcher wins). The
                    // endpoint's own @PreAuthorize + PublisherService.requireOwns
                    // (publisher_game_scopes) enforce per-game scope; this rule only
                    // widens the ROUTE so a publisher token reaches the controller
                    // instead of dying at the filter chain with an empty-body 403
                    // (which made publisher maker-build uploads impossible).
                    ex.pathMatchers("/api/admin/games/upload").hasAnyRole("ADMIN", "PUBLISHER");
                    ex.pathMatchers("/api/admin/games/*/uploads").hasAnyRole("ADMIN", "PUBLISHER");
                    ex.pathMatchers("/api/admin/**").hasRole("ADMIN");
                    ex.pathMatchers(PLAYER_PATHS).authenticated();
                    ex.pathMatchers(SPEC_PATHS).permitAll();
                    ex.pathMatchers(PUBLIC_PATHS).permitAll();
                    ex.anyExchange().authenticated();
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
     * Production filter chain (default when {@code dev} profile is not active):
     * OpenAPI spec requires {@code ROLE_ADMIN} so it is never publicly reachable.
     *
     * <p>{@code X-Frame-Options} is relaxed to {@code SAMEORIGIN} for the same
     * reason as the dev chain — the Godot WASM iframe is same-origin.
     *
     * @param http the Spring Security server HTTP security customizer
     * @return the configured {@link SecurityWebFilterChain}
     */
    @Bean
    @Profile("!dev")
    SecurityWebFilterChain prodFilterChain(ServerHttpSecurity http) {
        return http
                .authorizeExchange(ex -> {
                    ex.pathMatchers("/api/auth/**").permitAll();
                    ex.pathMatchers(INFRA_PROBES_PUBLIC).permitAll();
                    ex.pathMatchers(INFRA_GATED).hasRole("GAME-MASTER");
                    // Same publisher carve-outs as the dev chain — see the comment
                    // there; per-game scope is enforced by the endpoint itself.
                    ex.pathMatchers("/api/admin/games/upload").hasAnyRole("ADMIN", "PUBLISHER");
                    ex.pathMatchers("/api/admin/games/*/uploads").hasAnyRole("ADMIN", "PUBLISHER");
                    ex.pathMatchers("/api/admin/**").hasRole("ADMIN");
                    ex.pathMatchers(SPEC_PATHS).hasRole("ADMIN");
                    ex.pathMatchers(PLAYER_PATHS).authenticated();
                    ex.pathMatchers(PUBLIC_PATHS).permitAll();
                    ex.anyExchange().authenticated();
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

    // -------------------------------------------------------------------------
    // Resource-server wiring
    // -------------------------------------------------------------------------

    /**
     * Wires the JWT resource server. Multi-issuer (per-brand Casdoor) is OPT-IN
     * and backward-compatible: the per-issuer authentication-manager resolver
     * from {@link TenancyResourceServerConfig} is used ONLY when BOTH a resolver
     * was contributed AND the registry is genuinely multi-issuer
     * ({@link DomainRegistry#isMultiIssuer()} — more than the lone back-compat
     * entry). Gating on {@code isMultiIssuer()} — not merely
     * {@code resolver != null} — keeps a single-issuer cluster on the existing
     * {@code jwk-set-uri} {@code o.jwt(...)} decoder path even if the tenancy
     * conditional ever activates with just the lone back-compat issuer. In true
     * multi-brand mode each trusted per-brand Casdoor validates its own tokens
     * (unknown iss → 401, fail-closed).
     *
     * <p>The games realm ships a single Casdoor (only {@code jwk-set-uri} is
     * configured, no {@code issuer-uri} / {@code app.tenancy.issuers}), so this
     * always takes the single-issuer branch today and the existing decoder path
     * is byte-for-byte unchanged — the converter merely additionally stamps
     * {@code DOMAIN_default}.
     *
     * @param o the resource-server spec customizer provided by Spring Security
     */
    private void configureResourceServer(ServerHttpSecurity.OAuth2ResourceServerSpec o) {
        ReactiveAuthenticationManagerResolver<ServerWebExchange> resolver =
                tenantAuthManagerResolver.getIfAvailable();
        if (resolver != null && domainRegistry.isMultiIssuer()) {
            // Multi-brand mode: each trusted per-brand Casdoor manages its own tokens.
            // Unknown iss -> Mono.empty() from the resolver -> 401 (fail-closed).
            o.authenticationManagerResolver(resolver);
        } else {
            // Single-issuer path (the games realm default): standard jwk-set-uri decoder
            // with the shared Casdoor authorities converter applied.
            o.jwt(j -> j.jwtAuthenticationConverter(jwtConverter()));
        }
    }

    /**
     * Builds the {@link ReactiveJwtAuthenticationConverter} that wraps the
     * shared {@link CasdoorAuthorities} converter, additionally applying the
     * games-BFF-specific {@code UserDirectoryService.cacheLogin} side-effect.
     *
     * @return the configured converter
     */
    private ReactiveJwtAuthenticationConverter jwtConverter() {
        ReactiveJwtAuthenticationConverter converter = new ReactiveJwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(casdoorAuthoritiesConverter());
        return converter;
    }

    /**
     * Maps Casdoor JWT claims to Spring Security authorities, extended with the
     * games-BFF-specific {@code cacheLogin} side-effect. The AUTHORITY mapping
     * itself is delegated to the shared {@link CasdoorAuthorities} converter
     * (copied verbatim from hugmesh-gateway) so the single-issuer path here
     * and the optional multi-issuer path ({@link TenancyResourceServerConfig})
     * emit BYTE-IDENTICAL authorities.
     *
     * <p>Three claim sources, in precedence order:
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
     * <p>The wrapper additionally preserves the games-BFF-specific
     * username→casdoorId {@code cacheLogin} side-effect (best-effort cache
     * warm-up, NOT a security control) — it runs on every authenticated request
     * but never alters the emitted authorities.
     *
     * @return a converter from {@link Jwt} to a {@link Flux} of
     *         {@link GrantedAuthority} instances
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
            // Delegate to the shared converter for the actual authority mapping.
            return delegate.convert(jwt);
        };
    }
}
