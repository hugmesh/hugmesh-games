package com.threewizards.service;

import com.threewizards.model.GameUpload;
import com.threewizards.repository.GameUploadRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Bounded version-history cleanup for hot-uploaded games. Without
 * this every {@code POST /api/admin/games/upload} permanently grows
 * the {@code games_uploads} volume — a fresh CI/dev cycle stays
 * cheap, but a multi-month production deployment leaks.
 *
 * <p>Policy: keep the latest {@code app.uploads.versions-to-keep}
 * uploads per slug (default 3 — active + 2 rollback targets), prune
 * the rest. The DB row stays as audit trail; only the on-disk
 * {@code version_dir} is removed.
 *
 * <p>Schedule: every hour. Runs after a 10-minute initial delay so a
 * fresh {@code make e2e} doesn't have a sweep race with the freshly
 * installed bundles. Adjust via
 * {@code app.uploads.prune-fixed-rate-ms} +
 * {@code app.uploads.prune-initial-delay-ms}.
 */
@Service
public class GameUploadPruner {

    private static final Logger log = LoggerFactory.getLogger(GameUploadPruner.class);

    private final GameUploadRepository repo;
    private final int keepCount;

    public GameUploadPruner(GameUploadRepository repo,
                            @Value("${app.uploads.versions-to-keep:3}") int keepCount) {
        this.repo = repo;
        this.keepCount = Math.max(1, keepCount);
    }

    @Scheduled(fixedRateString = "${app.uploads.prune-fixed-rate-ms:3600000}",
               initialDelayString = "${app.uploads.prune-initial-delay-ms:600000}")
    public void prune() {
        repo.findAll()
                .collectList()
                .flatMapMany(this::pruneAcrossSlugs)
                .subscribe(
                        path -> log.info("[upload-pruner] removed version dir {}", path),
                        err  -> log.warn("[upload-pruner] sweep failed", err)
                );
    }

    /**
     * Group uploads by slug, sort each group newest-first, drop the
     * top {@code keepCount}, prune the rest. Returns the version
     * dirs that were removed for logging.
     */
    private Flux<String> pruneAcrossSlugs(List<GameUpload> all) {
        Map<String, List<GameUpload>> bySlug = all.stream()
                .collect(Collectors.groupingBy(
                        GameUpload::getSlug,
                        LinkedHashMap::new,
                        Collectors.toList()));

        return Flux.fromIterable(bySlug.entrySet())
                .flatMap(entry -> {
                    List<GameUpload> ordered = entry.getValue().stream()
                            .sorted(Comparator.comparing(GameUpload::getUploadedAt).reversed())
                            .toList();
                    if (ordered.size() <= keepCount) {
                        return Flux.<String>empty();
                    }
                    return Flux.fromIterable(ordered.subList(keepCount, ordered.size()))
                            .flatMap(this::removeVersionDir);
                });
    }

    private Mono<String> removeVersionDir(GameUpload upload) {
        String dir = upload.getVersionDir();
        if (dir == null || dir.isBlank()) {
            return Mono.empty();
        }
        return Mono.fromCallable(() -> {
            Path target = Path.of(dir).normalize();
            if (Files.exists(target)) {
                try (var stream = Files.walk(target)) {
                    stream.sorted(Comparator.reverseOrder()).forEach(p -> {
                        try { Files.deleteIfExists(p); }
                        catch (IOException ignored) {}
                    });
                }
            }
            return dir;
        });
    }
}
