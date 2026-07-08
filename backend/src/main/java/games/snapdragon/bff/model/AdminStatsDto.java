package games.snapdragon.bff.model;

import java.util.List;

/**
 * Aggregate usage statistics returned by the admin dashboard endpoint.
 * Contains platform-wide counters alongside a per-user breakdown so
 * admins can see both the big picture and individual player activity at
 * a glance.
 *
 * <p>All counts are computed at query time — there is no cached
 * materialized view behind this DTO.
 *
 * @param totalUsers            Total number of registered player accounts
 *                              (distinct {@code casdoor_id} rows in {@code player_characters}).
 * @param totalSessions         Cumulative number of game sessions ever created,
 *                              including completed and abandoned sessions.
 * @param currentlyActive       Number of sessions whose SSE connection is alive
 *                              right now (players currently in-game).
 * @param averageSessionMinutes Mean session duration in minutes across all
 *                              completed sessions (excludes still-active sessions).
 * @param totalCharacters       Total number of {@code player_characters} rows
 *                              across all players.
 * @param perUser               Per-player breakdown; one {@link PerUserStatsDto}
 *                              entry per distinct Casdoor account.
 */
public record AdminStatsDto(
        long totalUsers,
        long totalSessions,
        long currentlyActive,
        double averageSessionMinutes,
        long totalCharacters,
        List<PerUserStatsDto> perUser
) {
    /**
     * Statistics for a single player, nested inside {@link AdminStatsDto}.
     *
     * @param casdoorId     The player's stable Casdoor subject identifier.
     * @param characterName Display name of the player's primary (or most recently
     *                      active) character.
     * @param sessionCount  Total number of sessions this player has started or joined.
     * @param totalMinutes  Cumulative play time in minutes across all of this
     *                      player's completed sessions.
     * @param online        {@code true} if this player has an active SSE connection
     *                      in at least one session right now.
     */
    public record PerUserStatsDto(
            String casdoorId,
            String characterName,
            long sessionCount,
            long totalMinutes,
            boolean online
    ) {}
}
