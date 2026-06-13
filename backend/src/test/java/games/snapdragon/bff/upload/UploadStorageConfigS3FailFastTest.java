package games.snapdragon.bff.upload;

import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The s3 storage bean must refuse to start with blank credentials so the
 * AWS SDK can't silently fall through to its default credential provider
 * chain and authenticate against the wrong bucket (NIST IA-5; OWASP ASVS
 * 6.2.2).
 */
class UploadStorageConfigS3FailFastTest {

    private final UploadStorageConfig config = new UploadStorageConfig();
    private final CircuitBreakerRegistry cbr = CircuitBreakerRegistry.ofDefaults();

    @Test
    void blankAccessKeyRefusesToBuildS3Client() {
        assertThatThrownBy(() -> config.s3UploadStorage(
                "http://minio:9000", "us-east-1", "", "secret",
                "bucket", true, cbr))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("APP_UPLOADS_S3_ACCESS_KEY");
    }

    @Test
    void blankSecretKeyRefusesToBuildS3Client() {
        assertThatThrownBy(() -> config.s3UploadStorage(
                "http://minio:9000", "us-east-1", "access", "  ",
                "bucket", true, cbr))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("APP_UPLOADS_S3_SECRET_KEY");
    }

    @Test
    void presentCredentialsBuildTheS3Client() {
        assertThatCode(() -> config.s3UploadStorage(
                "http://minio:9000", "us-east-1", "access", "secret",
                "bucket", true, cbr))
                .doesNotThrowAnyException();
    }
}
