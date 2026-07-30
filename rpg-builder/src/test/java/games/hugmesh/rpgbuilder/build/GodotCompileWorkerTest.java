package games.hugmesh.rpgbuilder.build;

import games.hugmesh.rpgbuilder.entity.*;
import games.hugmesh.rpgbuilder.repository.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * End-to-end specs for the {@link GodotCompileWorker} pipeline (render → export →
 * package → upload → status), driven with test-double collaborators (no Godot, no BFF,
 * no DB). Covers:
 * <ul>
 *   <li>the disabled-export STUB path produces a registerable per-area bundle and
 *       reaches DONE (unchanged dev contract);</li>
 *   <li>the enabled path exports ONCE and produces the RESOLUTION-6 deduped shape:
 *       {@code bundle/_engine/} holds the shared runtime, each area dir carries ONLY a
 *       patched {@code index.html} referencing {@code ../_engine/}, and no per-area
 *       {@code index.wasm};</li>
 *   <li>{@code assertNoStubBundles} refuses an empty shared engine wasm AND an area html
 *       that was never patched to {@code ../_engine/} (tamper case);</li>
 *   <li>register.sql carries the games upsert, per-area chapters with the areas.json
 *       human NAME + sort in the {@code ON CONFLICT DO UPDATE}, a default-open
 *       {@code game_access} grant, and the post-strip asset_url; and forwards the
 *       originating user's bearer.</li>
 * </ul>
 */
class GodotCompileWorkerTest {

    private final RpgBuildJobRepository jobs = mock(RpgBuildJobRepository.class);
    private final RpgProjectRepository projects = mock(RpgProjectRepository.class);

    // --- test doubles (concrete classes subclassed; Java-25 ByteBuddy can't mock them) ---

    /** Writes a one-area godot-src tree (scenes/rooms + areas.json sidecar) so the worker
     *  reads a human area NAME distinct from the slug, then returns the rendered project. */
    private static final class FakeRenderer extends RpgGodotProjectRenderer {
        FakeRenderer() {
            super(mock(RpgAreaRepository.class), mock(RpgRoomRepository.class), mock(RpgExitRepository.class),
                  mock(RpgItemRepository.class), mock(RpgNpcRepository.class), mock(RpgAssetRepository.class),
                  mock(RpgBuildingRepository.class), mock(RpgShopStockRepository.class),
                  mock(RpgAvatarConfigRepository.class), "");
        }
        @Override
        public Mono<RenderedProject> render(RpgProject project, Path workdir) {
            return Mono.fromCallable(() -> {
                Path godotSrc = workdir.resolve("godot-src");
                Files.createDirectories(godotSrc.resolve("scenes/rooms/lineland"));
                // areas.json drives chapter name (human) + sort_order.
                Files.writeString(godotSrc.resolve("areas.json"),
                        "[\n  {\"slug\": \"lineland\", \"name\": \"Lineland\", \"sortOrder\": 0, "
                        + "\"theme\": \"plain\", \"startRoomSlug\": \"town-square\"}\n]\n",
                        StandardCharsets.UTF_8);
                RpgArea a = new RpgArea();
                a.setId(UUID.randomUUID());
                a.setSlug("lineland");
                return new RenderedProject(godotSrc, List.of(a), List.of(), List.of(),
                        project.getDisplayName(), project.getDescription());
            });
        }
    }

    /** The engine files a real Godot Web export emits (everything but the per-area html). */
    private static void writeEngineFiles(Path dir) throws IOException {
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("index.js"), "// engine loader");
        Files.write(dir.resolve("index.wasm"), new byte[]{0, 'a', 's', 'm', 1, 2, 3, 4});
        Files.write(dir.resolve("index.side.wasm"), new byte[]{0, 'a', 's', 'm', 9});
        Files.write(dir.resolve("index.pck"), new byte[]{'P', 'C', 'K', 1});
        Files.write(dir.resolve("index.png"), new byte[]{'P', 'N', 'G'});
        Files.writeString(dir.resolve("index.audio.worklet.js"), "// audio");
        Files.writeString(dir.resolve("index.audio.position.worklet.js"), "// audio pos");
        Files.write(dir.resolve("hugmesh_bridge.wasm"), new byte[]{0, 'a', 's', 'm', 5});
    }

    /** A Godot-shaped index.html carrying every anchor {@code patchAreaHtml} rewrites. */
    private static String godotIndexHtml() {
        return "<!DOCTYPE html><html><head><title>x</title></head><body>"
                + "<img id=\"status-splash\" src=\"index.png\" alt=\"\">"
                + "<script src=\"index.js\"></script>"
                + "<script>const GODOT_CONFIG = {\"executable\":\"index\","
                + "\"fileSizes\":{\"index.pck\":4,\"index.wasm\":8},"
                + "\"gdextensionLibs\":[\"hugmesh_bridge.wasm\"]};</script>"
                + "</body></html>";
    }

    /** Real-ish whole-project export: writes the full engine set + a patchable index.html
     *  into the export dir. The worker then dedups it into _engine/ + patched area html. */
    private static final class FakeExporter extends GodotExporter {
        final AtomicInteger calls = new AtomicInteger();
        FakeExporter() { super("godot", Duration.ofMinutes(1), "Web", Duration.ofMinutes(1), true); }
        @Override
        public void exportWeb(Path godotSrc, Path outputIndexHtml) throws IOException {
            calls.incrementAndGet();
            Path out = outputIndexHtml.getParent();
            writeEngineFiles(out);
            Files.writeString(outputIndexHtml, godotIndexHtml(), StandardCharsets.UTF_8);
        }
    }

    /** Export "succeeds" but the shared engine wasm is EMPTY — a stub leaking through. */
    private static final class StubLeakingExporter extends GodotExporter {
        StubLeakingExporter() { super("godot", Duration.ofMinutes(1), "Web", Duration.ofMinutes(1), true); }
        @Override
        public void exportWeb(Path godotSrc, Path outputIndexHtml) throws IOException {
            Path out = outputIndexHtml.getParent();
            writeEngineFiles(out);
            Files.write(out.resolve("index.wasm"), new byte[]{});   // zero-byte engine wasm
            Files.writeString(outputIndexHtml, godotIndexHtml(), StandardCharsets.UTF_8);
        }
    }

    /** Export produces a real engine wasm but an index.html with NO patch anchors, so the
     *  staged area html never references ../_engine/ — the second stub-guard branch. */
    private static final class UnpatchableHtmlExporter extends GodotExporter {
        UnpatchableHtmlExporter() { super("godot", Duration.ofMinutes(1), "Web", Duration.ofMinutes(1), true); }
        @Override
        public void exportWeb(Path godotSrc, Path outputIndexHtml) throws IOException {
            writeEngineFiles(outputIndexHtml.getParent());
            Files.writeString(outputIndexHtml, "<html>no engine anchors here</html>", StandardCharsets.UTF_8);
        }
    }

    private static final class CapturingBff extends BffUploadClient {
        final AtomicReference<byte[]> zip = new AtomicReference<>();
        final AtomicReference<String> bearer = new AtomicReference<>();
        CapturingBff() { super(null); }
        @Override
        public Mono<String> upload(byte[] zipBytes, String filename, String bearerToken) {
            zip.set(zipBytes);
            bearer.set(bearerToken);
            return Mono.just("{\"slug\":\"ok\"}");
        }
    }

    private RpgProject project() {
        RpgProject p = new RpgProject();
        p.setId(UUID.randomUUID());
        p.setSlug("flatland-rpg");
        p.setDisplayName("Flatland RPG");
        p.setDescription("A square's tale");
        return p;
    }

    private RpgBuildJob job(UUID projectId) {
        RpgBuildJob j = new RpgBuildJob();
        j.setId(UUID.randomUUID());
        j.setProjectId(projectId);
        j.setTargetSlug("flatland-rpg");
        j.setBearerToken("bearer-xyz");
        j.setStatus(BuildStatus.QUEUED);
        return j;
    }

    private GodotCompileWorker worker(Path workdir, GodotExporter exporter, BffUploadClient bff, boolean godotEnabled) {
        when(jobs.save(any())).thenAnswer(inv -> Mono.just(inv.getArgument(0)));
        return new GodotCompileWorker(jobs, projects, new FakeRenderer(), exporter, bff,
                workdir.toString(), godotEnabled);
    }

    private static Map<String, byte[]> unzip(byte[] bytes) throws IOException {
        Map<String, byte[]> out = new HashMap<>();
        try (ZipInputStream zis = new ZipInputStream(new ByteArrayInputStream(bytes))) {
            ZipEntry e;
            while ((e = zis.getNextEntry()) != null) out.put(e.getName(), zis.readAllBytes());
        }
        return out;
    }

    @Test
    void disabledExportProducesRegisterableBundleAndReachesDone(@TempDir Path workdir) throws IOException {
        RpgProject p = project();
        when(projects.findById(any(UUID.class))).thenReturn(Mono.just(p));
        CapturingBff bff = new CapturingBff();
        FakeExporter exporter = new FakeExporter();
        GodotCompileWorker w = worker(workdir, exporter, bff, false);   // godot disabled

        RpgBuildJob job = job(p.getId());
        StepVerifier.create(w.execute(job))
                .assertNext(j -> assertThat(j.getStatus()).isEqualTo(BuildStatus.DONE))
                .verifyComplete();

        assertThat(exporter.calls.get()).as("disabled export never calls Godot").isZero();
        assertThat(bff.bearer.get()).isEqualTo("bearer-xyz");

        Map<String, byte[]> entries = unzip(bff.zip.get());
        // STUB path shape is unchanged: per-area index.html + empty index.wasm, no _engine.
        assertThat(entries).containsKey("manifest.json").containsKey("register.sql")
                .containsKey("bundle/lineland/index.html").containsKey("bundle/lineland/index.wasm");
        assertThat(entries.keySet()).noneMatch(k -> k.startsWith("bundle/_engine/"));
        assertThat(new String(entries.get("manifest.json"), StandardCharsets.UTF_8))
                .contains("\"schema_version\": 2").contains("\"slug\": \"flatland-rpg\"");

        String sql = new String(entries.get("register.sql"), StandardCharsets.UTF_8);
        assertThat(sql).contains("INSERT INTO games").contains("flatland-rpg")
                .contains("INSERT INTO chapters")
                // areas.json-driven HUMAN name (not the slug) + sort, refreshed on conflict.
                .contains("'Lineland', 0")
                .contains("ON CONFLICT (slug) DO UPDATE SET name = EXCLUDED.name, sort_order = EXCLUDED.sort_order")
                .contains("'lineland/index.html', 'rpg'")
                // default-open visibility grant so a published game is playable.
                .contains("INSERT INTO game_access")
                .contains("'all'");
    }

    @Test
    void enabledExportProducesDedupedEngineBundle(@TempDir Path workdir) throws IOException {
        RpgProject p = project();
        when(projects.findById(any(UUID.class))).thenReturn(Mono.just(p));
        CapturingBff bff = new CapturingBff();
        FakeExporter exporter = new FakeExporter();
        GodotCompileWorker w = worker(workdir, exporter, bff, true);    // godot enabled

        StepVerifier.create(w.execute(job(p.getId())))
                .assertNext(j -> assertThat(j.getStatus()).isEqualTo(BuildStatus.DONE))
                .verifyComplete();

        assertThat(exporter.calls.get()).as("whole-project export runs exactly once").isEqualTo(1);
        Map<String, byte[]> entries = unzip(bff.zip.get());

        // Shared engine lives ONLY under bundle/_engine/ (no chapter row points at it).
        assertThat(entries.get("bundle/_engine/index.wasm")).isNotEmpty();
        assertThat(entries).containsKey("bundle/_engine/index.js")
                .containsKey("bundle/_engine/index.side.wasm")
                .containsKey("bundle/_engine/index.pck")
                .containsKey("bundle/_engine/index.png")
                .containsKey("bundle/_engine/hugmesh_bridge.wasm");

        // Each area dir carries a patched index.html — no per-area ENGINE copy, but
        // the GDExtension wasm IS duplicated next to each index.html: its
        // gdextensionLibs entry must stay a BASENAME (the entry doubles as the
        // emscripten LDSO registration key that Godot's descriptor dlopen matches
        // by basename — a ../_engine/ path preloads but never matches).
        assertThat(entries).containsKey("bundle/lineland/index.html");
        assertThat(entries).doesNotContainKey("bundle/lineland/index.wasm");
        assertThat(entries).doesNotContainKey("bundle/lineland/index.js");
        assertThat(entries).containsKey("bundle/lineland/hugmesh_bridge.wasm");
        String areaHtml = new String(entries.get("bundle/lineland/index.html"), StandardCharsets.UTF_8);
        assertThat(areaHtml)
                .contains("src=\"../_engine/index.js\"")
                .contains("\"executable\":\"../_engine/index\"")
                .contains("\"../_engine/index.pck\":").contains("\"../_engine/index.wasm\":")
                .contains("src=\"../_engine/index.png\"")
                .contains("\"gdextensionLibs\":[\"hugmesh_bridge.wasm\"]")
                .doesNotContain("\"../_engine/hugmesh_bridge.wasm\"");

        String sql = new String(entries.get("register.sql"), StandardCharsets.UTF_8);
        assertThat(sql).contains("'lineland/index.html', 'rpg'").contains("INSERT INTO game_access");
    }

    @Test
    void enabledExportRefusesEmptyEngineWasm(@TempDir Path workdir) {
        // Real export ON, but the shared engine index.wasm is zero bytes (a stub leaking
        // through). The stub-ship guard must FAIL the job before any BFF upload.
        RpgProject p = project();
        when(projects.findById(any(UUID.class))).thenReturn(Mono.just(p));
        CapturingBff bff = new CapturingBff();
        GodotCompileWorker w = worker(workdir, new StubLeakingExporter(), bff, true);

        StepVerifier.create(w.execute(job(p.getId())))
                .assertNext(j -> {
                    assertThat(j.getStatus()).isEqualTo(BuildStatus.FAILED);
                    assertThat(j.getErrorMessage()).containsIgnoringCase("stub");
                })
                .verifyComplete();
        assertThat(bff.zip.get()).as("a stub must never reach the BFF").isNull();
    }

    @Test
    void enabledExportRefusesUnpatchedAreaHtml(@TempDir Path workdir) {
        // The engine wasm is real, but the area html was never patched to ../_engine/
        // (e.g. not actually a Godot export). The guard's second branch must catch it.
        RpgProject p = project();
        when(projects.findById(any(UUID.class))).thenReturn(Mono.just(p));
        CapturingBff bff = new CapturingBff();
        GodotCompileWorker w = worker(workdir, new UnpatchableHtmlExporter(), bff, true);

        StepVerifier.create(w.execute(job(p.getId())))
                .assertNext(j -> {
                    assertThat(j.getStatus()).isEqualTo(BuildStatus.FAILED);
                    assertThat(j.getErrorMessage()).containsIgnoringCase("stub");
                })
                .verifyComplete();
        assertThat(bff.zip.get()).as("an unpatched bundle must never reach the BFF").isNull();
    }
}
