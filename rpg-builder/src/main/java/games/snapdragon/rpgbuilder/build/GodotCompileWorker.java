package games.snapdragon.rpgbuilder.build;

import com.fasterxml.jackson.databind.ObjectMapper;
import games.snapdragon.rpgbuilder.entity.*;
import games.snapdragon.rpgbuilder.repository.RpgBuildJobRepository;
import games.snapdragon.rpgbuilder.repository.RpgProjectRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.OffsetDateTime;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Polls the build queue, drives a single job at a time through the
 * pipeline:
 *
 * <pre>
 *   QUEUED   → claimed by claimNextQueued()
 *   RENDERING → RpgGodotProjectRenderer.render()
 *   EXPORTING → run Godot export per Area (or write stubs if disabled)
 *   UPLOADING → POST bundle zip to BFF /api/admin/games/upload
 *   DONE | FAILED
 * </pre>
 *
 * <p>Single-flight guard: an in-process AtomicBoolean prevents the
 * scheduler from starting a second tick while the previous one is
 * still working. The DB-level FOR UPDATE SKIP LOCKED in
 * claimNextQueued covers the multi-pod case.
 */
@Component
public class GodotCompileWorker {

    private static final Logger log = LoggerFactory.getLogger(GodotCompileWorker.class);
    private static final ObjectMapper JSON = new ObjectMapper();

    private final RpgBuildJobRepository jobs;
    private final RpgProjectRepository projects;
    private final RpgGodotProjectRenderer renderer;
    private final GodotExporter exporter;
    private final BffUploadClient bff;
    private final Path workdir;
    private final boolean godotEnabled;

    private final AtomicBoolean inFlight = new AtomicBoolean(false);

    public GodotCompileWorker(RpgBuildJobRepository jobs, RpgProjectRepository projects,
                              RpgGodotProjectRenderer renderer, GodotExporter exporter,
                              BffUploadClient bff,
                              @Value("${app.build.workdir}") String workdirCfg,
                              @Value("${app.build.godot-enabled}") boolean godotEnabled) {
        this.jobs = jobs;
        this.projects = projects;
        this.renderer = renderer;
        this.exporter = exporter;
        this.bff = bff;
        this.workdir = Path.of(workdirCfg);
        this.godotEnabled = godotEnabled;
    }

    @Scheduled(fixedDelayString = "${app.build.tick:PT5S}")
    public void tick() {
        if (!inFlight.compareAndSet(false, true)) return;
        jobs.claimNextQueued()
                .flatMap(this::execute)
                .doFinally(s -> inFlight.set(false))
                .subscribe(
                        ok -> {},
                        err -> log.warn("[worker] tick aborted", err));
    }

    /** Package-private so the full pipeline (render → export → package → upload → status)
     *  is unit-testable end to end with test-double collaborators. */
    Mono<RpgBuildJob> execute(RpgBuildJob job) {
        log.info("[worker] executing build {} for project {}", job.getId(), job.getProjectId());
        return projects.findById(job.getProjectId())
                .switchIfEmpty(Mono.error(new IllegalStateException("project gone")))
                .flatMap(project -> Mono.fromCallable(() -> Files.createDirectories(buildDir(job)))
                        .subscribeOn(Schedulers.boundedElastic())
                        .then(renderer.render(project, buildDir(job)))
                        .flatMap(rendered -> setStatus(job, BuildStatus.EXPORTING, 40, "rendered " + rendered.orderedAreas().size() + " areas"))
                        .flatMap(j -> exportAndPackage(j, project))
                        .flatMap(zip -> uploadAndFinish(job, zip)))
                .onErrorResume(err -> fail(job, err.toString()));
    }

    private Path buildDir(RpgBuildJob job) {
        return workdir.resolve(job.getProjectId().toString()).resolve(job.getId().toString());
    }

    private Mono<byte[]> exportAndPackage(RpgBuildJob job, RpgProject project) {
        return Mono.fromCallable(() -> doExportAndPackage(job, project))
                .subscribeOn(Schedulers.boundedElastic());
    }

    private byte[] doExportAndPackage(RpgBuildJob job, RpgProject project) throws IOException {
        Path bd = buildDir(job);
        Path godotSrc = bd.resolve("godot-src");
        Path stage = bd.resolve("stage");
        Files.createDirectories(stage.resolve("bundle"));

        // Per-area export. When godotEnabled = false we emit a tiny
        // stub bundle per area so the rest of the pipeline (zip,
        // manifest, BFF upload, register.sql) is still exercised end-
        // to-end. Real Godot exports replace these stubs verbatim.
        List<Path> areaDirs = listAreaDirs(godotSrc);
        if (areaDirs.isEmpty()) {
            // Fallback: at least one chapter — re-use the project slug
            // as the area slug so the BFF still gets a registerable game.
            areaDirs = List.of(godotSrc.resolve("scenes/rooms/main"));
            Path bundleMain = stage.resolve("bundle/main");
            Files.createDirectories(bundleMain);
            if (godotEnabled) {
                exporter.exportWeb(godotSrc, bundleMain.resolve("index.html"));
            } else {
                writeStubBundle(bundleMain, project, "main");
            }
        } else {
            // Real export is whole-project, so run it ONCE and copy the bundle into
            // each area's dir (re-exporting per area would just re-emit identical
            // bytes — the area a chapter boots is selected at runtime, not at export).
            // Disabled mode writes a cheap per-area stub so the rest of the pipeline
            // (zip → manifest → BFF upload → register.sql) still runs end-to-end.
            Path firstExported = null;
            for (Path ad : areaDirs) {
                String areaSlug = ad.getFileName().toString();
                Path bundleAreaDir = stage.resolve("bundle/" + areaSlug);
                Files.createDirectories(bundleAreaDir);
                if (godotEnabled) {
                    if (firstExported == null) {
                        exporter.exportWeb(godotSrc, bundleAreaDir.resolve("index.html"));
                        firstExported = bundleAreaDir;
                    } else {
                        copyBundle(firstExported, bundleAreaDir);
                    }
                } else {
                    writeStubBundle(bundleAreaDir, project, areaSlug);
                }
            }
        }

        // Stub-ship guard (defense in depth): when real export is ON, no staged
        // area may carry an empty/absent index.wasm. GodotExporter already rejects
        // a stub-shaped export, but this re-checks the WHOLE staged tree just before
        // upload so a placeholder can never reach the BFF when godot-enabled=true —
        // the build fails loudly instead of silently shipping a non-playable bundle.
        if (godotEnabled) {
            assertNoStubBundles(stage.resolve("bundle"));
        }

        // manifest.json (BFF requires schema_version=2 + game.slug)
        String manifest = """
                {
                  "schema_version": 2,
                  "game": {
                    "slug": "%s",
                    "name": "%s",
                    "description": "%s"
                  },
                  "built_by": "snapdragon-rpg-builder",
                  "built_at": "%s"
                }
                """.formatted(project.getSlug(), escape(project.getDisplayName()),
                              escape(project.getDescription() != null ? project.getDescription() : ""),
                              OffsetDateTime.now());
        Files.writeString(stage.resolve("manifest.json"), manifest, StandardCharsets.UTF_8);

        // register.sql — one INSERT for the games row, then one row in
        // chapters per area. The actual chapters table has columns
        // (game_id, slug UNIQUE, name, sort_order, asset_url, scene_type)
        // — NOT (game_slug, asset_path) as the first cut assumed. The
        // chapter slug must be globally unique because the existing
        // levels_slug_key index is on `slug` alone, so we namespace it
        // with the game slug ("<game>-<area>").
        StringBuilder sql = new StringBuilder();
        sql.append("INSERT INTO games (slug, name, description) VALUES ('")
           .append(project.getSlug()).append("', '")
           .append(escape(project.getDisplayName())).append("', '")
           .append(escape(project.getDescription() != null ? project.getDescription() : ""))
           .append("') ON CONFLICT (slug) DO UPDATE SET name = EXCLUDED.name, description = EXCLUDED.description;\n");
        int order = 0;
        for (Path ad : areaDirs) {
            String areaSlug = ad.getFileName().toString();
            String chapterSlug = project.getSlug() + "-" + areaSlug;
            sql.append("INSERT INTO chapters (game_id, slug, name, sort_order, asset_url, scene_type) VALUES (")
               .append("(SELECT id FROM games WHERE slug = '").append(project.getSlug()).append("'), '")
               .append(chapterSlug).append("', '")
               .append(escape(areaSlug)).append("', ").append(order++)
               // asset_url is a path WITHIN the unpacked version dir
               // (NOT within the zip). The BFF's GameUploadService
               // strips the `bundle/` prefix on extract — files land at
               // <versionDir>/<area>/index.html — so the chapter row's
               // asset_url must match THAT layout. Writing
               // `bundle/<area>/index.html` here used to leave a stale
               // path in the DB and made AssetController 404 every
               // maker-built chapter even when the bytes were on disk.
               .append(", '").append(areaSlug).append("/index.html', 'rpg')")
               .append(" ON CONFLICT (slug) DO UPDATE SET sort_order = EXCLUDED.sort_order, asset_url = EXCLUDED.asset_url;\n");
        }
        Files.writeString(stage.resolve("register.sql"), sql.toString(), StandardCharsets.UTF_8);

        // Pack the staged tree into a zip in memory.
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (ZipOutputStream zos = new ZipOutputStream(baos)) {
            try (var walk = Files.walk(stage)) {
                walk.filter(Files::isRegularFile).forEach(p -> {
                    String rel = stage.relativize(p).toString().replace('\\', '/');
                    try {
                        zos.putNextEntry(new ZipEntry(rel));
                        zos.write(Files.readAllBytes(p));
                        zos.closeEntry();
                    } catch (IOException io) { throw new RuntimeException(io); }
                });
            }
        }
        return baos.toByteArray();
    }

    private List<Path> listAreaDirs(Path godotSrc) throws IOException {
        Path roomsDir = godotSrc.resolve("scenes/rooms");
        if (!Files.exists(roomsDir)) return List.of();
        try (var s = Files.list(roomsDir)) {
            return s.filter(Files::isDirectory).sorted().toList();
        }
    }

    /** Copy a produced bundle's files ({@code index.html/js/wasm/pck}) from one area
     *  dir to another — used when a multi-area game reuses the single whole-project
     *  export across its chapters. */
    private static void copyBundle(Path src, Path dst) throws IOException {
        try (var files = Files.list(src)) {
            for (Path f : files.filter(Files::isRegularFile).toList()) {
                Files.copy(f, dst.resolve(f.getFileName()), StandardCopyOption.REPLACE_EXISTING);
            }
        }
    }

    /**
     * Fail the build if any staged area bundle carries an empty/absent
     * {@code index.wasm} while real export is enabled — a stub must never be
     * uploaded as if it were a real export. Each {@code bundle/<area>/} produced
     * by a real Godot Web export has a multi-MB {@code index.wasm}; a zero-byte
     * one is the dev-stub signature.
     */
    private static void assertNoStubBundles(Path bundleRoot) throws IOException {
        if (!Files.isDirectory(bundleRoot)) return;
        try (var areas = Files.list(bundleRoot)) {
            for (Path area : areas.filter(Files::isDirectory).toList()) {
                Path wasm = area.resolve("index.wasm");
                if (!Files.exists(wasm) || Files.size(wasm) == 0) {
                    throw new IOException("refusing to ship a STUB bundle: "
                            + area.getFileName() + "/index.wasm is empty/absent while "
                            + "app.build.godot-enabled=true (real Godot export must produce real WASM)");
                }
            }
        }
    }

    private void writeStubBundle(Path bundleAreaDir, RpgProject project, String areaSlug) throws IOException {
        // A 1KB stand-in HTML + empty WASM/PCK so the BFF accepts the
        // bundle and the existing /api/chapters/{id}/scene path can
        // serve it back. The content is replaced byte-for-byte once
        // godot-enabled flips on.
        String displayName = project != null ? project.getDisplayName() : areaSlug;
        Files.writeString(bundleAreaDir.resolve("index.html"),
                "<!DOCTYPE html><html><head><meta charset='utf-8'><title>" + escape(displayName) + " — " + areaSlug
                        + "</title></head><body><h1>" + escape(displayName) + " (" + areaSlug + ")</h1>"
                        + "<p>Stub bundle — replace once <code>app.build.godot-enabled=true</code>.</p></body></html>",
                StandardCharsets.UTF_8);
        Files.write(bundleAreaDir.resolve("index.js"), new byte[]{});
        Files.write(bundleAreaDir.resolve("index.wasm"), new byte[]{});
        Files.write(bundleAreaDir.resolve("index.pck"), new byte[]{});
    }

    private Mono<RpgBuildJob> uploadAndFinish(RpgBuildJob job, byte[] zipBytes) {
        return setStatus(job, BuildStatus.UPLOADING, 80, "uploading " + zipBytes.length + " bytes")
                .flatMap(j -> bff.upload(zipBytes, job.getTargetSlug() + ".zip", j.getBearerToken())
                        .flatMap(resp -> done(j, resp)));
    }

    private Mono<RpgBuildJob> setStatus(RpgBuildJob j, BuildStatus s, int pct, String logLine) {
        j.setStatus(s);
        j.setProgressPct(pct);
        j.setLogTail(appendLog(j.getLogTail(), logLine));
        return jobs.save(j);
    }

    private Mono<RpgBuildJob> done(RpgBuildJob j, String resp) {
        j.setStatus(BuildStatus.DONE);
        j.setProgressPct(100);
        j.setFinishedAt(OffsetDateTime.now());
        j.setLogTail(appendLog(j.getLogTail(), "BFF accepted: " + (resp == null ? "" : resp.replace('\n', ' '))));
        return jobs.save(j);
    }

    private Mono<RpgBuildJob> fail(RpgBuildJob j, String message) {
        log.warn("[worker] build {} failed: {}", j.getId(), message);
        j.setStatus(BuildStatus.FAILED);
        j.setErrorMessage(message);
        j.setFinishedAt(OffsetDateTime.now());
        j.setLogTail(appendLog(j.getLogTail(), "FAILED: " + message));
        return jobs.save(j);
    }

    private static String appendLog(String tail, String line) {
        String next = (tail == null ? "" : tail) + "[" + OffsetDateTime.now() + "] " + line + "\n";
        if (next.length() > 4096) next = next.substring(next.length() - 4096);
        return next;
    }

    private static String escape(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("'", "''").replace("\"", "\\\"");
    }
}
