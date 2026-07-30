package games.hugmesh.rpgbuilder.seed;

import games.hugmesh.rpgbuilder.entity.RpgAsset;
import games.hugmesh.rpgbuilder.repository.RpgAssetRepository;
import games.hugmesh.rpgbuilder.service.ProceduralAssetService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Resolves Liquibase-seeded asset ROWS into real bytes on disk at boot.
 *
 * <p>A changelog can't hard-code the runtime assets dir
 * ({@code app.build.assets-dir}), so seed changesets (008-3) insert the
 * row with a {@code seed:<token>} sentinel {@code storage_path} and
 * {@code byte_size = 0}. After Liquibase + context startup, this runner
 * finds every such row, generates deterministic house-style SVG bytes
 * via {@link ProceduralAssetService} (a pure function of kind+slug —
 * re-runs are byte-identical), writes them through the exact
 * {@code <assetsDir>/<projectId>/<assetId>} layout an upload uses, and
 * UPDATEs the row's storage_path + byte_size. The seeded asset is then
 * indistinguishable from an uploaded one to every downstream reader
 * (preview endpoint, renderer, build worker).
 *
 * <p>Idempotent: a row whose storage_path no longer starts with
 * {@code seed:} is skipped, and a boot race can never corrupt a render —
 * the renderer already warn+skips a storage_path that isn't a regular
 * file. A row that fails to materialize (unsupported kind, IO error) is
 * logged and skipped, never fails the boot.
 */
@Component
public class SeedAssetMaterializer implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(SeedAssetMaterializer.class);
    public static final String SEED_PREFIX = "seed:";

    private final RpgAssetRepository assets;
    private final ProceduralAssetService procedural;
    private final Path assetsDir;

    public SeedAssetMaterializer(RpgAssetRepository assets, ProceduralAssetService procedural,
                                 @Value("${app.build.assets-dir}") String assetsDirCfg) {
        this.assets = assets;
        this.procedural = procedural;
        this.assetsDir = Path.of(assetsDirCfg);
    }

    @Override
    public void run(ApplicationArguments args) {
        Long n = materialize().block();
        if (n != null && n > 0) {
            log.info("[seed] materialized {} seed asset(s) under {}", n, assetsDir);
        }
    }

    /** Materialize every pending {@code seed:*} row; emits the count. */
    public Mono<Long> materialize() {
        return assets.findByStoragePathStartingWith(SEED_PREFIX)
                // Re-check the prefix so only genuine sentinel rows are
                // rewritten even if the query over-returns.
                .filter(a -> a.getStoragePath() != null && a.getStoragePath().startsWith(SEED_PREFIX))
                .concatMap(this::materializeOne)
                .count();
    }

    private Mono<RpgAsset> materializeOne(RpgAsset a) {
        return Mono.fromCallable(() -> {
                    byte[] bytes = procedural.generate(a.getKind(), a.getSlug(), null);
                    Path projectDir = assetsDir.resolve(a.getProjectId().toString());
                    Files.createDirectories(projectDir);
                    Path target = projectDir.resolve(a.getId().toString());
                    Files.write(target, bytes);
                    a.setStoragePath(target.toString());
                    a.setByteSize(bytes.length);
                    return a;
                })
                .subscribeOn(Schedulers.boundedElastic())
                .flatMap(assets::save)
                .onErrorResume(e -> {
                    log.warn("[seed] could not materialize asset {} ({}): {}",
                            a.getId(), a.getSlug(), e.toString());
                    return Mono.empty();
                });
    }
}
