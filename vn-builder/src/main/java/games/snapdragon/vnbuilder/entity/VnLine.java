package games.snapdragon.vnbuilder.entity;

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

    public UUID getId() { return id; } public void setId(UUID id) { this.id = id; }
    public UUID getSceneId() { return sceneId; } public void setSceneId(UUID s) { this.sceneId = s; }
    public int getLineIndex() { return lineIndex; } public void setLineIndex(int i) { this.lineIndex = i; }
    public UUID getCharacterId() { return characterId; } public void setCharacterId(UUID c) { this.characterId = c; }
    public String getText() { return text; } public void setText(String t) { this.text = t; }
}
