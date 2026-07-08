package games.snapdragon.bff.upload;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Comparator;

import static org.springframework.http.HttpStatus.BAD_REQUEST;

/**
 * Host-filesystem implementation of {@link UploadStorage}.
 *
 * <h2>Responsibility</h2>
 *
 * <p>Mirrors the pre-strategy behaviour of {@code GameUploadService} and
 * {@code AssetController}: extracted bundle files land under
 * {@code <gamesDir>/<slug>/<sha-prefix>/} and reads stream straight off disk
 * via Spring's {@link FileSystemResource}.
 *
 * <p>This is the default strategy (active when {@code app.uploads.storage} is
 * absent or set to {@code filesystem}). It requires no external dependencies
 * beyond the local filesystem, making it the right choice for development and
 * single-node deployments where the BFF and its storage share the same host.
 * For multi-pod or cloud deployments use {@link S3UploadStorage} instead.
 *
 * <h2>Locator format</h2>
 *
 * <pre>
 *   file:/absolute/path/to/&lt;gamesDir&gt;/&lt;slug&gt;/&lt;sha-prefix&gt;
 * </pre>
 *
 * <p>The {@code file:} prefix is the discriminator the strategy router uses.
 * {@link #pathFromLocator} also accepts a raw absolute path (no prefix) so
 * existing {@code game_uploads.version_dir} rows written before the prefix
 * scheme was introduced keep resolving without a migration.
 *
 * <h2>Safety</h2>
 *
 * <p>All read and exists calls normalise the resolved path and verify it still
 * starts with the version-directory base before touching the filesystem —
 * preventing a path-traversal attack via a crafted {@code relativePath}
 * (e.g. {@code ../../etc/passwd}).
 *
 * @see UploadStorage
 * @see UploadStorageConfig
 */
public class FilesystemUploadStorage implements UploadStorage {

    private static final Logger log = LoggerFactory.getLogger(FilesystemUploadStorage.class);

    /** Locator scheme prefix; used to identify filesystem locators at read time. */
    private static final String PREFIX = "file:";

    /** Root directory under which all game bundles are stored, keyed by slug. */
    private final Path gamesDir;

    /**
     * @param gamesDir root directory for bundle storage; created if absent
     *                 by the first {@link #installFromStaging} call
     */
    public FilesystemUploadStorage(Path gamesDir) {
        this.gamesDir = gamesDir;
    }

    // -------------------------------------------------------------------------
    // UploadStorage implementation
    // -------------------------------------------------------------------------

    /**
     * Atomically installs the files in {@code stagingDir} under
     * {@code <gamesDir>/<slug>/<versionPrefix>} using an atomic rename.
     *
     * <p>If a version directory with the same prefix already exists (same
     * bundle hash re-uploaded), the staging directory is discarded and the
     * existing version is re-used — idempotent installs at no extra cost.
     *
     * <p>On failure the staging directory is cleaned up before the exception
     * propagates so orphaned temp files don't accumulate.
     *
     * @param slug          the game slug identifying the sub-directory
     * @param versionPrefix short SHA prefix used as the version directory name
     * @param stagingDir    source directory to move; consumed by this call
     * @return a {@code file:}-prefixed locator pointing at the installed
     *         version directory
     * @throws ResponseStatusException HTTP 400 on any filesystem error
     */
    @Override
    public String installFromStaging(String slug, String versionPrefix, Path stagingDir) {
        try {
            // Ensure the per-slug directory exists before resolving the version dir.
            Path slugDir = gamesDir.resolve(slug);
            Files.createDirectories(slugDir);
            Path versionDir = slugDir.resolve(versionPrefix);
            if (Files.exists(versionDir)) {
                // Same hash already installed — drop the staging dir and
                // re-use the existing version directory.
                deleteRecursively(stagingDir);
            } else {
                // Atomic rename: either the version dir fully appears or it
                // doesn't — no partially-written bundles visible to readers.
                Files.move(stagingDir, versionDir, StandardCopyOption.ATOMIC_MOVE);
            }
            return PREFIX + versionDir;
        } catch (IOException ex) {
            // Clean up the staging directory even on failure — don't leave
            // orphaned temp files behind.
            deleteRecursively(stagingDir);
            throw new ResponseStatusException(BAD_REQUEST, "filesystem install failed: " + ex.getMessage());
        }
    }

    /**
     * Streams the file at {@code locator/relativePath} as a
     * {@link FileSystemResource}. Returns {@code null} for missing files or
     * when the resolved path would escape the version directory.
     *
     * @param locator      {@code file:}-prefixed locator from
     *                     {@link #installFromStaging}, or a legacy raw path
     * @param relativePath file path within the version directory
     * @return a {@link FileSystemResource} ready to stream, or {@code null}
     */
    @Override
    public Resource read(String locator, String relativePath) {
        Path base = pathFromLocator(locator);
        if (base == null) return null;
        // Normalise to prevent path-traversal (e.g. ../../etc/passwd).
        Path target = base.resolve(relativePath).normalize();
        if (!target.startsWith(base) || !Files.isRegularFile(target)) {
            return null;
        }
        return new FileSystemResource(target);
    }

    /**
     * Cheap existence probe: resolves {@code locator/relativePath} and checks
     * it is a regular file without reading its bytes. Returns {@code false} on
     * any path-traversal attempt.
     *
     * @param locator      {@code file:}-prefixed locator or legacy raw path
     * @param relativePath file path within the version directory
     * @return {@code true} iff the file exists and is a regular file
     */
    @Override
    public boolean exists(String locator, String relativePath) {
        Path base = pathFromLocator(locator);
        if (base == null) return false;
        // Normalise before the startsWith containment check.
        Path target = base.resolve(relativePath).normalize();
        return target.startsWith(base) && Files.isRegularFile(target);
    }

    /**
     * Recursively deletes the version directory identified by {@code locator}.
     * No-ops when the locator is null, blank, or the directory no longer exists.
     *
     * @param locator {@code file:}-prefixed locator or legacy raw path
     */
    @Override
    public void deleteVersion(String locator) {
        Path base = pathFromLocator(locator);
        if (base == null || !Files.exists(base)) return;
        deleteRecursively(base);
    }

    /**
     * Returns {@code "filesystem"} — the short label surfaced in startup logs
     * and used as a discriminator for strategy-aware log lines.
     *
     * @return the string {@code "filesystem"}
     */
    @Override
    public String label() {
        return "filesystem";
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    /**
     * Resolves a filesystem path from a locator string. Accepts both the
     * prefixed form ({@code file:/path}) introduced by this strategy AND a raw
     * absolute path so existing {@code game_uploads.version_dir} rows written
     * before the prefix scheme was adopted keep resolving without a migration.
     * New writes always use the prefixed form.
     *
     * @param locator a {@code file:}-prefixed locator or a legacy absolute path;
     *                may be {@code null} or blank
     * @return the normalised {@link Path}, or {@code null} when the input is
     *         null or blank
     */
    private static Path pathFromLocator(String locator) {
        if (locator == null || locator.isBlank()) return null;
        // Strip the file: prefix when present; keep a bare path as-is.
        String raw = locator.startsWith(PREFIX) ? locator.substring(PREFIX.length()) : locator;
        return Path.of(raw).normalize();
    }

    /**
     * Walks {@code dir} in reverse depth-first order and deletes every entry,
     * then the directory itself. Silently swallows per-file delete errors (best
     * effort) but logs a warning at the directory level so a stuck cleanup is
     * observable.
     *
     * @param dir the directory to remove recursively; no-op when null or absent
     */
    private static void deleteRecursively(Path dir) {
        if (dir == null || !Files.exists(dir)) return;
        try (var stream = Files.walk(dir)) {
            // Reverse order ensures files are deleted before their parent dirs.
            stream.sorted(Comparator.reverseOrder()).forEach(p -> {
                try { Files.deleteIfExists(p); }
                catch (IOException ignored) {}
            });
        } catch (IOException ex) {
            log.warn("[upload-storage] failed to delete {}", dir, ex);
        }
    }
}
