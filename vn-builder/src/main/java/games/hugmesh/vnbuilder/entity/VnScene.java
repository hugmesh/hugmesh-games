package games.hugmesh.vnbuilder.entity;

import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

import java.util.UUID;

@Table("vn_scenes")
public class VnScene {
    @Id private UUID id;
    @Column("project_id") private UUID projectId;
    @Column("act_id") private UUID actId;
    private String slug;
    private String name;
    @Column("background_id") private UUID backgroundId;
    @Column("sort_order") private int sortOrder;
    @Column("is_entry_point") private boolean entryPoint;
    @Column("pauses_on_enter") private boolean pausesOnEnter;

    public UUID getId() { return id; } public void setId(UUID id) { this.id = id; }
    public UUID getProjectId() { return projectId; } public void setProjectId(UUID p) { this.projectId = p; }
    public UUID getActId() { return actId; } public void setActId(UUID a) { this.actId = a; }
    public String getSlug() { return slug; } public void setSlug(String slug) { this.slug = slug; }
    public String getName() { return name; } public void setName(String name) { this.name = name; }
    public UUID getBackgroundId() { return backgroundId; } public void setBackgroundId(UUID b) { this.backgroundId = b; }
    public int getSortOrder() { return sortOrder; } public void setSortOrder(int s) { this.sortOrder = s; }
    public boolean isEntryPoint() { return entryPoint; } public void setEntryPoint(boolean e) { this.entryPoint = e; }
    public boolean isPausesOnEnter() { return pausesOnEnter; } public void setPausesOnEnter(boolean p) { this.pausesOnEnter = p; }
}
