package games.snapdragon.bff.model;

import java.util.List;

/**
 * Umbrella game with its levels nested. Used by the Angular home view to render
 * games → levels drilldown.
 */
public record GameWithChaptersDto(
        Long id,
        String name,
        String description,
        String slug,
        String thumbnailUrl,
        int sortOrder,
        List<AvailableChapterDto> levels
) {}
