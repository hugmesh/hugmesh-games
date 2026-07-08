package games.snapdragon.bff.repository;

import games.snapdragon.bff.model.CharacterItem;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import reactor.core.publisher.Flux;

/**
 * Reactive R2DBC repository for {@code character_items} rows in Postgres.
 *
 * <p>A {@link CharacterItem} row represents an item currently held in a
 * player character's inventory (as opposed to items that are equipped — those
 * live in {@code character_equipment}). Multiple copies of the same item may
 * exist as separate rows; quantity tracking is at the caller's discretion.
 *
 * <p>The primary read pattern is loading the full inventory for a character
 * to render the inventory panel or validate item actions.
 */
public interface CharacterItemRepository extends ReactiveCrudRepository<CharacterItem, Long> {

    // -------------------------------------------------------------------------
    // Character-scoped lookups
    // -------------------------------------------------------------------------

    /**
     * Returns all inventory rows for the given character — every item
     * currently held in that character's backpack / inventory.
     *
     * @param characterId the surrogate id of the player character
     * @return a {@code Flux} streaming every item row for that character;
     *         empty if the character carries nothing
     */
    Flux<CharacterItem> findByCharacterId(Long characterId);
}
