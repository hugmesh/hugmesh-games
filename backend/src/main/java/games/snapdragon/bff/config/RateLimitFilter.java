package games.snapdragon.bff.config;

import games.snapdragon.bff.service.DistributedRateLimitService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
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
 * <p>Two-layer storage:
 * <ol>
 *   <li><b>In-process bucket</b> (this class) — per-pod fast-fail.
 *       Catches the common single-pod credential-stuffing case in a
 *       microsecond.</li>
 *   <li><b>Cassandra event log</b> (
 *       {@link DistributedRateLimitService}) — cluster-wide aggregate.
 *       Catches the multi-pod fan-out case where each pod's local
 *       bucket would individually allow but the cluster total
 *       exceeds the cap. Optional: when Cassandra is unreachable or
 *       the {@code app.rate-limit.distributed.enabled} property is
 *       false, the filter falls back to local-only.</li>
 * </ol>
 *
 * <p>The bucket is reset by a {@link Duration#getSeconds() WINDOW.toSeconds()}
 * sliding tail: every request whose timestamp is older than NOW-WINDOW
 * is dropped from the count via lazy GC on each insert.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 30)
public class RateLimitFilter implements WebFilter {

    private static final Logger log = LoggerFactory.getLogger(RateLimitFilter.class);

    // Profile-tunable caps. Defaults match the original hard-coded
    // values (20 attempts per 60 seconds per IP) — the production
    // credential-stuffing defense from the original implementation.
    // The integration-test profile overrides these to wide values so a
    // multi-scenario e2e run sharing one container IP doesn't trip
    // them; the test suite is its own auth-flood by design and isn't
    // the brute-force attacker the cap targets.
    private final Duration window;
    private final int maxPerWindow;

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

    /** Lazy provider so the filter still bootstraps if Cassandra
     *  isn't wired (Postgres-only test profile, unit harness). */
    private final ObjectProvider<DistributedRateLimitService> distributedProvider;

    public RateLimitFilter(
            ObjectProvider<DistributedRateLimitService> distributedProvider,
            @Value("${app.rate-limit.auth.window-seconds:60}") long windowSeconds,
            @Value("${app.rate-limit.auth.max-per-window:20}") int maxPerWindow) {
        this.distributedProvider = distributedProvider;
        this.window = Duration.ofSeconds(windowSeconds);
        this.maxPerWindow = maxPerWindow;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        String path = exchange.getRequest().getPath().value();
        String prefix = matchedPrefix(path);
        if (prefix == null) {
            return chain.filter(exchange);
        }

        String ip = clientIp(exchange);
        String key = ip + "|" + prefix;
        Bucket b = buckets.computeIfAbsent(key, k -> new Bucket(window, maxPerWindow));

        if (!b.tryAcquire()) {
            // Local-bucket fast path. Reject without the cluster-wide
            // round-trip — the per-pod cap is already trip-wired.
            log.warn("[rate-limit] {} on {} (key={})", "throttled-local", path, key);
            return reject429(exchange);
        }

        // Local allowed — augment with the cluster-wide check. The
        // distributed service appends a row + counts cluster events
        // for `key` in the rolling window. If Cassandra is offline
        // the service returns 0 (allow). The local bucket is still
        // doing its job so a transient Cassandra blip doesn't gate
        // every login.
        DistributedRateLimitService distributed = distributedProvider.getIfAvailable();
        if (distributed == null) {
            return chain.filter(exchange);
        }
        return distributed.recordAndCount(key)
                .flatMap(count -> {
                    if (count > maxPerWindow) {
                        log.warn("[rate-limit] {} on {} (key={}, cluster={})",
                                "throttled-cluster", path, key, count);
                        return reject429(exchange);
                    }
                    return chain.filter(exchange);
                });
    }

    private Mono<Void> reject429(ServerWebExchange exchange) {
        exchange.getResponse().setStatusCode(HttpStatus.TOO_MANY_REQUESTS);
        exchange.getResponse().getHeaders().add("Retry-After", String.valueOf(window.toSeconds()));
        return exchange.getResponse().setComplete();
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
     *  single-pod throttling. Window + cap come from the outer
     *  filter's @Value-injected fields so the integration-test profile
     *  can crank both wide without touching this code. */
    private static final class Bucket {
        private final AtomicInteger count = new AtomicInteger(0);
        private volatile Instant windowStart = Instant.now();
        private final Duration window;
        private final int maxPerWindow;

        Bucket(Duration window, int maxPerWindow) {
            this.window = window;
            this.maxPerWindow = maxPerWindow;
        }

        synchronized boolean tryAcquire() {
            Instant now = Instant.now();
            if (Duration.between(windowStart, now).compareTo(window) > 0) {
                windowStart = now;
                count.set(0);
            }
            return count.incrementAndGet() <= maxPerWindow;
        }
    }
}
