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
 * Resilience4j circuit-breaker decorator for {@link UploadStorage}.
 *
 * <h2>Responsibility</h2>
 *
 * <p>Wraps any {@link UploadStorage} delegate and guards every call through a
 * Resilience4j {@link CircuitBreaker}. The primary motivation is protecting
 * the {@link S3UploadStorage} delegate: a network partition, rate-limit storm,
 * or regional S3/MinIO outage would otherwise cause every {@code AssetController}
 * request to block on a slow S3 call until its thread-pool timeout, cascading
 * into BFF-wide slowness. The circuit breaker converts sustained failures into
 * fast-fails so the rest of the application stays responsive.
 *
 * <h2>Breaker states and behaviour</h2>
 *
 * <ul>
 *   <li><b>CLOSED</b> — calls pass through to the delegate; failures increment
 *       the sliding window.</li>
 *   <li><b>OPEN</b> — calls fast-fail with {@link CallNotPermittedException};
 *       read operations return {@code null} / {@code false} (soft miss), write
 *       operations propagate {@link UploadStorageException} so the catalog never
 *       silently diverges from the object store.</li>
 *   <li><b>HALF-OPEN</b> — the next few probe calls pass through; if they
 *       succeed the breaker closes, if they fail it re-opens.</li>
 * </ul>
 *
 * <h2>Why reads and writes are handled differently</h2>
 *
 * <p>Read methods ({@link #read}, {@link #exists}) return {@code null} /
 * {@code false} on breaker-open so {@code AssetController} can fall through to
 * its classpath fallback — a degraded-but-functional user experience. Write
 * methods ({@link #installFromStaging}, {@link #deleteVersion}) propagate the
 * breaker exception: a write that silently no-oped would leave
 * {@code game_uploads.version_dir} pointing at a non-existent object, causing
 * confusing downstream errors.
 *
 * <h2>Scope</h2>
 *
 * <p>The filesystem strategy is NOT decorated — local-disk failures are not
 * transient and do not benefit from a breaker. Only the network-bound S3
 * implementation is wrapped, gated by
 * {@code @ConditionalOnProperty(app.uploads.storage=s3)} in
 * {@link UploadStorageConfig}.
 *
 * <p>Breaker tuning (sliding window size, failure rate threshold, wait duration
 * in open state) lives in {@code application.yml} under
 * {@code resilience4j.circuitbreaker.instances.s3}.
 *
 * @see UploadStorage
 * @see S3UploadStorage
 * @see UploadStorageConfig
 */
public class CircuitBreakerUploadStorage implements UploadStorage {

    private static final Logger log = LoggerFactory.getLogger(CircuitBreakerUploadStorage.class);

    /** The underlying storage strategy being decorated. */
    private final UploadStorage delegate;

    /** Resilience4j circuit breaker instance; configured via {@code resilience4j.circuitbreaker.instances.s3}. */
    private final CircuitBreaker breaker;

    /**
     * @param delegate the storage strategy to wrap; typically
     *                 {@link S3UploadStorage}
     * @param breaker  the Resilience4j circuit breaker instance to use for
     *                 guarding calls to the delegate
     */
    public CircuitBreakerUploadStorage(UploadStorage delegate, CircuitBreaker breaker) {
        this.delegate = delegate;
        this.breaker  = breaker;
    }

    // -------------------------------------------------------------------------
    // UploadStorage implementation
    // -------------------------------------------------------------------------

    /**
     * Installs bundle files through the delegate, guarded by the circuit breaker.
     * Propagates {@link UploadStorageException} when the breaker is open so the
     * caller can surface a meaningful error rather than waiting for a timeout.
     *
     * @param slug          the game slug
     * @param versionPrefix short SHA prefix identifying the version directory
     * @param stagingDir    source directory to install; consumed by this call
     * @return the delegate's locator string
     * @throws UploadStorageException when the circuit breaker is open
     */
    @Override
    public String installFromStaging(String slug, String versionPrefix, Path stagingDir) {
        return runWriteThrough(() -> delegate.installFromStaging(slug, versionPrefix, stagingDir),
                               "installFromStaging");
    }

    /**
     * Reads a bundle file through the delegate, guarded by the circuit breaker.
     * Returns {@code null} (soft miss) when the breaker is open so
     * {@code AssetController} can fall through to its classpath fallback.
     *
     * @param locator      storage locator from {@link #installFromStaging}
     * @param relativePath file path within the version directory
     * @return a {@link Resource}, or {@code null} on any miss or when the
     *         breaker is open
     */
    @Override
    public Resource read(String locator, String relativePath) {
        return runReadOrNull(() -> delegate.read(locator, relativePath),
                             "read");
    }

    /**
     * Probes for a bundle file through the delegate, guarded by the circuit breaker.
     * Returns {@code false} (soft miss) when the breaker is open.
     *
     * @param locator      storage locator from {@link #installFromStaging}
     * @param relativePath file path within the version directory
     * @return {@code true} iff the file exists and the breaker is closed;
     *         {@code false} on any miss or when the breaker is open
     */
    @Override
    public boolean exists(String locator, String relativePath) {
        Boolean result = runReadOrNull(() -> delegate.exists(locator, relativePath),
                                       "exists");
        // A null result from runReadOrNull means breaker-open; treat as false.
        return result != null && result;
    }

    /**
     * Deletes a bundle version through the delegate, guarded by the circuit breaker.
     * Propagates {@link UploadStorageException} when the breaker is open —
     * a silent no-op here would leave stale locators in the catalog.
     *
     * @param locator storage locator identifying the version directory to remove
     * @throws UploadStorageException when the circuit breaker is open
     */
    @Override
    public void deleteVersion(String locator) {
        // Wrap the void call in a Supplier that returns null so runWriteThrough
        // can use the generic return-value path uniformly.
        runWriteThrough(() -> { delegate.deleteVersion(locator); return null; }, "deleteVersion");
    }

    /**
     * Delegates to the underlying storage; the breaker doesn't gate this
     * because it returns a static map that never touches the network.
     *
     * @return content-type override map from the delegate
     */
    @Override
    public Map<String, String> contentTypeOverrides() {
        return delegate.contentTypeOverrides();
    }

    /**
     * Returns the delegate's label with {@code "+cb"} appended to signal that
     * the circuit breaker is active (e.g. {@code "s3+cb"}).
     *
     * @return the decorated label string
     */
    @Override
    public String label() {
        return delegate.label() + "+cb";
    }

    // -------------------------------------------------------------------------
    // Circuit-breaker execution helpers
    // -------------------------------------------------------------------------

    /**
     * Runs a write operation through the circuit breaker, propagating any
     * failure — including {@link CallNotPermittedException} when the breaker is
     * open — as an {@link UploadStorageException}.
     *
     * <p>Write operations are not soft-failed because a silent no-op on a write
     * would leave the catalog in an inconsistent state (pointing at a version
     * that was never actually installed).
     *
     * @param action the write operation to execute
     * @param op     operation name for the log message
     * @param <T>    result type (often {@code null} for void operations)
     * @return the result of the action
     * @throws UploadStorageException when the circuit breaker is open
     */
    private <T> T runWriteThrough(Supplier<T> action, String op) {
        try {
            return breaker.executeSupplier(action);
        } catch (CallNotPermittedException e) {
            log.warn("[upload-storage-cb] {} short-circuited — breaker OPEN", op);
            throw new UploadStorageException(
                    "upload-storage circuit breaker is open; the underlying store is unreachable", e);
        }
    }

    /**
     * Runs a read probe through the circuit breaker, returning {@code null}
     * when the breaker is open ({@link CallNotPermittedException}) so the
     * caller can fall through to a classpath fallback. Other
     * {@link RuntimeException}s are re-thrown unchanged (they are real errors,
     * not breaker state).
     *
     * @param action the read operation to execute
     * @param op     operation name for the log message
     * @param <T>    result type (e.g. {@link Resource} or {@link Boolean})
     * @return the result, or {@code null} on breaker-open
     */
    private <T> T runReadOrNull(Supplier<T> action, String op) {
        try {
            return breaker.executeSupplier(action);
        } catch (CallNotPermittedException e) {
            // Breaker is open — soft-fail so AssetController falls back.
            log.warn("[upload-storage-cb] {} short-circuited — breaker OPEN; returning null", op);
            return null;
        } catch (RuntimeException e) {
            log.warn("[upload-storage-cb] {} failed: {}", op, e.getMessage());
            throw e;
        }
    }

    // -------------------------------------------------------------------------
    // Exception type
    // -------------------------------------------------------------------------

    /**
     * Caller-visible exception thrown by write operations when the underlying
     * circuit breaker is open. Wraps {@link CallNotPermittedException} so callers
     * don't need to import the Resilience4j type directly.
     */
    public static class UploadStorageException extends RuntimeException {
        /**
         * @param msg   human-readable explanation, surfaced in HTTP error responses
         * @param cause the originating {@link CallNotPermittedException}
         */
        public UploadStorageException(String msg, Throwable cause) { super(msg, cause); }
    }
}
