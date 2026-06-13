package games.snapdragon.vnbuilder.build;

import games.snapdragon.vnbuilder.entity.*;
import games.snapdragon.vnbuilder.repository.VnBuildJobRepository;
import games.snapdragon.vnbuilder.repository.VnProjectRepository;
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
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Mirror of the rpg-builder worker: claim → render → export → upload.
 * The export phase iterates the project's Acts and produces one
 * {@code bundle/<act-slug>/index.html} per. With godot-enabled=false
 * the worker writes stub HTML so the upload + register flow is still
 * exercised end-to-end.
 */
@Component
public class GodotCompileWorker {

    private static final Logger log = LoggerFactory.getLogger(GodotCompileWorker.class);

    private final VnBuildJobRepository jobs;
    private final VnProjectRepository projects;
    private final VnGodotProjectRenderer renderer;
    private final GodotExporter exporter;
    private final BffUploadClient bff;
    private final Path workdir;
    private final boolean godotEnabled;
    private final AtomicBoolean inFlight = new AtomicBoolean(false);

    public GodotCompileWorker(VnBuildJobRepository jobs, VnProjectRepository projects,
                              VnGodotProjectRenderer renderer, GodotExporter exporter,
                              BffUploadClient bff,
                              @Value("${app.build.workdir}") String workdirCfg,
                              @Value("${app.build.godot-enabled}") boolean godotEnabled) {
        this.jobs = jobs; this.projects = projects; this.renderer = renderer;
        this.exporter = exporter; this.bff = bff;
        this.workdir = Path.of(workdirCfg); this.godotEnabled = godotEnabled;
    }

    @Scheduled(fixedDelayString = "${app.build.tick:PT5S}")
    public void tick() {
        if (!inFlight.compareAndSet(false, true)) return;
        jobs.claimNextQueued()
                .flatMap(this::execute)
                .doFinally(s -> inFlight.set(false))
                .subscribe(ok -> {}, err -> log.warn("[worker] tick aborted", err));
    }

    private Mono<VnBuildJob> execute(VnBuildJob job) {
        log.info("[worker] executing VN build {} for project {}", job.getId(), job.getProjectId());
        return projects.findById(job.getProjectId())
                .switchIfEmpty(Mono.error(new IllegalStateException("project gone")))
                .flatMap(project -> Mono.fromCallable(() -> Files.createDirectories(buildDir(job)))
                        .subscribeOn(Schedulers.boundedElastic())
                        .then(renderer.render(project, buildDir(job)))
                        .flatMap(rendered -> setStatus(job, BuildStatus.EXPORTING, 40, "rendered " + rendered.orderedActs().size() + " acts"))
                        .flatMap(j -> exportAndPackage(j, project))
                        .flatMap(zip -> uploadAndFinish(job, zip)))
                .onErrorResume(err -> fail(job, err.toString()));
    }

    private Path buildDir(VnBuildJob job) {
        return workdir.resolve(job.getProjectId().toString()).resolve(job.getId().toString());
    }

    private Mono<byte[]> exportAndPackage(VnBuildJob job, VnProject project) {
        return Mono.fromCallable(() -> doExportAndPackage(job, project)).subscribeOn(Schedulers.boundedElastic());
    }

    private byte[] doExportAndPackage(VnBuildJob job, VnProject project) throws IOException {
        Path bd = buildDir(job);
        Path godotSrc = bd.resolve("godot-src");
        Path stage = bd.resolve("stage");
        Files.createDirectories(stage.resolve("bundle"));

        List<Path> actDirs = listActDirs(godotSrc);
        if (actDirs.isEmpty()) {
            actDirs = List.of(godotSrc.resolve("acts/main"));
            Path bundleAct = stage.resolve("bundle/main");
            Files.createDirectories(bundleAct);
            if (godotEnabled) {
                exporter.exportWeb(godotSrc, bundleAct.resolve("index.html"));
            } else {
                writeStubBundle(bundleAct, project, "main");
            }
        } else {
            // Whole-project export runs ONCE, then is copied into each act's bundle dir
            // (the act a chapter boots is selected at runtime). Disabled mode writes a
            // per-act stub so zip → manifest → upload → register still runs end-to-end.
            Path firstExported = null;
            for (Path ad : actDirs) {
                String actSlug = ad.getFileName().toString();
                Path bundleAct = stage.resolve("bundle/" + actSlug);
                Files.createDirectories(bundleAct);
                if (godotEnabled) {
                    if (firstExported == null) {
                        exporter.exportWeb(godotSrc, bundleAct.resolve("index.html"));
                        firstExported = bundleAct;
                    } else {
                        copyBundle(firstExported, bundleAct);
                    }
                } else {
                    // Inline scenes_data.json into the stub HTML so the
                    // produced bundle still teaches the host shell what
                    // the VN looks like (helpful for review previews).
                    Path data = ad.resolve("scenes_data.json");
                    String inlinedJson = Files.exists(data) ? Files.readString(data, StandardCharsets.UTF_8) : "{}";
                    writeStubBundle(bundleAct, project, actSlug, inlinedJson);
                }
            }
        }

        String manifest = """
                {
                  "schema_version": 2,
                  "game": { "slug": "%s", "name": "%s", "description": "%s" },
                  "built_by": "snapdragon-vn-builder",
                  "built_at": "%s"
                }
                """.formatted(project.getSlug(), escape(project.getDisplayName()),
                              escape(project.getDescription() != null ? project.getDescription() : ""),
                              OffsetDateTime.now());
        Files.writeString(stage.resolve("manifest.json"), manifest, StandardCharsets.UTF_8);

        // Real chapters columns: (game_id, slug UNIQUE, name,
        // sort_order, asset_url, scene_type). Slug is globally unique
        // — namespace per-game with "<game>-<act>". See rpg-builder
        // GodotCompileWorker for the long-form rationale.
        StringBuilder sql = new StringBuilder();
        sql.append("INSERT INTO games (slug, name, description) VALUES ('")
           .append(project.getSlug()).append("', '")
           .append(escape(project.getDisplayName())).append("', '")
           .append(escape(project.getDescription() != null ? project.getDescription() : ""))
           .append("') ON CONFLICT (slug) DO UPDATE SET name = EXCLUDED.name, description = EXCLUDED.description;\n");
        int order = 0;
        for (Path ad : actDirs) {
            String actSlug = ad.getFileName().toString();
            String chapterSlug = project.getSlug() + "-" + actSlug;
            sql.append("INSERT INTO chapters (game_id, slug, name, sort_order, asset_url, scene_type) VALUES (")
               .append("(SELECT id FROM games WHERE slug = '").append(project.getSlug()).append("'), '")
               .append(chapterSlug).append("', '")
               .append(escape(actSlug)).append("', ").append(order++)
               // asset_url is a path WITHIN the unpacked version dir.
               // BFF's GameUploadService strips the `bundle/` prefix on
               // extract, so the chapter row must record the post-strip
               // path. See the RPG-side worker for the full rationale.
               .append(", '").append(actSlug).append("/index.html', 'vn')")
               .append(" ON CONFLICT (slug) DO UPDATE SET sort_order = EXCLUDED.sort_order, asset_url = EXCLUDED.asset_url;\n");
        }
        Files.writeString(stage.resolve("register.sql"), sql.toString(), StandardCharsets.UTF_8);

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

    private List<Path> listActDirs(Path godotSrc) throws IOException {
        Path actsDir = godotSrc.resolve("acts");
        if (!Files.exists(actsDir)) return List.of();
        try (var s = Files.list(actsDir)) {
            return s.filter(Files::isDirectory).sorted().toList();
        }
    }

    /** Copy a produced bundle ({@code index.html/js/wasm/pck}) from one act dir to
     *  another — multi-act games reuse the single whole-project export across chapters. */
    private static void copyBundle(Path src, Path dst) throws IOException {
        try (var files = Files.list(src)) {
            for (Path f : files.filter(Files::isRegularFile).toList()) {
                Files.copy(f, dst.resolve(f.getFileName()), StandardCopyOption.REPLACE_EXISTING);
            }
        }
    }

    private void writeStubBundle(Path bundleAct, VnProject project, String actSlug) throws IOException {
        writeStubBundle(bundleAct, project, actSlug, "{}");
    }

    private void writeStubBundle(Path bundleAct, VnProject project, String actSlug, String inlineJson) throws IOException {
        String displayName = project != null ? project.getDisplayName() : actSlug;
        String html = "<!DOCTYPE html><html><head><meta charset='utf-8'><title>"
                + escape(displayName) + " — " + actSlug + "</title></head><body>"
                + "<h1>" + escape(displayName) + " (" + actSlug + ")</h1>"
                + "<p>Stub VN bundle — replace once <code>app.build.godot-enabled=true</code>.</p>"
                + "<pre id='dataset'>" + escapeHtml(inlineJson) + "</pre>"
                + "</body></html>";
        Files.writeString(bundleAct.resolve("index.html"), html, StandardCharsets.UTF_8);
        Files.write(bundleAct.resolve("index.js"), new byte[]{});
        Files.write(bundleAct.resolve("index.wasm"), new byte[]{});
        Files.write(bundleAct.resolve("index.pck"), new byte[]{});
    }

    private Mono<VnBuildJob> uploadAndFinish(VnBuildJob job, byte[] zip) {
        return setStatus(job, BuildStatus.UPLOADING, 80, "uploading " + zip.length + " bytes")
                .flatMap(j -> bff.upload(zip, job.getTargetSlug() + ".zip", j.getBearerToken())
                        .flatMap(resp -> done(j, resp)));
    }

    private Mono<VnBuildJob> setStatus(VnBuildJob j, BuildStatus s, int pct, String line) {
        j.setStatus(s); j.setProgressPct(pct); j.setLogTail(append(j.getLogTail(), line));
        return jobs.save(j);
    }

    private Mono<VnBuildJob> done(VnBuildJob j, String resp) {
        j.setStatus(BuildStatus.DONE); j.setProgressPct(100); j.setFinishedAt(OffsetDateTime.now());
        j.setLogTail(append(j.getLogTail(), "BFF accepted: " + (resp == null ? "" : resp.replace('\n', ' '))));
        return jobs.save(j);
    }

    private Mono<VnBuildJob> fail(VnBuildJob j, String message) {
        log.warn("[worker] VN build {} failed: {}", j.getId(), message);
        j.setStatus(BuildStatus.FAILED);
        j.setErrorMessage(message);
        j.setFinishedAt(OffsetDateTime.now());
        j.setLogTail(append(j.getLogTail(), "FAILED: " + message));
        return jobs.save(j);
    }

    private static String append(String tail, String line) {
        String next = (tail == null ? "" : tail) + "[" + OffsetDateTime.now() + "] " + line + "\n";
        if (next.length() > 4096) next = next.substring(next.length() - 4096);
        return next;
    }

    private static String escape(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("'", "''").replace("\"", "\\\"");
    }

    private static String escapeHtml(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
