package games.hugmesh.bff.service;

import org.junit.jupiter.api.Test;
import org.springframework.data.cassandra.core.cql.ReactiveCqlTemplate;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Specs for {@link DistributedRateLimitService}. Pin the four
 * behaviours the {@link games.hugmesh.bff.config.RateLimitFilter}
 * depends on:
 *
 * <ol>
 *   <li>Happy path — insert + count succeed, the cluster aggregate
 *       passes through.</li>
 *   <li>Insert failure — Cassandra timing out on the insert returns
 *       0 (allow). The local bucket is still in front so a transient
 *       outage doesn't gate every login.</li>
 *   <li>Count failure — same fallback. The service degrades
 *       gracefully.</li>
 *   <li>Empty count — also falls back to 0; an empty result on the
 *       count query (rare but possible if the row TTL'd between
 *       insert and count) shouldn't deny the request.</li>
 * </ol>
 *
 * <p>Tests use a hand-rolled {@link ReactiveCqlTemplate} subclass so
 * the test doesn't pull in the Mockito-inline agent (which is
 * brittle on JDK 25 — see surefire dump). Each scenario instantiates
 * its own stub; assertions verify both the returned aggregate AND
 * that the hot-path methods were actually invoked.
 */
class DistributedRateLimitServiceTest {

    @Test
    void happyPath_insertSucceedsAndCountReturnsClusterAggregate() {
        StubCqlTemplate cql = new StubCqlTemplate(Mono.just(true), Mono.just(7L));
        DistributedRateLimitService service = new DistributedRateLimitService(cql);

        StepVerifier.create(service.recordAndCount("1.2.3.4|/api/auth/"))
                .expectNext(7L)
                .verifyComplete();

        assertThat(cql.executeCalls.get()).isEqualTo(1);
        assertThat(cql.queryCalls.get()).isEqualTo(1);
    }

    @Test
    void insertFailure_fallsBackToZero() {
        StubCqlTemplate cql = new StubCqlTemplate(
                Mono.error(new RuntimeException("cassandra timeout")),
                Mono.just(7L));
        DistributedRateLimitService service = new DistributedRateLimitService(cql);

        StepVerifier.create(service.recordAndCount("1.2.3.4|/api/auth/"))
                .expectNext(0L)
                .verifyComplete();
    }

    @Test
    void countFailure_fallsBackToZero() {
        StubCqlTemplate cql = new StubCqlTemplate(
                Mono.just(true),
                Mono.error(new RuntimeException("cassandra unreachable")));
        DistributedRateLimitService service = new DistributedRateLimitService(cql);

        StepVerifier.create(service.recordAndCount("1.2.3.4|/api/auth/"))
                .expectNext(0L)
                .verifyComplete();
    }

    @Test
    void emptyCount_fallsBackToZero() {
        StubCqlTemplate cql = new StubCqlTemplate(Mono.just(true), Mono.empty());
        DistributedRateLimitService service = new DistributedRateLimitService(cql);

        StepVerifier.create(service.recordAndCount("1.2.3.4|/api/auth/"))
                .expectNext(0L)
                .verifyComplete();
    }

    /** Minimal hand-rolled stub. We only override the two methods
     *  DistributedRateLimitService actually calls; everything else
     *  inherits the parent's no-arg constructor (which works fine
     *  because the test never touches the underlying session). */
    private static final class StubCqlTemplate extends ReactiveCqlTemplate {
        final AtomicInteger executeCalls = new AtomicInteger();
        final AtomicInteger queryCalls   = new AtomicInteger();
        private final Mono<Boolean> executeReturn;
        private final Mono<Long> countReturn;

        StubCqlTemplate(Mono<Boolean> executeReturn, Mono<Long> countReturn) {
            this.executeReturn = executeReturn;
            this.countReturn   = countReturn;
        }

        @Override
        public Mono<Boolean> execute(@org.springframework.lang.NonNull String cql,
                                     @org.springframework.lang.NonNull Object... args) {
            executeCalls.incrementAndGet();
            return executeReturn;
        }

        @Override
        public <T> Mono<T> queryForObject(@org.springframework.lang.NonNull String cql,
                                          @org.springframework.lang.NonNull Class<T> requiredType,
                                          @org.springframework.lang.NonNull Object... args) {
            queryCalls.incrementAndGet();
            @SuppressWarnings("unchecked")
            Mono<T> coerced = (Mono<T>) countReturn;
            return coerced;
        }
    }
}
