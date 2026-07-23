package games.hugmesh.bff.model;

import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Table;

@Table("character_skill_slots")
public class CharacterSkillSlot {

    @Id
    private Long id;
    private Long characterId;
    private int slotNumber;
    private Long skillId;

    public Long getId() { return id; }
    public Long getCharacterId() { return characterId; }
    public int getSlotNumber() { return slotNumber; }
    public Long getSkillId() { return skillId; }

    public void setCharacterId(Long characterId) { this.characterId = characterId; }
    public void setSlotNumber(int slotNumber) { this.slotNumber = slotNumber; }
    public void setSkillId(Long skillId) { this.skillId = skillId; }
}
