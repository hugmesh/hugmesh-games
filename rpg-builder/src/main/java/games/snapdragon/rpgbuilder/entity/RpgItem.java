package games.snapdragon.rpgbuilder.entity;

import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

import java.util.UUID;

@Table("rpg_items")
public class RpgItem {
    @Id
    private UUID id;
    @Column("project_id")
    private UUID projectId;
    private String slug;
    private String name;
    private String description;
    @Column("icon_asset_id")
    private UUID iconAssetId;
    @Column("gd_script")
    private String gdScript;

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }
    public UUID getProjectId() { return projectId; }
    public void setProjectId(UUID projectId) { this.projectId = projectId; }
    public String getSlug() { return slug; }
    public void setSlug(String slug) { this.slug = slug; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public UUID getIconAssetId() { return iconAssetId; }
    public void setIconAssetId(UUID iconAssetId) { this.iconAssetId = iconAssetId; }
    public String getGdScript() { return gdScript; }
    public void setGdScript(String gdScript) { this.gdScript = gdScript; }
}
