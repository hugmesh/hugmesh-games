package com.threewizards.model;

import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Table;
import java.time.Instant;

@Table("character_quests")
public class CharacterQuest {

    @Id
    private Long id;
    private Long characterId;
    private Long questId;
    private String status;
    private Instant startedAt;
    private Instant completedAt;

    public Long getId() { return id; }
    public Long getCharacterId() { return characterId; }
    public Long getQuestId() { return questId; }
    public String getStatus() { return status; }
    public Instant getStartedAt() { return startedAt; }
    public Instant getCompletedAt() { return completedAt; }

    public void setStatus(String status) { this.status = status; }
    public void setCompletedAt(Instant completedAt) { this.completedAt = completedAt; }
}
