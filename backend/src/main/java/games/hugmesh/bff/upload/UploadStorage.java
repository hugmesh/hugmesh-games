package games.hugmesh.bff.upload;

import org.springframework.core.io.Resource;

import java.nio.file.Path;
import java.util.Map;

/**
 * Storage strategy for hot-uploaded game bundles.
 *
 * <h2>Responsibility</h2>
 *
 * <p>Abstracts where bundle bytes land after a publisher or admin posts a zip to
 * {@code POST /api/admin/games/upload}. Two implementations ship:
 * {@link FilesystemUploadStorage} writes files under a local directory
 * ({@code app.uploads.games-dir}), and {@link S3UploadStorage} writes to an
 * S3-compatible object store (MinIO in dev, real AWS S3 in prod). Both honour
 * the same logical layout so callers are blind to which backend is active:
 *
 * <pre>
 *   &lt;root&gt; / &lt;slug&gt; / &lt;sha-prefix&gt; / &lt;relative file&gt;
 * </pre>
 *
 * <p>{@code GameUploadService}, {@code AssetController}, and
 * {@code GameUploadPruner} all call into this interface; swapping the
 * implementation is a config-only change (see {@link UploadStorageConfig}).
 *
 * <h2>Locator contract</h2>
 *
 * <p>{@link #installFromStaging} returns an opaque <em>locator</em> string the
 * BFF persists in {@code game_uploads.version_dir}. The locator encodes the
 * strategy AND the version root so subsequent reads can reconstruct the full
 * path without knowing which implementation wrote it:
 *
 * <pre>
 *   filesystem  →  file:/var/hugmesh/games/&lt;slug&gt;/&lt;prefix&gt;
 *   S3          →  s3://&lt;bucket&gt;/&lt;slug&gt;/&lt;prefix&gt;
 * </pre>
 *
 * <p>Callers treat the locator as opaque. Serving the wrong strategy against a
 * locator from another strategy is a runtime error that both implementations
 * handle by returning {@code null} or an empty result rather than throwing.
 *
 * @see FilesystemUploadStorage
 * @see S3UploadStorage
 * @see CircuitBreakerUploadStorage
 * @see UploadStorageConfig
 */
public interface UploadStorage {

    /**
     * Persist every file in {@code stagingDir} under the logical key
     * {@code <slug>/<versionPrefix>/<relative-path-within-staging>}. Returns
     * an opaque locator the BFF stores in {@code game_uploads.version_dir} so
     * subsequent reads can find the same content.
     *
     * <p>The locator is the protocol-prefixed string the {@link #read} method
     * understands: {@code file:/var/hugmesh/games/<slug>/<prefix>} for the
     * filesystem strategy, {@code s3://<bucket>/<slug>/<prefix>} for S3.
     * Callers should treat it as opaque.
     *
     * <p>The implementation MAY move {@code stagingDir} (filesystem) or copy
     * its contents (S3). Either way the staging directory is consumed and
     * the caller must not reuse it.
     *
     * @param slug          the game's URL-safe slug (e.g. {@code three-wizards})
     * @param versionPrefix short SHA prefix derived from the bundle hash;
     *                      used as the version directory name
     * @param stagingDir    temporary directory that already holds the
     *                      extracted bundle files; ownership is transferred
     *                      to this method — the implementation removes it
     * @return an opaque locator identifying this version root; never {@code null}
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
     *
     * @param locator      storage locator from {@link #installFromStaging}
     * @param relativePath path within the version directory, e.g.
     *                     {@code index.html} or {@code wasm/game.wasm}
     * @return a {@link Resource} ready to stream, or {@code null} when the
     *         file does not exist or the locator is unrecognised
     */
    Resource read(String locator, String relativePath);

    /**
     * Whether {@code locator/relativePath} exists. Cheap probe used by
     * {@code AssetController} to decide between locator and classpath
     * fallback without buffering the file.
     *
     * @param locator      storage locator from {@link #installFromStaging}
     * @param relativePath path within the version directory
     * @return {@code true} iff the file exists and can be read
     */
    boolean exists(String locator, String relativePath);

    /**
     * Remove every file under {@code locator}. Called by
     * {@code GameUploadPruner} when a version is superseded and the
     * disk / bucket space should be reclaimed.
     *
     * @param locator storage locator from {@link #installFromStaging}
     *                identifying the version directory to remove
     */
    void deleteVersion(String locator);

    /**
     * Optional content-type override per extension. Today both strategies
     * defer to {@code AssetController}'s media-type table; the method is
     * exposed in the interface so future implementations (e.g. S3 with
     * pre-stored {@code Content-Type} metadata) can short-circuit it.
     *
     * @return a map from file extension (e.g. {@code ".wasm"}) to MIME type,
     *         or an empty map to let the controller decide
     */
    default Map<String, String> contentTypeOverrides() {
        return Map.of();
    }

    /**
     * Implementation name for log lines (e.g. {@code filesystem} / {@code s3} /
     * {@code s3+cb}). Surfaced on boot so a misconfigured stack is debuggable
     * without reading the bean graph.
     *
     * @return a short human-readable label identifying this storage strategy
     */
    String label();

    /**
     * Optional pre-signed read URL (decision-020 follow-up). When the
     * backing store supports it (S3-compatible buckets), the BFF can
     * 302-redirect asset reads to a short-lived URL the browser fetches
     * directly from the object store, taking the WASM hot-path off the
     * BFF cold path. Implementations that can't pre-sign — e.g. the
     * filesystem strategy — return {@code null} so {@code AssetController}
     * falls back to streaming the bytes through the BFF.
     *
     * <p>The returned URL MUST be valid for at least the TTL the caller
     * requested AND short enough that a leaked URL can't be replayed
     * indefinitely. S3-style presigners cap on the request's TTL.
     *
     * @param locator      storage locator from {@link #installFromStaging}
     * @param relativePath path within the version directory
     * @param ttl          requested validity window (capped by the backend)
     * @return a fetchable URL, or {@code null} if pre-signing isn't
     *         supported or the object doesn't exist
     */
    default java.net.URI presignReadUrl(String locator, String relativePath, java.time.Duration ttl) {
        return null;
    }
}
