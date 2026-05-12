package games.snapdragon.bff.model;

import java.time.Instant;

/**
 * SSE event signalling a session-lifecycle change that every player's
 * WASM iframe needs to react to. Fanned out on both the per-session
 * sink (admins watching a single session see only that session) and
 * the global sink (every connected player's host shell can react —
 * particularly to {@link Action#SESSION_CLOSED} which collapses the
 * game iframe).
 *
 * <p>Four shapes:
 *
 * <ul>
 *   <li>{@link Action#SESSION_PAUSED} / {@link Action#SESSION_RESUMED}
 *       — entire session is frozen / unfrozen. {@code casdoorId} is
 *       null (every player). {@code reason} is human-readable.</li>
 *   <li>{@link Action#PLAYER_PAUSED} / {@link Action#PLAYER_RESUMED}
 *       — one player's view is parked while a new tab / window takes
 *       over rendering for that player. Other players keep playing.
 *       {@code casdoorId} is the affected player; {@code launchUid}
 *       (optional) is the new tab's launch UID so the old tab can
 *       distinguish "I'm being superseded by ME-elsewhere" from "the
 *       session owner kicked me".</li>
 *   <li>{@link Action#SESSION_CLOSED} — session was closed (host
 *       drop, force-close from admin/mod, or last member left).
 *       Every iframe should tear down. {@code reason} is one of
 *       {@code host-disconnected}, {@code force-closed},
 *       {@code last-member-left}.</li>
 * </ul>
 *
 * @param sessionId  the session this event applies to.
 * @param action     which lifecycle transition.
 * @param casdoorId  the affected player (PLAYER_*); null on SESSION_*.
 * @param launchUid  the new tab's launch UID that triggered the
 *                   takeover (PLAYER_PAUSED only); null otherwise.
 * @param reason     human-readable explanation, mirrors the
 *                   {@code game_sessions.pause_reason} column on
 *                   SESSION_PAUSED.
 * @param occurredAt server timestamp; informational for the client.
 */
public record SessionControlEvent(
        String  sessionId,
        Action  action,
        String  casdoorId,
        String  launchUid,
        String  reason,
        Instant occurredAt
) {
    public enum Action {
        SESSION_PAUSED,
        SESSION_RESUMED,
        PLAYER_PAUSED,
        PLAYER_RESUMED,
        SESSION_CLOSED
    }
}
