package games.hugmesh.bff.service.realtime.wasm;

import com.dylibso.chicory.wasm.Parser;
import com.dylibso.chicory.wasm.WasmModule;
import games.hugmesh.bff.service.realtime.SimCore;
import games.hugmesh.bff.service.realtime.SimCoreFactory;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.Objects;

/**
 * {@link SimCoreFactory} that builds {@link WasmSimCore} matches from the
 * {@code wasm32-unknown-unknown} FPS sim core, run in-JVM via Chicory.
 *
 * <p>The {@code .wasm} is parsed ONCE into an immutable {@link WasmModule}
 * (cheap to share, holds no mutable state) and reused for every match;
 * {@link #create} builds a fresh {@link com.dylibso.chicory.runtime.Instance}
 * per match so each gets its own private linear memory + global
 * ({@code static mut GAME}) — the isolation the single-global-instance shim
 * relies on.
 *
 * <h2>Kind</h2>
 * {@code "fps-arena-wasm"} — distinct from the eventual native/FFM
 * {@code "fps-arena"} so both can be registered side-by-side for differential
 * comparison.
 *
 * <h2>Spike vs production</h2>
 * For the spike the module is loaded from the test classpath
 * ({@value #DEFAULT_RESOURCE}). The {@link #WasmSimCoreFactory(WasmModule)}
 * constructor takes a pre-parsed module so a promoted main/ version can source
 * the bytes from the upload store wasm-fragment serves (per
 * {@code docs/realtime-sim-wiring-p4.md}: "loads the .wasm from the same store
 * wasm-fragment serves") and register itself as a Spring {@code @Component}.
 */
public final class WasmSimCoreFactory implements SimCoreFactory {

    /** The factory kind registered in the tick service's factory map. */
    public static final String KIND = "fps-arena-wasm";

    /** Test-classpath location of the spike module. */
    public static final String DEFAULT_RESOURCE = "/wasm/hugmesh_fps_core.wasm";

    private final WasmModule module;

    /** Build from an already-parsed module (the promotion-friendly path). */
    public WasmSimCoreFactory(WasmModule module) {
        this.module = Objects.requireNonNull(module, "module");
    }

    /**
     * Load + parse the spike module from the test classpath
     * ({@value #DEFAULT_RESOURCE}).
     */
    public static WasmSimCoreFactory fromClasspath() {
        return fromClasspath(DEFAULT_RESOURCE);
    }

    /** Load + parse a module from an arbitrary classpath resource. */
    public static WasmSimCoreFactory fromClasspath(String resource) {
        try (InputStream in = WasmSimCoreFactory.class.getResourceAsStream(resource)) {
            if (in == null) {
                throw new IllegalStateException("wasm module not on classpath: " + resource);
            }
            return new WasmSimCoreFactory(Parser.parse(in));
        } catch (IOException e) {
            throw new UncheckedIOException("failed to read wasm module: " + resource, e);
        }
    }

    @Override
    public String kind() {
        return KIND;
    }

    @Override
    public SimCore create(long seed, String configJson) {
        return WasmSimCore.create(module, seed, configJson);
    }

    @Override
    public SimCore restore(String snapshotJson) {
        // The FPS core resumes from a command-log replay document, not a parsed
        // snapshot (it never round-trips a snapshot back). WasmSimCore.restore
        // feeds the supplied JSON straight to the wasm fps_restore export, which
        // expects {seed,config,log}. See WasmSimCore#restore.
        return WasmSimCore.restore(module, snapshotJson);
    }
}
