package games.snapdragon.bff.service.realtime;

import org.springframework.stereotype.Component;

/**
 * {@link SimCoreFactory} for the P2 reference arena sim — registers
 * {@link ReferenceArenaSim} in {@link AuthoritativeTickService}'s factory
 * registry under the kind key {@link #KIND}.
 *
 * <h2>Role in the factory registry</h2>
 *
 * <p>Spring component-scans this {@code @Component} and
 * {@link AuthoritativeTickService} collects it (alongside any other
 * {@link SimCoreFactory} beans) into an unmodifiable
 * {@code Map<String, SimCoreFactory>} at startup. A
 * {@code startMatch} request for {@code kind = "reference-arena"} resolves
 * to this factory's {@link #create} method; a {@code recoverMatch} resolves
 * to {@link #restore}.
 *
 * <h2>P2 vs P4 distinction</h2>
 *
 * <p>This factory is the P2 phase proof-of-concept integration point. At P4,
 * genre-specific Rust cores (snapdragon-rts-core, snapdragon-fps-core) will
 * supply their own {@link SimCoreFactory} beans with different {@link #kind()}
 * strings ({@code "rts-core"}, {@code "fps-core"}, etc.). The reference arena
 * factory coexists with those and remains available for integration tests.
 *
 * <h2>Caller-role access matrix</h2>
 *
 * <p>Indirectly invoked only by {@link AuthoritativeTickService} on paths
 * initiated by admins or superadmins ({@code startMatch}, {@code recoverMatch}).
 *
 * <pre>
 *  Caller            │ Triggers this factory?
 *  ──────────────────┼───────────────────────────────────────────────────────
 *  Admin/Superadmin  │ Yes — via startMatch (create) or recoverMatch (restore)
 *                    │ when kind="reference-arena"
 *  Player            │ No — indirectly benefits from the created SimCore
 *  Mod               │ No access to the realtime package
 * </pre>
 *
 * @see ReferenceArenaSim
 * @see SimCoreFactory
 * @see AuthoritativeTickService
 */
@Component
public class ReferenceArenaSimFactory implements SimCoreFactory {

    // -------------------------------------------------------------------------
    // Constants
    // -------------------------------------------------------------------------

    /**
     * The kind key under which this factory is registered in the
     * {@link AuthoritativeTickService} factory map. Also passed as the
     * {@code kind} parameter to {@code startMatch} by callers that want to
     * start a reference-arena match.
     */
    public static final String KIND = "reference-arena";

    // -------------------------------------------------------------------------
    // SimCoreFactory implementation
    // -------------------------------------------------------------------------

    /**
     * @return {@code "reference-arena"} — the unique kind identifier for the
     *         P2 Java proof-of-concept sim
     */
    @Override
    public String kind() {
        return KIND;
    }

    /**
     * Delegates to {@link ReferenceArenaSim#create}: initialises a new arena
     * match with the given seed and parsed config (grid dimensions, player
     * roster, max ticks).
     *
     * @param seed       entropy seed for the PRNG; 0 is remapped internally
     * @param configJson effective match config JSON as assembled by
     *                   {@link AuthoritativeTickService}
     * @return a fresh {@link ReferenceArenaSim} at {@code tick() == 0}
     */
    @Override
    public SimCore create(long seed, String configJson) {
        return ReferenceArenaSim.create(seed, configJson);
    }

    /**
     * Delegates to {@link ReferenceArenaSim#restore}: reconstructs a
     * bit-exact sim instance from a keyframe snapshot previously produced by
     * {@link ReferenceArenaSim#snapshotJson()}.
     *
     * @param snapshotJson a JSON snapshot string from a live
     *                     {@code ReferenceArenaSim} instance
     * @return a {@link ReferenceArenaSim} whose state is bit-exact equal to
     *         the snapshotted instance
     */
    @Override
    public SimCore restore(String snapshotJson) {
        return ReferenceArenaSim.restore(snapshotJson);
    }
}
