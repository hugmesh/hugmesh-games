package games.snapdragon.bff.model;

import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Table;
import java.time.Instant;

/**
 * A playable level = one compiled Godot WASM bundle.
 * `assetUrl` points to the level's own index.html served by AssetController.
 * Transitions between levels unload the current iframe and mount the next level's
 * WASM in the Angular game-canvas.
 */
@Table("chapters")
public class Chapter {

    @Id
    private Long id;
    private Long gameId;
    private String name;
    private String description;
    private String slug;
    private String thumbnailUrl;
    private String sceneConfig;
    private String sceneType;
    private String assetUrl;
    private int sortOrder;
    private Instant createdAt;

    public Long getId() { return id; }
    public Long getGameId() { return gameId; }
    public String getName() { return name; }
    public String getDescription() { return description; }
    public String getSlug() { return slug; }
    public String getThumbnailUrl() { return thumbnailUrl; }
    public String getSceneConfig() { return sceneConfig; }
    public String getSceneType() { return sceneType; }
    public String getAssetUrl() { return assetUrl; }
    public int getSortOrder() { return sortOrder; }
    public Instant getCreatedAt() { return createdAt; }
}
