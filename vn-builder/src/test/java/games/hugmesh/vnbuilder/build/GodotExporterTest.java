package games.hugmesh.vnbuilder.build;

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
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Specs for {@link GodotExporter} — the headless Godot Web-export orchestration. The
 * only OS-process call is isolated behind {@code runProcess}, so the success / failure
 * / timeout / no-bundle paths are exercised here with a fake process (no Godot binary
 * or export templates needed).
 */
class GodotExporterTest {

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

    /** A GodotExporter whose "Godot run" is faked: optionally writes a non-empty bundle
     *  (index.html + a non-empty sibling index.wasm — what a REAL Web export emits) at
     *  the requested output path, and returns a process with the given exit code. */
    private static GodotExporter fakeExporter(int exit, boolean writesBundle, boolean finishes) {
        return new GodotExporter("godot", Duration.ofMinutes(5), "Web") {
            @Override
            protected Process runProcess(List<String> cmd) throws IOException {
                if (writesBundle) {
                    Path out = Path.of(cmd.get(cmd.size() - 1));
                    Files.createDirectories(out.getParent());
                    Files.writeString(out, "<html>a real-ish bundle</html>");
                    // Real Web exports always emit a multi-MB index.wasm alongside.
                    Files.write(out.resolveSibling("index.wasm"), new byte[]{0, 'a', 's', 'm', 1, 2, 3, 4});
                }
                return new FakeProcess(exit, "Godot export log…", finishes);
            }
        };
    }

    /** Faked run that writes index.html but a ZERO-BYTE index.wasm — the signature of a
     *  stub (or a broken export that only emitted the HTML). */
    private static GodotExporter stubWasmExporter() {
        return new GodotExporter("godot", Duration.ofMinutes(5), "Web") {
            @Override
            protected Process runProcess(List<String> cmd) throws IOException {
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
}
