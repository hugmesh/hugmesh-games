package games.snapdragon.bff.model;

import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Table;

/**
 * Replaces the old GamePrerequisite after the games→levels rename.
 * A Level unlocks when the character has completed every required quest listed here.
 */
@Table("level_prerequisites")
public class LevelPrerequisite {

    @Id
    private Long id;
    private Long levelId;
    private Long requiredQuestId;

    public Long getId() { return id; }
    public Long getLevelId() { return levelId; }
    public Long getRequiredQuestId() { return requiredQuestId; }

    public void setLevelId(Long levelId) { this.levelId = levelId; }
    public void setRequiredQuestId(Long requiredQuestId) { this.requiredQuestId = requiredQuestId; }
}
