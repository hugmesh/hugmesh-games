package games.hugmesh.bff.model;

import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Table;
import java.time.Instant;

/**
 * R2DBC entity for one row in the {@code chapters} table — a playable level that
 * corresponds to a single compiled Godot WASM bundle (or a web-native canvas game).
 *
 * <p>{@code assetUrl} points to the level's own {@code index.html}, served by
 * {@code AssetController} at {@code /api/assets/levels/<slug>/index.html}.
 * Transitions between chapters unload the current iframe and mount the next
 * chapter's WASM in the Angular game-canvas component.
 *
 * <p>The entity was renamed from {@code Level} to {@code Chapter} in PR #20.
 * The on-disk asset path ({@code resources/assets/levels/<slug>/}) intentionally
 * kept its old name to avoid a large filesystem migration.
 *
 * @see games.hugmesh.bff.repository.ChapterRepository
 * @see AvailableChapterDto
 */
@Table("chapters")
public class Chapter {

    // -------------------------------------------------------------------------
    // Persistent fields
    // -------------------------------------------------------------------------

    /** Auto-generated surrogate primary key for the {@code chapters} row. */
    @Id
    private Long id;

    /** Foreign key to the parent {@code games} row — identifies which game title
     *  this chapter belongs to. */
    private Long gameId;

    /** Human-readable display name shown in the chapter-select UI (e.g. "Cave"). */
    private String name;

    /** Optional longer description of this chapter's story or gameplay premise;
     *  shown in the chapter-detail panel. May be {@code null}. */
    private String description;

    /** URL-safe slug used in asset path resolution and deep-link routing
     *  (e.g. {@code "cave"}, {@code "field"}). Unique within a game. */
    private String slug;

    /** URL of the preview thumbnail displayed in the chapter-select grid.
     *  May be {@code null} if no thumbnail has been uploaded yet. */
    private String thumbnailUrl;

    /** JSON blob holding per-scene configuration passed to the Angular wrapper
     *  before the Godot WASM boots (spawn points, ambient lighting hints, etc.).
     *  Loaded from {@code /api/chapters/{id}/scene}. */
    private String sceneConfig;

    /** Rendering-mode discriminator — tells the Angular host whether to mount a
     *  Godot WASM bundle, a web-native canvas game, or another scene type. */
    private String sceneType;

    /** URL of the chapter's entry point {@code index.html}, served by
     *  {@code AssetController}. Constructed at upload time as
     *  {@code /api/assets/levels/<slug>/index.html}. */
    private String assetUrl;

    /** Display ordering within the parent game; lower values appear first in the
     *  chapter list. Chapters with the same value are sorted by {@code id}. */
    private int sortOrder;

    /** Wall-clock timestamp at which this chapter row was first inserted. */
    private Instant createdAt;

    // -------------------------------------------------------------------------
    // Accessors
    // -------------------------------------------------------------------------

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
