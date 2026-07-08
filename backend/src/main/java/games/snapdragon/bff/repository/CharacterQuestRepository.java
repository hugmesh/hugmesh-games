package games.snapdragon.bff.repository;

import games.snapdragon.bff.model.CharacterQuest;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Reactive R2DBC repository for {@code character_quests} rows in Postgres.
 *
 * <p>A {@link CharacterQuest} row tracks the progress or completion status
 * of a single quest for a specific player character. The {@code (character_id,
 * quest_id)} pair is unique — a character has at most one progress record per
 * quest.
 *
 * <p>The primary read patterns are: load all active or completed quests for a
 * character (for the quest log panel), and look up a character's progress on a
 * specific quest (for quest-step validation or completion checks).
 */
public interface CharacterQuestRepository extends ReactiveCrudRepository<CharacterQuest, Long> {

    // -------------------------------------------------------------------------
    // Character-scoped lookups
    // -------------------------------------------------------------------------

    /**
     * Returns all quest-progress rows for the given character — the character's
     * full quest log, including active and completed quests.
     *
     * @param characterId the surrogate id of the player character
     * @return a {@code Flux} streaming every quest-progress row for that character;
     *         empty if the character has not started any quests
     */
    Flux<CharacterQuest> findByCharacterId(Long characterId);

    /**
     * Returns the progress record for a specific quest on the given character.
     *
     * @param characterId the surrogate id of the player character
     * @param questId     the surrogate id of the quest
     * @return a {@code Mono} emitting the matching progress row, or empty if
     *         the character has not yet started that quest
     */
    Mono<CharacterQuest> findByCharacterIdAndQuestId(Long characterId, Long questId);
}
