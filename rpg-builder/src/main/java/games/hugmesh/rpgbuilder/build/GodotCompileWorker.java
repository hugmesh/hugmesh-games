package games.hugmesh.rpgbuilder.build;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import games.hugmesh.rpgbuilder.entity.*;
import games.hugmesh.rpgbuilder.repository.RpgBuildJobRepository;
import games.hugmesh.rpgbuilder.repository.RpgProjectRepository;
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
import java.util.regex.Matcher;
import java.util.regex.Pattern;
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

    /** Mirrors the BFF's MAX_ZIP_ENTRIES (GameUploadService) — fail early if exceeded. */
    private static final int MAX_ZIP_ENTRIES = 500;

    /** Shared engine files moved into {@code bundle/_engine/} — everything a real Godot
     *  Web export emits EXCEPT the per-area {@code index.html}. Any additional file the
     *  export drops (e.g. a {@code *.wasm} GDExtension like {@code hugmesh_bridge.wasm})
     *  is moved too by {@link #realExportDeduped}. */
    private static final String ENGINE_DIR = "_engine";

    /** Locates the {@code "gdextensionLibs":[ … ]} array in the exported index.html so
     *  each entry can be re-pointed at {@code ../_engine/}. */
    private static final Pattern GDEXT_LIBS =
            Pattern.compile("\"gdextensionLibs\"\\s*:\\s*\\[([^\\]]*)\\]");

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
        Path bundle = stage.resolve("bundle");
        Files.createDirectories(bundle);

        // Chapter list (slug + human name + sort) comes from the renderer's
        // areas.json sidecar; a missing/unparseable sidecar falls back to the
        // scenes/rooms/<area> dir listing (name = slug, sort = discovery order).
        List<AreaInfo> areas = readAreas(godotSrc);

        if (godotEnabled) {
            // RESOLUTION 6 — engine dedup. A real Godot Web export is whole-project
            // and identical for every chapter, and the shared runtime (index.wasm ~1.5MB,
            // index.side.wasm ~41MB, the bridge wasm, …) dwarfs the per-area HTML.
            // Copying the whole bundle per area would multiply ~50MB by N areas and blow
            // the BFF heap + zip cap. Instead: export ONCE, move the shared engine files
            // into bundle/_engine/, and give each area dir ONLY a patched index.html that
            // references ../_engine/*. The area a chapter boots is chosen at runtime.
            realExportDeduped(godotSrc, bd, bundle, areas);
            // Stub-ship guard (defense in depth): when real export is ON, the shared
            // engine wasm must be real AND every area html must reference it. A stub can
            // never reach the BFF when godot-enabled=true — the build fails loudly.
            assertNoStubBundles(bundle, areas);
        } else {
            // Disabled mode keeps today's per-area dev-stub shape (unchanged contract):
            // a tiny HTML + empty index.js/wasm/pck per area so the rest of the pipeline
            // (zip → manifest → BFF upload → register.sql) still runs end-to-end.
            for (AreaInfo a : areas) {
                Path bundleAreaDir = bundle.resolve(a.slug());
                Files.createDirectories(bundleAreaDir);
                writeStubBundle(bundleAreaDir, project, a.slug());
            }
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
                  "built_by": "hugmesh-rpg-builder",
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
        for (AreaInfo a : areas) {
            String chapterSlug = project.getSlug() + "-" + a.slug();
            sql.append("INSERT INTO chapters (game_id, slug, name, sort_order, asset_url, scene_type) VALUES (")
               .append("(SELECT id FROM games WHERE slug = '").append(project.getSlug()).append("'), '")
               .append(chapterSlug).append("', '")
               // Human area name (from areas.json) — NOT the slug — so the chapter
               // chooser shows "Scorched Dunes", not "desert".
               .append(escape(a.name())).append("', ").append(a.sortOrder())
               // asset_url is a path WITHIN the unpacked version dir
               // (NOT within the zip). The BFF's GameUploadService
               // strips the `bundle/` prefix on extract — files land at
               // <versionDir>/<area>/index.html — so the chapter row's
               // asset_url must match THAT layout. Writing
               // `bundle/<area>/index.html` here used to leave a stale
               // path in the DB and made AssetController 404 every
               // maker-built chapter even when the bytes were on disk.
               .append(", '").append(a.slug()).append("/index.html', 'rpg')")
               // name refreshes too — a rebuild after renaming an area must update
               // the existing chapter row, not silently keep the stale name.
               .append(" ON CONFLICT (slug) DO UPDATE SET name = EXCLUDED.name, ")
               .append("sort_order = EXCLUDED.sort_order, asset_url = EXCLUDED.asset_url;\n");
        }
        // Default-open visibility (RESOLUTION 11): grant every player access so a
        // publisher-built game is playable without an out-of-band admin grant. The
        // (game_id, scope, scope_value) unique key has a NULL scope_value for the
        // 'all' row, and NULL never dedupes in a Postgres unique index — so we use
        // the same NOT EXISTS idempotency guard the platform's own game_access
        // backfills use (changelog 019/051/053) instead of ON CONFLICT. Allowlisted
        // by RegisterSqlPolicy. This is a product-posture change: maker games are now
        // world-visible on publish (flag it in the PR body).
        sql.append("INSERT INTO game_access (game_id, scope, scope_value, granted_by, granted_at) ")
           .append("SELECT g.id, 'all', NULL, 'system:rpg-builder', NOW() FROM games g WHERE g.slug = '")
           .append(project.getSlug())
           .append("' AND NOT EXISTS (SELECT 1 FROM game_access ga WHERE ga.game_id = g.id ")
           .append("AND ga.scope = 'all' AND ga.scope_value IS NULL);\n");
        Files.writeString(stage.resolve("register.sql"), sql.toString(), StandardCharsets.UTF_8);

        // Pack the staged tree into a zip in memory. NOTE (heap): the whole staged
        // tree is read into a ByteArrayOutputStream, so peak heap ≈ the uncompressed
        // bundle + the compressed zip. The _engine dedup above keeps this bounded (one
        // shared engine copy, not one per area) — a 6-area game stages ~50MB, not
        // ~300MB — but a very large project could still pressure the worker heap; a
        // future streaming-to-temp-file zip would remove the ceiling entirely.
        List<Path> files;
        try (var walk = Files.walk(stage)) {
            files = walk.filter(Files::isRegularFile).sorted().toList();
        }
        // Entry-count sanity: the BFF rejects bundles over MAX_ZIP_ENTRIES (500). A
        // real deduped bundle is ~20 entries; a runaway (e.g. a project that generated
        // thousands of scene files) is a bug we want to catch here, not at upload.
        if (files.size() > MAX_ZIP_ENTRIES) {
            throw new IOException("staged bundle has " + files.size() + " entries (>= "
                    + MAX_ZIP_ENTRIES + " BFF cap) — refusing to package");
        }
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (ZipOutputStream zos = new ZipOutputStream(baos)) {
            for (Path p : files) {
                String rel = stage.relativize(p).toString().replace('\\', '/');
                zos.putNextEntry(new ZipEntry(rel));
                zos.write(Files.readAllBytes(p));
                zos.closeEntry();
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

    /** Chapter metadata the worker needs: slug (bundle dir + chapter-slug suffix +
     *  asset_url), human name (chapter row) and sort order. */
    private record AreaInfo(String slug, String name, int sortOrder) {}

    /**
     * The ordered chapter list: prefer the renderer's {@code areas.json} sidecar
     * (human names + explicit sort), fall back to the {@code scenes/rooms/<area>}
     * dir listing (name = slug, sort = discovery order), and finally a single
     * {@code "main"} area so a degenerate project still produces one registerable
     * chapter.
     */
    private List<AreaInfo> readAreas(Path godotSrc) throws IOException {
        Path areasJson = godotSrc.resolve("areas.json");
        if (Files.exists(areasJson)) {
            try {
                JsonNode arr = JSON.readTree(Files.readString(areasJson, StandardCharsets.UTF_8));
                List<AreaInfo> out = new ArrayList<>();
                if (arr.isArray()) {
                    int i = 0;
                    for (JsonNode n : arr) {
                        String slug = n.path("slug").asText("");
                        if (slug.isBlank()) continue;
                        String name = n.path("name").asText(slug);
                        if (name.isBlank()) name = slug;
                        int sort = n.path("sortOrder").asInt(i);
                        out.add(new AreaInfo(slug, name, sort));
                        i++;
                    }
                }
                if (!out.isEmpty()) return out;
                log.warn("[worker] areas.json contained no usable areas; falling back to dir listing");
            } catch (com.fasterxml.jackson.core.JacksonException | RuntimeException e) {
                // Jackson's readTree throws the checked JsonProcessingException (a
                // JacksonException) on malformed JSON — catch it here so a corrupt
                // sidecar degrades to the dir listing as documented, rather than
                // failing the whole build. A genuine Files.readString IOException
                // still propagates.
                log.warn("[worker] areas.json unparseable ({}); falling back to dir listing", e.toString());
            }
        }
        List<Path> dirs = listAreaDirs(godotSrc);
        if (dirs.isEmpty()) {
            return List.of(new AreaInfo("main", "main", 0));
        }
        List<AreaInfo> out = new ArrayList<>();
        int i = 0;
        for (Path d : dirs) {
            String slug = d.getFileName().toString();
            out.add(new AreaInfo(slug, slug, i++));
        }
        return out;
    }

    /**
     * RESOLUTION 6 engine dedup. Export the whole project ONCE into a temp dir, move
     * every emitted file EXCEPT {@code index.html} into {@code bundle/_engine/} (the
     * shared runtime — index.js/.wasm/.side.wasm/.pck/.png, both audio worklets, and
     * any GDExtension {@code *.wasm}), then write each area dir a single patched
     * {@code index.html} that references {@code ../_engine/*}. The patch strings are the
     * empirically-proven ones from the biome probe (serve/). {@code _engine/} is a
     * sibling of the area dirs under {@code bundle/}, so no chapter row points at it and
     * the browser resolves {@code ../_engine/…} without any server-side {@code ..}.
     *
     * <p>GDExtension libs are the one exception to the {@code ../_engine/} re-pointing:
     * emscripten registers each {@code dynamicLibraries} entry in
     * {@code LDSO.loadedLibsByName} under its EXACT string, and Godot's descriptor
     * ({@code res://bin/<lib>.wasm}) falls back to a BASENAME lookup — so a
     * {@code ../_engine/}-prefixed entry is fetched fine but never found at dlopen
     * time and the extension silently degrades to placeholder classes (frozen player,
     * no BridgeGlue; live-diagnosed from the e2e Playwright trace). The entry must
     * stay a basename, and the wasm is copied into EVERY area dir (page-relative
     * fetch) AND kept in {@code _engine/} (script-relative fetch) so either
     * {@code locateFile} root resolves it.
     */
    private void realExportDeduped(Path godotSrc, Path bd, Path bundle, List<AreaInfo> areas) throws IOException {
        Path exportDir = bd.resolve("export");
        // Fresh export dir each run so a stale file can't leak into _engine.
        Files.createDirectories(exportDir);
        exporter.exportWeb(godotSrc, exportDir.resolve("index.html"));

        Path engine = bundle.resolve(ENGINE_DIR);
        Files.createDirectories(engine);
        String rawHtml = null;
        List<Path> gdextLibs = new ArrayList<>();
        try (var files = Files.list(exportDir)) {
            for (Path f : files.filter(Files::isRegularFile).toList()) {
                String name = f.getFileName().toString();
                if (name.equals("index.html")) {
                    rawHtml = Files.readString(f, StandardCharsets.UTF_8);
                    continue;   // per-area, patched below — never shared
                }
                Path moved = engine.resolve(name);
                Files.move(f, moved, StandardCopyOption.REPLACE_EXISTING);
                // GDExtension libs are every non-engine wasm (engine files are index.*).
                if (name.endsWith(".wasm") && !name.startsWith("index.")) {
                    gdextLibs.add(moved);
                }
            }
        }
        if (rawHtml == null) {
            throw new IOException("real export produced no index.html at " + exportDir);
        }
        String patched = patchAreaHtml(rawHtml);
        for (AreaInfo a : areas) {
            Path areaDir = bundle.resolve(a.slug());
            Files.createDirectories(areaDir);
            Files.writeString(areaDir.resolve("index.html"), patched, StandardCharsets.UTF_8);
            for (Path lib : gdextLibs) {
                Files.copy(lib, areaDir.resolve(lib.getFileName().toString()),
                        StandardCopyOption.REPLACE_EXISTING);
            }
        }
    }

    /**
     * Re-point an exported {@code index.html} at the shared {@code ../_engine/} bundle.
     * Exact patch set (biome probe serve/, curl-verified): script src, GODOT_CONFIG
     * {@code executable} (mainPack + side.wasm + audio worklets follow it via locateFile),
     * the {@code fileSizes} progress keys, the splash {@code <img>} src, and every
     * {@code gdextensionLibs} entry. Idempotent-ish: absent anchors are left untouched.
     */
    static String patchAreaHtml(String html) {
        String out = html
                .replace("src=\"index.js\"", "src=\"../_engine/index.js\"")
                .replace("\"executable\":\"index\"", "\"executable\":\"../_engine/index\"")
                .replace("\"index.pck\":", "\"../_engine/index.pck\":")
                .replace("\"index.wasm\":", "\"../_engine/index.wasm\":")
                .replace("src=\"index.png\"", "src=\"../_engine/index.png\"");
        return patchGdextensionLibs(out);
    }

    /** Normalize each {@code "gdextensionLibs":[…]} entry to its BASENAME — never a
     *  {@code ../_engine/} path. The entry string doubles as the emscripten
     *  {@code LDSO.loadedLibsByName} registration key, and Godot's dlopen of the
     *  descriptor path ({@code res://bin/<lib>.wasm}) falls back to a basename
     *  lookup: a path-shaped entry preloads fine but never MATCHES, so the
     *  extension silently loads as placeholders. The wasm itself is copied next to
     *  every area index.html (and kept in {@code _engine/}) by
     *  {@code realExportDeduped} so the basename fetch resolves. */
    static String patchGdextensionLibs(String html) {
        Matcher m = GDEXT_LIBS.matcher(html);
        if (!m.find()) return html;
        String inner = m.group(1).trim();
        if (inner.isEmpty()) return html;
        StringBuilder rebuilt = new StringBuilder("\"gdextensionLibs\":[");
        Matcher em = Pattern.compile("\"([^\"]+)\"").matcher(inner);
        boolean first = true;
        while (em.find()) {
            String lib = em.group(1);
            String base = lib.substring(lib.lastIndexOf('/') + 1);
            if (!first) rebuilt.append(",");
            rebuilt.append("\"").append(base).append("\"");
            first = false;
        }
        rebuilt.append("]");
        return html.substring(0, m.start()) + rebuilt + html.substring(m.end());
    }

    /**
     * Fail the build (real export ON) unless the deduped bundle is genuinely playable:
     * the shared {@code bundle/_engine/index.wasm} must be present + non-empty (a
     * zero-byte one is the dev-stub / broken-export signature), AND every area's
     * {@code index.html} must exist and reference {@code ../_engine/} (proof the area
     * html was patched to the shared engine, not left as an unpatched stub). A stub can
     * then never reach the BFF when {@code app.build.godot-enabled=true}.
     */
    private static void assertNoStubBundles(Path bundleRoot, List<AreaInfo> areas) throws IOException {
        Path engineWasm = bundleRoot.resolve(ENGINE_DIR).resolve("index.wasm");
        if (!Files.exists(engineWasm) || Files.size(engineWasm) == 0) {
            throw new IOException("refusing to ship a STUB bundle: bundle/" + ENGINE_DIR
                    + "/index.wasm is empty/absent while app.build.godot-enabled=true "
                    + "(real Godot export must produce real WASM)");
        }
        for (AreaInfo a : areas) {
            Path html = bundleRoot.resolve(a.slug()).resolve("index.html");
            if (!Files.exists(html) || Files.size(html) == 0) {
                throw new IOException("refusing to ship a STUB bundle: " + a.slug()
                        + "/index.html is missing/empty while app.build.godot-enabled=true");
            }
            if (!Files.readString(html, StandardCharsets.UTF_8).contains("../" + ENGINE_DIR + "/")) {
                throw new IOException("refusing to ship a STUB bundle: " + a.slug()
                        + "/index.html does not reference the shared ../" + ENGINE_DIR + "/ engine "
                        + "(area html was not patched — likely not a real Godot export)");
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
