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
 * {@link SimCore} that proves the authoritative tick loop before the Rust
 * cores (snapdragon-rts-core / snapdragon-fps-core) plug in at P4.
 *
 * <h2>Purpose</h2>
 *
 * <p>This class is a proof-of-concept integration point: it verifies that
 * {@link AuthoritativeTickService} correctly drives a {@link SimCore} through
 * the full start → tick → keyframe → recover lifecycle using the simplest
 * possible game logic. It is NOT production gameplay; it is replaced at P4 by
 * genre-specific Rust cores via JNI/FFI wrappers that implement the same
 * {@link SimCore} interface.
 *
 * <h2>Determinism rules</h2>
 *
 * <p>All rules mirror the Rust cores (determinism must be preserved across
 * languages when the same match replays on either side):
 * <ul>
 *   <li><b>Integer grid only</b> — positions are integer cell indices on a
 *       bounded {@code gridW × gridH} grid; no floating-point arithmetic on
 *       the state path.</li>
 *   <li><b>Seeded xorshift64* PRNG</b> — the only source of randomness; used
 *       exclusively for spawn-point assignment at {@link #create}. PRNG state
 *       is serialised in {@link #snapshotJson()} so restore is bit-exact.</li>
 *   <li><b>Fixed player iteration order</b> — player ids are sorted
 *       lexicographically at creation time and inserted into a
 *       {@code LinkedHashMap} in that order. Every state-touching loop iterates
 *       {@code players.values()} which preserves that insertion order, so two
 *       pods with the same player set always visit players in the same
 *       sequence.</li>
 *   <li><b>No wall clock</b> — {@code System.currentTimeMillis()} and
 *       {@code Instant.now()} are never called during state transitions.</li>
 * </ul>
 *
 * <h2>Command vocabulary</h2>
 *
 * <p>The {@code payloadJson} field of each {@link SimCommand} is interpreted
 * by this sim as one of two operations:
 * <ul>
 *   <li>{@code {"op":"move","dx":<int>,"dy":<int>}} — move the acting player
 *       by {@code (dx, dy)} cells, clamped to the grid boundary.</li>
 *   <li>{@code {"op":"fire","dir":<0|1|2|3>}} — fire hitscan in direction
 *       {@code 0=N, 1=E, 2=S, 3=W} along the actor's row or column; the
 *       nearest live player on that line receives {@link #FIRE_DAMAGE} HP
 *       damage. Equal-distance ties resolve in fixed (sorted-id) iteration
 *       order.</li>
 * </ul>
 * An unknown {@code op} is a deterministic no-op — the sim advances without
 * error, preserving the bit-exact contract even for future command extensions.
 *
 * <h2>Terminal conditions</h2>
 *
 * <p>The match ends when either (a) any player's {@code hp} drops to zero or
 * below, or (b) the tick counter reaches {@code maxTicks} (configurable via
 * the match's {@code game.maxTicks} config field, defaulting to 1000). Once
 * terminal, {@link #step} becomes a no-op and {@link #resultJson()} returns
 * the outcome.
 *
 * <h2>Snapshot / restore contract</h2>
 *
 * <p>{@link #snapshotJson()} serialises ALL internal state in a canonical fixed
 * field order (tick, rng, gridW, gridH, maxTicks, players array sorted by
 * creation order). {@link #restore(String)} reconstructs a byte-identical
 * instance. Both paths are exercised by the {@code ReferenceArenaSimTest} suite.
 *
 * @see SimCore
 * @see SimCoreFactory
 * @see ReferenceArenaSimFactory
 * @see AuthoritativeTickService
 */
public final class ReferenceArenaSim implements SimCore {

    // -------------------------------------------------------------------------
    // Constants
    // -------------------------------------------------------------------------

    /** Shared Jackson instance — stateless, thread-safe, reused for all JSON work. */
    private static final ObjectMapper JSON = new ObjectMapper();

    /** HP each player starts with at match creation. */
    private static final int START_HP = 100;

    /** HP lost by the target of a hitscan {@code fire} command. */
    private static final int FIRE_DAMAGE = 25;

    // -------------------------------------------------------------------------
    // Inner types
    // -------------------------------------------------------------------------

    /**
     * Mutable per-player state held in the sim's {@code players} map. All
     * fields are integers to satisfy the determinism rule (no floats on the
     * state path). The {@code id} field is final and is the map key — it never
     * changes after construction.
     */
    private static final class Player {
        /** Casdoor subject id — the map key and the SimCommand.playerId target. */
        final String id;
        /** Horizontal grid position (0 = left edge, gridW-1 = right edge). */
        int x;
        /** Vertical grid position (0 = top edge, gridH-1 = bottom edge). */
        int y;
        /** Remaining hit-points; match ends when this reaches 0. */
        int hp;
        /** Last fire direction (0=N, 1=E, 2=S, 3=W); used by nearestInDirection. */
        int dir;

        Player(String id, int x, int y, int hp, int dir) {
            this.id = id; this.x = x; this.y = y; this.hp = hp; this.dir = dir;
        }
    }

    // -------------------------------------------------------------------------
    // State
    // -------------------------------------------------------------------------

    /** Number of ticks completed so far; 0 before the first step. */
    private long tick;

    /**
     * xorshift64* PRNG state. Never zero (the xorshift64* algorithm cannot
     * escape the all-zero state — a zero seed is remapped at construction
     * time). Serialised in {@link #snapshotJson()} as an unsigned decimal
     * string so {@link #restore} can recover it exactly.
     */
    private long rngState;

    /** Horizontal extent of the grid (cell count); immutable after construction. */
    private final int gridW;

    /** Vertical extent of the grid (cell count); immutable after construction. */
    private final int gridH;

    /** Tick limit; the match ends when {@code tick >= maxTicks}. */
    private final long maxTicks;

    /**
     * Per-player live state keyed by Casdoor id. A {@code LinkedHashMap} in
     * sorted-id insertion order — the fixed iteration order every state-touching
     * loop uses (determinism rule: fixed iteration order over entities).
     */
    private final Map<String, Player> players = new LinkedHashMap<>();

    // -------------------------------------------------------------------------
    // Construction
    // -------------------------------------------------------------------------

    /**
     * Internal constructor — initialises grid dimensions and PRNG; does NOT
     * place players. Use {@link #create} or {@link #restore} instead.
     *
     * @param seed    entropy seed; 0 is remapped to a stable non-zero constant
     * @param gridW   horizontal grid extent in cells
     * @param gridH   vertical grid extent in cells
     * @param maxTicks maximum ticks before the match ends in a draw
     */
    private ReferenceArenaSim(long seed, int gridW, int gridH, long maxTicks) {
        // Zero seed remaps to a non-zero constant, same as the Rust cores'
        // xorshift64* (an all-zero state never leaves zero).
        this.rngState = seed == 0 ? 0x9E3779B97F4A7C15L : seed;
        this.gridW = gridW;
        this.gridH = gridH;
        this.maxTicks = maxTicks;
    }

    /**
     * Fresh match from the effective config the tick service composes.
     *
     * <p>The config schema is:
     * <pre>
     *   {
     *     "playerIds": ["id1", "id2", ...],
     *     "game": {
     *       "gridW":    32,    // optional; default 32
     *       "gridH":    32,    // optional; default 32
     *       "maxTicks": 1000   // optional; default 1000
     *     }
     *   }
     * </pre>
     * Players are sorted by id then each assigned a random spawn point via the
     * seeded PRNG. Sorting first ensures the PRNG is consumed in the same order
     * across any two pods given the same seed and roster.
     *
     * @param seed       entropy seed for the PRNG; 0 is remapped internally
     * @param configJson genre-agnostic effective match config JSON
     * @return a new {@code ReferenceArenaSim} at {@code tick() == 0}
     */
    static ReferenceArenaSim create(long seed, String configJson) {
        JsonNode cfg = parse(configJson);
        JsonNode game = cfg.path("game");
        ReferenceArenaSim sim = new ReferenceArenaSim(
                seed,
                game.path("gridW").asInt(32),
                game.path("gridH").asInt(32),
                game.path("maxTicks").asLong(1000));
        // Collect player ids and sort them for a fixed, deterministic spawn order.
        List<String> ids = new ArrayList<>();
        cfg.path("playerIds").forEach(n -> ids.add(n.asText()));
        ids.sort(String::compareTo);                      // fixed iteration order
        // Place each player at a PRNG-derived cell position.
        for (String id : ids) {
            int x = (int) Long.remainderUnsigned(sim.nextRng(), sim.gridW);
            int y = (int) Long.remainderUnsigned(sim.nextRng(), sim.gridH);
            sim.players.put(id, new Player(id, x, y, START_HP, 0));
        }
        return sim;
    }

    /**
     * Bit-exact resume from a {@link #snapshotJson()} keyframe.
     *
     * <p>The snapshot must have been produced by this class. Field order in the
     * {@code players} array must match creation order (i.e. sorted by id at
     * creation time) so the resulting {@code LinkedHashMap} retains the same
     * iteration order as the original.
     *
     * @param snapshotJson a JSON string previously returned by
     *                     {@link #snapshotJson()} on a live {@code ReferenceArenaSim}
     * @return a {@code ReferenceArenaSim} whose state is bit-exact equal to the
     *         snapshotted instance
     */
    static ReferenceArenaSim restore(String snapshotJson) {
        JsonNode s = parse(snapshotJson);
        // Seed is irrelevant for restore — the PRNG state is loaded directly.
        ReferenceArenaSim sim = new ReferenceArenaSim(
                1, s.path("gridW").asInt(), s.path("gridH").asInt(), s.path("maxTicks").asLong());
        // Restore tick counter and PRNG state from the snapshot.
        sim.tick = s.path("tick").asLong();
        sim.rngState = Long.parseUnsignedLong(s.path("rng").asText());
        // Restore players in the array order they were written (sorted-id order
        // preserved from creation), so the LinkedHashMap iteration order matches.
        for (JsonNode p : s.path("players")) {
            sim.players.put(p.path("id").asText(), new Player(
                    p.path("id").asText(), p.path("x").asInt(), p.path("y").asInt(),
                    p.path("hp").asInt(), p.path("dir").asInt()));
        }
        return sim;
    }

    // -------------------------------------------------------------------------
    // SimCore implementation
    // -------------------------------------------------------------------------

    /** @inheritDoc */
    @Override public long tick() { return tick; }

    /**
     * Advances the sim by exactly one tick, applying the given commands in the
     * order they appear in {@code orderedCommands}. Commands targeting dead
     * players or unknown player ids are silently skipped. An unknown {@code op}
     * is a deterministic no-op.
     *
     * <p>If the sim is already terminal, this method returns immediately without
     * modifying any state.
     *
     * @param orderedCommands commands to apply this tick, in {@code (forTick,
     *                        seq)} ascending order; the sim must NOT re-sort them
     */
    @Override
    public void step(List<SimCommand> orderedCommands) {
        // If the match is already over, discard all commands and do nothing.
        if (isTerminal()) return;
        for (SimCommand cmd : orderedCommands) {
            Player actor = players.get(cmd.playerId());
            // Skip commands from unknown players or players who are already dead.
            if (actor == null || actor.hp <= 0) continue;
            JsonNode payload = parse(cmd.payloadJson());
            switch (payload.path("op").asText()) {
                case "move" -> {
                    // Clamp the new position to stay within the grid boundary.
                    actor.x = clamp(actor.x + payload.path("dx").asInt(), gridW - 1);
                    actor.y = clamp(actor.y + payload.path("dy").asInt(), gridH - 1);
                }
                case "fire" -> {
                    // Update the actor's facing direction (mod 4 to stay in 0–3).
                    actor.dir = Math.floorMod(payload.path("dir").asInt(), 4);
                    // Find the nearest live player on the actor's fire line.
                    Player hit = nearestInDirection(actor);
                    // Apply damage; floor at 0 so HP never goes negative.
                    if (hit != null) hit.hp = Math.max(0, hit.hp - FIRE_DAMAGE);
                }
                default -> { /* unknown op: deterministic no-op */ }
            }
        }
        // Advance the tick counter exactly once per step call.
        tick++;
    }

    // -------------------------------------------------------------------------
    // Hitscan helper
    // -------------------------------------------------------------------------

    /**
     * Returns the nearest live player on the actor's row or column in the
     * actor's current fire direction, or {@code null} if the line is clear.
     *
     * <p>Hitscan geometry (integer cell distances):
     * <ul>
     *   <li>N (dir=0): same column ({@code x}), smaller {@code y} value.</li>
     *   <li>E (dir=1): same row ({@code y}), larger {@code x} value.</li>
     *   <li>S (dir=2): same column ({@code x}), larger {@code y} value.</li>
     *   <li>W (dir=3): same row ({@code y}), smaller {@code x} value.</li>
     * </ul>
     * When two players are equidistant, the one that comes first in
     * {@code players.values()} iteration order (sorted-id order from creation)
     * wins — this is deterministic across all pods.
     *
     * @param actor the firing player; excluded from its own hitscan
     * @return the nearest player on the fire line, or {@code null}
     */
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

    // -------------------------------------------------------------------------
    // Snapshot / terminal / result
    // -------------------------------------------------------------------------

    /**
     * Serialises the complete sim state as a canonical JSON string. All fields
     * are emitted in a fixed order (tick, rng, gridW, gridH, maxTicks, players
     * array) so the string itself is byte-comparable across pods.
     *
     * <p>The {@code rng} field is serialised as an unsigned decimal string via
     * {@link Long#toUnsignedString} so the full 64-bit unsigned range is
     * preserved without sign ambiguity.
     *
     * <p>The {@code players} array is written in {@code players.values()}
     * iteration order (sorted-id order from creation), so {@link #restore} can
     * reconstruct the same {@code LinkedHashMap} iteration order.
     *
     * @return a non-null JSON string encoding all state including PRNG, suitable
     *         for {@link #restore}
     */
    @Override
    public String snapshotJson() {
        // Canonical field order (fixed insertion order, players sorted at
        // creation) makes the snapshot string itself bit-comparable.
        ObjectNode root = JSON.createObjectNode();
        root.put("tick", tick);
        // Unsigned string preserves the full 64-bit range without sign confusion.
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

    /**
     * Returns {@code true} when the match has ended — either any player's HP
     * has reached zero (a kill), or the tick counter has reached {@code maxTicks}
     * (the tick ceiling draw condition).
     *
     * @return {@code true} iff no further state transitions are possible
     */
    @Override
    public boolean isTerminal() {
        return tick >= maxTicks || players.values().stream().anyMatch(p -> p.hp <= 0);
    }

    /**
     * Returns the match outcome as JSON, or {@code null} if the match is still
     * in progress.
     *
     * <p>Result schema:
     * <pre>
     *   {
     *     "winner": "playerId" | null,   // null on max-ticks draw
     *     "reason": "kill" | "max-ticks",
     *     "tick":   &lt;long&gt;
     *   }
     * </pre>
     *
     * <p>Winner resolution for a kill: the first live player in sorted-id
     * (fixed) iteration order. If all players are dead simultaneously (which
     * cannot currently happen given single-target hitscan), winner is
     * {@code null}.
     *
     * @return a JSON result string when {@link #isTerminal()}, or {@code null}
     *         while the match is still running
     */
    @Override
    public String resultJson() {
        if (!isTerminal()) return null;
        String winner = null;
        boolean kill = players.values().stream().anyMatch(p -> p.hp <= 0);
        if (kill) {
            // With >2 players, a kill can leave several survivors — the pick
            // among them is otherwise arbitrary, but it MUST be deterministic.
            // This same resultJson() call runs independently on every pod, and
            // again on any pod that restore()s this match from a snapshot; all
            // of them have to agree on one winnerId for the same match state
            // (the authoritative-tick recovery contract — see class-level
            // "Determinism rules"). Iterating players.values() in the fixed
            // sorted-id order guarantees that; swapping in a HashMap or any
            // other order-unstable collection here would let two pods report
            // different winners for the identical match.
            for (Player p : players.values()) {
                if (p.hp > 0) { winner = p.id; break; }
            }
        }
        ObjectNode root = JSON.createObjectNode();
        root.put("winner", winner);
        root.put("reason", kill ? "kill" : "max-ticks");
        root.put("tick", tick);
        return root.toString();
    }

    // -------------------------------------------------------------------------
    // PRNG and utility helpers
    // -------------------------------------------------------------------------

    /**
     * xorshift64* PRNG step. Returns a high-quality 64-bit value derived from
     * the current {@link #rngState} and advances the state. The multiplier
     * ({@code 0x2545F4914F6CDD1DL}) is the standard xorshift64* output
     * scrambler — it is NOT the state; the raw XOR shifts update
     * {@link #rngState} before the multiply.
     *
     * @return the next pseudorandom 64-bit value (unsigned; use with
     *         {@link Long#remainderUnsigned} to map to a range)
     */
    private long nextRng() {
        long x = rngState;
        x ^= x >>> 12;
        x ^= x << 25;
        x ^= x >>> 27;
        rngState = x;
        // Apply the xorshift64* output scrambler to improve bit distribution.
        return x * 0x2545F4914F6CDD1DL;
    }

    /**
     * Clamps {@code v} to the range {@code [0, max]} (inclusive). Used to keep
     * player positions within the grid after a move command.
     *
     * @param v   the raw (potentially out-of-bounds) value
     * @param max the inclusive upper bound ({@code gridW - 1} or
     *            {@code gridH - 1})
     * @return {@code v} clamped to {@code [0, max]}
     */
    private static int clamp(int v, int max) {
        return Math.max(0, Math.min(max, v));
    }

    /**
     * Null-safe JSON parse. A null or blank input is treated as {@code "{}"}
     * so callers can safely use {@code .path(...).asXxx(defaultValue)} without
     * null checks.
     *
     * @param json the raw JSON string, or null / blank
     * @return a parsed {@link JsonNode}; never {@code null}
     * @throws IllegalArgumentException if the input is non-blank but malformed
     */
    private static JsonNode parse(String json) {
        try {
            return JSON.readTree(json == null || json.isBlank() ? "{}" : json);
        } catch (com.fasterxml.jackson.core.JacksonException ex) {
            throw new IllegalArgumentException("malformed sim JSON: " + ex.getMessage(), ex);
        }
    }
}
