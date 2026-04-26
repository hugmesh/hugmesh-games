package games.snapdragon.bff.model;

import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Table;

/**
 * Replaces the old GamePrerequisite after the games→levels rename.
 * A Chapter unlocks when the character has completed every required quest listed here.
 */
@Table("chapter_prerequisites")
public class ChapterPrerequisite {

    @Id
    private Long id;
    private Long chapterId;
    private Long requiredQuestId;

    public Long getId() { return id; }
    public Long getChapterId() { return chapterId; }
    public Long getRequiredQuestId() { return requiredQuestId; }

    public void setChapterId(Long chapterId) { this.chapterId = chapterId; }
    public void setRequiredQuestId(Long requiredQuestId) { this.requiredQuestId = requiredQuestId; }
}
