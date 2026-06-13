package games.snapdragon.bff.service.realtime.wasm;

import com.dylibso.chicory.runtime.ExportFunction;
import com.dylibso.chicory.runtime.Instance;
import com.dylibso.chicory.runtime.Memory;
import com.dylibso.chicory.wasm.WasmModule;
import games.snapdragon.bff.service.realtime.SimCommand;
import games.snapdragon.bff.service.realtime.SimCore;

import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * A {@link SimCore} backed by the {@code wasm32-unknown-unknown} build of the
 * Snapdragon FPS sim core, executed in-JVM by <a
 * href="https://github.com/dylibso/chicory">Chicory</a> — a pure-Java,
 * zero-native-dependency WebAssembly runtime. This is the P4 "WASM-in-JVM"
 * path (decision {@code docs/realtime-sim-wiring-p4.md}, option A): the server
 * runs the <em>same bytes</em> the browser runs for client-side prediction, so
 * server authority and client prediction cannot drift by construction.
 *
 * <h2>The wasm contract this class drives</h2>
 * The module ({@code snapdragon_fps_core.wasm}) is a freestanding C-ABI / JSON
 * shim over {@code snapdragon_fps_core::Game}. It has <strong>zero host
 * imports</strong> (verified: no import section), brings its own linear
 * {@code memory}, and exports:
 * <ul>
 *   <li>{@code alloc(len:i32) -> ptr:i32} / {@code dealloc(ptr:i32,len:i32)} —
 *       host&nbsp;↔&nbsp;module byte buffers in linear memory.</li>
 *   <li>{@code fps_new(seed:i64, cfgPtr:i32, cfgLen:i32) -> i32} — (re)build the
 *       single global match from a CONFIG JSON; {@code 0} ok, negative on parse
 *       error. The {@code seed} arg is authoritative.</li>
 *   <li>{@code fps_step(cmdPtr:i32, cmdLen:i32)} — advance one tick applying a
 *       COMMANDS JSON array.</li>
 *   <li>{@code fps_snapshot() -> i64} — packed {@code (ptr<<32)|len} of the
 *       snapshot JSON (UTF-8) in linear memory.</li>
 *   <li>{@code fps_is_terminal() -> i32} — {@code 1} once the match has ended.</li>
 *   <li>{@code fps_result() -> i64} — packed {@code (ptr<<32)|len} of a small
 *       result JSON synthesised from the core's public status API.</li>
 *   <li>{@code fps_restore(replayPtr:i32, replayLen:i32) -> i32} — rebuild the
 *       match from a REPLAY JSON {@code {seed,config,log}} (command-log-as-truth;
 *       the core never parses a snapshot back).</li>
 * </ul>
 *
 * <h2>Instance model — one {@link Instance} per match</h2>
 * The shim keeps ONE global match per module instance ({@code static mut GAME}).
 * Concurrent matches are isolated by instantiating the module N times — each
 * {@link Instance} has its own private linear memory + global, which is also the
 * idiomatic Chicory pattern. {@link WasmSimCoreFactory#create} does exactly that:
 * it shares the parsed {@link WasmModule} (cheap to reuse) but builds a fresh
 * {@link Instance} for every match. The module is single-threaded, matching the
 * authoritative tick loop's single-writer-per-match invariant.
 *
 * <h2>Marshalling</h2>
 * Every string crossing the boundary is UTF-8 JSON. {@link #writeBytes} runs the
 * alloc → write contract; {@link #readPacked} decodes a packed {@code i64} return
 * into the JSON string and hands the buffer back via {@code dealloc}. The input
 * buffers are released after each call so a long match doesn't accumulate
 * linear-memory garbage.
 *
 * <h2>Promotion to main/</h2>
 * This lives under {@code src/test/} for the spike (the only {@code .wasm} we have
 * is a test resource). Promoting is mechanical — see the note on the Chicory
 * dependency block in {@code backend/pom.xml}: move the two Chicory deps to
 * compile scope, move this class + {@link WasmSimCoreFactory} to the matching
 * {@code src/main} path, annotate the factory {@code @Component}, and source the
 * module bytes from the upload store instead of the classpath. The marshalling
 * code here is unchanged by that move.
 */
public final class WasmSimCore implements SimCore {

    private final Instance instance;
    private final Memory memory;
    private final ExportFunction alloc;
    private final ExportFunction dealloc;
    private final ExportFunction fpsStep;
    private final ExportFunction fpsSnapshot;
    private final ExportFunction fpsIsTerminal;
    private final ExportFunction fpsResult;

    /** wasm i32 returns sign-extend into Chicory's {@code long}; mask to u32. */
    private static final long U32 = 0xFFFF_FFFFL;

    private WasmSimCore(Instance instance) {
        this.instance = instance;
        this.memory = instance.memory();
        this.alloc = instance.export("alloc");
        this.dealloc = instance.export("dealloc");
        this.fpsStep = instance.export("fps_step");
        this.fpsSnapshot = instance.export("fps_snapshot");
        this.fpsIsTerminal = instance.export("fps_is_terminal");
        this.fpsResult = instance.export("fps_result");
    }

    // ── construction / restore (package-private; the factory is the front door) ──

    /** Build a fresh module instance and call {@code fps_new(seed, config)}. */
    static WasmSimCore create(WasmModule module, long seed, String configJson) {
        WasmSimCore core = new WasmSimCore(newInstance(module));
        int rc = core.callWithJson(core.instance.export("fps_new"), configJson, seed);
        if (rc != 0) {
            throw new IllegalArgumentException(
                    "wasm fps_new rejected config (rc=" + rc + "): " + configJson);
        }
        return core;
    }

    /**
     * Rebuild from a REPLAY JSON {@code {seed,config,log}} via {@code fps_restore}.
     * The FPS core's resume model is command-log-as-truth, NOT snapshot-injection
     * (it never parses a snapshot back), so the "snapshot" handed here is actually
     * the replay document the command log produces.
     */
    static WasmSimCore restore(WasmModule module, String replayJson) {
        WasmSimCore core = new WasmSimCore(newInstance(module));
        int rc = core.callWithJson(core.instance.export("fps_restore"), replayJson);
        if (rc != 0) {
            throw new IllegalArgumentException(
                    "wasm fps_restore rejected replay (rc=" + rc + "): " + replayJson);
        }
        return core;
    }

    private static Instance newInstance(WasmModule module) {
        return Instance.builder(module)
                .withStart(false)
                .build();
    }

    // ── SimCore ──────────────────────────────────────────────────────────────

    @Override
    public long tick() {
        // No separate tick export in the shim; the snapshot is the single source of truth.
        return readSnapshotTick(snapshotJson());
    }

    @Override
    public void step(List<SimCommand> orderedCommands) {
        // Commands arrive pre-sorted (forTick, seq); do NOT re-order them.
        String commandsJson = commandsArrayJson(orderedCommands);
        callVoidWithJson(fpsStep, commandsJson);
    }

    @Override
    public String snapshotJson() {
        long packed = fpsSnapshot.apply()[0];
        return readPacked(packed);
    }

    @Override
    public boolean isTerminal() {
        return (fpsIsTerminal.apply()[0] & U32) != 0;
    }

    @Override
    public String resultJson() {
        if (!isTerminal()) {
            return null;
        }
        return rawResultJson();
    }

    /**
     * The shim's {@code fps_result} projection regardless of terminal state —
     * {@code {"type":"fps.result","terminal":bool,"status":"ongoing|win:N","tick":T}}.
     * The public {@link #resultJson()} contract returns {@code null} until the match
     * is terminal; this package-private accessor lets the differential oracle prove
     * the WASM result <em>wire</em> matches the native core's status API byte-for-byte
     * even mid-match (where the golden run lives — {@code frag_limit:0} never ends).
     */
    String rawResultJson() {
        long packed = fpsResult.apply()[0];
        return readPacked(packed);
    }

    // ── command marshalling ────────────────────────────────────────────────────

    /**
     * Concatenate the ordered {@link SimCommand} payloads into the JSON array the
     * wasm shim's {@code parse_commands} expects: {@code [ <payload>, <payload> ]}.
     * Each {@code payloadJson()} is already the per-player command object
     * ({@code {"id":1,"move_forward":1024,...}}); we only join them, preserving
     * the caller's deterministic order.
     */
    static String commandsArrayJson(List<SimCommand> orderedCommands) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < orderedCommands.size(); i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(orderedCommands.get(i).payloadJson());
        }
        return sb.append(']').toString();
    }

    // ── linear-memory marshalling ────────────────────────────────────────────────

    /** {@code fps_new} / {@code fps_restore} shape: i32 rc, JSON arg, then extra args. */
    private int callWithJson(ExportFunction fn, String json, long... leadingArgs) {
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        int ptr = writeBytes(bytes);
        try {
            long[] args = new long[leadingArgs.length + 2];
            System.arraycopy(leadingArgs, 0, args, 0, leadingArgs.length);
            args[leadingArgs.length] = ptr & U32;
            args[leadingArgs.length + 1] = bytes.length & U32;
            return (int) (fn.apply(args)[0] & U32);
        } finally {
            dealloc.apply(ptr & U32, bytes.length & U32);
        }
    }

    /** {@code fps_step} shape: void, JSON arg (ptr,len). */
    private void callVoidWithJson(ExportFunction fn, String json) {
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        int ptr = writeBytes(bytes);
        try {
            fn.apply(ptr & U32, bytes.length & U32);
        } finally {
            dealloc.apply(ptr & U32, bytes.length & U32);
        }
    }

    /** alloc a buffer in linear memory and write {@code bytes} into it; returns ptr. */
    private int writeBytes(byte[] bytes) {
        int ptr = (int) (alloc.apply(bytes.length & U32)[0] & U32);
        if (bytes.length > 0) {
            memory.write(ptr, bytes);
        }
        return ptr;
    }

    /**
     * Decode a packed {@code (ptr<<32)|len} return into its UTF-8 JSON string,
     * then hand the module-owned (leaked {@code Box<[u8]>}) buffer back via
     * {@code dealloc} so it doesn't leak for the life of the instance.
     */
    private String readPacked(long packed) {
        if (packed == 0) {
            return null; // no game / empty
        }
        int ptr = (int) ((packed >>> 32) & U32);
        int len = (int) (packed & U32);
        byte[] out = memory.readBytes(ptr, len);
        dealloc.apply(ptr & U32, len & U32);
        return new String(out, StandardCharsets.UTF_8);
    }

    // ── snapshot tick extraction (no serde dependency on the hot path) ──────────

    /** Pull {@code "tick":N} out of the canonical snapshot JSON. */
    private static long readSnapshotTick(String snapshot) {
        if (snapshot == null) {
            return 0;
        }
        int k = snapshot.indexOf("\"tick\":");
        if (k < 0) {
            return 0;
        }
        int i = k + "\"tick\":".length();
        int start = i;
        while (i < snapshot.length()
                && (Character.isDigit(snapshot.charAt(i)) || snapshot.charAt(i) == '-')) {
            i++;
        }
        return start == i ? 0 : Long.parseLong(snapshot.substring(start, i));
    }
}
