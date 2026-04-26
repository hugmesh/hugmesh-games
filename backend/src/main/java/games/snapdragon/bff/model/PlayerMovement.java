package games.snapdragon.bff.model;

/** Emitted on the per-character movement SSE stream only when position or action changes. */
public record PlayerMovement(
        long characterId,
        double x,
        double y,
        double z,
        double rotationY,
        String action,
        boolean pressed,
        long tick
) {}
