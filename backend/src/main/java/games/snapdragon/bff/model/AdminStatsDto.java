package games.snapdragon.bff.model;

import java.util.List;

/**
 * Aggregate usage statistics for the admin dashboard.
 */
public record AdminStatsDto(
        long totalUsers,
        long totalSessions,
        long currentlyActive,
        double averageSessionMinutes,
        long totalCharacters,
        List<PerUserStatsDto> perUser
) {
    public record PerUserStatsDto(
            String casdoorId,
            String characterName,
            long sessionCount,
            long totalMinutes,
            boolean online
    ) {}
}
