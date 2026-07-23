package games.hugmesh.bff.upload;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.Resource;
import org.springframework.web.server.ResponseStatusException;
import software.amazon.awssdk.core.ResponseBytes;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.Delete;
import software.amazon.awssdk.services.s3.model.DeleteObjectsRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.ObjectIdentifier;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Object;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest;
import software.amazon.awssdk.services.s3.presigner.model.PresignedGetObjectRequest;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

import static org.springframework.http.HttpStatus.BAD_REQUEST;

/**
 * S3-compatible implementation of {@link UploadStorage}.
 *
 * <h2>Responsibility</h2>
 *
 * <p>Stores game bundle files in an S3-compatible object store. Works against
 * AWS S3 in production and MinIO in dev / CI (the {@code compose.yml} ships a
 * {@code minio} container that the {@code integration-test} Spring profile
 * points at). Bundle files land under {@code s3://<bucket>/<slug>/<sha-prefix>/}
 * keyed by their relative path inside the staging directory.
 *
 * <h2>Locator format</h2>
 *
 * <pre>
 *   s3://&lt;bucket&gt;/&lt;slug&gt;/&lt;sha-prefix&gt;
 * </pre>
 *
 * <p>The {@code s3://} prefix is the discriminator the strategy router uses.
 * The bucket in the locator is cross-checked against the configured bucket at
 * read time; a mismatch logs a warning and returns a clean miss rather than
 * reading from the wrong store.
 *
 * <h2>Synchronous client rationale</h2>
 *
 * <p>This implementation deliberately uses the synchronous {@link S3Client}
 * wrapped in {@code Mono.fromCallable(...).subscribeOn(boundedElastic())} at
 * call sites. The AWS SDK's reactive/async client would pull in Netty NIO which
 * conflicts with Reactor Netty already on the classpath, so the sync client is
 * the safe choice here.
 *
 * <h2>Presigned URL support (decision-020)</h2>
 *
 * <p>When wired with a non-null {@link S3Presigner} the strategy can return a
 * short-lived GET URL the browser fetches directly from the object store,
 * removing the WASM asset hot-path from the BFF cold path. TTLs are capped at
 * {@link #MAX_PRESIGN_TTL} regardless of what the caller requests.
 *
 * @see UploadStorage
 * @see FilesystemUploadStorage
 * @see CircuitBreakerUploadStorage
 * @see UploadStorageConfig
 */
public class S3UploadStorage implements UploadStorage {

    private static final Logger log = LoggerFactory.getLogger(S3UploadStorage.class);

    /** Locator scheme prefix; identifies S3 locators at read time. */
    private static final String PREFIX = "s3://";

    /**
     * Hard cap on every presigned URL — defensive guard against a
     * caller-supplied long TTL leaking a long-lived URL into a log
     * or a CDN. The AssetController never asks for more than this.
     */
    private static final Duration MAX_PRESIGN_TTL = Duration.ofMinutes(15);

    /** Synchronous S3 client shared across all operations. */
    private final S3Client s3;

    /** Bucket name into which all bundles are written and from which they are read. */
    private final String bucket;

    /**
     * Optional — present only when the same S3 client config supports
     * presigning (always true for AWS SDK v2 in this codebase; left
     * null-checkable for forward-compat / test doubles).
     */
    private final S3Presigner presigner;

    /**
     * Constructs a storage instance without presigning support.
     *
     * @param s3     synchronous S3 client configured for the target endpoint
     * @param bucket bucket name that must already exist
     */
    public S3UploadStorage(S3Client s3, String bucket) {
        this(s3, bucket, null);
    }

    /**
     * Constructs a storage instance with optional presigning support.
     *
     * @param s3       synchronous S3 client configured for the target endpoint
     * @param bucket   bucket name that must already exist
     * @param presigner presigner sharing the same credentials and endpoint, or
     *                 {@code null} to disable presigned URL generation
     */
    public S3UploadStorage(S3Client s3, String bucket, S3Presigner presigner) {
        this.s3 = s3;
        this.bucket = bucket;
        this.presigner = presigner;
    }

    // -------------------------------------------------------------------------
    // UploadStorage implementation
    // -------------------------------------------------------------------------

    /**
     * Walks {@code stagingDir} and uploads every regular file to S3 under
     * {@code <slug>/<versionPrefix>/<relative-path>}. The staging directory is
     * always cleaned up after the walk, regardless of outcome.
     *
     * <p>There is no idempotency check like the filesystem strategy: re-uploading
     * the same bundle will overwrite the existing objects in-place (S3's
     * last-writer-wins semantics). This is acceptable because the sha-based
     * version prefix makes a collision extremely unlikely.
     *
     * @param slug          the game slug identifying the S3 key prefix
     * @param versionPrefix short SHA prefix used as the version directory name
     * @param stagingDir    source directory to upload; consumed by this call
     * @return an {@code s3://}-prefixed locator pointing at the uploaded version
     * @throws ResponseStatusException HTTP 400 on any S3 or filesystem error
     */
    @Override
    public String installFromStaging(String slug, String versionPrefix, Path stagingDir) {
        String keyPrefix = slug + "/" + versionPrefix;
        try (Stream<Path> walk = Files.walk(stagingDir)) {
            // Upload every file, preserving its relative path within the staging dir.
            walk.filter(Files::isRegularFile).forEach(p -> {
                // Normalise the separator so S3 keys always use forward slashes,
                // regardless of the host OS.
                String rel = stagingDir.relativize(p).toString().replace('\\', '/');
                String key = keyPrefix + "/" + rel;
                try {
                    s3.putObject(
                            PutObjectRequest.builder()
                                    .bucket(bucket)
                                    .key(key)
                                    .build(),
                            RequestBody.fromFile(p));
                } catch (Exception ex) {
                    throw new ResponseStatusException(BAD_REQUEST,
                            "S3 putObject failed for " + key + ": " + ex.getMessage());
                }
            });
        } catch (IOException ex) {
            throw new ResponseStatusException(BAD_REQUEST,
                    "S3 install walk failed: " + ex.getMessage());
        } finally {
            // Staging dir always cleaned regardless of outcome.
            deleteRecursively(stagingDir);
        }
        return PREFIX + bucket + "/" + keyPrefix;
    }

    /**
     * Downloads the S3 object at {@code locator/relativePath} and wraps its
     * bytes in a {@link ByteArrayResource}. Returns {@code null} for missing
     * keys ({@link NoSuchKeyException}) or unrecognised locators, and logs a
     * warning for unexpected S3 errors rather than propagating them (read
     * failures in the S3 strategy are best surfaced as cache misses so
     * {@code AssetController} can fall through to its classpath fallback).
     *
     * @param locator      {@code s3://}-prefixed locator from
     *                     {@link #installFromStaging}
     * @param relativePath file path within the version directory
     * @return a byte-array resource, or {@code null} on any miss or error
     */
    @Override
    public Resource read(String locator, String relativePath) {
        String key = keyFromLocator(locator, relativePath);
        if (key == null) return null;
        try {
            ResponseBytes<?> bytes = s3.getObjectAsBytes(
                    GetObjectRequest.builder().bucket(bucket).key(key).build());
            byte[] raw = bytes.asByteArray();
            if (raw == null) return null;
            // The key is passed as the description so log lines can identify
            // which object was fetched.
            return new ByteArrayResource(raw, key);
        } catch (NoSuchKeyException ex) {
            // Clean miss — the object doesn't exist; let the caller fall back.
            return null;
        } catch (Exception ex) {
            log.warn("[upload-storage:s3] read failed for {}: {}", key, ex.getMessage());
            return null;
        }
    }

    /**
     * Issues a HEAD request against the S3 object at {@code locator/relativePath}
     * as a cheap existence probe. Returns {@code false} on missing keys or
     * unrecognised locators without throwing.
     *
     * @param locator      {@code s3://}-prefixed locator or legacy raw path
     * @param relativePath file path within the version directory
     * @return {@code true} iff the object exists in the bucket
     */
    @Override
    public boolean exists(String locator, String relativePath) {
        String key = keyFromLocator(locator, relativePath);
        if (key == null) return false;
        try {
            // HEAD is cheaper than GET: no body transfer, just a metadata probe.
            s3.headObject(HeadObjectRequest.builder().bucket(bucket).key(key).build());
            return true;
        } catch (NoSuchKeyException ex) {
            return false;
        } catch (Exception ex) {
            log.warn("[upload-storage:s3] head failed for {}: {}", key, ex.getMessage());
            return false;
        }
    }

    /**
     * Lists all objects under the {@code locator/} prefix and deletes them in a
     * single batch {@code DeleteObjects} request. No-ops when the prefix resolves
     * to nothing or no objects are found; logs a warning on error rather than
     * throwing (pruning failures should not break the main request path).
     *
     * @param locator {@code s3://}-prefixed locator identifying the version prefix
     */
    @Override
    public void deleteVersion(String locator) {
        String prefix = keyPrefixFromLocator(locator);
        if (prefix == null) return;
        try {
            // List everything under the version prefix.
            ListObjectsV2Request listReq = ListObjectsV2Request.builder()
                    .bucket(bucket).prefix(prefix + "/").build();
            List<S3Object> objects = s3.listObjectsV2(listReq).contents();
            if (objects.isEmpty()) return;
            // Batch delete — more efficient than N individual DeleteObject calls.
            List<ObjectIdentifier> ids = objects.stream()
                    .map(o -> ObjectIdentifier.builder().key(o.key()).build())
                    .toList();
            s3.deleteObjects(DeleteObjectsRequest.builder()
                    .bucket(bucket)
                    .delete(Delete.builder().objects(ids).build())
                    .build());
        } catch (Exception ex) {
            log.warn("[upload-storage:s3] deleteVersion failed for {}: {}", prefix, ex.getMessage());
        }
    }

    /**
     * Returns {@code "s3"} — the short label surfaced in startup logs and used
     * as a discriminator for strategy-aware log lines. When wrapped by
     * {@link CircuitBreakerUploadStorage} that decorator appends {@code "+cb"}
     * to produce {@code "s3+cb"}.
     *
     * @return the string {@code "s3"}
     */
    @Override
    public String label() {
        return "s3";
    }

    // -------------------------------------------------------------------------
    // Presigned URL support (decision-020 follow-up)
    // -------------------------------------------------------------------------

    /**
     * Pre-signed GET URL (decision-020 follow-up). When the storage was
     * constructed with a non-null {@link S3Presigner}, returns a
     * short-lived URL the browser can fetch directly — taking the WASM
     * asset hot path off the BFF cold path. Requested TTLs are capped
     * at {@link #MAX_PRESIGN_TTL} so a misconfigured caller can't mint
     * a long-lived link.
     *
     * <p>Returns {@code null} when:
     * <ul>
     *   <li>The presigner wasn't wired (legacy two-arg constructor).</li>
     *   <li>The locator isn't an {@code s3://} URI for this bucket.</li>
     *   <li>The object doesn't exist (HEAD probe before presign).</li>
     *   <li>The presigning call throws for any reason.</li>
     * </ul>
     * The {@code AssetController} reads a {@code null} as "fall back to
     * streaming the bytes through me" so the change is a strict
     * opt-in performance optimisation, not a regression risk.
     *
     * @param locator      storage locator from {@link #installFromStaging}
     * @param relativePath path within the version directory
     * @param ttl          requested validity window; capped at
     *                     {@link #MAX_PRESIGN_TTL} regardless of the supplied value
     * @return a short-lived presigned GET URL, or {@code null} when
     *         presigning is unavailable or the object is absent
     */
    @Override
    public URI presignReadUrl(String locator, String relativePath, Duration ttl) {
        if (presigner == null) return null;
        String key = keyFromLocator(locator, relativePath);
        if (key == null) return null;
        // Probe existence before presigning — minting a URL for a missing key
        // produces a valid-looking but immediately-404 redirect.
        if (!exists(locator, relativePath)) return null;
        // Cap at MAX_PRESIGN_TTL: reject null, negative, zero, or over-long values.
        Duration effectiveTtl = (ttl == null || ttl.isNegative() || ttl.isZero()
                                 || ttl.compareTo(MAX_PRESIGN_TTL) > 0)
                                ? MAX_PRESIGN_TTL : ttl;
        try {
            GetObjectPresignRequest req = GetObjectPresignRequest.builder()
                    .signatureDuration(effectiveTtl)
                    .getObjectRequest(GetObjectRequest.builder()
                            .bucket(bucket).key(key).build())
                    .build();
            PresignedGetObjectRequest signed = presigner.presignGetObject(req);
            return signed.url().toURI();
        } catch (Exception ex) {
            log.warn("[upload-storage:s3] presign failed for {}: {}", key, ex.getMessage());
            return null;
        }
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    /**
     * Builds the full S3 object key for a file given the locator and relative
     * path. Returns {@code null} when the locator isn't an {@code s3://} URI
     * for this bucket (so a filesystem locator accidentally served by the S3
     * strategy is a clean miss rather than a runtime exception).
     *
     * @param locator      {@code s3://}-prefixed locator
     * @param relativePath file path within the version directory; leading
     *                     {@code /} is stripped if present
     * @return the full S3 object key, or {@code null} on any mismatch
     */
    private String keyFromLocator(String locator, String relativePath) {
        String prefix = keyPrefixFromLocator(locator);
        if (prefix == null) return null;
        String rel = relativePath == null ? "" : relativePath;
        if (rel.startsWith("/")) rel = rel.substring(1);
        return prefix + "/" + rel;
    }

    /**
     * Extracts the {@code <slug>/<prefix>} portion of an {@code s3://} locator,
     * verifying the embedded bucket name matches the configured one. Logs a
     * warning and returns {@code null} when the buckets differ — a sign of
     * cross-stack data access or a stale locator.
     *
     * @param locator {@code s3://}-prefixed locator string
     * @return the key prefix ({@code <slug>/<sha-prefix>}) or {@code null}
     */
    private String keyPrefixFromLocator(String locator) {
        if (locator == null || !locator.startsWith(PREFIX)) return null;
        // Strip the s3:// scheme, then split bucket from path on the first /.
        String stripped = locator.substring(PREFIX.length());
        int slash = stripped.indexOf('/');
        if (slash < 0) return null;
        String locBucket = stripped.substring(0, slash);
        if (!locBucket.equals(bucket)) {
            log.warn("[upload-storage:s3] locator bucket {} != configured bucket {}; cross-stack read?",
                    locBucket, bucket);
            return null;
        }
        return stripped.substring(slash + 1);
    }

    /**
     * Walks {@code dir} in reverse depth-first order and silently deletes every
     * entry. Used to clean the staging directory after upload, regardless of
     * whether the upload succeeded.
     *
     * @param dir the directory to remove recursively; no-op when null or absent
     */
    private static void deleteRecursively(Path dir) {
        if (dir == null || !Files.exists(dir)) return;
        try (var stream = Files.walk(dir)) {
            // Reverse order: files before their parent directories.
            stream.sorted(Comparator.reverseOrder()).forEach(p -> {
                try { Files.deleteIfExists(p); }
                catch (IOException ignored) {}
            });
        } catch (IOException ignored) {}
    }
}
