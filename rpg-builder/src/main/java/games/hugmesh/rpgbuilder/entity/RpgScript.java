package games.hugmesh.rpgbuilder.entity;

import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

import java.util.UUID;

@Table("rpg_scripts")
public class RpgScript {
    @Id
    private UUID id;
    @Column("project_id")
    private UUID projectId;
    private String slug;
    private String name;
    private ScriptKind kind;
    private String source;

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }
    public UUID getProjectId() { return projectId; }
    public void setProjectId(UUID projectId) { this.projectId = projectId; }
    public String getSlug() { return slug; }
    public void setSlug(String slug) { this.slug = slug; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public ScriptKind getKind() { return kind; }
    public void setKind(ScriptKind kind) { this.kind = kind; }
    public String getSource() { return source; }
    public void setSource(String source) { this.source = source; }
}
