package games.hugmesh.bff.repository;

import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Pins the per-slug cache behind gates 2 and 3 (hugmesh-games#13): the hot
 * path must not pay one database read per event, a flag change must land
 * within one TTL, a database error must never be remembered, and a client
 * spraying random slugs must not grow the map without bound.
 *
 * <p>Exercises {@link InputGateLookup#cached} directly with a counting loader;
 * the SQL itself is pinned against a real Postgres by
 * {@code InputGateFeatureFlagsMigrationTest}.
 */
class InputGateLookupCacheTest {

    private static InputGateLookup lookup(Duration ttl) {
        return new InputGateLookup(null, ttl);
    }

    @Test
    void aSecondReadWithinTheTtl_doesNotReachTheDatabase() {
        var cache = new ConcurrentHashMap<String, Mono<String>>();
        var loads = new AtomicInteger();
        var l = lookup(Duration.ofMinutes(5));
        for (int i = 0; i < 50; i++) {
            assertThat(l.cached(cache, "chess", () -> Mono.fromCallable(() -> "v" + loads.incrementAndGet()))
                    .block()).isEqualTo("v1");
        }
        assertThat(loads).hasValue(1);
    }

    @Test
    void afterTheTtl_theValueIsReloaded() throws InterruptedException {
        var cache = new ConcurrentHashMap<String, Mono<String>>();
        var loads = new AtomicInteger();
        var l = lookup(Duration.ofMillis(50));
        l.cached(cache, "chess", () -> Mono.fromCallable(() -> "v" + loads.incrementAndGet())).block();
        Thread.sleep(150);
        assertThat(l.cached(cache, "chess", () -> Mono.fromCallable(() -> "v" + loads.incrementAndGet()))
                .block()).isEqualTo("v2");
    }

    @Test
    void anErrorIsNeverCached() {
        var cache = new ConcurrentHashMap<String, Mono<String>>();
        var loads = new AtomicInteger();
        var l = lookup(Duration.ofMinutes(5));
        java.util.function.Supplier<Mono<String>> flaky = () -> Mono.defer(() ->
                loads.incrementAndGet() == 1
                        ? Mono.error(new IllegalStateException("db blip"))
                        : Mono.just("ok"));
        assertThatThrownBy(() -> l.cached(cache, "chess", flaky).block()).hasMessageContaining("db blip");
        assertThat(l.cached(cache, "chess", flaky).block()).isEqualTo("ok");
        assertThat(loads).hasValue(2);
    }

    @Test
    void slugsAreCachedSeparately() {
        var cache = new ConcurrentHashMap<String, Mono<String>>();
        var l = lookup(Duration.ofMinutes(5));
        assertThat(l.cached(cache, "chess", () -> Mono.just("c")).block()).isEqualTo("c");
        assertThat(l.cached(cache, "tron", () -> Mono.just("t")).block()).isEqualTo("t");
    }

    @Test
    void theMapIsBounded() {
        Map<String, Mono<String>> cache = new ConcurrentHashMap<>();
        var l = lookup(Duration.ofMinutes(5));
        for (int i = 0; i < InputGateLookup.MAX_SLUGS * 3; i++) {
            String slug = "junk-" + i;
            l.cached(cache, slug, () -> Mono.just(slug)).block();
        }
        assertThat(cache.size()).isLessThanOrEqualTo(InputGateLookup.MAX_SLUGS);
    }

    @Test
    void aZeroTtlDisablesCaching() {
        var cache = new ConcurrentHashMap<String, Mono<String>>();
        var loads = new AtomicInteger();
        var l = lookup(Duration.ZERO);
        for (int i = 0; i < 3; i++) {
            l.cached(cache, "chess", () -> Mono.fromCallable(loads::incrementAndGet).map(String::valueOf)).block();
        }
        assertThat(loads).hasValue(3);
        assertThat(cache).isEmpty();
    }
}
