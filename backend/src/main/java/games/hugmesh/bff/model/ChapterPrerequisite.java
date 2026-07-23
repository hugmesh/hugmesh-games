package games.hugmesh.bff.model;

import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Table;

/**
 * R2DBC entity for one row in the {@code chapter_prerequisites} table.
 * Each row asserts that a character must have completed a specific quest before
 * the referenced chapter becomes available to them. A chapter with no rows here
 * is immediately accessible.
 *
 * <p>Replaces the old {@code GamePrerequisite} entity after the games→chapters
 * rename landed in PR #20. The unlock check reads all prerequisite rows for a
 * chapter and compares them against the character's {@code quest_progress} entries
 * with status {@code 'completed'}.
 *
 * @see games.hugmesh.bff.repository.ChapterPrerequisiteRepository
 * @see Chapter
 * @see AvailableChapterDto
 */
@Table("chapter_prerequisites")
public class ChapterPrerequisite {

    // -------------------------------------------------------------------------
    // Persistent fields
    // -------------------------------------------------------------------------

    /** Auto-generated surrogate primary key for the {@code chapter_prerequisites} row. */
    @Id
    private Long id;

    /** Foreign key to the {@code chapters} row that this prerequisite guards.
     *  The chapter remains locked until the character satisfies all prerequisite rows. */
    private Long chapterId;

    /** Foreign key to the quest (in {@code quests}) that must reach status
     *  {@code 'completed'} in the character's {@code quest_progress} before the
     *  parent chapter unlocks. */
    private Long requiredQuestId;

    // -------------------------------------------------------------------------
    // Accessors
    // -------------------------------------------------------------------------

    public Long getId() { return id; }
    public Long getChapterId() { return chapterId; }
    public Long getRequiredQuestId() { return requiredQuestId; }

    public void setChapterId(Long chapterId) { this.chapterId = chapterId; }
    public void setRequiredQuestId(Long requiredQuestId) { this.requiredQuestId = requiredQuestId; }
}
