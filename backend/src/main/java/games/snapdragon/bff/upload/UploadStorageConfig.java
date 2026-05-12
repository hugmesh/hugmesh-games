package games.snapdragon.bff.upload;

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
 * Selects an {@link UploadStorage} implementation by {@code app.uploads.storage}
 * config. Defaults to filesystem so a vanilla {@code docker compose up -d}
 * keeps working without MinIO. Set {@code app.uploads.storage=s3} to swap
 * in the S3 strategy.
 *
 * <p>For the S3 path the four required env vars are:
 * <ul>
 *   <li>{@code APP_UPLOADS_S3_ENDPOINT} — endpoint URI (e.g. {@code http://minio:9000})</li>
 *   <li>{@code APP_UPLOADS_S3_REGION}   — region label (any string works for MinIO; AWS expects a real one)</li>
 *   <li>{@code APP_UPLOADS_S3_ACCESS_KEY} / {@code _SECRET_KEY} — credentials</li>
 *   <li>{@code APP_UPLOADS_S3_BUCKET}   — bucket name (must exist; create with the MinIO init script)</li>
 * </ul>
 */
@Configuration
public class UploadStorageConfig {

    private static final Logger log = LoggerFactory.getLogger(UploadStorageConfig.class);

    @Bean
    @ConditionalOnProperty(name = "app.uploads.storage", havingValue = "filesystem", matchIfMissing = true)
    public UploadStorage filesystemUploadStorage(
            @Value("${app.uploads.games-dir:/var/snapdragon/games}") String gamesDir) {
        log.info("[upload-storage] filesystem mode → {}", gamesDir);
        return new FilesystemUploadStorage(Path.of(gamesDir));
    }

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
        log.info("[upload-storage] s3 mode → endpoint={} bucket={} region={} pathStyle={}",
                endpoint, bucket, region, pathStyleAccess);
        var builder = S3Client.builder()
                .region(Region.of(region))
                .httpClient(UrlConnectionHttpClient.builder().build())
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create(accessKey, secretKey)))
                .serviceConfiguration(S3Configuration.builder()
                        .pathStyleAccessEnabled(pathStyleAccess)
                        .build());
        if (!endpoint.isBlank()) {
            builder = builder.endpointOverride(URI.create(endpoint));
        }
        // Companion presigner — shares credentials + region + endpoint
        // + path-style with the S3Client so presigned URLs target the
        // same bucket the regular client reads from. Wired into
        // S3UploadStorage as the third constructor arg so
        // `presignReadUrl` can mint short-lived GET URLs when
        // AssetController opts into the redirect path
        // (`app.uploads.s3.presigned-reads=true`). Decision-020
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
        // Wrap with Resilience4j circuit breaker — a hung S3 endpoint
        // otherwise blocks every AssetController request on the thread
        // pool until it times out. Reads soft-fall through to null on
        // breaker-open (AssetController has a classpath fallback);
        // writes propagate so the catalog doesn't silently miss
        // installs. Tuning lives in application.yml under
        // resilience4j.circuitbreaker.instances.s3 (decision-020
        // follow-up).
        return new CircuitBreakerUploadStorage(
                raw,
                circuitBreakerRegistry.circuitBreaker("s3"));
    }
}
