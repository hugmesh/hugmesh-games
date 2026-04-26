package games.snapdragon.bff.model;

import java.util.List;

/**
 * Passive-view snapshot for the player dashboard. Character stats, time,
 * unlocked levels (nested per game), and quest progress — without loading the
 * game canvas. Dashboard overlays live SSE `character` / `time` events on top.
 */
public record DashboardDto(
        CharacterDto character,
        GameTimeDto gameTime,
        List<GameWithChaptersDto> games,
        List<QuestDto> quests,
        int totalPlayMinutes,
        int totalSessions
) {}
