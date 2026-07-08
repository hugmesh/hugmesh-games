package games.snapdragon.bff.service.realtime;

/**
 * Creates and restores one kind of {@link SimCore}.
 *
 * <h2>Registration</h2>
 *
 * <p>Spring discovers every {@code SimCoreFactory} bean at startup. The
 * {@link AuthoritativeTickService} constructor collects them into an
 * unmodifiable {@code Map<String, SimCoreFactory>} keyed by {@link #kind()}.
 * Adding a new genre core (the P4 Rust FFI wrappers, for example) is therefore
 * purely additive: implement this interface, declare the bean, and the registry
 * gains the new kind automatically — no changes to
 * {@link AuthoritativeTickService} itself.
 *
 * <h2>Caller-role access matrix</h2>
 *
 * <p>Factories are only invoked by {@link AuthoritativeTickService} on the paths
 * initiated by admins or superadmins ({@code startMatch}, {@code recoverMatch}).
 * Players never interact with factories directly.
 *
 * <pre>
 *  Caller            │ Triggers factory?
 *  ──────────────────┼────────────────────────────────────────────────────────
 *  Admin/Superadmin  │ Yes — via startMatch (create) and recoverMatch (restore)
 *  Player            │ No — indirectly benefits from the created SimCore
 *  Mod               │ No access to the realtime package
 * </pre>
 *
 * @see AuthoritativeTickService
 * @see SimCore
 * @see ReferenceArenaSimFactory
 */
public interface SimCoreFactory {

    // -------------------------------------------------------------------------
    // Identity
    // -------------------------------------------------------------------------

    /**
     * The sim-kind identifier this factory builds. Must be unique across all
     * registered {@code SimCoreFactory} beans — a duplicate triggers an
     * exception at startup when the registry map is assembled.
     *
     * <p>Examples: {@code "reference-arena"} (the P2 Java proof-of-concept),
     * {@code "fps-core"} (the future Rust FFI wrapper).
     *
     * @return a non-null, non-blank lowercase hyphenated kind string
     */
    String kind();

    // -------------------------------------------------------------------------
    // Lifecycle
    // -------------------------------------------------------------------------

    /**
     * Start a fresh sim with the given entropy seed and effective match config.
     *
     * <p>{@code configJson} is the genre-agnostic config object assembled by
     * {@link AuthoritativeTickService} before calling this method:
     * <pre>
     *   {
     *     "playerIds": ["id1", "id2", ...],
     *     "game": { /* caller-supplied sim-specific config, or {} *{@literal /} }
     *   }
     * </pre>
     * The roster is embedded in the config because match creation is
     * genre-agnostic — the tick service never knows what fields a given sim
     * kind requires beyond the roster.
     *
     * <p>Implementations must use {@code seed} as the sole source of entropy
     * (seeded PRNG only; no system entropy). A zero seed must be remapped to a
     * stable non-zero constant so the xorshift64* PRNG never stalls in the
     * all-zero state.
     *
     * @param seed       entropy seed for the sim's PRNG; derived from the match
     *                   record and reproducible across recovery
     * @param configJson genre-agnostic effective match config JSON (roster +
     *                   caller-supplied game section)
     * @return a newly initialised {@link SimCore} at {@code tick() == 0}, ready
     *         for its first {@link SimCore#step} call
     */
    SimCore create(long seed, String configJson);

    /**
     * Bit-exact resume from a {@link SimCore#snapshotJson()} keyframe.
     *
     * <p>The returned {@link SimCore} must satisfy:
     * {@code factory.restore(snapshot).snapshotJson().equals(snapshot)} — that
     * is, restoring a snapshot and immediately re-snapshotting it must yield the
     * same string. This is the cross-pod recovery contract: any pod can load a
     * keyframe from Cassandra via {@link RealtimeTruthStore#latestKeyframe} and
     * resume the match at the exact state the originating pod left it in.
     *
     * @param snapshotJson a JSON string previously returned by
     *                     {@link SimCore#snapshotJson()} on a live instance of
     *                     this factory's kind
     * @return a {@link SimCore} whose state is bit-exact equal to the snapshotted
     *         instance, with {@link SimCore#tick()} restored to the value it held
     *         when the snapshot was taken
     */
    SimCore restore(String snapshotJson);
}
