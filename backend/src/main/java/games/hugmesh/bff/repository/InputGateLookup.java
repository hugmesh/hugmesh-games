package games.hugmesh.bff.repository;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Repository;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * The two per-game reads behind {@code InputEventController.submit()} gates 2
 * and 3 (hugmesh-games#13): a game's {@code games.features} flags and the
 * {@code game_event_support} triples it has enabled.
 *
 * <h2>Why cached, and the invalidation story</h2>
 *
 * <p>{@code submit()} is the hot path — Asteroids Duel posts a
 * {@code control:input} vector every frame, and the gamepad mirror is
 * per-frame when it is live — so a naive lookup is one extra database round
 * trip per event per gate. Both reads are therefore cached PER SLUG, in this
 * pod, for {@code app.input.gate-cache-ttl} (default 10 s):
 *
 * <ul>
 *   <li><b>Invalidation is by expiry only.</b> Nothing in this service writes
 *       {@code games.features} or {@code game_event_support} at runtime except
 *       an upload's {@code register.sql} and hand-run SQL; neither signals
 *       this cache. A flag flipped by either takes effect on every pod within
 *       one TTL. That bounded staleness is the whole contract — a flag is a
 *       product switch, not a revocation, so seconds of lag are acceptable
 *       and there is no cross-pod bus to keep coherent.</li>
 *   <li><b>Errors are never cached</b> (TTL zero), so a database blip refuses
 *       nothing for longer than the blip itself; the next event retries.</li>
 *   <li><b>Bounded.</b> A client can put any string in {@code gameSlug}; the
 *       map is cleared once it holds {@value #MAX_SLUGS} slugs, so unknown-slug
 *       spam cannot grow the heap. Real traffic is a few dozen titles.</li>
 * </ul>
 *
 * <p>An unknown slug and a game whose {@code features} object is empty both
 * read as an empty map — every flag absent — which gate 2 refuses. That is
 * deliberate: decision 103b seeded {@code world} and {@code control} precisely
 * so "absent" could mean "refused" without breaking any shipped title.
 *
 * <p>Implemented on {@link DatabaseClient}, like {@link InputEventRepository},
 * because both reads are jsonb/composite-key shaped. The flag values are
 * decided in SQL ({@code value = 'true'::jsonb}) so a non-boolean flag such as
 * {@code "yes"} or {@code 1} is NOT true — only a real JSON {@code true} is.
 */
@Repository
public class InputGateLookup {

    /** Upper bound on cached slugs per map before it is cleared. */
    static final int MAX_SLUGS = 512;

    private final DatabaseClient db;
    private final Duration ttl;

    private final Map<String, Mono<Map<String, Boolean>>> featureCache = new ConcurrentHashMap<>();
    private final Map<String, Mono<Set<String>>> supportCache = new ConcurrentHashMap<>();

    public InputGateLookup(DatabaseClient db,
                           @Value("${app.input.gate-cache-ttl:10s}") Duration ttl) {
        this.db = db;
        this.ttl = ttl;
    }

    /**
     * {@code games.features} for {@code slug} as flag → is-JSON-true. Empty when
     * the slug has no games row or the row carries no flags.
     */
    public Mono<Map<String, Boolean>> features(String slug) {
        return cached(featureCache, slug, () -> db.sql("""
                    SELECT f.key AS flag, (f.value = 'true'::jsonb) AS enabled
                      FROM games g
                     CROSS JOIN LATERAL jsonb_each(g.features) f
                     WHERE g.slug = :slug
                    """)
                .bind("slug", slug)
                .map((row, meta) -> Map.entry(
                        row.get("flag", String.class),
                        Boolean.TRUE.equals(row.get("enabled", Boolean.class))))
                .all()
                .collectMap(Map.Entry::getKey, Map.Entry::getValue)
                .map(Map::copyOf));
    }

    /**
     * Every ENABLED {@code game_event_support} triple for {@code slug}, as
     * {@code "kind:type"} strings. Empty when the game has no enabled rows.
     */
    public Mono<Set<String>> enabledEvents(String slug) {
        return cached(supportCache, slug, () -> db.sql("""
                    SELECT event_kind, event_type
                      FROM game_event_support
                     WHERE game_slug = :slug AND enabled
                    """)
                .bind("slug", slug)
                .map((row, meta) -> row.get("event_kind", String.class)
                        + ":" + row.get("event_type", String.class))
                .all()
                .collect(Collectors.toUnmodifiableSet()));
    }

    /** Package-private for {@code InputGateLookupCacheTest}. */
    <T> Mono<T> cached(Map<String, Mono<T>> cache, String slug,
                               java.util.function.Supplier<Mono<T>> load) {
        if (ttl.isZero() || ttl.isNegative()) {
            return Mono.defer(load);
        }
        Mono<T> hit = cache.get(slug);
        if (hit != null) {
            return hit;
        }
        if (cache.size() >= MAX_SLUGS) {
            cache.clear();
        }
        return cache.computeIfAbsent(slug, s -> Mono.defer(load)
                .cache(v -> ttl, e -> Duration.ZERO, () -> Duration.ZERO));
    }
}
