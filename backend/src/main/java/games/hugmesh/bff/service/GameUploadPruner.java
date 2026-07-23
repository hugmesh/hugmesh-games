package games.hugmesh.bff.service;

import games.hugmesh.bff.model.GameUpload;
import games.hugmesh.bff.repository.GameUploadRepository;
import games.hugmesh.bff.upload.UploadStorage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Scheduled sweeper that prunes old game-bundle version directories while
 * keeping a configurable number of rollback targets per slug.
 *
 * <h2>Responsibility</h2>
 *
 * <p>Every {@code POST /api/admin/games/upload} adds a row to
 * {@code game_uploads} and installs a new version directory via the
 * configured {@link UploadStorage} strategy (filesystem or S3). Without
 * pruning, a CI/CD pipeline that ships multiple builds per day will grow
 * the upload volume indefinitely. This sweeper enforces a bounded
 * version history: the latest {@code app.uploads.versions-to-keep} uploads
 * per game slug (active version + rollback targets) are retained; everything
 * older has its storage artifact removed.
 *
 * <p><b>Audit trail</b>: only the on-disk (or S3) {@code version_dir} is
 * deleted. The {@code game_uploads} DB row is preserved so the admin console
 * retains a complete upload history.
 *
 * <h2>Schedule</h2>
 *
 * <pre>
 *   fixed-rate   : ${app.uploads.prune-fixed-rate-ms}    (default 3 600 000 ms = 1 h)
 *   initial-delay: ${app.uploads.prune-initial-delay-ms} (default   600 000 ms = 10 min)
 * </pre>
 *
 * <p>The 10-minute initial delay prevents a prune from racing with bundles
 * that were just installed during an {@code e2e} test run and need a brief
 * window before they would otherwise be considered candidates for removal.
 *
 * <h2>Failure behaviour</h2>
 *
 * <p>Errors in the reactive pipeline are caught at subscription level and
 * logged as a WARNING. A failure mid-sweep (e.g. an I/O error deleting one
 * version directory) stops processing for the current run but leaves the
 * DB rows and the storage artifacts for any un-processed slugs intact —
 * the next scheduled run retries everything from scratch. No partial
 * commit or state tracking is performed.
 *
 * <h2>Caller-role access matrix</h2>
 *
 * <pre>
 *   Player      — not applicable (background task)
 *   Publisher   — not applicable
 *   Mod         — not applicable
 *   Admin       — upload-console lists remaining versions after prune
 *   Superadmin  — same as Admin
 * </pre>
 *
 * @see GameUploadRepository
 * @see UploadStorage#deleteVersion(String)
 */
@Service
public class GameUploadPruner {

    private static final Logger log = LoggerFactory.getLogger(GameUploadPruner.class);

    private final GameUploadRepository repo;
    private final UploadStorage storage;

    /** Minimum number of versions to retain per slug (clamped to at least 1). */
    private final int keepCount;

    public GameUploadPruner(GameUploadRepository repo,
                            UploadStorage storage,
                            @Value("${app.uploads.versions-to-keep:3}") int keepCount) {
        this.repo = repo;
        this.storage = storage;
        // Guard against a mis-configured zero or negative: always keep at least one version.
        this.keepCount = Math.max(1, keepCount);
    }

    // -------------------------------------------------------------------------
    // Scheduled prune
    // -------------------------------------------------------------------------

    /**
     * Loads the complete {@code game_uploads} table, groups rows by slug, and
     * removes the storage artifact (but not the DB row) for every version
     * beyond the newest {@link #keepCount} per slug.
     *
     * <p>Reactive marble diagram:
     * <pre>
     *   repo.findAll()
     *     → collectList()                       — fetch all upload rows at once
     *     → flatMapMany(pruneAcrossSlugs)        — group + sort + identify excess
     *       → for each excess version:
     *           removeVersionDir(upload)          — call storage.deleteVersion
     *     → subscribe(
     *           path → log INFO "removed …",
     *           err  → log WARN "sweep failed"
     *       )
     * </pre>
     */
    @Scheduled(fixedRateString = "${app.uploads.prune-fixed-rate-ms:3600000}",
               initialDelayString = "${app.uploads.prune-initial-delay-ms:600000}")
    public void prune() {
        repo.findAll()
                // Collect all rows so we can group them by slug in memory.
                .collectList()
                // Determine which version dirs to prune across every slug.
                .flatMapMany(this::pruneAcrossSlugs)
                .subscribe(
                        // Log each removed path so the admin can audit what was cleaned.
                        path -> log.info("[upload-pruner] removed version dir {}", path),
                        // A storage or DB error ends this run; the next scheduled run retries.
                        err  -> log.warn("[upload-pruner] sweep failed", err)
                );
    }

    // -------------------------------------------------------------------------
    // Pipeline stages
    // -------------------------------------------------------------------------

    /**
     * Groups the full upload list by slug, sorts each group newest-first, retains
     * the top {@link #keepCount} entries, and emits the {@code version_dir} locator
     * string for every excess entry after calling the storage strategy to delete it.
     *
     * <p>A slug with {@code keepCount} or fewer uploads emits nothing — no storage
     * is removed for that slug.
     *
     * @param all every {@code game_uploads} row currently in the DB
     * @return a {@link Flux} of version-dir locator strings that were successfully removed
     */
    private Flux<String> pruneAcrossSlugs(List<GameUpload> all) {
        // Group all rows by slug, preserving insertion order for deterministic iteration.
        Map<String, List<GameUpload>> bySlug = all.stream()
                .collect(Collectors.groupingBy(
                        GameUpload::getSlug,
                        LinkedHashMap::new,
                        Collectors.toList()));

        return Flux.fromIterable(bySlug.entrySet())
                .flatMap(entry -> {
                    // Sort newest-first so the head of the list is what we keep.
                    List<GameUpload> ordered = entry.getValue().stream()
                            .sorted(Comparator.comparing(GameUpload::getUploadedAt).reversed())
                            .toList();
                    // If the slug has fewer versions than the keep count, nothing to prune.
                    if (ordered.size() <= keepCount) {
                        return Flux.<String>empty();
                    }
                    // Everything past the keep window is excess — delete their storage artifacts.
                    return Flux.fromIterable(ordered.subList(keepCount, ordered.size()))
                            .flatMap(this::removeVersionDir);
                });
    }

    /**
     * Calls the configured {@link UploadStorage} strategy to remove the storage
     * artifact for a single excess upload. Returns the locator string on success
     * so the caller can log it; emits empty when the {@code version_dir} field
     * is null or blank (nothing to delete).
     *
     * <p>The {@link UploadStorage#deleteVersion(String)} call is wrapped in
     * {@link Mono#fromCallable} so blocking I/O (filesystem) or a synchronous
     * S3 SDK call runs safely off the event loop.
     *
     * @param upload the excess upload row whose storage artifact should be removed
     * @return a {@link Mono} emitting the locator string, or empty if no artifact
     */
    private Mono<String> removeVersionDir(GameUpload upload) {
        String locator = upload.getVersionDir();
        // Skip rows whose version_dir was never set (e.g. DB rows from before the
        // storage strategy was wired up).
        if (locator == null || locator.isBlank()) {
            return Mono.empty();
        }
        return Mono.fromCallable(() -> {
            // Delegate to the storage strategy — filesystem moves a directory,
            // S3 issues DeleteObject calls. Both are synchronous here.
            storage.deleteVersion(locator);
            return locator;
        });
    }
}
