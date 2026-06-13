package games.snapdragon.bff.config;

import games.snapdragon.bff.security.DomainRegistry;
import games.snapdragon.bff.security.Roles;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.ReactiveSecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

/**
 * Per-instance DOMAIN guard — the BFF-side, defence-in-depth mirror of the
 * snapdragon-gateway {@code GatewayRouteAccessFilter} domain hard-deny, enforced at
 * the games BFF itself.
 *
 * <p><b>What it consumes.</b> The {@code domain-foundation} work taught the BFF to
 * STAMP exactly one issuer-derived {@code DOMAIN_<x>} authority on every token
 * ({@link CasdoorAuthorities}); this filter is the first thing that READS it. It
 * answers a single question — "does this authenticated principal belong to the brand
 * this BFF instance serves?" — and 403s when it does not.
 *
 * <p><b>Why a BFF copy of a gateway check.</b> The platform's primary isolation is
 * per-brand INSTANCES: brand-X's gateway + BFF run as their own deployment, the
 * gateway already hard-denies a foreign-brand token at the edge
 * ({@code GatewayRouteAccessFilter}). But the games README documents a deliberate
 * defence-in-depth posture — "service-to-service traffic on the compose network that
 * bypasses the gateway stays gated" (the BFF re-runs the gateway's revocation +
 * rate-limit checks for exactly this reason). A request that reaches the BFF directly
 * on the compose network, skipping the gateway, would otherwise carry a foreign-brand
 * Casdoor token straight past the (now absent) edge domain check. This filter closes
 * that hole: the same {@code DOMAIN_<x>}-vs-instance-domain match the gateway runs,
 * re-run at the BFF.
 *
 * <p><b>Single-tenant: a complete no-op.</b> The guard activates ONLY when
 * {@code app.tenancy.domain} names the brand this instance serves (e.g.
 * {@code app.tenancy.domain=brandx}). With the property unset — the default, and the
 * games realm's single-Casdoor shape today — {@link #filter} returns
 * {@code chain.filter(exchange)} immediately and the request path is byte-for-byte
 * unchanged. So this is inert until a deployment opts into per-brand isolation.
 *
 * <p><b>Decision table</b> (only when {@code app.tenancy.domain} is set):
 * <ul>
 *   <li><b>No authenticated JWT principal</b> (anonymous / public endpoint) — pass
 *       through. Public catalog reads stay public; there is no token to scope, exactly
 *       like the gateway, which only domain-checks authenticated {@code Jwt}
 *       principals.</li>
 *   <li><b>SUPERADMIN</b> — pass through. The ONLY cross-domain bypass (the infra
 *       control plane reaches every brand), short-circuited before the match so it
 *       never has to carry the instance's {@code DOMAIN_}.</li>
 *   <li><b>Domain matches</b> ({@code DOMAIN_<instance>}) — pass through.</li>
 *   <li><b>Domain mismatch / unprovable</b> — 403 + {@code X-Domain-Denied} header.
 *       Covers a foreign-brand token ({@code DOMAIN_brandy} at a {@code brandx}
 *       instance), a legacy {@code DOMAIN_default} token at a branded instance, AND
 *       the fail-closed {@code null} from {@link Roles#domainOf(Authentication)} when
 *       a token carries zero or &gt;1 {@code DOMAIN_} authorities (the injection
 *       signature).</li>
 * </ul>
 *
 * <p>Order mirrors {@link BanGateFilter} ({@code HIGHEST_PRECEDENCE + 50}): this runs
 * at {@code +40}, just BEFORE the ban gate, so a foreign-brand token is rejected as a
 * tenant violation before any per-user ban lookup (a cheaper, more fundamental deny
 * that never touches the {@code AdminActionService} cache). Both read the JWT from the
 * reactive {@link ReactiveSecurityContextHolder} populated by Spring Security's
 * authentication filter.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 40)
public class DomainGuardFilter implements WebFilter {

    /** The brand this BFF instance serves. Empty => single-tenant => the guard is a
     *  no-op. Sanitized to the same {@code [a-z0-9-]} shape the converter / registry
     *  emit so a configured {@code brandX } or {@code BrandX} still matches a stamped
     *  {@code DOMAIN_brandx}. */
    private final String instanceDomain;

    public DomainGuardFilter(@Value("${app.tenancy.domain:}") String instanceDomain) {
        this.instanceDomain = sanitize(instanceDomain);
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        // No instance domain configured => single-tenant => complete no-op. Returned
        // before touching the security context so the request path is unchanged.
        if (instanceDomain.isEmpty()) {
            return chain.filter(exchange);
        }
        return ReactiveSecurityContextHolder.getContext()
                .map(c -> c.getAuthentication())
                .filter(a -> a != null && a.isAuthenticated() && a.getPrincipal() instanceof Jwt)
                .map(this::allowed)
                // No authenticated JWT principal (anonymous / public) => allow. Mirrors
                // the gateway: only authenticated principals are domain-checked.
                .defaultIfEmpty(Boolean.TRUE)
                // Single terminal branch — the Mono<Void> from chain.filter() emits no
                // onNext, so there must be NO switchIfEmpty downstream of it (that would
                // double-filter). map+defaultIfEmpty guarantees exactly one Boolean here.
                .flatMap(allow -> allow ? chain.filter(exchange) : reject(exchange));
    }

    /** True iff this principal may be served by this instance. SUPERADMIN always may
     *  (cross-domain infra plane); otherwise its {@code DOMAIN_<x>} must match. */
    private boolean allowed(Authentication auth) {
        if (Roles.isSuperadmin(auth)) {
            return true;
        }
        return domainMatches(Roles.domainOf(auth), instanceDomain);
    }

    /**
     * Same contract as the gateway's {@code GatewayRouteAccessFilter.domainMatches}: a
     * {@code null} principal domain fails closed (deny); {@code default}/{@code default}
     * is the single-tenant exemption; otherwise an exact match is required. Package-
     * private + static so the spec can pin the contract directly.
     */
    static boolean domainMatches(String principalDomain, String instanceDomain) {
        if (principalDomain == null) {
            return false;                                                   // fail-closed
        }
        if (DomainRegistry.DEFAULT_DOMAIN.equals(principalDomain)
                && DomainRegistry.DEFAULT_DOMAIN.equals(instanceDomain)) {
            return true;                                                    // single-tenant
        }
        return principalDomain.equals(instanceDomain);
    }

    private Mono<Void> reject(ServerWebExchange exchange) {
        exchange.getResponse().setStatusCode(HttpStatus.FORBIDDEN);
        exchange.getResponse().getHeaders().add("X-Domain-Denied", instanceDomain);
        return exchange.getResponse().setComplete();
    }

    private static String sanitize(String s) {
        return s == null ? "" : s.trim().toLowerCase().replaceAll("[^a-z0-9-]", "");
    }
}
