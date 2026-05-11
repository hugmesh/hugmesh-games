package games.snapdragon.rpgbuilder.entity;

import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

import java.util.UUID;

@Table("rpg_rooms")
public class RpgRoom {
    @Id
    private UUID id;
    @Column("project_id")
    private UUID projectId;
    @Column("area_id")
    private UUID areaId;
    private String slug;
    private String name;
    private String description;
    private int environment;
    private int x;
    private int y;
    private int z;
    @Column("icon_asset_id")
    private UUID iconAssetId;
    @Column("gd_script")
    private String gdScript;

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }
    public UUID getProjectId() { return projectId; }
    public void setProjectId(UUID projectId) { this.projectId = projectId; }
    public UUID getAreaId() { return areaId; }
    public void setAreaId(UUID areaId) { this.areaId = areaId; }
    public String getSlug() { return slug; }
    public void setSlug(String slug) { this.slug = slug; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public int getEnvironment() { return environment; }
    public void setEnvironment(int environment) { this.environment = environment; }
    public int getX() { return x; }
    public void setX(int x) { this.x = x; }
    public int getY() { return y; }
    public void setY(int y) { this.y = y; }
    public int getZ() { return z; }
    public void setZ(int z) { this.z = z; }
    public UUID getIconAssetId() { return iconAssetId; }
    public void setIconAssetId(UUID iconAssetId) { this.iconAssetId = iconAssetId; }
    public String getGdScript() { return gdScript; }
    public void setGdScript(String gdScript) { this.gdScript = gdScript; }
}
