package games.snapdragon.bff.upload;

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
 * S3-compatible implementation. Works against AWS S3 in prod and MinIO in
 * dev / CI (the {@code compose.yml} ships a {@code minio} container the
 * {@code integration-test} profile points at). Bundle files land under
 * {@code s3://<bucket>/<slug>/<sha-prefix>/} keyed by their relative path
 * inside the staging dir.
 *
 * <p>Locator format: {@code s3://<bucket>/<slug>/<sha-prefix>}.
 *
 * <p>The implementation deliberately uses the synchronous S3 client wrapped
 * in {@code Mono.fromCallable(...).subscribeOn(boundedElastic())} at call
 * sites; the AWS SDK's reactive client would pull in Netty NIO which
 * conflicts with Reactor Netty already on the classpath.
 */
public class S3UploadStorage implements UploadStorage {

    private static final Logger log = LoggerFactory.getLogger(S3UploadStorage.class);
    private static final String PREFIX = "s3://";

    /** Hard cap on every presigned URL — defensive guard against a
     *  caller-supplied long TTL leaking a long-lived URL into a log
     *  or a CDN. The AssetController never asks for more than this. */
    private static final Duration MAX_PRESIGN_TTL = Duration.ofMinutes(15);

    private final S3Client s3;
    private final String bucket;
    /** Optional — present only when the same S3 client config supports
     *  presigning (always true for AWS SDK v2 in this codebase; left
     *  null-checkable for forward-compat / test doubles). */
    private final S3Presigner presigner;

    public S3UploadStorage(S3Client s3, String bucket) {
        this(s3, bucket, null);
    }

    public S3UploadStorage(S3Client s3, String bucket, S3Presigner presigner) {
        this.s3 = s3;
        this.bucket = bucket;
        this.presigner = presigner;
    }

    @Override
    public String installFromStaging(String slug, String versionPrefix, Path stagingDir) {
        String keyPrefix = slug + "/" + versionPrefix;
        try (Stream<Path> walk = Files.walk(stagingDir)) {
            walk.filter(Files::isRegularFile).forEach(p -> {
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

    @Override
    public Resource read(String locator, String relativePath) {
        String key = keyFromLocator(locator, relativePath);
        if (key == null) return null;
        try {
            ResponseBytes<?> bytes = s3.getObjectAsBytes(
                    GetObjectRequest.builder().bucket(bucket).key(key).build());
            byte[] raw = bytes.asByteArray();
            if (raw == null) return null;
            return new ByteArrayResource(raw, key);
        } catch (NoSuchKeyException ex) {
            return null;
        } catch (Exception ex) {
            log.warn("[upload-storage:s3] read failed for {}: {}", key, ex.getMessage());
            return null;
        }
    }

    @Override
    public boolean exists(String locator, String relativePath) {
        String key = keyFromLocator(locator, relativePath);
        if (key == null) return false;
        try {
            s3.headObject(HeadObjectRequest.builder().bucket(bucket).key(key).build());
            return true;
        } catch (NoSuchKeyException ex) {
            return false;
        } catch (Exception ex) {
            log.warn("[upload-storage:s3] head failed for {}: {}", key, ex.getMessage());
            return false;
        }
    }

    @Override
    public void deleteVersion(String locator) {
        String prefix = keyPrefixFromLocator(locator);
        if (prefix == null) return;
        try {
            ListObjectsV2Request listReq = ListObjectsV2Request.builder()
                    .bucket(bucket).prefix(prefix + "/").build();
            List<S3Object> objects = s3.listObjectsV2(listReq).contents();
            if (objects.isEmpty()) return;
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

    @Override
    public String label() {
        return "s3";
    }

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
     *   <li>The locator isn't an s3:// URI for this bucket.</li>
     *   <li>The object doesn't exist (HEAD probe before presign).</li>
     * </ul>
     * The {@code AssetController} reads a {@code null} as "fall back to
     * streaming the bytes through me" so the change is a strict
     * opt-in performance optimisation, not a regression risk.
     */
    @Override
    public URI presignReadUrl(String locator, String relativePath, Duration ttl) {
        if (presigner == null) return null;
        String key = keyFromLocator(locator, relativePath);
        if (key == null) return null;
        if (!exists(locator, relativePath)) return null;
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

    // ── helpers ───────────────────────────────────────────────────────────

    /**
     * Build the full S3 key for a file given the locator + relative path.
     * Returns {@code null} when the locator isn't an s3:// URI (so a
     * filesystem locator served by the S3 strategy is a clean miss rather
     * than a runtime exception).
     */
    private String keyFromLocator(String locator, String relativePath) {
        String prefix = keyPrefixFromLocator(locator);
        if (prefix == null) return null;
        String rel = relativePath == null ? "" : relativePath;
        if (rel.startsWith("/")) rel = rel.substring(1);
        return prefix + "/" + rel;
    }

    /**
     * Extract the {@code <slug>/<prefix>} portion of an s3:// locator,
     * verifying the bucket matches our configured one.
     */
    private String keyPrefixFromLocator(String locator) {
        if (locator == null || !locator.startsWith(PREFIX)) return null;
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

    private static void deleteRecursively(Path dir) {
        if (dir == null || !Files.exists(dir)) return;
        try (var stream = Files.walk(dir)) {
            stream.sorted(Comparator.reverseOrder()).forEach(p -> {
                try { Files.deleteIfExists(p); }
                catch (IOException ignored) {}
            });
        } catch (IOException ignored) {}
    }
}
