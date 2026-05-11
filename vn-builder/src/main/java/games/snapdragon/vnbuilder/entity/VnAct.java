package games.snapdragon.vnbuilder.entity;

import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

import java.util.UUID;

/** A grouping of scenes that becomes a Chapter (one WASM bundle) when built. */
@Table("vn_acts")
public class VnAct {
    @Id private UUID id;
    @Column("project_id") private UUID projectId;
    private String slug;
    private String name;
    @Column("sort_order") private int sortOrder;

    public UUID getId() { return id; } public void setId(UUID id) { this.id = id; }
    public UUID getProjectId() { return projectId; } public void setProjectId(UUID p) { this.projectId = p; }
    public String getSlug() { return slug; } public void setSlug(String slug) { this.slug = slug; }
    public String getName() { return name; } public void setName(String name) { this.name = name; }
    public int getSortOrder() { return sortOrder; } public void setSortOrder(int s) { this.sortOrder = s; }
}
