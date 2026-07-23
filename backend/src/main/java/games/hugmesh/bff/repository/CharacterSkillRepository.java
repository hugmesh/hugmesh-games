package games.hugmesh.bff.repository;

import games.hugmesh.bff.model.CharacterSkill;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import reactor.core.publisher.Flux;

/**
 * Reactive R2DBC repository for {@code character_skills} rows in Postgres.
 *
 * <p>A {@link CharacterSkill} row records that a player character has
 * learned or unlocked a particular skill. Skill definitions live in the
 * {@code skills} catalogue; this table is the per-character join that
 * records which of those catalogue entries a given character possesses.
 *
 * <p>The primary read pattern is loading all skills for a character so the
 * skill panel and action-bar can render the character's available abilities.
 */
public interface CharacterSkillRepository extends ReactiveCrudRepository<CharacterSkill, Long> {

    // -------------------------------------------------------------------------
    // Character-scoped lookups
    // -------------------------------------------------------------------------

    /**
     * Returns all skill rows for the given character — every skill that
     * character has learned or unlocked.
     *
     * @param characterId the surrogate id of the player character
     * @return a {@code Flux} streaming every skill row for that character;
     *         empty if the character has no skills
     */
    Flux<CharacterSkill> findByCharacterId(Long characterId);
}
