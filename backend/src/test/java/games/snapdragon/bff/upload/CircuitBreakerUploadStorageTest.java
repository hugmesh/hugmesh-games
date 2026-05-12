package games.snapdragon.bff.upload;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.Resource;

import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for {@link CircuitBreakerUploadStorage}.
 *
 * <p>The breaker config used here is intentionally aggressive
 * (minimumNumberOfCalls=2, slidingWindow=2, failureRate=50%) so the
 * test can OPEN the breaker in two failures and verify both the
 * write-throw and read-fall-through semantics without orchestrating
 * a real outage. The production tuning lives in
 * {@code application.yml} under {@code resilience4j.circuitbreaker.instances.s3}.
 */
class CircuitBreakerUploadStorageTest {

    private FailingDelegate delegate;
    private CircuitBreaker breaker;
    private CircuitBreakerUploadStorage cb;

    @BeforeEach
    void setUp() {
        delegate = new FailingDelegate();
        breaker = CircuitBreaker.of("test", CircuitBreakerConfig.custom()
                .slidingWindowSize(2)
                .minimumNumberOfCalls(2)
                .failureRateThreshold(50.0f)
                .waitDurationInOpenState(Duration.ofSeconds(30))
                .build());
        cb = new CircuitBreakerUploadStorage(delegate, breaker);
    }

    @Test
    void delegatesSuccessfulRead() {
        Resource resource = cb.read("file:/tmp/x", "index.html");
        assertThat(resource).isInstanceOf(ByteArrayResource.class);
        assertThat(delegate.readCount).isEqualTo(1);
    }

    @Test
    void readFallsThroughToNullWhenBreakerOpens() {
        delegate.failNext(3);
        // Two failures trip the breaker.
        assertThatThrownBy(() -> cb.read("file:/tmp/x", "a")).isInstanceOf(RuntimeException.class);
        assertThatThrownBy(() -> cb.read("file:/tmp/x", "b")).isInstanceOf(RuntimeException.class);
        assertThat(breaker.getState()).isEqualTo(CircuitBreaker.State.OPEN);
        // Third call short-circuits without hitting the delegate — caller sees null.
        delegate.failNext(0);
        Resource third = cb.read("file:/tmp/x", "c");
        assertThat(third).isNull();
        // Delegate was NOT called for the third read.
        assertThat(delegate.readCount).isEqualTo(2);
    }

    @Test
    void existsReturnsFalseWhenBreakerOpen() {
        delegate.failNext(3);
        assertThatThrownBy(() -> cb.exists("file:/tmp/x", "a")).isInstanceOf(RuntimeException.class);
        assertThatThrownBy(() -> cb.exists("file:/tmp/x", "b")).isInstanceOf(RuntimeException.class);
        assertThat(breaker.getState()).isEqualTo(CircuitBreaker.State.OPEN);
        assertThat(cb.exists("file:/tmp/x", "c")).isFalse();
    }

    @Test
    void writePropagatesBreakerOpenException() {
        delegate.failNext(3);
        assertThatThrownBy(() -> cb.installFromStaging("slug", "abc123", Path.of("/tmp/staging")))
                .isInstanceOf(RuntimeException.class);
        assertThatThrownBy(() -> cb.installFromStaging("slug", "abc123", Path.of("/tmp/staging")))
                .isInstanceOf(RuntimeException.class);
        assertThat(breaker.getState()).isEqualTo(CircuitBreaker.State.OPEN);
        // Third call hits CallNotPermittedException → propagates as UploadStorageException.
        assertThatThrownBy(() -> cb.installFromStaging("slug", "abc123", Path.of("/tmp/staging")))
                .isInstanceOf(CircuitBreakerUploadStorage.UploadStorageException.class)
                .hasMessageContaining("circuit breaker is open");
    }

    @Test
    void labelAppendsCbSuffix() {
        assertThat(cb.label()).isEqualTo("test+cb");
    }

    /** Toy delegate that can be primed to fail N consecutive calls. */
    private static class FailingDelegate implements UploadStorage {
        int failuresRemaining = 0;
        int readCount = 0;

        void failNext(int n) { failuresRemaining = n; }

        @Override
        public String installFromStaging(String slug, String versionPrefix, Path stagingDir) {
            if (failuresRemaining-- > 0) throw new RuntimeException("simulated S3 outage");
            return "s3://test/" + slug + "/" + versionPrefix;
        }
        @Override
        public Resource read(String locator, String relativePath) {
            readCount++;
            if (failuresRemaining-- > 0) throw new RuntimeException("simulated S3 outage");
            return new ByteArrayResource(new byte[]{1, 2, 3});
        }
        @Override
        public boolean exists(String locator, String relativePath) {
            if (failuresRemaining-- > 0) throw new RuntimeException("simulated S3 outage");
            return true;
        }
        @Override
        public void deleteVersion(String locator) {
            if (failuresRemaining-- > 0) throw new RuntimeException("simulated S3 outage");
        }
        @Override
        public Map<String, String> contentTypeOverrides() { return Map.of(); }
        @Override
        public String label() { return "test"; }
    }
}
