package games.snapdragon.vnbuilder.entity;

import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

import java.time.OffsetDateTime;
import java.util.UUID;

@Table("vn_projects")
public class VnProject {
    @Id private UUID id;
    private String slug;
    @Column("display_name") private String displayName;
    private String description;
    @Column("owner_casdoor_id") private String ownerCasdoorId;
    @Column("created_at") private OffsetDateTime createdAt;
    @Column("updated_at") private OffsetDateTime updatedAt;

    public UUID getId() { return id; } public void setId(UUID id) { this.id = id; }
    public String getSlug() { return slug; } public void setSlug(String slug) { this.slug = slug; }
    public String getDisplayName() { return displayName; } public void setDisplayName(String s) { this.displayName = s; }
    public String getDescription() { return description; } public void setDescription(String s) { this.description = s; }
    public String getOwnerCasdoorId() { return ownerCasdoorId; } public void setOwnerCasdoorId(String s) { this.ownerCasdoorId = s; }
    public OffsetDateTime getCreatedAt() { return createdAt; } public void setCreatedAt(OffsetDateTime t) { this.createdAt = t; }
    public OffsetDateTime getUpdatedAt() { return updatedAt; } public void setUpdatedAt(OffsetDateTime t) { this.updatedAt = t; }
}
