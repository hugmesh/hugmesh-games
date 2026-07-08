package games.snapdragon.bff.service.realtime;

import java.util.List;

/**
 * Genre-agnostic deterministic simulation core driven by
 * {@link AuthoritativeTickService} — the Java seam the P4 Rust cores
 * (snapdragon-rts-core / snapdragon-fps-core) plug into via
 * {@link SimCoreFactory}.
 *
 * <h2>Determinism contract</h2>
 *
 * <p>The single most important property of any {@code SimCore} implementation
 * is determinism: given the same seed and the same ordered command stream,
 * every call to {@link #step} must produce bit-exact identical state, and every
 * call to {@link #snapshotJson} must return the same string on any pod, on any
 * JVM restart, at any wall-clock time.
 *
 * <p>Implementations must honour these rules (mirroring the Rust cores):
 * <ul>
 *   <li>Use only integer or fixed-point arithmetic on the state path — no
 *       {@code float}, no {@code double}.</li>
 *   <li>Use a seeded PRNG only (e.g. xorshift64*); never consume system entropy
 *       ({@code Math.random()}, {@code new Random()}, {@code SecureRandom}).</li>
 *   <li>Maintain a fixed, deterministic iteration order over all collections
 *       (e.g. sort entity ids at creation, use {@code LinkedHashMap}).</li>
 *   <li>Never read the wall clock ({@code System.currentTimeMillis()},
 *       {@code Instant.now()}) during state transitions.</li>
 *   <li>Treat the {@code orderedCommands} list passed to {@link #step} as
 *       already sorted — the sim must NOT re-sort or reorder commands; the
 *       authoritative order is established by the caller using
 *       {@code (forTick, seq)}.</li>
 * </ul>
 *
 * <h2>Snapshot / restore contract</h2>
 *
 * <p>The string returned by {@link #snapshotJson} must be a self-contained,
 * fully reproducible checkpoint: calling {@link SimCoreFactory#restore} on it
 * must yield a {@code SimCore} whose next {@code snapshotJson()} call returns
 * the same string. In particular, PRNG state must be serialised.
 *
 * <h2>Caller-role access matrix</h2>
 *
 * <p>{@code SimCore} instances are held only by
 * {@link AuthoritativeTickService.MatchState} and are never exposed directly
 * over the API. Access is mediated entirely by {@link AuthoritativeTickService}.
 *
 * <pre>
 *  Caller            │ Interacts with SimCore?
 *  ──────────────────┼────────────────────────────────────────────────────────
 *  Player            │ Indirectly — via submitInput (which calls step at tick
 *                    │ time) and snapshotStream (which reads snapshotJson output)
 *  Admin/Superadmin  │ Indirectly — via startMatch, recoverMatch, terminate
 *  Mod               │ No access to the realtime package
 * </pre>
 *
 * @see SimCoreFactory
 * @see ReferenceArenaSim
 * @see AuthoritativeTickService
 * @see SimCommand
 */
public interface SimCore {

    // -------------------------------------------------------------------------
    // State accessors
    // -------------------------------------------------------------------------

    /**
     * Number of completed ticks (0 before the first {@link #step} call).
     * The tick counter increments exactly once per {@link #step} call and is
     * the authoritative clock the command lag buffer
     * ({@code submitInput → forTick = tick() + inputDelayTicks}) and the
     * keyframe schedule ({@code tick % keyframeInterval == 0}) both depend on.
     *
     * @return the number of ticks the sim has advanced; always {@code >= 0}
     */
    long tick();

    /**
     * Advance exactly one tick, applying each command in {@code orderedCommands}
     * in the order given.
     *
     * <p>The caller ({@link AuthoritativeTickService#runTick}) drains the pending
     * {@link SimCommand} set using the {@code (forTick, seq)} comparator before
     * passing it here. The sim must NOT re-order the list — any reordering breaks
     * the determinism contract when two pods replay the same log.
     *
     * <p>After this method returns, {@link #tick()} must be exactly one greater
     * than its previous value. If the commands trigger a terminal condition (e.g.
     * a player reaches zero HP), {@link #isTerminal()} must return {@code true}
     * immediately.
     *
     * @param orderedCommands commands to apply this tick, already sorted in
     *                        {@code (forTick, seq)} ascending order; may be empty
     *                        when no input was submitted for this tick
     */
    void step(List<SimCommand> orderedCommands);

    // -------------------------------------------------------------------------
    // Persistence / recovery
    // -------------------------------------------------------------------------

    /**
     * Canonical full-state snapshot serialised as a JSON string.
     *
     * <p>This is the keyframe the server persists to Cassandra
     * ({@link RealtimeTruthStore#saveKeyframe}) every {@code keyframeInterval}
     * ticks, and it is the data {@link SimCoreFactory#restore} consumes for
     * cross-pod recovery. The snapshot must be bit-exact idempotent:
     * {@code factory.restore(sim.snapshotJson()).snapshotJson().equals(sim.snapshotJson())}
     * must hold for all reachable states.
     *
     * <p>Field order in the JSON must be deterministic (e.g. fixed insertion
     * order with Jackson's {@code ObjectNode}) so that the string itself is
     * byte-comparable and can be used as a consistency check.
     *
     * @return a non-null JSON string encoding the complete simulation state,
     *         including PRNG state, so {@link SimCoreFactory#restore} can
     *         produce a bit-exact resume point
     */
    String snapshotJson();

    // -------------------------------------------------------------------------
    // Terminal conditions
    // -------------------------------------------------------------------------

    /**
     * Returns {@code true} once the match has reached a terminal state (a win
     * condition, a draw, or the configured tick ceiling). Once {@code true},
     * subsequent calls to {@link #step} are no-ops and
     * {@link #resultJson()} becomes non-null.
     *
     * @return {@code true} iff no further state transitions are possible
     */
    boolean isTerminal();

    /**
     * Final match result serialised as a JSON string; {@code null} until
     * {@link #isTerminal()} returns {@code true}.
     *
     * <p>The schema is sim-kind-specific (e.g. {@code {"winner":"playerId",
     * "reason":"kill", "tick":N}} for {@link ReferenceArenaSim}). The
     * authoritative tick service persists this to the {@code realtime_matches}
     * Postgres row when it calls {@link AuthoritativeTickService#runTick} and
     * detects {@code isTerminal()}.
     *
     * @return a JSON string describing the match outcome, or {@code null} while
     *         the match is still in progress
     */
    String resultJson();
}
