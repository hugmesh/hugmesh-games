package com.threewizards.model;

import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Table;
import java.time.Instant;

/**
 * A user-registered asset attached to a level — texture, plugin, sound, mesh, etc.
 * Allows Godot developers to add custom assets that are indexed in the DB and
 * served through the API alongside the compiled WASM bundle. The `label` gives
 * GDScript a stable handle to look up an asset regardless of filename churn.
 */
@Table("level_assets")
public class LevelAsset {

    @Id
    private Long id;
    private Long levelId;
    private String label;
    private String assetType;
    private String filePath;
    private String contentType;
    private Long sizeBytes;
    private Instant uploadedAt;

    public Long getId() { return id; }
    public Long getLevelId() { return levelId; }
    public String getLabel() { return label; }
    public String getAssetType() { return assetType; }
    public String getFilePath() { return filePath; }
    public String getContentType() { return contentType; }
    public Long getSizeBytes() { return sizeBytes; }
    public Instant getUploadedAt() { return uploadedAt; }

    public void setLevelId(Long levelId) { this.levelId = levelId; }
    public void setLabel(String label) { this.label = label; }
    public void setAssetType(String assetType) { this.assetType = assetType; }
    public void setFilePath(String filePath) { this.filePath = filePath; }
    public void setContentType(String contentType) { this.contentType = contentType; }
    public void setSizeBytes(Long sizeBytes) { this.sizeBytes = sizeBytes; }
}
