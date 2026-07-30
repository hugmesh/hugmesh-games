package games.hugmesh.rpgbuilder.build;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Specs for {@link GodotExporter} — the headless Godot Web-export orchestration plus the
 * defensive pre-export {@code --import} belt (parity with the rpg-builder exporter). The
 * only OS-process call is isolated behind {@code runProcess}, so the success / failure /
 * timeout / no-bundle / import paths are exercised here with a fake process (no Godot
 * binary or export templates needed).
 */
class GodotExporterTest {

    private static final Duration EXPORT_TIMEOUT = Duration.ofMinutes(5);
    private static final Duration IMPORT_TIMEOUT = Duration.ofMinutes(2);

    /** Minimal fake of {@link Process}. */
    private static final class FakeProcess extends Process {
        private final int exit;
        private final byte[] out;
        private final boolean finishes;
        FakeProcess(int exit, String out, boolean finishes) {
            this.exit = exit; this.out = out.getBytes(StandardCharsets.UTF_8); this.finishes = finishes;
        }
        @Override public OutputStream getOutputStream() { return OutputStream.nullOutputStream(); }
        @Override public InputStream getInputStream() { return new ByteArrayInputStream(out); }
        @Override public InputStream getErrorStream() { return InputStream.nullInputStream(); }
        @Override public int waitFor() { return exit; }
        @Override public boolean waitFor(long t, TimeUnit u) { return finishes; }
        @Override public int exitValue() { return exit; }
        @Override public void destroy() {}
        @Override public Process destroyForcibly() { return this; }
    }

    private static void writeRealBundle(List<String> cmd) throws IOException {
        Path out = Path.of(cmd.get(cmd.size() - 1));
        Files.createDirectories(out.getParent());
        Files.writeString(out, "<html>a real-ish bundle</html>");
        // Real Web exports always emit a multi-MB index.wasm alongside.
        Files.write(out.resolveSibling("index.wasm"), new byte[]{0, 'a', 's', 'm', 1, 2, 3, 4});
    }

    /** A GodotExporter whose "Godot run" is faked. The import pass (default on) always
     *  succeeds; the EXPORT run optionally writes a real-shaped bundle and returns the
     *  given exit / finishes. */
    private static GodotExporter fakeExporter(int exit, boolean writesBundle, boolean finishes) {
        return new GodotExporter("godot", EXPORT_TIMEOUT, "Web", IMPORT_TIMEOUT, true) {
            @Override
            protected Process runProcess(List<String> cmd) throws IOException {
                if (cmd.contains("--import")) {
                    return new FakeProcess(0, "Godot import log…", true);   // import OK
                }
                if (writesBundle) {
                    writeRealBundle(cmd);
                }
                return new FakeProcess(exit, "Godot export log…", finishes);
            }
        };
    }

    /** Faked run that writes index.html but a ZERO-BYTE index.wasm — the signature of a
     *  stub (or a broken export that only emitted the HTML). */
    private static GodotExporter stubWasmExporter() {
        return new GodotExporter("godot", EXPORT_TIMEOUT, "Web", IMPORT_TIMEOUT, true) {
            @Override
            protected Process runProcess(List<String> cmd) throws IOException {
                if (cmd.contains("--import")) {
                    return new FakeProcess(0, "Godot import log…", true);
                }
                Path out = Path.of(cmd.get(cmd.size() - 1));
                Files.createDirectories(out.getParent());
                Files.writeString(out, "<html>stub — replace once app.build.godot-enabled=true</html>");
                Files.write(out.resolveSibling("index.wasm"), new byte[]{});   // zero bytes
                return new FakeProcess(0, "Godot export log…", true);
            }
        };
    }

    @Test
    void exportWritesBundleOnSuccess(@TempDir Path dir) throws IOException {
        Path out = dir.resolve("bundle/main/index.html");
        fakeExporter(0, true, true).exportWeb(dir.resolve("godot-src"), out);
        assertThat(Files.exists(out)).isTrue();
        assertThat(Files.size(out)).isGreaterThan(0);
        // The load-bearing artifact — a real export's index.wasm is non-empty.
        assertThat(Files.size(out.resolveSibling("index.wasm"))).isGreaterThan(0);
    }

    @Test
    void exitZeroButEmptyWasmFailsAsStub(@TempDir Path dir) {
        // exit 0 + non-empty index.html but a ZERO-BYTE index.wasm is the stub
        // signature: a real Web export always emits a multi-MB wasm. The exporter
        // must reject it so prod can't silently ship a placeholder when enabled.
        assertThatThrownBy(() ->
                stubWasmExporter().exportWeb(dir.resolve("godot-src"), dir.resolve("out/index.html")))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("STUB");
    }

    @Test
    void nonZeroExitFails(@TempDir Path dir) {
        assertThatThrownBy(() ->
                fakeExporter(1, true, true).exportWeb(dir.resolve("godot-src"), dir.resolve("out/index.html")))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("exit 1");
    }

    @Test
    void exitZeroButNoBundleFails(@TempDir Path dir) {
        // Godot can exit 0 yet skip the bundle when export templates are missing.
        assertThatThrownBy(() ->
                fakeExporter(0, false, true).exportWeb(dir.resolve("godot-src"), dir.resolve("out/index.html")))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("no bundle");
    }

    @Test
    void timeoutFails(@TempDir Path dir) {
        assertThatThrownBy(() ->
                fakeExporter(0, true, false).exportWeb(dir.resolve("godot-src"), dir.resolve("out/index.html")))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("timed out");
    }

    // ---- pre-export import belt (RESOLUTION 7, parity) --------------------

    @Test
    void importRunsBeforeExportWhenEnabled(@TempDir Path dir) throws IOException {
        List<List<String>> calls = new ArrayList<>();
        GodotExporter ex = new GodotExporter("godot", EXPORT_TIMEOUT, "Web", IMPORT_TIMEOUT, true) {
            @Override
            protected Process runProcess(List<String> cmd) throws IOException {
                calls.add(List.copyOf(cmd));
                if (!cmd.contains("--import")) writeRealBundle(cmd);
                return new FakeProcess(0, "log", true);
            }
        };
        ex.exportWeb(dir.resolve("godot-src"), dir.resolve("out/index.html"));

        assertThat(calls).as("import then export").hasSize(2);
        assertThat(calls.get(0)).contains("--import").doesNotContain("--export-release");
        assertThat(calls.get(1)).contains("--export-release").doesNotContain("--import");
    }

    @Test
    void importFailureFailsBuildBeforeExport(@TempDir Path dir) {
        List<List<String>> calls = new ArrayList<>();
        GodotExporter ex = new GodotExporter("godot", EXPORT_TIMEOUT, "Web", IMPORT_TIMEOUT, true) {
            @Override
            protected Process runProcess(List<String> cmd) {
                calls.add(List.copyOf(cmd));
                return new FakeProcess(cmd.contains("--import") ? 3 : 0, "import boom", true);
            }
        };
        assertThatThrownBy(() -> ex.exportWeb(dir.resolve("godot-src"), dir.resolve("out/index.html")))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("import failed");
        assertThat(calls).as("export skipped after import failure").hasSize(1);
        assertThat(calls.get(0)).contains("--import");
    }

    @Test
    void importDisabledSkipsImportPass(@TempDir Path dir) throws IOException {
        List<List<String>> calls = new ArrayList<>();
        GodotExporter ex = new GodotExporter("godot", EXPORT_TIMEOUT, "Web", IMPORT_TIMEOUT, false) {
            @Override
            protected Process runProcess(List<String> cmd) throws IOException {
                calls.add(List.copyOf(cmd));
                writeRealBundle(cmd);
                return new FakeProcess(0, "log", true);
            }
        };
        ex.exportWeb(dir.resolve("godot-src"), dir.resolve("out/index.html"));

        assertThat(calls).as("only the export runs").hasSize(1);
        assertThat(calls.get(0)).contains("--export-release").doesNotContain("--import");
    }
}
