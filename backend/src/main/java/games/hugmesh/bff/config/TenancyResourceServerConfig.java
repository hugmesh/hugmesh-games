package games.hugmesh.bff.config;

import games.hugmesh.bff.security.DomainRegistry;
import games.hugmesh.bff.security.GamesAdminPolicy;
import games.hugmesh.bff.service.UserDirectoryService;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.convert.converter.Converter;
import org.springframework.security.authentication.ReactiveAuthenticationManager;
import org.springframework.security.authentication.ReactiveAuthenticationManagerResolver;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusReactiveJwtDecoder;
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtIssuerReactiveAuthenticationManagerResolver;
import org.springframework.security.oauth2.server.resource.authentication.JwtReactiveAuthenticationManager;
import org.springframework.security.oauth2.server.resource.authentication.ReactiveJwtAuthenticationConverter;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import jakarta.annotation.PostConstruct;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * CONFIG-GATED, backward-compatible multi-issuer (per-brand Casdoor) wiring for the
 * games-BFF. COPIED from hugmesh-gateway
 * ({@code systems.hugmesh.gateway.config.TenancyResourceServerConfig}).
 *
 * <p>Contributes a per-issuer {@link ReactiveAuthenticationManagerResolver} ONLY when
 * {@code app.tenancy.issuers} actually carries issuer=domain pairs (the
 * {@code .contains('=')} guard) — NOT merely when the property is "present" with the
 * empty {@code ${APP_TENANCY_ISSUERS:}} default, which a bare
 * {@code @ConditionalOnProperty(name=...)} would have treated as present-and-true and
 * so ALWAYS activated. {@link SecurityConfig} additionally only swaps onto this
 * resolver when {@link DomainRegistry#isMultiIssuer()} (more than the lone back-compat
 * entry), so a single-issuer cluster keeps the existing single {@code o.jwt(...)}
 * decoder path verbatim and is byte-for-byte unchanged.
 *
 * <p><b>Fail-closed on unknown issuer.</b> The resolver's allowlist is exactly
 * {@link DomainRegistry#trustedIssuers()} (the configured per-brand Casdoors). A token
 * whose {@code iss} is not in that set gets NO manager and 401s before any authority is
 * emitted.
 *
 * <p><b>Custom converter preserved per issuer.</b> Each per-issuer
 * {@link JwtReactiveAuthenticationManager} is built with the SAME Casdoor authorities
 * converter the single-issuer path uses — identical authority output, plus the
 * issuer-derived {@code DOMAIN_<x>} and the {@code cacheLogin} warm-up.
 *
 * <p><b>JWKS per issuer, never discovery.</b> The cluster's two issuers are public
 * hostnames that do not resolve in-cluster, and the BFF has no egress to reach them,
 * so OIDC discovery cannot work there. It would also make the BFF's outbound traffic
 * depend on a value read from an unverified token. Every trusted issuer gets its key
 * set from {@code app.tenancy.jwks-uris} (or the {@code jwk-set-uri} when it is the
 * lone {@code casdoor.issuer-uri}); a trusted issuer with neither stops the BFF at
 * boot, and {@code iss} is bound to the issuer whose keys verified the token.
 */
@Configuration
// Fire ONLY when app.tenancy.issuers genuinely carries issuer=domain pairs. The '='
// guard makes the empty ${APP_TENANCY_ISSUERS:} default a no-op. A valid CSV always
// contains at least one '='.
@ConditionalOnExpression("'${app.tenancy.issuers:}'.contains('=')")
public class TenancyResourceServerConfig {

    private final DomainRegistry domainRegistry;

    /** Trimmed issuer → JWKS URI, from {@code app.tenancy.jwks-uris}. */
    private final Map<String, String> jwksByIssuer;

    /** Same games-admin policy bean the single-issuer path uses (SecurityConfig). */
    private final GamesAdminPolicy gamesAdminPolicy;

    /** Same lazy directory the single-issuer path warms. */
    private final ObjectProvider<UserDirectoryService> directories;

    /**
     * @param backCompatIssuer    {@code casdoor.issuer-uri}; with
     *                            {@code backCompatJwkSetUri} it counts as one JWKS entry
     * @param backCompatJwkSetUri the single-issuer {@code jwk-set-uri}
     * @param jwksUrisCsv         {@code app.tenancy.jwks-uris}: issuer=jwksUri,...
     */
    public TenancyResourceServerConfig(
            DomainRegistry domainRegistry,
            @Value("${casdoor.issuer-uri:}") String backCompatIssuer,
            @Value("${spring.security.oauth2.resourceserver.jwt.jwk-set-uri:}") String backCompatJwkSetUri,
            @Value("${app.tenancy.jwks-uris:}") String jwksUrisCsv,
            GamesAdminPolicy gamesAdminPolicy,
            ObjectProvider<UserDirectoryService> directories) {
        this.domainRegistry = domainRegistry;
        this.gamesAdminPolicy = gamesAdminPolicy;
        this.directories = directories;
        this.jwksByIssuer = new LinkedHashMap<>();
        String lone = trimSlash(backCompatIssuer);
        String loneJwks = backCompatJwkSetUri == null ? "" : backCompatJwkSetUri.trim();
        if (!lone.isEmpty() && !loneJwks.isEmpty()) {
            jwksByIssuer.put(lone, loneJwks);
        }
        if (jwksUrisCsv != null) {
            // The CSV outranks the lone pair: it is the explicit per-issuer statement.
            for (String pair : jwksUrisCsv.split(",")) {
                int eq = pair.indexOf('=');
                if (eq <= 0) continue;
                String iss = trimSlash(pair.substring(0, eq));
                String uri = pair.substring(eq + 1).trim();
                if (!iss.isEmpty() && !uri.isEmpty()) jwksByIssuer.put(iss, uri);
            }
        }
    }

    /**
     * Refuses to start a multi-issuer BFF that could not verify some trusted issuer's
     * tokens: at runtime that issuer's users would all get 401s with nothing in the
     * configuration to say why. A single-pair {@code APP_TENANCY_ISSUERS} never reaches
     * this resolver (see {@link SecurityConfig}), so it is not checked.
     *
     * @throws IllegalStateException naming the issuers with no JWKS entry
     */
    @PostConstruct
    void requireJwksForEveryTrustedIssuer() {
        if (!domainRegistry.isMultiIssuer()) return;
        List<String> missing = new ArrayList<>();
        for (String iss : domainRegistry.trustedIssuers()) {
            if (!jwksByIssuer.containsKey(iss)) missing.add(iss);
        }
        if (!missing.isEmpty()) {
            throw new IllegalStateException(
                    "app.tenancy.jwks-uris (APP_TENANCY_JWKS_URIS) has no entry for trusted issuer(s) "
                    + missing + ". The BFF never discovers keys from a token's issuer: "
                    + "add issuer=jwksUri for each.");
        }
    }

    private static String trimSlash(String s) {
        if (s == null) return "";
        String t = s.trim();
        return t.endsWith("/") ? t.substring(0, t.length() - 1) : t;
    }

    @Bean
    ReactiveAuthenticationManagerResolver<ServerWebExchange> tenantAuthManagerResolver() {
        // Cache one manager per trusted issuer (build lazily on first sight, never per
        // request: each decoder holds its own JWKS cache).
        Map<String, ReactiveAuthenticationManager> byIssuer = new ConcurrentHashMap<>();

        // Per-issuer resolver: ONLY trusted issuers (registry keys) get a manager; an
        // unknown iss yields Mono.empty() -> 401 (fail-closed).
        ReactiveAuthenticationManagerResolver<String> byIssuerResolver =
                byIssuerResolver(byIssuer);

        JwtIssuerReactiveAuthenticationManagerResolver delegate =
                new JwtIssuerReactiveAuthenticationManagerResolver(byIssuerResolver);
        return delegate::resolve;
    }

    /** Per-issuer resolver: maps a token's {@code iss} to its cached manager, or
     *  {@code Mono.empty()} (-> 401) when the issuer is not on the allowlist.
     *  Package-private rather than inlined so the fail-closed contract is unit
     *  testable without a Spring context or a synthetic exchange. */
    ReactiveAuthenticationManagerResolver<String> byIssuerResolver(
            Map<String, ReactiveAuthenticationManager> byIssuer) {
        return issuer -> {
            String iss = trustedIssuerOrNull(issuer);
            // The boot check makes a missing key set unreachable; never build a decoder
            // without one regardless.
            if (iss == null || !jwksByIssuer.containsKey(iss)) {
                return Mono.empty();
            }
            return Mono.just(byIssuer.computeIfAbsent(iss, this::managerForIssuer));
        };
    }

    /**
     * Resolve {@code issuer} to its canonical entry in the trusted-issuer allowlist,
     * or {@code null} when it is not trusted.
     *
     * <p>Deliberately strict membership of a NON-EMPTY {@link DomainRegistry#trustedIssuers()}
     * rather than {@link DomainRegistry#isTrusted}: the latter delegates to
     * {@code domainOf}, which short-circuits to the legacy single-tenant "default"
     * WITHOUT inspecting its argument on an empty registry — i.e. it answers "trusted"
     * for any string at all, including an attacker-chosen issuer. A manager must only
     * ever be built for an issuer that was configured, not for one a token named.
     *
     * <p>That empty-registry state is REACHABLE: {@code app.tenancy.issuers=&#61;brand}
     * satisfies this class's {@code @ConditionalOnExpression} {@code .contains('=')}
     * guard (bean ACTIVE) yet parses to zero valid pairs (the {@code eq <= 0} skip),
     * and with no {@code jwt.issuer-uri} to seed the back-compat entry the map stays
     * empty. Today the SecurityConfig swap-in is gated on
     * {@link DomainRegistry#isMultiIssuer()} (false for an empty map), so this is
     * latent — but that gate is a caller's discipline, not this class's guarantee.
     * Fail closed here instead.
     *
     * <p>Does NOT change {@code domainOf}'s legacy fail-open, which stays load-bearing
     * for legacy single-tenant clusters.
     */
    private String trustedIssuerOrNull(String issuer) {
        if (issuer == null) return null;
        Set<String> trusted = domainRegistry.trustedIssuers();
        // Empty (or absent) allowlist: deny. Never fall back to a default.
        if (trusted == null || trusted.isEmpty()) return null;
        String candidate = trimSlash(issuer);
        if (candidate.isEmpty()) return null;
        for (String t : trusted) {
            // Return the SET's element so the cache key is canonical: "https://x" and
            // "https://x/" share one manager instead of a duplicate per variant.
            if (t != null && t.equals(candidate)) return t;
        }
        return null;
    }

    /** Build the per-issuer manager wired to the SAME Casdoor authorities converter the
     *  single-issuer path uses, so the {@code DOMAIN_<x>} authority is emitted in
     *  multi-issuer mode too. */
    private ReactiveAuthenticationManager managerForIssuer(String issuer) {
        JwtReactiveAuthenticationManager mgr =
                new JwtReactiveAuthenticationManager(decoderForIssuer(issuer));
        ReactiveJwtAuthenticationConverter converter = new ReactiveJwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(authoritiesConverter());
        mgr.setJwtAuthenticationConverter(converter);
        return mgr;
    }

    /** Decoder for a trusted issuer: its own key set, fetched without discovery, the
     *  standard exp/nbf validators, and an {@code iss}-binding validator. A token
     *  signed with one issuer's key and naming the other fails here, because the
     *  resolver picked the decoder by the {@code iss} it claims and that issuer's keys
     *  do not verify it. */
    private ReactiveJwtDecoder decoderForIssuer(String issuer) {
        String iss = trimSlash(issuer);
        NimbusReactiveJwtDecoder decoder =
                NimbusReactiveJwtDecoder.withJwkSetUri(jwksByIssuer.get(iss)).build();
        OAuth2TokenValidator<Jwt> withIssuer = JwtValidators.createDefaultWithIssuer(iss);
        decoder.setJwtValidator(withIssuer);
        return decoder;
    }

    /** The Casdoor authorities converter — the same one
     *  {@code SecurityConfig.casdoorAuthoritiesConverter()} builds (the shared
     *  {@link CasdoorAuthorities}), so both paths emit identical authorities. */
    private Converter<Jwt, Flux<GrantedAuthority>> authoritiesConverter() {
        return CasdoorAuthorities.converter(domainRegistry, gamesAdminPolicy, directories);
    }
}
