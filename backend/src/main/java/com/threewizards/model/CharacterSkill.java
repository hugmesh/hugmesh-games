package com.threewizards.model;

import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Table;
import java.time.Instant;

@Table("character_skills")
public class CharacterSkill {

    @Id
    private Long id;
    private Long characterId;
    private Long skillId;
    private Instant learnedAt;

    public Long getId() { return id; }
    public Long getCharacterId() { return characterId; }
    public Long getSkillId() { return skillId; }
    public Instant getLearnedAt() { return learnedAt; }

    public void setCharacterId(Long characterId) { this.characterId = characterId; }
    public void setSkillId(Long skillId) { this.skillId = skillId; }
}
