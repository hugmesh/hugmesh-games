package games.snapdragon.rpgbuilder.entity;

import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

import java.util.UUID;

/**
 * A region of rooms in the RPG project. Becomes a Chapter when the
 * project is built — each Area produces its own WASM bundle, all
 * registered as separate {@code chapters} rows in the BFF.
 */
@Table("rpg_areas")
public class RpgArea {
    @Id
    private UUID id;
    @Column("project_id")
    private UUID projectId;
    private String slug;
    private String name;
    @Column("sort_order")
    private int sortOrder;
    @Column("grid_mode")
    private boolean gridMode;

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }
    public UUID getProjectId() { return projectId; }
    public void setProjectId(UUID projectId) { this.projectId = projectId; }
    public String getSlug() { return slug; }
    public void setSlug(String slug) { this.slug = slug; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public int getSortOrder() { return sortOrder; }
    public void setSortOrder(int sortOrder) { this.sortOrder = sortOrder; }
    public boolean isGridMode() { return gridMode; }
    public void setGridMode(boolean gridMode) { this.gridMode = gridMode; }
}
