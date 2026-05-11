package games.snapdragon.vnbuilder.entity;

import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

import java.util.UUID;

@Table("vn_backgrounds")
public class VnBackground {
    @Id private UUID id;
    @Column("project_id") private UUID projectId;
    private String slug;
    private String name;
    @Column("asset_id") private UUID assetId;

    public UUID getId() { return id; } public void setId(UUID id) { this.id = id; }
    public UUID getProjectId() { return projectId; } public void setProjectId(UUID p) { this.projectId = p; }
    public String getSlug() { return slug; } public void setSlug(String slug) { this.slug = slug; }
    public String getName() { return name; } public void setName(String name) { this.name = name; }
    public UUID getAssetId() { return assetId; } public void setAssetId(UUID a) { this.assetId = a; }
}
