package games.snapdragon.bff.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

import java.net.InetSocketAddress;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Token-bucket rate limiter on credential-grant endpoints.
 *
 * <p>OWASP: Authentication endpoints are the canonical place
 * credential-stuffing / brute-force attempts land. We cap each
 * remote-IP at {@link #MAX_PER_WINDOW} requests per
 * {@link #WINDOW} on {@code /api/auth/token} (and
 * {@code /api/admin/actions/*} so a stolen ROLE_ADMIN token can't
 * fan out arbitrary bans). Other endpoints stay un-throttled at this
 * layer; nginx applies a wider per-IP req-per-second limit at the
 * edge.
 *
 * <p>Storage is in-process — fine for a single-pod dev / staging
 * deploy. The Cassandra cross-pod aggregator is a Phase-2 concern.
 *
 * <p>The bucket is reset by a {@link Duration#getSeconds() WINDOW.toSeconds()}
 * sliding tail: every request whose timestamp is older than NOW-WINDOW
 * is dropped from the count via lazy GC on each insert.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 30)
public class RateLimitFilter implements WebFilter {

    private static final Logger log = LoggerFactory.getLogger(RateLimitFilter.class);

    private static final Duration WINDOW = Duration.ofMinutes(1);
    private static final int MAX_PER_WINDOW = 20;

    /**
     * Path prefixes the limiter applies to. Anything else is passed
     * through. This list intentionally excludes the SSE streams (long-
     * lived) and the high-volume input event surface (already capped
     * in the upstream services).
     */
    private static final String[] LIMITED_PREFIXES = {
            "/api/auth/",
            "/api/admin/actions/",
    };

    /** key = remote-IP + "|" + path-prefix → bucket. */
    private final ConcurrentHashMap<String, Bucket> buckets = new ConcurrentHashMap<>();

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        String path = exchange.getRequest().getPath().value();
        String prefix = matchedPrefix(path);
        if (prefix == null) {
            return chain.filter(exchange);
        }

        String ip = clientIp(exchange);
        String key = ip + "|" + prefix;
        Bucket b = buckets.computeIfAbsent(key, k -> new Bucket());

        if (!b.tryAcquire()) {
            log.warn("[rate-limit] {} on {} (key={})", "throttled", path, key);
            exchange.getResponse().setStatusCode(HttpStatus.TOO_MANY_REQUESTS);
            exchange.getResponse().getHeaders().add("Retry-After", String.valueOf(WINDOW.toSeconds()));
            return exchange.getResponse().setComplete();
        }
        return chain.filter(exchange);
    }

    private static String matchedPrefix(String path) {
        for (String p : LIMITED_PREFIXES) {
            if (path.startsWith(p)) return p;
        }
        return null;
    }

    private static String clientIp(ServerWebExchange exchange) {
        var fwd = exchange.getRequest().getHeaders().getFirst("X-Forwarded-For");
        if (fwd != null && !fwd.isBlank()) {
            int comma = fwd.indexOf(',');
            return (comma >= 0 ? fwd.substring(0, comma) : fwd).trim();
        }
        InetSocketAddress addr = exchange.getRequest().getRemoteAddress();
        return addr == null ? "unknown" : addr.getAddress().getHostAddress();
    }

    /** Sliding-window counter. Not a true token bucket — adequate for
     *  single-pod throttling. */
    private static final class Bucket {
        private final AtomicInteger count = new AtomicInteger(0);
        private volatile Instant windowStart = Instant.now();

        synchronized boolean tryAcquire() {
            Instant now = Instant.now();
            if (Duration.between(windowStart, now).compareTo(WINDOW) > 0) {
                windowStart = now;
                count.set(0);
            }
            return count.incrementAndGet() <= MAX_PER_WINDOW;
        }
    }
}
