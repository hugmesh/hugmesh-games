package games.snapdragon.bff.model;

/**
 * Emitted on the per-character movement SSE stream when position or
 * action changes.
 *
 * <p>{@code levelId} scopes the movement so subscribers can ignore
 * events from other levels (a player in cave shouldn't react to a
 * player moving in field). Omitted (null) for legacy clients that
 * pre-date Liquibase 027's per-level cache.
 */
public record PlayerMovement(
        long characterId,
        Long levelId,
        double x,
        double y,
        double z,
        double rotationY,
        String action,
        boolean pressed,
        long tick
) {
    /** Backward-compatible constructor for callers that don't have a
     *  level id yet. The SSE wire format always carries levelId; this
     *  is for in-process compose stages (test seeds). */
    public PlayerMovement(long characterId,
                          double x, double y, double z, double rotationY,
                          String action, boolean pressed, long tick) {
        this(characterId, null, x, y, z, rotationY, action, pressed, tick);
    }
}
