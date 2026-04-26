package games.snapdragon.bff.config;

import games.snapdragon.bff.service.AdminActionService;
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
 * <p>Order is set just AFTER Spring Security's authentication filter
 * (HIGHEST_PRECEDENCE + 50) so the JWT is already bound to the
 * reactive context when this filter runs.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 50)
public class BanGateFilter implements WebFilter {

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
                        exchange.getResponse().getHeaders().add("X-Snapdragon-Ban", "active");
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
