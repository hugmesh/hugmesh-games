package games.hugmesh.bff.repository;

import games.hugmesh.bff.model.CharacterSkillSlot;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Reactive R2DBC repository for {@code character_skill_slots} rows in Postgres.
 *
 * <p>A {@link CharacterSkillSlot} maps a numbered action-bar slot to the skill
 * (if any) currently assigned to it for a given player character. Slot numbers
 * are player-configurable; a slot may be empty (no skill assigned). The
 * {@code (character_id, slot_number)} pair is unique.
 *
 * <p>The primary read patterns are: load the full action-bar layout for a
 * character in slot-number order (for rendering the HUD), and look up a single
 * slot (for equip/unequip write paths that check or modify one slot at a time).
 */
public interface CharacterSkillSlotRepository extends ReactiveCrudRepository<CharacterSkillSlot, Long> {

    // -------------------------------------------------------------------------
    // Character-scoped lookups
    // -------------------------------------------------------------------------

    /**
     * Returns all action-bar slot rows for the given character, ordered by
     * {@code slot_number} ascending — the character's complete action-bar layout
     * in display order.
     *
     * @param characterId the surrogate id of the player character
     * @return a {@code Flux} streaming every slot row for that character ordered
     *         by slot number; empty if the character has no configured slots
     */
    Flux<CharacterSkillSlot> findByCharacterIdOrderBySlotNumber(Long characterId);

    /**
     * Returns the skill-slot row for a specific numbered slot on the given
     * character, if it exists.
     *
     * @param characterId the surrogate id of the player character
     * @param slotNumber  the action-bar slot number to look up
     * @return a {@code Mono} emitting the matching slot row, or empty if
     *         that slot has not been configured for the character
     */
    Mono<CharacterSkillSlot> findByCharacterIdAndSlotNumber(Long characterId, int slotNumber);
}
