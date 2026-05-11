package games.snapdragon.vnbuilder.entity;

import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

import java.util.UUID;

@Table("vn_choices")
public class VnChoice {
    @Id private UUID id;
    @Column("line_id") private UUID lineId;
    private String label;
    @Column("target_scene_id") private UUID targetSceneId;
    @Column("sort_order") private int sortOrder;

    public UUID getId() { return id; } public void setId(UUID id) { this.id = id; }
    public UUID getLineId() { return lineId; } public void setLineId(UUID l) { this.lineId = l; }
    public String getLabel() { return label; } public void setLabel(String l) { this.label = l; }
    public UUID getTargetSceneId() { return targetSceneId; } public void setTargetSceneId(UUID s) { this.targetSceneId = s; }
    public int getSortOrder() { return sortOrder; } public void setSortOrder(int s) { this.sortOrder = s; }
}
