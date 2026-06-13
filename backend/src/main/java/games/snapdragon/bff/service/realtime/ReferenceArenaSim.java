package games.snapdragon.bff.service.realtime;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Small pure-Java deterministic arena sim — the P2 reference
 * {@link SimCore} that PROVES the authoritative loop before the Rust
 * cores (snapdragon-rts-core / snapdragon-fps-core) plug in at P4.
 *
 * <p>Determinism rules mirror the Rust cores: integer positions on a
 * bounded grid, seeded xorshift64* PRNG (spawn points only), fixed
 * player-iteration order (sorted by player id), no floats, no wall
 * clock. Commands: {@code {"op":"move","dx":..,"dy":..}} and
 * {@code {"op":"fire","dir":..}} (dir 0=N 1=E 2=S 3=W, hitscan along
 * the row/column). Terminal when any player's hp ≤ 0 or maxTicks is
 * reached. {@link #snapshotJson()} serialises the FULL internal state
 * (PRNG state included) in a canonical field order so
 * {@code restore(snapshotJson)} is a bit-exact resume point.
 */
public final class ReferenceArenaSim implements SimCore {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final int START_HP = 100;
    private static final int FIRE_DAMAGE = 25;

    private static final class Player {
        final String id;
        int x, y, hp, dir;
        Player(String id, int x, int y, int hp, int dir) {
            this.id = id; this.x = x; this.y = y; this.hp = hp; this.dir = dir;
        }
    }

    private long tick;
    private long rngState;
    private final int gridW, gridH;
    private final long maxTicks;
    // LinkedHashMap in sorted-id insertion order — the fixed iteration
    // order every state-touching loop uses (determinism rule #4).
    private final Map<String, Player> players = new LinkedHashMap<>();

    private ReferenceArenaSim(long seed, int gridW, int gridH, long maxTicks) {
        // Zero seed remaps to a non-zero constant, same as the Rust cores'
        // xorshift64* (an all-zero state never leaves zero).
        this.rngState = seed == 0 ? 0x9E3779B97F4A7C15L : seed;
        this.gridW = gridW;
        this.gridH = gridH;
        this.maxTicks = maxTicks;
    }

    /** Fresh match from the effective config the tick service composes. */
    static ReferenceArenaSim create(long seed, String configJson) {
        JsonNode cfg = parse(configJson);
        JsonNode game = cfg.path("game");
        ReferenceArenaSim sim = new ReferenceArenaSim(
                seed,
                game.path("gridW").asInt(32),
                game.path("gridH").asInt(32),
                game.path("maxTicks").asLong(1000));
        List<String> ids = new ArrayList<>();
        cfg.path("playerIds").forEach(n -> ids.add(n.asText()));
        ids.sort(String::compareTo);                      // fixed iteration order
        for (String id : ids) {
            int x = (int) Long.remainderUnsigned(sim.nextRng(), sim.gridW);
            int y = (int) Long.remainderUnsigned(sim.nextRng(), sim.gridH);
            sim.players.put(id, new Player(id, x, y, START_HP, 0));
        }
        return sim;
    }

    /** Bit-exact resume from a {@link #snapshotJson()} keyframe. */
    static ReferenceArenaSim restore(String snapshotJson) {
        JsonNode s = parse(snapshotJson);
        ReferenceArenaSim sim = new ReferenceArenaSim(
                1, s.path("gridW").asInt(), s.path("gridH").asInt(), s.path("maxTicks").asLong());
        sim.tick = s.path("tick").asLong();
        sim.rngState = Long.parseUnsignedLong(s.path("rng").asText());
        for (JsonNode p : s.path("players")) {
            sim.players.put(p.path("id").asText(), new Player(
                    p.path("id").asText(), p.path("x").asInt(), p.path("y").asInt(),
                    p.path("hp").asInt(), p.path("dir").asInt()));
        }
        return sim;
    }

    @Override public long tick() { return tick; }

    @Override
    public void step(List<SimCommand> orderedCommands) {
        if (isTerminal()) return;
        for (SimCommand cmd : orderedCommands) {
            Player actor = players.get(cmd.playerId());
            if (actor == null || actor.hp <= 0) continue;
            JsonNode payload = parse(cmd.payloadJson());
            switch (payload.path("op").asText()) {
                case "move" -> {
                    actor.x = clamp(actor.x + payload.path("dx").asInt(), gridW - 1);
                    actor.y = clamp(actor.y + payload.path("dy").asInt(), gridH - 1);
                }
                case "fire" -> {
                    actor.dir = Math.floorMod(payload.path("dir").asInt(), 4);
                    Player hit = nearestInDirection(actor);
                    if (hit != null) hit.hp = Math.max(0, hit.hp - FIRE_DAMAGE);
                }
                default -> { /* unknown op: deterministic no-op */ }
            }
        }
        tick++;
    }

    /** First player on the actor's row/column in fire direction; nearest
     *  wins, equal distance resolves in fixed (sorted-id) iteration order. */
    private Player nearestInDirection(Player actor) {
        Player best = null;
        int bestDist = Integer.MAX_VALUE;
        for (Player p : players.values()) {
            if (p == actor || p.hp <= 0) continue;
            int dist = switch (actor.dir) {
                case 0  -> (p.x == actor.x && p.y < actor.y) ? actor.y - p.y : -1;  // N
                case 1  -> (p.y == actor.y && p.x > actor.x) ? p.x - actor.x : -1;  // E
                case 2  -> (p.x == actor.x && p.y > actor.y) ? p.y - actor.y : -1;  // S
                default -> (p.y == actor.y && p.x < actor.x) ? actor.x - p.x : -1;  // W
            };
            if (dist > 0 && dist < bestDist) { best = p; bestDist = dist; }
        }
        return best;
    }

    @Override
    public String snapshotJson() {
        // Canonical field order (fixed insertion order, players sorted at
        // creation) makes the snapshot string itself bit-comparable.
        ObjectNode root = JSON.createObjectNode();
        root.put("tick", tick);
        root.put("rng", Long.toUnsignedString(rngState));
        root.put("gridW", gridW);
        root.put("gridH", gridH);
        root.put("maxTicks", maxTicks);
        ArrayNode arr = root.putArray("players");
        for (Player p : players.values()) {
            ObjectNode n = arr.addObject();
            n.put("id", p.id);
            n.put("x", p.x);
            n.put("y", p.y);
            n.put("hp", p.hp);
            n.put("dir", p.dir);
        }
        return root.toString();
    }

    @Override
    public boolean isTerminal() {
        return tick >= maxTicks || players.values().stream().anyMatch(p -> p.hp <= 0);
    }

    @Override
    public String resultJson() {
        if (!isTerminal()) return null;
        String winner = null;
        boolean kill = players.values().stream().anyMatch(p -> p.hp <= 0);
        if (kill) {
            for (Player p : players.values()) {
                if (p.hp > 0) { winner = p.id; break; }    // fixed iteration order
            }
        }
        ObjectNode root = JSON.createObjectNode();
        root.put("winner", winner);
        root.put("reason", kill ? "kill" : "max-ticks");
        root.put("tick", tick);
        return root.toString();
    }

    private long nextRng() {
        long x = rngState;
        x ^= x >>> 12;
        x ^= x << 25;
        x ^= x >>> 27;
        rngState = x;
        return x * 0x2545F4914F6CDD1DL;
    }

    private static int clamp(int v, int max) {
        return Math.max(0, Math.min(max, v));
    }

    private static JsonNode parse(String json) {
        try {
            return JSON.readTree(json == null || json.isBlank() ? "{}" : json);
        } catch (com.fasterxml.jackson.core.JacksonException ex) {
            throw new IllegalArgumentException("malformed sim JSON: " + ex.getMessage(), ex);
        }
    }
}
