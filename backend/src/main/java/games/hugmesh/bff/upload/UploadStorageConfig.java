package games.hugmesh.bff.upload;

import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

import java.net.URI;
import java.nio.file.Path;

/**
 * Selects and wires the active {@link UploadStorage} implementation.
 *
 * <h2>Responsibility</h2>
 *
 * <p>Reads {@code app.uploads.storage} at startup and contributes exactly one
 * {@link UploadStorage} bean. Two choices are available:
 *
 * <ul>
 *   <li><b>filesystem</b> (default, {@code matchIfMissing = true}) — a local
 *       directory strategy; no extra dependencies needed. Works with a vanilla
 *       {@code docker compose up -d}. Backed by
 *       {@link FilesystemUploadStorage}.</li>
 *   <li><b>s3</b> — writes to an S3-compatible object store (MinIO in dev, AWS
 *       S3 in prod). Requires four environment variables (see below). Backed by
 *       {@link S3UploadStorage} wrapped in
 *       {@link CircuitBreakerUploadStorage}.</li>
 * </ul>
 *
 * <h2>S3 configuration properties</h2>
 *
 * <pre>
 *   APP_UPLOADS_S3_ENDPOINT     endpoint URI     e.g. http://minio:9000  (omit for AWS)
 *   APP_UPLOADS_S3_REGION       region label     any string for MinIO; real region for AWS
 *   APP_UPLOADS_S3_ACCESS_KEY   access key ID    required; blank triggers startup failure
 *   APP_UPLOADS_S3_SECRET_KEY   secret access key required; blank triggers startup failure
 *   APP_UPLOADS_S3_BUCKET       bucket name      default: snapdragon-uploads (must pre-exist)
 *   APP_UPLOADS_S3_PATH_STYLE   path-style URLs  default: true (required for MinIO)
 * </pre>
 *
 * <h2>Credential validation</h2>
 *
 * <p>If either access-key or secret-key is blank when {@code s3} mode is
 * requested, the application refuses to start. Without this gate the AWS SDK
 * falls through to its default credential provider chain (environment variables,
 * {@code ~/.aws}, EC2/IRSA instance metadata) and may silently authenticate
 * as an unrelated identity against the wrong bucket — a quiet data-leak / wrong-
 * store failure mode (NIST IA-5; OWASP ASVS 6.2.2).
 *
 * <h2>S3 client design</h2>
 *
 * <p>The synchronous {@link S3Client} (URL-connection HTTP client, not Netty)
 * is chosen to avoid a second Netty instance on the classpath. Reactor Netty is
 * already present as the WebFlux transport; the AWS async client would pull in
 * a second Netty that conflicts with it.
 *
 * <p>A companion {@link S3Presigner} is always built and passed to
 * {@link S3UploadStorage}; it shares the same credentials, region, and endpoint
 * as the {@link S3Client} so presigned URLs target the same bucket (decision-020
 * follow-up: {@code AssetController} can redirect asset reads to presigned URLs
 * when {@code app.uploads.s3.presigned-reads=true}).
 *
 * <h2>Circuit-breaker wrapping</h2>
 *
 * <p>The S3 bean is always wrapped in a {@link CircuitBreakerUploadStorage} keyed
 * {@code "s3"}. Tuning lives in {@code application.yml} under
 * {@code resilience4j.circuitbreaker.instances.s3}. The filesystem bean is not
 * wrapped — local-disk failures are not transient and don't benefit from a breaker.
 *
 * @see UploadStorage
 * @see FilesystemUploadStorage
 * @see S3UploadStorage
 * @see CircuitBreakerUploadStorage
 */
@Configuration
public class UploadStorageConfig {

    private static final Logger log = LoggerFactory.getLogger(UploadStorageConfig.class);

    // -------------------------------------------------------------------------
    // Filesystem strategy (default)
    // -------------------------------------------------------------------------

    /**
     * Contributes the filesystem-backed {@link UploadStorage} bean when
     * {@code app.uploads.storage} is absent (default) or explicitly set to
     * {@code filesystem}.
     *
     * @param gamesDir root directory for bundle storage; defaults to
     *                 {@code /var/hugmesh/games} when the property is absent
     * @return a {@link FilesystemUploadStorage} rooted at {@code gamesDir}
     */
    @Bean
    @ConditionalOnProperty(name = "app.uploads.storage", havingValue = "filesystem", matchIfMissing = true)
    public UploadStorage filesystemUploadStorage(
            @Value("${app.uploads.games-dir:/var/hugmesh/games}") String gamesDir) {
        log.info("[upload-storage] filesystem mode → {}", gamesDir);
        return new FilesystemUploadStorage(Path.of(gamesDir));
    }

    // -------------------------------------------------------------------------
    // S3 strategy
    // -------------------------------------------------------------------------

    /**
     * Contributes the S3-backed {@link UploadStorage} bean when
     * {@code app.uploads.storage=s3}. Builds a synchronous {@link S3Client}
     * and companion {@link S3Presigner}, wraps them in {@link S3UploadStorage},
     * and decorates that with a Resilience4j {@link CircuitBreakerUploadStorage}.
     *
     * @param endpoint             S3-compatible endpoint URI; empty string means
     *                             use the AWS SDK's default (real AWS endpoint)
     * @param region               AWS region string or MinIO placeholder
     * @param accessKey            static access key ID; must be non-blank
     * @param secretKey            static secret access key; must be non-blank
     * @param bucket               target bucket name; must already exist
     * @param pathStyleAccess      {@code true} for path-style URLs
     *                             ({@code http://host/bucket/key}), required for
     *                             MinIO; {@code false} for virtual-host style
     *                             ({@code http://bucket.host/key}), used by AWS
     * @param circuitBreakerRegistry Resilience4j registry from which the
     *                               {@code "s3"} instance is resolved
     * @return a circuit-breaker-wrapped {@link S3UploadStorage} bean
     * @throws IllegalStateException when either credential is blank (fail-fast
     *                               to prevent silent default-credential-chain use)
     */
    @Bean
    @ConditionalOnProperty(name = "app.uploads.storage", havingValue = "s3")
    public UploadStorage s3UploadStorage(
            @Value("${app.uploads.s3.endpoint:}")   String endpoint,
            @Value("${app.uploads.s3.region:us-east-1}") String region,
            @Value("${app.uploads.s3.access-key:}") String accessKey,
            @Value("${app.uploads.s3.secret-key:}") String secretKey,
            @Value("${app.uploads.s3.bucket:snapdragon-uploads}") String bucket,
            @Value("${app.uploads.s3.path-style:true}") boolean pathStyleAccess,
            CircuitBreakerRegistry circuitBreakerRegistry) {

        // Fail fast on missing S3 credentials (NIST IA-5; OWASP ASVS 6.2.2).
        // Without this, blank access/secret keys let the AWS SDK silently
        // fall through to its default credential provider chain (env vars,
        // ~/.aws, EC2/IRSA instance metadata) and authenticate as some
        // unrelated identity against the wrong bucket — a quiet data-leak /
        // wrong-store failure mode. Refuse to construct the client instead.
        if (isBlank(accessKey) || isBlank(secretKey)) {
            throw new IllegalStateException(
                    "app.uploads.storage=s3 but S3 credentials are blank. "
                    + "Set both APP_UPLOADS_S3_ACCESS_KEY and APP_UPLOADS_S3_SECRET_KEY "
                    + "(refusing to start so the AWS SDK can't silently use the wrong "
                    + "default credential chain).");
        }
        log.info("[upload-storage] s3 mode → endpoint={} bucket={} region={} pathStyle={}",
                endpoint, bucket, region, pathStyleAccess);

        // Build the synchronous S3 client. The URL-connection HTTP client is
        // chosen over the async/Netty client to avoid a conflicting second
        // Netty instance alongside the already-present Reactor Netty.
        var builder = S3Client.builder()
                .region(Region.of(region))
                .httpClient(UrlConnectionHttpClient.builder().build())
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create(accessKey, secretKey)))
                .serviceConfiguration(S3Configuration.builder()
                        .pathStyleAccessEnabled(pathStyleAccess)
                        .build());
        if (!endpoint.isBlank()) {
            // Non-AWS endpoint override — required for MinIO and other S3-compatible stores.
            builder = builder.endpointOverride(URI.create(endpoint));
        }

        // Companion presigner — shares credentials, region, endpoint, and path-style
        // with the S3Client so presigned URLs target the same bucket the regular client
        // reads from. Wired into S3UploadStorage as the third constructor arg so
        // presignReadUrl() can mint short-lived GET URLs when AssetController opts
        // into the redirect path (app.uploads.s3.presigned-reads=true). Decision-020
        // follow-up.
        var presignerBuilder = S3Presigner.builder()
                .region(Region.of(region))
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create(accessKey, secretKey)))
                .serviceConfiguration(S3Configuration.builder()
                        .pathStyleAccessEnabled(pathStyleAccess)
                        .build());
        if (!endpoint.isBlank()) {
            presignerBuilder = presignerBuilder.endpointOverride(URI.create(endpoint));
        }

        UploadStorage raw = new S3UploadStorage(builder.build(), bucket, presignerBuilder.build());

        // Wrap with Resilience4j circuit breaker — a hung S3 endpoint otherwise
        // blocks every AssetController request on the thread pool until it times
        // out. Reads soft-fall through to null on breaker-open (AssetController
        // has a classpath fallback); writes propagate so the catalog doesn't
        // silently miss installs. Tuning lives in application.yml under
        // resilience4j.circuitbreaker.instances.s3 (decision-020 follow-up).
        return new CircuitBreakerUploadStorage(
                raw,
                circuitBreakerRegistry.circuitBreaker("s3"));
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    /**
     * Null-safe blank check used in the S3 credential guard.
     *
     * @param s the string to test
     * @return {@code true} when {@code s} is null or whitespace-only
     */
    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}
