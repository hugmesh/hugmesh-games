package games.snapdragon.bff.service.realtime.wasm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import games.snapdragon.bff.service.realtime.SimCommand;
import games.snapdragon.bff.service.realtime.SimCore;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Differential / smoke test for {@link WasmSimCore}: runs the SAME
 * {@code wasm32-unknown-unknown} FPS sim-core bytes the browser runs, in-JVM via
 * Chicory, and checks its output against the native golden oracle captured in
 * step&nbsp;1 ({@code wasm/golden-fps-vectors.json}).
 *
 * <p>This is the gnuchess-style differential oracle, now cross-runtime: native
 * {@code cargo test} produced the golden snapshots; the JVM-hosted wasm must
 * reproduce them <strong>byte-for-byte</strong> at every tick. Because the config
 * + per-tick command JSON the native harness emitted is byte-identical to what the
 * shim parses, feeding the same bytes to both sides is a true equality check.
 *
 * <p>If this is ever {@code @Disabled}, the reason is on the annotation — the
 * wiring (this test + {@link WasmSimCore} + {@link WasmSimCoreFactory}) stays in
 * place for a connected build that can resolve the Chicory dependency.
 */
class WasmSimCoreDifferentialTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String GOLDEN = "/wasm/golden-fps-vectors.json";

    @Test
    @DisplayName("smoke: the module loads, creates a match, and produces a snapshot in-JVM")
    void smokeLoadsAndExecutes() throws Exception {
        Golden g = loadGolden();
        WasmSimCoreFactory factory = WasmSimCoreFactory.fromClasspath();

        SimCore sim = factory.create(g.seed, g.config);

        // Pre-step snapshot proves the module instantiated, ran fps_new, and the
        // packed-pointer marshalling round-trips a real JSON string out of linear
        // memory — the whole runtime path, end to end.
        String snap0 = sim.snapshotJson();
        assertThat(snap0).as("initial snapshot JSON from the wasm module")
                .isNotBlank()
                .contains("\"type\":\"fps.snapshot\"")
                .contains("\"tick\":0");
        assertThat(sim.tick()).isZero();
        assertThat(sim.isTerminal()).isFalse();

        // One tick exercises the command-array marshalling + fps_step path.
        sim.step(g.steps.get(0).commands);
        assertThat(sim.tick()).isEqualTo(1);
        assertThat(sim.snapshotJson()).contains("\"tick\":1");
    }

    @Test
    @DisplayName("differential: in-JVM wasm reproduces the native golden snapshot byte-for-byte every tick")
    void matchesNativeGoldenEveryTick() throws Exception {
        Golden g = loadGolden();
        WasmSimCoreFactory factory = WasmSimCoreFactory.fromClasspath();
        SimCore sim = factory.create(g.seed, g.config);

        for (Step step : g.steps) {
            sim.step(step.commands);
            assertThat(sim.tick())
                    .as("tick counter after applying tick %d commands", step.tick)
                    .isEqualTo(step.tick);
            assertThat(sim.snapshotJson())
                    .as("wasm snapshot must equal native golden at tick %d", step.tick)
                    .isEqualTo(step.expectedSnapshot);
        }
    }

    @Test
    @DisplayName("isolation: a second instance is independent of the first (one Instance per match)")
    void instancesAreIsolated() throws Exception {
        Golden g = loadGolden();
        WasmSimCoreFactory factory = WasmSimCoreFactory.fromClasspath();

        SimCore a = factory.create(g.seed, g.config);
        SimCore b = factory.create(g.seed, g.config);

        // Advance only A; B's single-global-instance state must be untouched —
        // proving each match gets its own linear memory + GAME global.
        a.step(g.steps.get(0).commands);

        assertThat(a.tick()).isEqualTo(1);
        assertThat(b.tick()).as("second instance unaffected by stepping the first").isZero();
        assertThat(b.snapshotJson()).contains("\"tick\":0");
    }

    // ── golden fixture loading ───────────────────────────────────────────────

    private record Golden(long seed, String config, List<Step> steps) {}

    private record Step(long tick, List<SimCommand> commands, String expectedSnapshot) {}

    private static Golden loadGolden() throws Exception {
        try (InputStream in = WasmSimCoreDifferentialTest.class.getResourceAsStream(GOLDEN)) {
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
     * Split the golden tick's {@code commandsJson} ARRAY into one
     * {@link SimCommand} per element, preserving the golden order (which
     * deliberately includes an out-of-order tick). The wasm shim re-assembles
     * the array via {@link WasmSimCore#commandsArrayJson}, so feeding the parts as
     * individual commands faithfully exercises the marshalling path while
     * remaining byte-equivalent to the native harness input.
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
}
