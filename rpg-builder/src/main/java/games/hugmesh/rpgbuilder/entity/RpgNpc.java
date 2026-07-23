package games.hugmesh.rpgbuilder.entity;

import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

import java.util.UUID;

@Table("rpg_npcs")
public class RpgNpc {
    @Id
    private UUID id;
    @Column("project_id")
    private UUID projectId;
    private String slug;
    private String name;
    @Column("default_room_id")
    private UUID defaultRoomId;
    @Column("dialogue_text")
    private String dialogueText;
    @Column("portrait_asset_id")
    private UUID portraitAssetId;
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
    public UUID getDefaultRoomId() { return defaultRoomId; }
    public void setDefaultRoomId(UUID defaultRoomId) { this.defaultRoomId = defaultRoomId; }
    public String getDialogueText() { return dialogueText; }
    public void setDialogueText(String dialogueText) { this.dialogueText = dialogueText; }
    public UUID getPortraitAssetId() { return portraitAssetId; }
    public void setPortraitAssetId(UUID portraitAssetId) { this.portraitAssetId = portraitAssetId; }
    public String getGdScript() { return gdScript; }
    public void setGdScript(String gdScript) { this.gdScript = gdScript; }
}
