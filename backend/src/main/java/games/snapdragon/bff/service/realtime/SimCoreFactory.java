package games.snapdragon.bff.service.realtime;

/**
 * Creates / restores one kind of {@link SimCore}. Spring collects every
 * {@code SimCoreFactory} bean into the registry map inside
 * {@link AuthoritativeTickService}, so adding a new genre core (the P4
 * Rust FFI wrappers included) is just registering another bean.
 */
public interface SimCoreFactory {

    /** The sim kind this factory builds (e.g. {@code "reference-arena"}). */
    String kind();

    /**
     * Start a fresh sim. {@code configJson} is the effective match config
     * the tick service composes: {@code {"playerIds":[...],"game":<caller
     * config or {}>}} — the roster rides inside it because match creation
     * is genre-agnostic.
     */
    SimCore create(long seed, String configJson);

    /** Bit-exact resume from a {@link SimCore#snapshotJson()} keyframe. */
    SimCore restore(String snapshotJson);
}
