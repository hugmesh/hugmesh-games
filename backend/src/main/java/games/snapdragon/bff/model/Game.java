package games.snapdragon.bff.model;

import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Table;
import java.time.Instant;

/**
 * Top-level umbrella. A Game groups one or more playable Levels (each its own WASM).
 * Example: "Three Wizards Saga" → [Cave level, Field level, …].
 */
@Table("games")
public class Game {

    @Id
    private Long id;
    private String name;
    private String description;
    private String slug;
    private String thumbnailUrl;
    private int sortOrder;
    private Instant createdAt;
    /** Filesystem path of the active hot-upload version (migration 021). */
    private String currentVersionDir;

    public Long getId() { return id; }
    public String getName() { return name; }
    public String getDescription() { return description; }
    public String getSlug() { return slug; }
    public String getThumbnailUrl() { return thumbnailUrl; }
    public int getSortOrder() { return sortOrder; }
    public Instant getCreatedAt() { return createdAt; }
    public String getCurrentVersionDir() { return currentVersionDir; }

    public void setCurrentVersionDir(String currentVersionDir) { this.currentVersionDir = currentVersionDir; }
}
