package games.hugmesh.vnbuilder.entity;

import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

import java.util.UUID;

@Table("vn_characters")
public class VnCharacter {
    @Id private UUID id;
    @Column("project_id") private UUID projectId;
    private String slug;
    @Column("display_name") private String displayName;
    private String color;
    @Column("default_portrait_asset_id") private UUID defaultPortraitAssetId;

    public UUID getId() { return id; } public void setId(UUID id) { this.id = id; }
    public UUID getProjectId() { return projectId; } public void setProjectId(UUID p) { this.projectId = p; }
    public String getSlug() { return slug; } public void setSlug(String slug) { this.slug = slug; }
    public String getDisplayName() { return displayName; } public void setDisplayName(String s) { this.displayName = s; }
    public String getColor() { return color; } public void setColor(String c) { this.color = c; }
    public UUID getDefaultPortraitAssetId() { return defaultPortraitAssetId; }
    public void setDefaultPortraitAssetId(UUID a) { this.defaultPortraitAssetId = a; }
}
