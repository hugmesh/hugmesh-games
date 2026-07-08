package games.snapdragon.bff.model;

import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Table;
import java.time.Instant;

/**
 * R2DBC entity for one row in the {@code chapter_assets} table — a user-registered
 * supplemental asset attached to a chapter (texture, plugin, sound, mesh, etc.).
 *
 * <p>Allows Godot developers to add custom assets that are indexed in the DB and
 * served through the API alongside the compiled WASM bundle. The {@code label}
 * field gives GDScript a stable, filename-independent handle for looking up an
 * asset at runtime, so renaming or replacing the underlying file doesn't break
 * scene scripts.
 *
 * @see games.snapdragon.bff.repository.ChapterAssetRepository
 * @see Chapter
 */
@Table("chapter_assets")
public class ChapterAsset {

    // -------------------------------------------------------------------------
    // Persistent fields
    // -------------------------------------------------------------------------

    /** Auto-generated surrogate primary key for the {@code chapter_assets} row. */
    @Id
    private Long id;

    /** Foreign key to the parent {@code chapters} row this asset is attached to. */
    private Long chapterId;

    /**
     * Stable GDScript-facing identifier for this asset (e.g. {@code "player_skin"}).
     * Must be unique within a chapter. GDScript calls
     * {@code AssetRegistry.get_url("player_skin")} using this label rather than a
     * hard-coded filename, so replacing the underlying file leaves scripts unchanged.
     */
    private String label;

    /** Broad category of the asset — e.g. {@code "texture"}, {@code "sound"},
     *  {@code "mesh"}, {@code "plugin"}. Used for filtering in the asset browser. */
    private String assetType;

    /** Server-side file-system path (or object-storage key) where the asset bytes
     *  are stored. Resolved via the active {@code UploadStorage} strategy when
     *  the asset is served. */
    private String filePath;

    /** MIME type of the asset file (e.g. {@code "image/png"}, {@code "audio/ogg"}).
     *  Set as the {@code Content-Type} response header when the asset is downloaded. */
    private String contentType;

    /** Byte size of the stored asset file; used for bandwidth and quota reporting. */
    private Long sizeBytes;

    /** Wall-clock timestamp at which this asset was first registered in the DB. */
    private Instant uploadedAt;

    // -------------------------------------------------------------------------
    // Accessors
    // -------------------------------------------------------------------------

    public Long getId() { return id; }
    public Long getChapterId() { return chapterId; }
    public String getLabel() { return label; }
    public String getAssetType() { return assetType; }
    public String getFilePath() { return filePath; }
    public String getContentType() { return contentType; }
    public Long getSizeBytes() { return sizeBytes; }
    public Instant getUploadedAt() { return uploadedAt; }

    public void setChapterId(Long chapterId) { this.chapterId = chapterId; }
    public void setLabel(String label) { this.label = label; }
    public void setAssetType(String assetType) { this.assetType = assetType; }
    public void setFilePath(String filePath) { this.filePath = filePath; }
    public void setContentType(String contentType) { this.contentType = contentType; }
    public void setSizeBytes(Long sizeBytes) { this.sizeBytes = sizeBytes; }
}
