package games.snapdragon.bff.model;

import java.time.Instant;

/**
 * One row of a player's session-history view: the session metadata
 * joined with the caller's {@code session_members} membership row.
 *
 * <p>Read-only. Unlike {@link GameSessionDto}, this carries the
 * caller's {@code joinedAt} / {@code leftAt} timestamps so the UI can
 * render a duration and visibility-history timeline.
 */
public record SessionLogEntry(
        String  sessionId,
        String  sessionName,
        String  gameSlug,
        String  visibility,
        String  creatorCasdoorId,
        String  memberCasdoorId,
        Long    characterId,
        Integer slotIndex,
        boolean wasCreator,
        Instant joinedAt,
        Instant leftAt,
        Instant sessionClosedAt,
        Instant sessionPausedAt,
        String  sessionPauseReason
) {}
