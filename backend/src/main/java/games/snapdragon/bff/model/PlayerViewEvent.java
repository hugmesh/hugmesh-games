package games.snapdragon.bff.model;

import java.time.Instant;

/**
 * One sample of a player's camera / orientation state, emitted by the Rust
 * GDExtension's {@code view_egress} signal at ~10 Hz. Fans out on the
 * per-session {@code view} SSE channel that admins subscribe to via
 * {@code /api/admin/sessions/{id}/stream}.
 *
 * @param sessionId   multiplayer session this event belongs to.
 * @param slotIndex   which slot within the session emitted the event
 *                    (0..maxPlayers-1).
 * @param characterId character backing the slot.
 * @param x,y,z       camera world position.
 * @param yaw,pitch   camera orientation (radians).
 * @param fov         camera field-of-view (degrees) — admins can render
 *                    a frustum hint without sampling video frames.
 * @param occurredAt  pod-side stamp time.
 */
public record PlayerViewEvent(
        String  sessionId,
        int     slotIndex,
        Long    characterId,
        double  x,
        double  y,
        double  z,
        double  yaw,
        double  pitch,
        double  fov,
        Instant occurredAt
) {}
