package games.snapdragon.bff.model;

import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Table;

/**
 * R2DBC entity for one row in the {@code character_items} table — a stack of a
 * single item type held in a character's inventory. Stackable items are represented
 * as one row whose {@code quantity} is incremented/decremented; non-stackable items
 * each occupy their own row with {@code quantity = 1}.
 *
 * <p>When the character equips an item from their inventory, the corresponding
 * {@code character_items} row is either removed (last copy) or decremented, and a
 * {@link CharacterEquipment} row is created or updated for the relevant slot.
 *
 * @see games.snapdragon.bff.repository.CharacterItemRepository
 * @see PlayerCharacter
 * @see CharacterEquipment
 */
@Table("character_items")
public class CharacterItem {

    // -------------------------------------------------------------------------
    // Persistent fields
    // -------------------------------------------------------------------------

    /** Auto-generated surrogate primary key for the {@code character_items} row. */
    @Id
    private Long id;

    /** Foreign key to the {@code player_characters} row that owns this inventory stack. */
    private Long characterId;

    /** Foreign key to the item definition ({@code items} table) identifying what
     *  item type is held in this stack. */
    private Long itemId;

    /** Number of copies of this item the character currently holds. Always ≥ 1;
     *  a row is removed rather than set to zero when the last copy is consumed. */
    private int quantity;

    // -------------------------------------------------------------------------
    // Accessors
    // -------------------------------------------------------------------------

    public Long getId() { return id; }
    public Long getCharacterId() { return characterId; }
    public Long getItemId() { return itemId; }
    public int getQuantity() { return quantity; }

    public void setQuantity(int quantity) { this.quantity = quantity; }
}
