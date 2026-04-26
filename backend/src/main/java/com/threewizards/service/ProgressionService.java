package com.threewizards.service;

import com.threewizards.model.*;
import com.threewizards.repository.*;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.*;

/**
 * Evaluates level unlock state from a character's quest completions, then
 * filters to only the games the calling principal is permitted to see
 * according to the {@code game_access} policy table (migration 019).
 *
 * <p>Two layers of gating, intersected:
 * <ul>
 *   <li><b>Access</b> — does the principal's Casdoor role/user id grant
 *       visibility on this game at all? Admin-controlled.</li>
 *   <li><b>Unlock</b> — within a visible game, is this level's quest
 *       prerequisite satisfied for this character? Quest-progression-controlled.</li>
 * </ul>
 */
@Service
public class ProgressionService {

    private final GameRepository games;
    private final LevelRepository levels;
    private final LevelPrerequisiteRepository prereqs;
    private final QuestRepository quests;
    private final CharacterQuestRepository charQuests;
    private final GameAccessRepository access;

    public ProgressionService(GameRepository games,
                              LevelRepository levels,
                              LevelPrerequisiteRepository prereqs,
                              QuestRepository quests,
                              CharacterQuestRepository charQuests,
                              GameAccessRepository access) {
        this.games      = games;
        this.levels     = levels;
        this.prereqs    = prereqs;
        this.quests     = quests;
        this.charQuests = charQuests;
        this.access     = access;
    }

    /**
     * Returns every level for a character, annotated with unlock state + quest progress.
     * Legacy helper kept for the dashboard which wants a flat list.
     */
    public Flux<AvailableLevelDto> availableLevels(Long characterId) {
        return charQuests.findByCharacterId(characterId)
                .collectList()
                .flatMapMany(myQuests -> {
                    Set<Long> completedIds = completedSet(myQuests);
                    return levels.findAllByOrderBySortOrderAsc()
                            .flatMap(level -> buildLevelDto(level, completedIds));
                });
    }

    /**
     * Legacy single-arg overload. Used by callers that don't yet plumb the
     * JWT through (e.g. {@code /api/characters/{id}/dashboard}). Returns
     * games whose access policy includes {@code scope='all'} only.
     */
    public Flux<GameWithLevelsDto> availableGamesWithLevels(Long characterId) {
        return availableGamesWithLevels(characterId, List.of(), null);
    }

    /**
     * Returns every umbrella Game with its nested Levels (each annotated with unlock state).
     * Drives the home page games → levels drilldown.
     *
     * <p>Three-arg overload: filters to games visible to the principal per
     * {@code game_access}. Pass empty {@code roleNames} + null {@code userId}
     * to fall back to "scope='all' only" (anonymous / legacy behavior).
     */
    public Flux<GameWithLevelsDto> availableGamesWithLevels(Long characterId,
                                                            List<String> roleNames,
                                                            String userId) {
        String[] roles = roleNames == null ? new String[0] : roleNames.toArray(String[]::new);
        Mono<Set<Long>> visibleGameIds = access.findVisibleGameIdsForPrincipal(roles, userId)
                .collect(java.util.stream.Collectors.toSet());

        return Mono.zip(
                charQuests.findByCharacterId(characterId).collectList(),
                visibleGameIds
        ).flatMapMany(tuple -> {
            Set<Long> completedIds = completedSet(tuple.getT1());
            Set<Long> allowed = tuple.getT2();
            return games.findAllByOrderBySortOrderAsc()
                    .filter(g -> allowed.contains(g.getId()))
                    .flatMap(game ->
                            levels.findByGameIdOrderBySortOrderAsc(game.getId())
                                    .flatMap(level -> buildLevelDto(level, completedIds))
                                    .collectList()
                                    .map(levelList -> new GameWithLevelsDto(
                                            game.getId(), game.getName(), game.getDescription(),
                                            game.getSlug(), game.getThumbnailUrl(),
                                            game.getSortOrder(), levelList)));
        });
    }

    private Set<Long> completedSet(List<CharacterQuest> myQuests) {
        Set<Long> s = new HashSet<>();
        for (CharacterQuest cq : myQuests) {
            if ("completed".equalsIgnoreCase(cq.getStatus())) {
                s.add(cq.getQuestId());
            }
        }
        return s;
    }

    private Mono<AvailableLevelDto> buildLevelDto(Level level, Set<Long> completedQuestIds) {
        Mono<List<Long>> required = prereqs.findByLevelId(level.getId())
                .map(LevelPrerequisite::getRequiredQuestId)
                .collectList();

        Mono<List<Long>> inLevel = quests.findAll()
                .filter(q -> Objects.equals(q.getLevelId(), level.getId()))
                .map(Quest::getId)
                .collectList();

        return Mono.zip(required, inLevel).map(tuple -> {
            List<Long> req = tuple.getT1();
            List<Long> levelQuests = tuple.getT2();
            List<Long> completedInLevel = levelQuests.stream()
                    .filter(completedQuestIds::contains)
                    .toList();
            boolean unlocked = req.isEmpty() || completedQuestIds.containsAll(req);

            return new AvailableLevelDto(
                    level.getId(), level.getGameId(),
                    level.getName(), level.getDescription(),
                    level.getSlug(), level.getSceneType(), level.getAssetUrl(),
                    level.getThumbnailUrl(), level.getSortOrder(),
                    unlocked, req, completedInLevel,
                    levelQuests.size(), completedInLevel.size()
            );
        });
    }
}
