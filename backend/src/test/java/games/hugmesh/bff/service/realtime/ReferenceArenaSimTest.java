package games.hugmesh.bff.service.realtime;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins the {@link ReferenceArenaSim} determinism contract the
 * authoritative loop is built on (mirrors the Rust cores' test suites):
 * same seed + same ordered command stream ⇒ identical
 * {@code snapshotJson()} at EVERY tick, terminal states resolve
 * identically, and a snapshot is a bit-exact resume point.
 */
class ReferenceArenaSimTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final ReferenceArenaSimFactory FACTORY = new ReferenceArenaSimFactory();

    private static String config(String game, String... playerIds) {
        StringBuilder sb = new StringBuilder("{\"playerIds\":[");
        for (int i = 0; i < playerIds.length; i++) {
            if (i > 0) sb.append(',');
            sb.append('"').append(playerIds[i]).append('"');
        }
        return sb.append("],\"game\":").append(game).append('}').toString();
    }

    private static SimCommand cmd(long forTick, int seq, String playerId, String payload) {
        return new SimCommand(forTick, seq, playerId, payload);
    }

    // ── determinism ───────────────────────────────────────────────────────

    @Test
    void sameSeedAndCommandsProduceIdenticalSnapshotsForTwoHundredTicks() {
        String cfg = config("{\"maxTicks\":100000}", "p1", "p2", "p3");
        SimCore a = FACTORY.create(42L, cfg);
        SimCore b = FACTORY.create(42L, cfg);
        assertThat(a.snapshotJson()).isEqualTo(b.snapshotJson());

        // Pseudo-random but REPRODUCIBLE command script — both sims get the
        // exact same ordered list every tick. Moves + unknown ops only so no
        // kill can end the match early (fire determinism is pinned by the
        // kill + restore tests); all 250 ticks are real.
        Random script = new Random(7);
        String[] players = {"p1", "p2", "p3"};
        for (long tick = 1; tick <= 250; tick++) {
            List<SimCommand> commands = new ArrayList<>();
            int n = script.nextInt(4);
            for (int seq = 0; seq < n; seq++) {
                String p = players[script.nextInt(players.length)];
                String payload = script.nextBoolean()
                        ? "{\"op\":\"move\",\"dx\":" + (script.nextInt(5) - 2)
                            + ",\"dy\":" + (script.nextInt(5) - 2) + "}"
                        : "{\"op\":\"taunt\"}";
                commands.add(cmd(tick, seq, p, payload));
            }
            a.step(commands);
            b.step(commands);
            assertThat(a.snapshotJson())
                    .as("tick %d must be bit-exact", tick)
                    .isEqualTo(b.snapshotJson());
        }
        assertThat(a.tick()).isEqualTo(250);
    }

    @Test
    void differentSeedsProduceDifferentSpawns() {
        String cfg = config("{}", "p1", "p2");
        assertThat(FACTORY.create(1L, cfg).snapshotJson())
                .isNotEqualTo(FACTORY.create(2L, cfg).snapshotJson());
    }

    // ── terminal states ───────────────────────────────────────────────────

    @Test
    void killIsTerminalWithTheSurvivorAsWinner() throws Exception {
        SimCore sim = FACTORY.create(5L, config("{\"maxTicks\":1000}", "p1", "p2"));
        // Read p1's spawn, then walk p2 onto the cell directly beside it on
        // the y axis: clamping pins the first (oversized) move to the (0,0)
        // corner, so the second move's deltas are absolute coordinates.
        JsonNode p1 = JSON.readTree(sim.snapshotJson()).path("players").get(0);
        int x = p1.path("x").asInt(), y = p1.path("y").asInt();
        int targetY = y < 31 ? y + 1 : y - 1;     // south of p1, or north at the border
        int fireDir = y < 31 ? 2 : 0;
        sim.step(List.of(cmd(1, 0, "p2", "{\"op\":\"move\",\"dx\":-100,\"dy\":-100}")));
        sim.step(List.of(cmd(2, 0, "p2",
                "{\"op\":\"move\",\"dx\":" + x + ",\"dy\":" + targetY + "}")));
        // Four hits of 25 kill a 100-hp player; all in one tick is legal.
        sim.step(List.of(
                cmd(3, 0, "p1", "{\"op\":\"fire\",\"dir\":" + fireDir + "}"),
                cmd(3, 1, "p1", "{\"op\":\"fire\",\"dir\":" + fireDir + "}"),
                cmd(3, 2, "p1", "{\"op\":\"fire\",\"dir\":" + fireDir + "}"),
                cmd(3, 3, "p1", "{\"op\":\"fire\",\"dir\":" + fireDir + "}")));
        assertThat(sim.isTerminal()).isTrue();
        JsonNode result = JSON.readTree(sim.resultJson());
        assertThat(result.path("reason").asText()).isEqualTo("kill");
        assertThat(result.path("winner").asText()).isEqualTo("p1");
    }

    @Test
    void maxTicksIsTerminalWithNoWinner() throws Exception {
        SimCore sim = FACTORY.create(9L, config("{\"maxTicks\":5}", "p1", "p2"));
        for (int i = 0; i < 5; i++) {
            assertThat(sim.isTerminal()).isFalse();
            assertThat(sim.resultJson()).isNull();
            sim.step(List.of());
        }
        assertThat(sim.isTerminal()).isTrue();
        JsonNode result = JSON.readTree(sim.resultJson());
        assertThat(result.path("reason").asText()).isEqualTo("max-ticks");
        assertThat(result.path("winner").isNull()).isTrue();
        assertThat(result.path("tick").asLong()).isEqualTo(5);
    }

    // ── snapshot / restore ────────────────────────────────────────────────

    @Test
    void restoreOfASnapshotIsABitExactResumePoint() {
        SimCore live = FACTORY.create(99L, config("{\"maxTicks\":100000}", "alice", "bob"));
        for (long tick = 1; tick <= 40; tick++) {
            live.step(List.of(
                    cmd(tick, 0, "alice", "{\"op\":\"move\",\"dx\":1,\"dy\":0}"),
                    cmd(tick, 1, "bob",   "{\"op\":\"fire\",\"dir\":" + (tick % 4) + "}")));
        }
        String snapshot = live.snapshotJson();

        SimCore restored = FACTORY.restore(snapshot);
        // Round-trip equality: the FULL internal state (PRNG included)
        // serialises back to the identical string.
        assertThat(restored.snapshotJson()).isEqualTo(snapshot);
        assertThat(restored.tick()).isEqualTo(live.tick());

        // And the restored sim keeps simulating bit-exactly alongside the
        // original — the persist→recover contract.
        for (long tick = 41; tick <= 80; tick++) {
            List<SimCommand> commands = List.of(
                    cmd(tick, 0, "bob",   "{\"op\":\"move\",\"dx\":-1,\"dy\":1}"),
                    cmd(tick, 1, "alice", "{\"op\":\"fire\",\"dir\":1}"));
            live.step(commands);
            restored.step(commands);
            assertThat(restored.snapshotJson()).isEqualTo(live.snapshotJson());
        }
    }
}
