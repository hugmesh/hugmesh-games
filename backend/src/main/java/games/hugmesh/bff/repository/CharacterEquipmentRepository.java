package games.hugmesh.bff.repository;

import games.hugmesh.bff.model.CharacterEquipment;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Reactive R2DBC repository for {@code character_equipment} rows in Postgres.
 *
 * <p>A {@link CharacterEquipment} row records an item currently equipped
 * by a player character in a named slot (e.g. {@code "head"}, {@code "weapon"}).
 * Each character may equip at most one item per slot; the {@code (character_id,
 * slot)} pair is unique.
 *
 * <p>The primary read patterns are: load the full equipment set for a character
 * (for inventory display), and look up the item occupying a specific slot
 * (for equip/unequip write paths that check the current occupant before mutating).
 */
public interface CharacterEquipmentRepository extends ReactiveCrudRepository<CharacterEquipment, Long> {

    // -------------------------------------------------------------------------
    // Character-scoped lookups
    // -------------------------------------------------------------------------

    /**
     * Returns all equipment rows for the given character — the character's
     * complete equipped-item set across all slots.
     *
     * @param characterId the surrogate id of the player character
     * @return a {@code Flux} streaming every equipment row for that character;
     *         empty if the character has nothing equipped
     */
    Flux<CharacterEquipment> findByCharacterId(Long characterId);

    /**
     * Returns the item currently occupying a specific equipment slot for
     * the given character, if any.
     *
     * @param characterId the surrogate id of the player character
     * @param slot        the equipment slot name (e.g. {@code "weapon"})
     * @return a {@code Mono} emitting the equipment row, or empty if that
     *         slot is currently unoccupied
     */
    Mono<CharacterEquipment> findByCharacterIdAndSlot(Long characterId, String slot);
}
