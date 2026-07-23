package games.hugmesh.vnbuilder.entity;

import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

import java.util.UUID;

@Table("vn_lines")
public class VnLine {
    @Id private UUID id;
    @Column("scene_id") private UUID sceneId;
    @Column("line_index") private int lineIndex;
    @Column("character_id") private UUID characterId;
    private String text;
    // SAY (default — a character speaks), PAUSE (fires session-pause when
    // the BFF reaches this line at runtime), RESUME (clears the pause).
    // Enforced by ck_vn_lines_kind in Liquibase 005.
    private String kind = "SAY";

    public UUID getId() { return id; } public void setId(UUID id) { this.id = id; }
    public UUID getSceneId() { return sceneId; } public void setSceneId(UUID s) { this.sceneId = s; }
    public int getLineIndex() { return lineIndex; } public void setLineIndex(int i) { this.lineIndex = i; }
    public UUID getCharacterId() { return characterId; } public void setCharacterId(UUID c) { this.characterId = c; }
    public String getText() { return text; } public void setText(String t) { this.text = t; }
    public String getKind() { return kind; } public void setKind(String k) { this.kind = k; }
}
