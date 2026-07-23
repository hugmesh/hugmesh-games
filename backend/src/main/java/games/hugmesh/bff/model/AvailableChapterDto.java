package games.hugmesh.bff.model;

import java.util.List;

/**
 * A chapter (playable level) enriched with per-character unlock information,
 * returned by the available-chapters endpoint. A chapter is considered unlocked
 * when every quest in {@code requiredQuestIds} has status {@code 'completed'} for
 * the calling character, or when there are no prerequisites at all.
 *
 * <p>The DTO is assembled by joining {@code chapters}, {@code chapter_prerequisites},
 * and the character's {@code quest_progress} rows so that the frontend can render
 * lock/unlock state without issuing additional requests.
 *
 * @param id                 Surrogate primary key of the chapter row.
 * @param gameId             Foreign key to the parent {@code games} row — identifies
 *                           which game title this chapter belongs to.
 * @param name               Human-readable display name for this chapter (e.g. "Cave").
 * @param description        Longer description shown in the chapter-select UI, may be
 *                           {@code null} if not yet authored.
 * @param slug               URL-safe identifier used in asset paths and routing
 *                           (e.g. {@code "cave"}, {@code "field"}).
 * @param sceneType          Rendering mode hint to the Angular wrapper — distinguishes
 *                           Godot WASM scenes from web-native canvas scenes.
 * @param assetUrl           URL of the chapter's {@code index.html} entry point,
 *                           served by {@code AssetController} at
 *                           {@code /api/assets/levels/<slug>/index.html}.
 * @param thumbnailUrl       URL of the preview thumbnail shown in the chapter-select
 *                           grid. May be {@code null} if no thumbnail has been uploaded.
 * @param sortOrder          Integer display order within the game; lower values appear
 *                           first in the chapter list.
 * @param unlocked           {@code true} when all prerequisite quests are completed
 *                           for the requesting character, or when there are no
 *                           prerequisites.
 * @param requiredQuestIds   IDs of all quests that must be completed before this
 *                           chapter unlocks. Empty list means the chapter is always
 *                           available.
 * @param completedQuestIds  Subset of {@code requiredQuestIds} that the character has
 *                           already completed; the frontend uses this to render a
 *                           partial-progress indicator.
 * @param totalQuests        Total number of quests available inside this chapter,
 *                           used for the completion-progress bar.
 * @param completedQuests    Number of quests inside this chapter the character has
 *                           already completed.
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
