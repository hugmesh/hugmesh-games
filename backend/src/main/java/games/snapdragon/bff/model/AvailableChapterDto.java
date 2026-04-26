package games.snapdragon.bff.model;

import java.util.List;

/**
 * A level enriched with per-character unlock info. A level is unlocked when all
 * `requiredQuestIds` are in status 'completed' for the calling character, or when
 * there are no prerequisites.
 */
public record AvailableChapterDto(
        Long id,
        Long gameId,
        String name,
        String description,
        String slug,
        String sceneType,
        String assetUrl,
        String thumbnailUrl,
        int sortOrder,
        boolean unlocked,
        List<Long> requiredQuestIds,
        List<Long> completedQuestIds,
        int totalQuests,
        int completedQuests
) {}
