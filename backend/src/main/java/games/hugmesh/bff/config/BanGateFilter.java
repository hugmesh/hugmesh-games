package games.hugmesh.bff.config;

import games.hugmesh.bff.service.AdminActionService;
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

import java.util.Set;

/**
 * Refuses every {@code /api/*} request from a banned user with
 * <code>403 Forbidden</code>.
 *
 * <p>Two narrow allowlists keep the player able to reach the
 * resolution path:
 * <ul>
 *   <li>{@code /api/auth/*} — login / token refresh stays open so the
 *       session token can be renewed (the frontend redirects to /banned
 *       after the refresh succeeds and the ban-status check fires).</li>
 *   <li>{@code /api/me/ban-status} + {@code /api/me/admin-actions/stream}
 *       — the only paths the banned-page component needs.</li>
 * </ul>
 *
 * <p>Order is set just AFTER Spring Security's {@code WebFilterChainProxy}
 * (registered at {@code -100}) so the JWT is already bound to the reactive
 * context when this filter runs. The previous {@code HIGHEST_PRECEDENCE + 50}
 * (≈ {@link Integer#MIN_VALUE}) ran the filter BEFORE the security chain, so the
 * {@link ReactiveSecurityContextHolder} was empty and the ban lookup short-
 * circuited on {@code defaultIfEmpty(false)} — a banned user's requests sailed
 * through (fail-open). {@code -100 + 50 = -50} runs last of the three
 * SecurityContext-reading app filters (RateLimit {@code -70} &lt; DomainGuard
 * {@code -60} &lt; BanGate {@code -50}), preserving their original relative order.
 */
@Component
@Order(BanGateFilter.SECURITY_CHAIN_ORDER + 50)
public class BanGateFilter implements WebFilter {

    /** Spring Security's {@code WebFilterChainProxy} bean order; this filter sits
     *  just after it so it observes the populated SecurityContext. */
    static final int SECURITY_CHAIN_ORDER = -100;

    /** Paths the banned user is allowed to reach (login + the ban-info
     *  endpoints). Keep the list narrow — every other /api/* path
     *  short-circuits with 403. */
    private static final Set<String> ALLOWED_API_PREFIXES = Set.of(
            "/api/auth/",
            "/api/me/ban-status",
            "/api/me/admin-actions/stream"
    );

    private final AdminActionService actions;

    public BanGateFilter(AdminActionService actions) {
        this.actions = actions;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        String path = exchange.getRequest().getPath().value();
        if (!path.startsWith("/api/") || isAllowed(path)) {
            return chain.filter(exchange);
        }
        return ReactiveSecurityContextHolder.getContext()
                .map(c -> c.getAuthentication())
                .filter(Authentication::isAuthenticated)
                .filter(auth -> auth.getPrincipal() instanceof Jwt)
                .map(auth -> ((Jwt) auth.getPrincipal()).getSubject())
                .flatMap(actions::isBanned)
                .defaultIfEmpty(false)
                .flatMap(banned -> {
                    if (banned) {
                        exchange.getResponse().setStatusCode(HttpStatus.FORBIDDEN);
                        exchange.getResponse().getHeaders().add("X-HugMesh-Ban", "active");
                        return exchange.getResponse().setComplete();
                    }
                    return chain.filter(exchange);
                });
    }

    private static boolean isAllowed(String path) {
        for (String prefix : ALLOWED_API_PREFIXES) {
            if (path.startsWith(prefix)) return true;
        }
        return false;
    }
}
