package com.threewizards.service;

import com.threewizards.model.*;
import com.threewizards.repository.*;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.*;

/**
 * Evaluates level unlock state from a character's quest completions.
 *
 * <p>Rule: a level is unlocked for a character when every prerequisite quest in
 * {@code level_prerequisites} has a matching {@code character_quests} row with
 * {@code status = 'completed'}. Levels with no prerequisites are always unlocked.
 */
@Service
public class ProgressionService {

    private final GameRepository games;
    private final LevelRepository levels;
    private final LevelPrerequisiteRepository prereqs;
    private final QuestRepository quests;
    private final CharacterQuestRepository charQuests;

    public ProgressionService(GameRepository games,
                              LevelRepository levels,
                              LevelPrerequisiteRepository prereqs,
                              QuestRepository quests,
                              CharacterQuestRepository charQuests) {
        this.games      = games;
        this.levels     = levels;
        this.prereqs    = prereqs;
        this.quests     = quests;
        this.charQuests = charQuests;
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
     * Returns every umbrella Game with its nested Levels (each annotated with unlock state).
     * Drives the home page games → levels drilldown.
     */
    public Flux<GameWithLevelsDto> availableGamesWithLevels(Long characterId) {
        return charQuests.findByCharacterId(characterId)
                .collectList()
                .flatMapMany(myQuests -> {
                    Set<Long> completedIds = completedSet(myQuests);
                    return games.findAllByOrderBySortOrderAsc().flatMap(game ->
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
