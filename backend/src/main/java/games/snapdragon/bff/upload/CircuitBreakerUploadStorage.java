package games.snapdragon.bff.upload;

import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.Resource;

import java.nio.file.Path;
import java.util.Map;
import java.util.function.Supplier;

/**
 * Resilience4j circuit-breaker decorator for {@link UploadStorage}
 * (decision-020 follow-up). Used to wrap the {@link S3UploadStorage}
 * delegate so a network partition / rate-limit storm / regional
 * outage against the bucket doesn't cascade into every
 * {@code AssetController} request blocking on a slow S3 call.
 *
 * <p>Behaviour:
 * <ul>
 *   <li><b>CLOSED</b> — calls pass through to the delegate. Failures
 *       increment the sliding window.</li>
 *   <li><b>OPEN</b> — calls fast-fail with {@link CallNotPermittedException}
 *       wrapped in {@link UploadStorageException} so the caller sees a
 *       deterministic exception type instead of a stuck thread.</li>
 *   <li><b>HALF-OPEN</b> — the next few calls go through. If they
 *       succeed, the breaker closes; if they fail, it re-opens.</li>
 * </ul>
 *
 * <p>The filesystem strategy is NOT decorated — failures there are
 * local-disk problems that aren't transient and don't benefit from
 * a breaker. Only the network-bound S3 implementation is wrapped
 * (gated by {@code @ConditionalOnProperty(app.uploads.storage=s3)} in
 * {@code UploadStorageConfig}).
 *
 * <p>Read methods ({@link #read(String, String)}, {@link #exists(String, String)})
 * intentionally fall through to a soft-fail signal on breaker-open:
 * {@code read()} returns {@code null} so {@code AssetController} can
 * fall through to its classpath fallback, and {@code exists()} returns
 * {@code false}. Write methods ({@link #installFromStaging}, {@link
 * #deleteVersion}) propagate the breaker exception — a write that
 * silently no-oped would leave the catalog in an inconsistent state.
 */
public class CircuitBreakerUploadStorage implements UploadStorage {

    private static final Logger log = LoggerFactory.getLogger(CircuitBreakerUploadStorage.class);

    private final UploadStorage delegate;
    private final CircuitBreaker breaker;

    public CircuitBreakerUploadStorage(UploadStorage delegate, CircuitBreaker breaker) {
        this.delegate = delegate;
        this.breaker  = breaker;
    }

    @Override
    public String installFromStaging(String slug, String versionPrefix, Path stagingDir) {
        return runWriteThrough(() -> delegate.installFromStaging(slug, versionPrefix, stagingDir),
                               "installFromStaging");
    }

    @Override
    public Resource read(String locator, String relativePath) {
        return runReadOrNull(() -> delegate.read(locator, relativePath),
                             "read");
    }

    @Override
    public boolean exists(String locator, String relativePath) {
        Boolean result = runReadOrNull(() -> delegate.exists(locator, relativePath),
                                       "exists");
        return result != null && result;
    }

    @Override
    public void deleteVersion(String locator) {
        runWriteThrough(() -> { delegate.deleteVersion(locator); return null; }, "deleteVersion");
    }

    @Override
    public Map<String, String> contentTypeOverrides() {
        return delegate.contentTypeOverrides();
    }

    @Override
    public String label() {
        return delegate.label() + "+cb";
    }

    /** Write operations: propagate failures (including CB-open) to the caller. */
    private <T> T runWriteThrough(Supplier<T> action, String op) {
        try {
            return breaker.executeSupplier(action);
        } catch (CallNotPermittedException e) {
            log.warn("[upload-storage-cb] {} short-circuited — breaker OPEN", op);
            throw new UploadStorageException(
                    "upload-storage circuit breaker is open; the underlying store is unreachable", e);
        }
    }

    /** Read probes: return null on breaker-open so the caller falls back. */
    private <T> T runReadOrNull(Supplier<T> action, String op) {
        try {
            return breaker.executeSupplier(action);
        } catch (CallNotPermittedException e) {
            log.warn("[upload-storage-cb] {} short-circuited — breaker OPEN; returning null", op);
            return null;
        } catch (RuntimeException e) {
            log.warn("[upload-storage-cb] {} failed: {}", op, e.getMessage());
            throw e;
        }
    }

    /** Caller-visible exception when the breaker is open on a write path. */
    public static class UploadStorageException extends RuntimeException {
        public UploadStorageException(String msg, Throwable cause) { super(msg, cause); }
    }
}
