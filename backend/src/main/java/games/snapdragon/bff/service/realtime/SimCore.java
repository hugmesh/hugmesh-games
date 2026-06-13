package games.snapdragon.bff.service.realtime;

import java.util.List;

/**
 * Genre-agnostic deterministic simulation core driven by
 * {@link AuthoritativeTickService} — the Java seam the P4 Rust cores
 * (snapdragon-rts-core / snapdragon-fps-core) plug into.
 *
 * <p>The contract that matters above all else is <strong>determinism</strong>:
 * same seed + same ordered command stream ⇒ bit-exact identical state and
 * identical {@link #snapshotJson()} output, forever. Implementations must
 * use only integer / fixed-point state, a seeded PRNG, and a fixed
 * iteration order — no wall clock, no floats on the state path, no system
 * entropy (mirrors the Rust cores' determinism rules).
 */
public interface SimCore {

    /** Number of completed ticks (0 before the first {@link #step}). */
    long tick();

    /**
     * Advance exactly one tick, applying {@code orderedCommands} (already
     * sorted in {@code (forTick, seq)} order by the caller — the sim must
     * NOT re-order them).
     */
    void step(List<SimCommand> orderedCommands);

    /**
     * Canonical full-state snapshot. This is the keyframe the server
     * persists; {@link SimCoreFactory#restore(String)} on its output must
     * yield a bit-exact resume point ({@code restore(s).snapshotJson()}
     * equals {@code s}).
     */
    String snapshotJson();

    /** True once the match has reached a terminal state. */
    boolean isTerminal();

    /** Final result JSON; {@code null} until {@link #isTerminal()}. */
    String resultJson();
}
