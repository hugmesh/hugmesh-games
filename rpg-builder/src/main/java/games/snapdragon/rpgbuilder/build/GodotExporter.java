package games.snapdragon.rpgbuilder.build;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Runs a headless Godot 4 <em>Web</em> export of a rendered project into a real WASM
 * bundle. This is the load-bearing step that turns a maker project into a playable
 * Godot game — previously a stub that emitted zero-byte {@code index.wasm}.
 *
 * <p>The actual {@code godot --headless --export-release "Web"} invocation is isolated
 * behind {@link #runProcess(List)} so the build worker's orchestration can be unit
 * tested with a fake exporter (no multi-GB Godot image / export templates needed in
 * CI). A production deployment provides a Godot 4 binary with the Web export templates
 * installed (see the {@code godot-export} compose profile) and flips
 * {@code app.build.godot-enabled=true}; when that flag is off the worker never calls
 * this class (it writes a dev stub instead).
 *
 * <p><b>Fails loudly.</b> A non-zero exit, a timeout, or a missing/empty output bundle
 * raises — the build is marked FAILED rather than silently shipping a broken bundle.
 */
@Component
public class GodotExporter {

    private static final Logger log = LoggerFactory.getLogger(GodotExporter.class);

    /** The Godot 4 binary (on PATH or an absolute path) with Web export templates
     *  installed. */
    private final String godotBin;
    /** Hard cap on a single export so a hung Godot process can't wedge the queue. */
    private final Duration timeout;
    /** The export preset name in {@code export_presets.cfg} (the template ships "Web"). */
    private final String presetName;

    public GodotExporter(@Value("${app.build.godot-bin:godot}") String godotBin,
                         @Value("${app.build.godot-timeout:PT10M}") Duration timeout,
                         @Value("${app.build.godot-preset:Web}") String presetName) {
        this.godotBin = godotBin;
        this.timeout = timeout;
        this.presetName = presetName;
    }

    /**
     * Export the Godot project rooted at {@code godotSrc} to {@code outputIndexHtml}
     * (and its sibling {@code index.js/.wasm/.pck}). Blocking — the build worker calls
     * this on a bounded-elastic scheduler.
     *
     * @throws IOException if the export fails, times out, or produces no bundle.
     */
    public void exportWeb(Path godotSrc, Path outputIndexHtml) throws IOException {
        Files.createDirectories(outputIndexHtml.getParent());
        // --headless: no window. --path: the project dir. --export-release "<preset>" <out>:
        // the CLI output path overrides the preset's export_path, so we drop each area's
        // bundle exactly where the packager expects it.
        List<String> cmd = List.of(
                godotBin, "--headless", "--path", godotSrc.toString(),
                "--export-release", presetName, outputIndexHtml.toString());
        log.info("[export] {}", String.join(" ", cmd));

        Process proc = runProcess(cmd);
        String output;
        boolean finished;
        try {
            output = new String(proc.getInputStream().readAllBytes());
            finished = proc.waitFor(timeout.toSeconds(), TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            proc.destroyForcibly();
            Thread.currentThread().interrupt();
            throw new IOException("godot export interrupted", e);
        }
        if (!finished) {
            proc.destroyForcibly();
            throw new IOException("godot export timed out after " + timeout);
        }
        int exit = proc.exitValue();
        if (exit != 0) {
            throw new IOException("godot export failed (exit " + exit + "): " + tail(output));
        }
        // Godot can exit 0 yet skip the bundle if templates are missing — verify bytes.
        if (!Files.exists(outputIndexHtml) || Files.size(outputIndexHtml) == 0) {
            throw new IOException("godot export produced no bundle at " + outputIndexHtml
                    + " (Web export templates installed?): " + tail(output));
        }
        log.info("[export] wrote {} ({} bytes)", outputIndexHtml, Files.size(outputIndexHtml));
    }

    /** Isolated, overridable for tests — the only place a real OS process is spawned. */
    protected Process runProcess(List<String> cmd) throws IOException {
        return new ProcessBuilder(cmd).redirectErrorStream(true).start();
    }

    private static String tail(String s) {
        if (s == null) return "";
        s = s.strip();
        return s.length() > 800 ? "…" + s.substring(s.length() - 800) : s;
    }
}
