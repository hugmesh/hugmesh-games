package games.snapdragon.bff.model;

import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Table;

/**
 * R2DBC entity for one row in the {@code character_equipment} table — a single
 * equipped item occupying one gear slot on a character. Each character has at most
 * one row per slot; equipping a new item replaces the existing row for that slot
 * (via {@code setItemId}) rather than inserting a new one.
 *
 * <p>The set of valid slot names (e.g. {@code "head"}, {@code "body"},
 * {@code "weapon"}) is enforced by a DB CHECK constraint, not in Java, to stay
 * compatible with R2DBC's plain row mapping.
 *
 * @see games.snapdragon.bff.repository.CharacterEquipmentRepository
 * @see PlayerCharacter
 * @see CharacterItem
 */
@Table("character_equipment")
public class CharacterEquipment {

    // -------------------------------------------------------------------------
    // Persistent fields
    // -------------------------------------------------------------------------

    /** Auto-generated surrogate primary key for the {@code character_equipment} row. */
    @Id
    private Long id;

    /** Foreign key to the {@code player_characters} row that owns this equipped slot. */
    private Long characterId;

    /**
     * Name of the gear slot this row occupies (e.g. {@code "head"}, {@code "body"},
     * {@code "weapon"}, {@code "offhand"}, {@code "accessory"}).
     * The DB enforces uniqueness of (characterId, slot) so a character can only have
     * one item per slot at a time.
     */
    private String slot;

    /**
     * Foreign key to the item ({@code items} table) currently occupying this slot.
     * Updated in-place when the player equips a different item; the previous item
     * is moved back to the character's inventory (see {@code CharacterItem}).
     */
    private Long itemId;

    // -------------------------------------------------------------------------
    // Accessors
    // -------------------------------------------------------------------------

    public Long getId() { return id; }
    public Long getCharacterId() { return characterId; }
    public String getSlot() { return slot; }
    public Long getItemId() { return itemId; }

    public void setItemId(Long itemId) { this.itemId = itemId; }
}
