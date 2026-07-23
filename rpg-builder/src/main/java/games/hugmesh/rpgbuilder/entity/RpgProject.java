package games.hugmesh.rpgbuilder.entity;

import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

import java.time.OffsetDateTime;
import java.util.UUID;

@Table("rpg_projects")
public class RpgProject {
    @Id
    private UUID id;
    private String slug;
    @Column("display_name")
    private String displayName;
    private String description;
    @Column("owner_casdoor_id")
    private String ownerCasdoorId;
    @Column("created_at")
    private OffsetDateTime createdAt;
    @Column("updated_at")
    private OffsetDateTime updatedAt;

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }
    public String getSlug() { return slug; }
    public void setSlug(String slug) { this.slug = slug; }
    public String getDisplayName() { return displayName; }
    public void setDisplayName(String displayName) { this.displayName = displayName; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public String getOwnerCasdoorId() { return ownerCasdoorId; }
    public void setOwnerCasdoorId(String ownerCasdoorId) { this.ownerCasdoorId = ownerCasdoorId; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(OffsetDateTime createdAt) { this.createdAt = createdAt; }
    public OffsetDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(OffsetDateTime updatedAt) { this.updatedAt = updatedAt; }
}
