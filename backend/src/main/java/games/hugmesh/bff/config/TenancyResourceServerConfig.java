package games.hugmesh.bff.config;

import games.hugmesh.bff.security.DomainRegistry;
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
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoders;
import org.springframework.security.oauth2.server.resource.authentication.JwtIssuerReactiveAuthenticationManagerResolver;
import org.springframework.security.oauth2.server.resource.authentication.JwtReactiveAuthenticationManager;
import org.springframework.security.oauth2.server.resource.authentication.ReactiveJwtAuthenticationConverter;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

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
 * issuer-derived {@code DOMAIN_<x>}. (The games-BFF's username→casdoorId
 * {@code cacheLogin} side-effect lives only on the single-issuer
 * {@code SecurityConfig} converter; the multi-issuer path validates tokens identically
 * but does not need to warm that cache — the side-effect is best-effort, not a
 * security control.)
 */
@Configuration
// Fire ONLY when app.tenancy.issuers genuinely carries issuer=domain pairs. The '='
// guard makes the empty ${APP_TENANCY_ISSUERS:} default a no-op. A valid CSV always
// contains at least one '='.
@ConditionalOnExpression("'${app.tenancy.issuers:}'.contains('=')")
public class TenancyResourceServerConfig {

    private final DomainRegistry domainRegistry;

    /** Explicit issuer-uri of the lone back-compat issuer (the single-issuer
     *  {@code spring.security.oauth2.resourceserver.jwt.issuer-uri}). */
    private final String backCompatIssuer;

    /** Explicit jwk-set-uri of the lone back-compat issuer — used to build the
     *  per-issuer decoder WITHOUT OIDC discovery (same no-network profile as the
     *  single-issuer {@code o.jwt(...)} path). Empty on pure multi-brand configs (each
     *  brand decoder discovers from its own iss). */
    private final String backCompatJwkSetUri;

    public TenancyResourceServerConfig(
            DomainRegistry domainRegistry,
            @Value("${spring.security.oauth2.resourceserver.jwt.issuer-uri:}") String backCompatIssuer,
            @Value("${spring.security.oauth2.resourceserver.jwt.jwk-set-uri:}") String backCompatJwkSetUri) {
        this.domainRegistry = domainRegistry;
        this.backCompatIssuer = trimSlash(backCompatIssuer);
        this.backCompatJwkSetUri = backCompatJwkSetUri == null ? "" : backCompatJwkSetUri.trim();
    }

    private static String trimSlash(String s) {
        if (s == null) return "";
        String t = s.trim();
        return t.endsWith("/") ? t.substring(0, t.length() - 1) : t;
    }

    @Bean
    ReactiveAuthenticationManagerResolver<ServerWebExchange> tenantAuthManagerResolver() {
        // Cache one manager per trusted issuer (decoder construction does network
        // discovery — build lazily on first sight, never per request).
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
            if (iss == null) {
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
     * for any string at all, including an attacker-chosen issuer. Reaching
     * {@code decoderForIssuer} with an attacker-chosen {@code iss} would mean OIDC
     * discovery against an attacker-controlled URL.
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

    /** Decoder for an issuer. The lone back-compat issuer (the one with a configured
     *  jwk-set-uri) gets a discovery-free {@code withJwkSetUri} decoder + the standard
     *  exp/nbf validators + an {@code iss}-binding validator — the SAME validation
     *  surface and no-network profile as the single-issuer path. Any other (per-brand)
     *  issuer uses {@code fromIssuerLocation} discovery. */
    private ReactiveJwtDecoder decoderForIssuer(String issuer) {
        String iss = trimSlash(issuer);
        if (!backCompatJwkSetUri.isEmpty() && !backCompatIssuer.isEmpty()
                && backCompatIssuer.equals(iss)) {
            NimbusReactiveJwtDecoder decoder =
                    NimbusReactiveJwtDecoder.withJwkSetUri(backCompatJwkSetUri).build();
            // Default validators (exp/nbf + the standard set) PLUS an explicit iss
            // validator so a token's `iss` must equal the configured issuer — the
            // equivalent binding fromIssuerLocation would have applied, without the
            // discovery round-trip.
            OAuth2TokenValidator<Jwt> withIssuer = JwtValidators.createDefaultWithIssuer(iss);
            decoder.setJwtValidator(withIssuer);
            return decoder;
        }
        return ReactiveJwtDecoders.fromIssuerLocation(iss);
    }

    /** The Casdoor authorities converter — identical shape to
     *  {@code SecurityConfig.casdoorAuthoritiesConverter()} (the shared
     *  {@link CasdoorAuthorities}), with the issuer-derived {@code DOMAIN_<x>} added. */
    private Converter<Jwt, Flux<GrantedAuthority>> authoritiesConverter() {
        return CasdoorAuthorities.converter(domainRegistry);
    }
}
