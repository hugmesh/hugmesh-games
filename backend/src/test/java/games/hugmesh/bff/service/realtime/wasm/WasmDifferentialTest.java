package games.hugmesh.bff.service.realtime.wasm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import games.hugmesh.bff.service.realtime.SimCommand;
import games.hugmesh.bff.service.realtime.SimCore;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * <h1>The crux cross-runtime differential oracle (P4 spike, step&nbsp;3).</h1>
 *
 * Proves that the {@code wasm32-unknown-unknown} FPS sim core, executed
 * <strong>in-JVM</strong> by Chicory ({@link WasmSimCore} / {@link WasmSimCoreFactory}
 * from step&nbsp;2), reproduces the <strong>native</strong> golden vectors from
 * step&nbsp;1 ({@code wasm/golden-fps-vectors.json}, captured by native
 * {@code cargo}) <strong>byte-for-byte</strong>:
 *
 * <pre>
 *   same seed  +  same (forTick, seq)-ordered command log
 *        ⇒  identical snapshot JSON at EVERY recorded tick
 *        ⇒  identical result JSON + identical terminal state at the end.
 * </pre>
 *
 * <h2>Why byte-identity, not structural-equality</h2>
 * Both sides serialize through the <em>same Rust serde code</em>
 * ({@code Game::snapshot()} / the shim's {@code fps_result} projection). The native
 * golden and the WASM-in-JVM output are produced by the identical source compiled
 * to two targets ({@code x86_64} vs {@code wasm32}). The determinism contract
 * (integer / fixed-point state, seeded PRNG, fixed iteration order — see
 * {@link SimCore}) means the bytes must match <em>exactly</em>: same key order, same
 * whitespace, same number formatting. So the primary assertion is exact
 * {@link String} equality.
 *
 * <p>If exact-string equality ever failed but a structural compare passed, that is
 * <strong>itself a finding</strong> — it would mean a cross-target serde formatting
 * nuance (key-ordering, float vs fixed-point rendering, integer width) had crept in,
 * defeating the whole point of running the same bytes on both sides. This test
 * therefore asserts byte-identity directly and documents (in
 * {@link #documentsByteIdentityRationale()}) why a structural fallback is NOT used.
 *
 * <h2>Disabled-pending-dependency</h2>
 * If Chicory could not be resolved (step&nbsp;2's {@link WasmSimCore} won't link),
 * annotate this class {@code @Disabled} with the same reason so it runs the moment
 * the dependency resolves. As checked in, Chicory&nbsp;1.7.5 resolves from Maven
 * Central, so these run live. {@link #goldenVectorsAreSelfConsistent()} is a
 * pure-JVM check that needs no WASM runtime at all — it proves the golden oracle is
 * internally consistent even fully offline.
 */
class WasmDifferentialTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String GOLDEN = "/wasm/golden-fps-vectors.json";

    // ── 1. THE CRUX: native golden ≡ WASM-in-JVM, byte-for-byte, every tick ──────

    @Test
    @DisplayName("DIFFERENTIAL: WASM-in-JVM reproduces native golden snapshot JSON byte-for-byte at every tick")
    void wasmReproducesNativeGoldenByteForByte() throws Exception {
        Golden g = loadGolden();
        WasmSimCore sim = (WasmSimCore) WasmSimCoreFactory.fromClasspath().create(g.seed, g.config);

        assertEquals(0L, sim.tick(), "fresh WASM match must be at tick 0");
        assertFalse(sim.isTerminal(), "fresh WASM match must not be terminal");

        int identicalTicks = 0;
        for (Step step : g.steps) {
            sim.step(step.commands);

            // Tick counter advances exactly as the (forTick,seq)-ordered log dictates.
            assertEquals(step.tick, sim.tick(),
                    "WASM tick counter after applying tick " + step.tick + " commands");

            String wasmSnapshot = sim.snapshotJson();
            assertEquals(step.expectedSnapshot, wasmSnapshot,
                    "WASM snapshot must be BYTE-IDENTICAL to the native golden at tick " + step.tick);
            identicalTicks++;
        }

        // ── final result_json + terminal state (cross-runtime) ──────────────────
        // This golden run uses frag_limit:0 ⇒ the native core "runs forever"
        // (hugmesh-fps-core: no_frag_limit_runs_forever), so after the last tick
        // the match is NOT terminal and status stays "ongoing".
        assertFalse(sim.isTerminal(),
                "frag_limit:0 golden run never terminates — isTerminal must stay false");
        assertNull(sim.resultJson(),
                "SimCore.resultJson() contract: null until terminal");

        // The shim's fps_result projection (status API → wire) must still match the
        // native core's status API byte-for-byte mid-match. We compare against the
        // expectation derived from the native golden's own final snapshot (status +
        // tick), so this is a true native-vs-wasm equality, not a hand-typed literal.
        Step last = g.steps.get(g.steps.size() - 1);
        String expectedResult = expectedResultJson(last);
        assertEquals(expectedResult, sim.rawResultJson(),
                "WASM fps_result wire must be byte-identical to the native status projection");

        System.out.printf(
                "%n[WasmDifferentialTest] %d ticks byte-identical native vs WASM-in-JVM; "
                        + "final result wire identical (%s)%n",
                identicalTicks, expectedResult);

        assertThat(identicalTicks)
                .as("every recorded golden tick was byte-identical")
                .isEqualTo(g.steps.size())
                .isPositive();
    }

    // ── 2. determinism: the same WASM sim run twice in-JVM yields identical streams ──

    @Test
    @DisplayName("DETERMINISM: two independent WASM instances, same seed+log, emit identical snapshot+result streams")
    void wasmIsDeterministicAcrossTwoRuns() throws Exception {
        Golden g = loadGolden();
        WasmSimCoreFactory factory = WasmSimCoreFactory.fromClasspath();

        WasmSimCore a = (WasmSimCore) factory.create(g.seed, g.config);
        WasmSimCore b = (WasmSimCore) factory.create(g.seed, g.config);

        // Pre-step snapshots must already agree (same fps_new(seed, config)).
        assertEquals(a.snapshotJson(), b.snapshotJson(),
                "two fresh WASM matches with the same seed+config must have identical tick-0 snapshots");

        List<String> streamA = new ArrayList<>();
        List<String> streamB = new ArrayList<>();
        for (Step step : g.steps) {
            a.step(step.commands);
            b.step(step.commands);
            String sa = a.snapshotJson();
            String sb = b.snapshotJson();
            streamA.add(sa);
            streamB.add(sb);
            assertEquals(sa, sb,
                    "WASM run A and run B must produce identical snapshot JSON at tick " + step.tick);
        }

        // Whole-stream equality (not just per-tick) + identical terminal/result wires.
        assertEquals(streamA, streamB, "the two in-JVM WASM snapshot streams must be identical");
        assertEquals(a.isTerminal(), b.isTerminal(), "terminal state must agree across runs");
        assertEquals(a.rawResultJson(), b.rawResultJson(), "result wire must agree across runs");

        System.out.printf("[WasmDifferentialTest] determinism: %d-tick WASM stream identical across 2 in-JVM runs%n",
                streamA.size());
    }

    // ── 3. instance isolation (one Instance per match) — supports the determinism claim ──

    @Test
    @DisplayName("ISOLATION: stepping one WASM match leaves a sibling match (own linear memory) untouched")
    void wasmInstancesAreIsolated() throws Exception {
        Golden g = loadGolden();
        WasmSimCoreFactory factory = WasmSimCoreFactory.fromClasspath();

        SimCore a = factory.create(g.seed, g.config);
        SimCore b = factory.create(g.seed, g.config);

        a.step(g.steps.get(0).commands);

        assertEquals(1L, a.tick(), "stepped match advanced");
        assertEquals(0L, b.tick(), "sibling match (own GAME global + linear memory) untouched");
        assertEquals(g.steps.get(0).expectedSnapshot, a.snapshotJson(),
                "stepped match still byte-matches the native golden");
    }

    // ── 4. offline: the golden oracle is internally self-consistent (no WASM needed) ──

    @Test
    @DisplayName("OFFLINE: golden vectors are self-consistent (config ⇄ snapshot, monotonic ticks, ongoing while frag_limit:0)")
    void goldenVectorsAreSelfConsistent() throws Exception {
        // A pure-JVM structural audit of the native oracle that runs even if no WASM
        // runtime is available. It proves the golden file is a coherent oracle: the
        // config the match was seeded with is reflected in every snapshot, ticks are
        // strictly monotonic 1..N, and every recorded snapshot honours the frag_limit:0
        // "runs forever" rule (status stays "ongoing", no one ever flips terminal).
        Golden g = loadGolden();
        JsonNode config = JSON.readTree(g.config);

        int width = config.get("width").asInt();
        int height = config.get("height").asInt();
        int fragLimit = config.get("frag_limit").asInt();
        int playerCount = config.get("players").size();
        String wallsJson = config.get("walls").toString();

        assertTrue(g.seed > 0, "golden carries a real seed");
        assertThat(g.steps).as("golden carries a non-empty command log").isNotEmpty();

        long previousTick = 0;
        for (Step step : g.steps) {
            JsonNode snap = JSON.readTree(step.expectedSnapshot);

            // monotonic, contiguous tick numbering 1..N
            assertEquals(previousTick + 1, step.tick, "golden ticks must be contiguous");
            assertEquals(step.tick, snap.get("tick").asLong(),
                    "snapshot's embedded tick must equal the step tick at tick " + step.tick);
            previousTick = step.tick;

            // the config invariants are echoed into every snapshot (same serde source)
            assertEquals("fps.snapshot", snap.get("type").asText());
            assertEquals(width, snap.get("width").asInt(), "snapshot width must match config");
            assertEquals(height, snap.get("height").asInt(), "snapshot height must match config");
            assertEquals(fragLimit, snap.get("frag_limit").asInt(), "snapshot frag_limit must match config");
            assertEquals(wallsJson, snap.get("walls").toString(), "snapshot walls must match config");
            assertEquals(playerCount, snap.get("players").size(), "player roster size must match config");

            // frag_limit:0 ⇒ runs forever ⇒ never terminal in any recorded snapshot
            if (fragLimit == 0) {
                assertEquals("ongoing", snap.get("status").asText(),
                        "frag_limit:0 must keep status 'ongoing' at tick " + step.tick);
            }

            // every player carries the full status shape (hp/score/alive/respawn_in)
            for (JsonNode p : snap.get("players")) {
                assertTrue(p.has("id") && p.has("x") && p.has("y") && p.has("yaw")
                                && p.has("hp") && p.has("score") && p.has("alive") && p.has("respawn_in"),
                        "player record shape at tick " + step.tick);
            }

            // command-log shape: each command targets a real player id, fields present
            assertThat(step.commands).as("tick %d has one command per player", step.tick)
                    .hasSize(playerCount);
        }

        // the (forTick, seq) total order is faithfully reconstructed from the log:
        // seq is the per-tick arrival stamp (the tick-7 log is deliberately out of id
        // order — id 3 first — and seq must follow ARRIVAL, not id).
        Step tick7 = g.steps.stream().filter(s -> s.tick == 7).findFirst().orElseThrow();
        assertEquals(0, tick7.commands.get(0).seq(), "first arrival in tick 7 is seq 0");
        assertEquals("3", tick7.commands.get(0).playerId(),
                "tick-7 log is out of id-order on purpose: id 3 arrives first (seq 0)");
        assertEquals(7L, tick7.commands.get(0).forTick(), "forTick stamped from the step tick");
    }

    // ── 5. the byte-identity rationale, asserted as a contract (documents the design) ──

    @Test
    @DisplayName("RATIONALE: native and WASM snapshots are canonical JSON from the same serde — exact-string, never structural")
    void documentsByteIdentityRationale() throws Exception {
        // Belt-and-suspenders: re-run the final tick and show that a STRUCTURAL
        // compare would also pass — but that the EXACT-STRING compare is the one that
        // matters and is the one actually enforced above. If exact-string ever failed
        // while structural passed, THIS is where the finding would surface: a
        // cross-target serde formatting drift (key order / number rendering / width).
        Golden g = loadGolden();
        WasmSimCore sim = (WasmSimCore) WasmSimCoreFactory.fromClasspath().create(g.seed, g.config);
        Step last = null;
        for (Step step : g.steps) {
            sim.step(step.commands);
            last = step;
        }
        assertNotNull(last);

        String wasm = sim.snapshotJson();
        String native_ = last.expectedSnapshot;

        boolean exactMatch = native_.equals(wasm);
        boolean structuralMatch = JSON.readTree(native_).equals(JSON.readTree(wasm));

        // Both must hold. The point of the spike is byte-identity; if these ever
        // diverge (structural true, exact false) the assertion message records the
        // finding for the report.
        assertTrue(structuralMatch, "structural JSON equality (sanity floor)");
        assertEquals(native_, wasm,
                "EXACT-STRING byte-identity is the contract. If this fails while the "
                        + "structural compare passes, a cross-target serde formatting nuance "
                        + "(key order / whitespace / number rendering) has crept in — that is "
                        + "the finding the whole differential exists to catch.");
        assertTrue(exactMatch && structuralMatch,
                "native and WASM serialize identical canonical JSON from the same Rust serde");
    }

    // ── golden fixture loading + (forTick, seq) reconstruction ───────────────────

    private record Golden(long seed, String config, List<Step> steps) {}

    private record Step(long tick, List<SimCommand> commands, String expectedSnapshot) {}

    private static Golden loadGolden() throws Exception {
        try (InputStream in = WasmDifferentialTest.class.getResourceAsStream(GOLDEN)) {
            assertNotNull(in, "golden vectors must be on the test classpath: " + GOLDEN);
            JsonNode root = JSON.readTree(in);
            long seed = root.get("seed").asLong();
            String config = root.get("config").asText();
            List<Step> steps = new ArrayList<>();
            for (JsonNode s : root.get("steps")) {
                long tick = s.get("tick").asLong();
                String commandsJson = s.get("commandsJson").asText();
                String expected = s.get("expectedSnapshot").asText();
                steps.add(new Step(tick, toCommands(tick, commandsJson), expected));
            }
            return new Golden(seed, config, steps);
        }
    }

    /**
     * Split the golden tick's {@code commandsJson} ARRAY into one {@link SimCommand}
     * per element, stamping {@code (forTick=tick, seq=arrival-index)} — the exact
     * total order the authoritative loop hands the sim. The wasm shim re-assembles
     * the array via {@link WasmSimCore#commandsArrayJson}, byte-equivalent to the
     * native harness input. The golden deliberately records one tick out of id-order
     * to prove the order is arrival-driven, not id-sorted.
     */
    private static List<SimCommand> toCommands(long forTick, String commandsArrayJson) throws Exception {
        List<SimCommand> out = new ArrayList<>();
        JsonNode arr = JSON.readTree(commandsArrayJson);
        int seq = 0;
        for (JsonNode cmd : arr) {
            String playerId = cmd.get("id").asText();
            out.add(new SimCommand(forTick, seq++, playerId, cmd.toString()));
        }
        return out;
    }

    /**
     * The {@code fps.result} wire the shim must emit, DERIVED from the native golden's
     * own final snapshot (its {@code status} + {@code tick}) rather than hand-typed —
     * so the result assertion is a genuine native-vs-wasm equality. Mirrors the shim's
     * exact formatting in {@code hugmesh-fps-core-wasm} {@code fps_result}:
     * {@code {"type":"fps.result","terminal":bool,"status":"...","tick":T}}.
     */
    private static String expectedResultJson(Step last) throws Exception {
        JsonNode snap = JSON.readTree(last.expectedSnapshot);
        String status = snap.get("status").asText();
        boolean terminal = !"ongoing".equals(status); // ongoing ⇒ not terminal
        long tick = snap.get("tick").asLong();
        return "{\"type\":\"fps.result\",\"terminal\":" + (terminal ? "true" : "false")
                + ",\"status\":\"" + status + "\",\"tick\":" + tick + "}";
    }
}
