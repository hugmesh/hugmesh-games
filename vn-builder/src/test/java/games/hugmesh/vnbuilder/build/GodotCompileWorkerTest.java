package games.hugmesh.vnbuilder.build;

import games.hugmesh.vnbuilder.entity.*;
import games.hugmesh.vnbuilder.repository.*;
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
 * Specs for the VN {@link GodotCompileWorker}, focused on the real-export gate
 * and the stub-ship guard (mirrors the rpg-builder worker test): with
 * godot-enabled=true the worker calls {@link GodotExporter} and produces a real
 * (non-empty) bundle; and a stub-shaped export (empty index.wasm) is REFUSED
 * before upload so a placeholder can never reach the BFF.
 */
class GodotCompileWorkerTest {

    private final VnBuildJobRepository jobs = mock(VnBuildJobRepository.class);
    private final VnProjectRepository projects = mock(VnProjectRepository.class);

    /** Writes a one-act godot-src tree so listActDirs() finds it, then returns it. */
    private static final class FakeRenderer extends VnGodotProjectRenderer {
        FakeRenderer() {
            super(mock(VnActRepository.class), mock(VnSceneRepository.class), mock(VnLineRepository.class),
                  mock(VnChoiceRepository.class), mock(VnCharacterRepository.class),
                  mock(VnBackgroundRepository.class), mock(VnAssetRepository.class), "");
        }
        @Override
        public Mono<RenderedProject> render(VnProject project, Path workdir) {
            return Mono.fromCallable(() -> {
                Path godotSrc = workdir.resolve("godot-src");
                Files.createDirectories(godotSrc.resolve("acts/spaceland-encounter"));
                VnAct a = new VnAct();
                a.setId(UUID.randomUUID());
                a.setSlug("spaceland-encounter");
                a.setName("Spaceland Encounter");
                return new RenderedProject(godotSrc, List.of(a), List.of(),
                        project.getDisplayName(), project.getDescription());
            });
        }
    }

    /** Real-ish export: writes index.html + a non-empty index.wasm. */
    private static final class RealExporter extends GodotExporter {
        final AtomicInteger calls = new AtomicInteger();
        RealExporter() { super("godot", Duration.ofMinutes(1), "Web", Duration.ofMinutes(1), true); }
        @Override
        public void exportWeb(Path godotSrc, Path outputIndexHtml) throws IOException {
            calls.incrementAndGet();
            Files.createDirectories(outputIndexHtml.getParent());
            Files.writeString(outputIndexHtml, "<html>real export</html>");
            Files.writeString(outputIndexHtml.resolveSibling("index.wasm"), "\0\0realwasm");
        }
    }

    /** "Succeeds" but leaves a zero-byte index.wasm — a stub leaking through. */
    private static final class StubLeakingExporter extends GodotExporter {
        StubLeakingExporter() { super("godot", Duration.ofMinutes(1), "Web", Duration.ofMinutes(1), true); }
        @Override
        public void exportWeb(Path godotSrc, Path outputIndexHtml) throws IOException {
            Files.createDirectories(outputIndexHtml.getParent());
            Files.writeString(outputIndexHtml, "<html>stub</html>");
            Files.write(outputIndexHtml.resolveSibling("index.wasm"), new byte[]{});
        }
    }

    private static final class CapturingBff extends BffUploadClient {
        final AtomicReference<byte[]> zip = new AtomicReference<>();
        CapturingBff() { super(null); }
        @Override
        public Mono<String> upload(byte[] zipBytes, String filename, String bearerToken) {
            zip.set(zipBytes);
            return Mono.just("{\"slug\":\"ok\"}");
        }
    }

    private VnProject project() {
        VnProject p = new VnProject();
        p.setId(UUID.randomUUID());
        p.setSlug("flatland-vn");
        p.setDisplayName("Flatland VN");
        p.setDescription("Romance of many dimensions");
        return p;
    }

    private VnBuildJob job(UUID projectId) {
        VnBuildJob j = new VnBuildJob();
        j.setId(UUID.randomUUID());
        j.setProjectId(projectId);
        j.setTargetSlug("flatland-vn");
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
    void enabledExportInvokesGodotAndProducesRealBundle(@TempDir Path workdir) throws IOException {
        VnProject p = project();
        when(projects.findById(any(UUID.class))).thenReturn(Mono.just(p));
        CapturingBff bff = new CapturingBff();
        RealExporter exporter = new RealExporter();
        GodotCompileWorker w = worker(workdir, exporter, bff, true);

        StepVerifier.create(w.execute(job(p.getId())))
                .assertNext(j -> assertThat(j.getStatus()).isEqualTo(BuildStatus.DONE))
                .verifyComplete();

        assertThat(exporter.calls.get()).as("enabled export calls Godot once").isEqualTo(1);
        Map<String, byte[]> entries = unzip(bff.zip.get());
        assertThat(new String(entries.get("bundle/spaceland-encounter/index.html"), StandardCharsets.UTF_8))
                .contains("real export");
        assertThat(entries.get("bundle/spaceland-encounter/index.wasm")).isNotEmpty();
    }

    @Test
    void enabledExportRefusesToShipStubWasm(@TempDir Path workdir) {
        // Real export ON but a zero-byte index.wasm leaks through — the worker
        // must FAIL the job rather than upload a non-playable placeholder.
        VnProject p = project();
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
}
