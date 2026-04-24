package com.threewizards.model;

import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Table;

@Table("character_items")
public class CharacterItem {

    @Id
    private Long id;
    private Long characterId;
    private Long itemId;
    private int quantity;

    public Long getId() { return id; }
    public Long getCharacterId() { return characterId; }
    public Long getItemId() { return itemId; }
    public int getQuantity() { return quantity; }

    public void setQuantity(int quantity) { this.quantity = quantity; }
}
