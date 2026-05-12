package games.snapdragon.bff.upload;

import org.springframework.core.io.Resource;

import java.nio.file.Path;
import java.util.Map;

/**
 * Storage strategy for hot-uploaded game bundles. The legacy implementation
 * writes to the host filesystem under {@code app.uploads.games-dir}; the
 * S3 implementation writes to an S3-compatible bucket (MinIO in dev, real
 * S3 in prod). Both honour the same logical layout:
 *
 * <pre>
 *   &lt;root&gt; / &lt;slug&gt; / &lt;sha-prefix&gt; / &lt;relative file&gt;
 * </pre>
 *
 * <p>{@code GameUploadService}, {@code AssetController}, and
 * {@code GameUploadPruner} all call into this interface; swapping the
 * implementation is a config-only change.
 */
public interface UploadStorage {

    /**
     * Persist every file in {@code stagingDir} under the logical key
     * {@code <slug>/<versionPrefix>/<relative-path-within-staging>}. Returns
     * an opaque locator the BFF stores in {@code game_uploads.version_dir} so
     * subsequent reads can find the same content.
     *
     * <p>The locator is the protocol-prefixed string the {@link #read} method
     * understands: {@code file:/var/snapdragon/games/<slug>/<prefix>} for the
     * filesystem strategy, {@code s3://<bucket>/<slug>/<prefix>} for S3.
     * Callers should treat it as opaque.
     *
     * <p>The implementation MAY move {@code stagingDir} (filesystem) or copy
     * its contents (S3). Either way the staging directory is consumed and
     * the caller must not reuse it.
     */
    String installFromStaging(String slug, String versionPrefix, Path stagingDir);

    /**
     * Stream the file at {@code locator/relativePath} as a Spring {@link Resource}.
     * Returns {@code null} when the file isn't present (caller decides whether
     * to fall back to classpath or 404).
     *
     * <p>The {@code locator} is one of the strings previously returned by
     * {@link #installFromStaging}; it carries enough information to identify
     * both the strategy AND the version (so a filesystem-locator served by
     * the S3 strategy is a runtime error worth catching).
     */
    Resource read(String locator, String relativePath);

    /**
     * Whether {@code locator/relativePath} exists. Cheap probe used by
     * {@code AssetController} to decide between locator and classpath
     * fallback without buffering the file.
     */
    boolean exists(String locator, String relativePath);

    /**
     * Remove every file under {@code locator}. Called by
     * {@code GameUploadPruner} when a version is superseded.
     */
    void deleteVersion(String locator);

    /**
     * Optional content-type override per extension. Today both strategies
     * defer to {@code AssetController}'s media-type table; the method is
     * exposed in the interface so future implementations (e.g. S3 with
     * pre-stored {@code Content-Type} metadata) can short-circuit it.
     */
    default Map<String, String> contentTypeOverrides() {
        return Map.of();
    }

    /**
     * Implementation name for log lines (e.g. {@code filesystem} / {@code s3}).
     * Surfaced on boot so a misconfigured stack is debuggable.
     */
    String label();
}
