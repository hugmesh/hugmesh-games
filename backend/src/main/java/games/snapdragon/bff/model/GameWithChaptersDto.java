package games.snapdragon.bff.model;

import java.util.List;

/**
 * Umbrella game with its chapters nested. Used by the Angular home
 * view to render the games-then-chapters drilldown: the home grid
 * shows one card per game; clicking a card opens a modal listing
 * that game's chapters with their per-chapter unlock state.
 */
public record GameWithChaptersDto(
        Long id,
        String name,
        String description,
        String slug,
        String thumbnailUrl,
        int sortOrder,
        List<AvailableChapterDto> chapters
) {}
