package games.snapdragon.rpgbuilder.build;

import games.snapdragon.rpgbuilder.entity.*;
import games.snapdragon.rpgbuilder.repository.*;
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
 * no DB). Covers: the disabled-export stub path produces a registerable bundle and
 * reaches DONE; the enabled path actually calls {@link GodotExporter}; an export
 * failure marks the job FAILED; and the produced zip carries a correct manifest +
 * register.sql (games upsert + per-area chapter with the post-strip asset_url) and
 * forwards the originating user's bearer to the BFF.
 */
class GodotCompileWorkerTest {

    private final RpgBuildJobRepository jobs = mock(RpgBuildJobRepository.class);
    private final RpgProjectRepository projects = mock(RpgProjectRepository.class);

    // --- test doubles (concrete classes subclassed; Java-25 ByteBuddy can't mock them) ---

    /** Writes a one-area godot-src tree so listAreaDirs() finds it, then returns it. */
    private static final class FakeRenderer extends RpgGodotProjectRenderer {
        FakeRenderer() {
            super(mock(RpgAreaRepository.class), mock(RpgRoomRepository.class), mock(RpgExitRepository.class),
                  mock(RpgItemRepository.class), mock(RpgNpcRepository.class), mock(RpgAssetRepository.class), "");
        }
        @Override
        public Mono<RenderedProject> render(RpgProject project, Path workdir) {
            return Mono.fromCallable(() -> {
                Path godotSrc = workdir.resolve("godot-src");
                Files.createDirectories(godotSrc.resolve("scenes/rooms/lineland"));
                RpgArea a = new RpgArea();
                a.setId(UUID.randomUUID());
                a.setSlug("lineland");
                return new RenderedProject(godotSrc, List.of(a), List.of(), List.of(),
                        project.getDisplayName(), project.getDescription());
            });
        }
    }

    private static final class FakeExporter extends GodotExporter {
        final AtomicInteger calls = new AtomicInteger();
        private final boolean fail;
        FakeExporter(boolean fail) { super("godot", Duration.ofMinutes(1), "Web"); this.fail = fail; }
        @Override
        public void exportWeb(Path godotSrc, Path outputIndexHtml) throws IOException {
            calls.incrementAndGet();
            if (fail) throw new IOException("boom: export templates missing");
            Files.createDirectories(outputIndexHtml.getParent());
            Files.writeString(outputIndexHtml, "<html>real export</html>");
            Files.writeString(outputIndexHtml.resolveSibling("index.wasm"), "\0\0realwasm");
        }
    }

    /** Faked exporter that "succeeds" but leaves an EMPTY index.wasm — i.e. a stub
     *  sneaking past as a real export. The worker's stub-ship guard must catch it. */
    private static final class StubLeakingExporter extends GodotExporter {
        StubLeakingExporter() { super("godot", Duration.ofMinutes(1), "Web"); }
        @Override
        public void exportWeb(Path godotSrc, Path outputIndexHtml) throws IOException {
            Files.createDirectories(outputIndexHtml.getParent());
            Files.writeString(outputIndexHtml, "<html>stub</html>");
            Files.write(outputIndexHtml.resolveSibling("index.wasm"), new byte[]{});   // zero bytes
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
        FakeExporter exporter = new FakeExporter(false);
        GodotCompileWorker w = worker(workdir, exporter, bff, false);   // godot disabled

        RpgBuildJob job = job(p.getId());
        StepVerifier.create(w.execute(job))
                .assertNext(j -> assertThat(j.getStatus()).isEqualTo(BuildStatus.DONE))
                .verifyComplete();

        assertThat(exporter.calls.get()).as("disabled export never calls Godot").isZero();
        assertThat(bff.bearer.get()).isEqualTo("bearer-xyz");

        Map<String, byte[]> entries = unzip(bff.zip.get());
        assertThat(entries).containsKey("manifest.json").containsKey("register.sql")
                .containsKey("bundle/lineland/index.html").containsKey("bundle/lineland/index.wasm");
        assertThat(new String(entries.get("manifest.json"), StandardCharsets.UTF_8))
                .contains("\"schema_version\": 2").contains("\"slug\": \"flatland-rpg\"");
        String sql = new String(entries.get("register.sql"), StandardCharsets.UTF_8);
        assertThat(sql).contains("INSERT INTO games").contains("flatland-rpg")
                .contains("INSERT INTO chapters").contains("'lineland/index.html', 'rpg'");
    }

    @Test
    void enabledExportInvokesGodotAndProducesRealBundle(@TempDir Path workdir) throws IOException {
        RpgProject p = project();
        when(projects.findById(any(UUID.class))).thenReturn(Mono.just(p));
        CapturingBff bff = new CapturingBff();
        FakeExporter exporter = new FakeExporter(false);
        GodotCompileWorker w = worker(workdir, exporter, bff, true);    // godot enabled

        StepVerifier.create(w.execute(job(p.getId())))
                .assertNext(j -> assertThat(j.getStatus()).isEqualTo(BuildStatus.DONE))
                .verifyComplete();

        assertThat(exporter.calls.get()).as("enabled export calls Godot once").isEqualTo(1);
        Map<String, byte[]> entries = unzip(bff.zip.get());
        assertThat(new String(entries.get("bundle/lineland/index.html"), StandardCharsets.UTF_8))
                .contains("real export");
        assertThat(entries.get("bundle/lineland/index.wasm")).isNotEmpty();   // real (non-stub) bytes
    }

    @Test
    void enabledExportRefusesToShipStubWasm(@TempDir Path workdir) {
        // Real export ON, but the exporter "succeeds" with a zero-byte index.wasm
        // (a stub leaking through). The worker's stub-ship guard must FAIL the job
        // rather than upload a non-playable placeholder to the BFF.
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
    void exportFailureMarksJobFailed(@TempDir Path workdir) {
        RpgProject p = project();
        when(projects.findById(any(UUID.class))).thenReturn(Mono.just(p));
        GodotCompileWorker w = worker(workdir, new FakeExporter(true), new CapturingBff(), true);

        StepVerifier.create(w.execute(job(p.getId())))
                .assertNext(j -> {
                    assertThat(j.getStatus()).isEqualTo(BuildStatus.FAILED);
                    assertThat(j.getErrorMessage()).contains("boom");
                })
                .verifyComplete();
    }
}
