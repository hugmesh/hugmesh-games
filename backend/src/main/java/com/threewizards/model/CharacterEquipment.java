package com.threewizards.model;

import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Table;

@Table("character_equipment")
public class CharacterEquipment {

    @Id
    private Long id;
    private Long characterId;
    private String slot;
    private Long itemId;

    public Long getId() { return id; }
    public Long getCharacterId() { return characterId; }
    public String getSlot() { return slot; }
    public Long getItemId() { return itemId; }

    public void setItemId(Long itemId) { this.itemId = itemId; }
}
