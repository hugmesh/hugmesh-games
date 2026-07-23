package games.hugmesh.bff.model;

import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Table;
import java.time.Instant;

/**
 * One row per (characterId, chapterId) — Liquibase 027 promoted the
 * legacy single-row-per-character layout into a per-level layout so
 * a character traversing cave → field → cave returns to the cave
 * coordinates they left, not a single global pose that gets clobbered
 * across levels.
 */
@Table("player_positions")
public class PlayerPosition {

    @Id
    private Long id;
    private Long characterId;
    private Long chapterId;
    private double posX;
    private double posY;
    private double posZ;
    private double rotationY;
    private Instant updatedAt;

    public Long getId() { return id; }
    public Long getCharacterId() { return characterId; }
    public Long getChapterId() { return chapterId; }
    public double getPosX() { return posX; }
    public double getPosY() { return posY; }
    public double getPosZ() { return posZ; }
    public double getRotationY() { return rotationY; }
    public Instant getUpdatedAt() { return updatedAt; }

    public void setCharacterId(Long characterId) { this.characterId = characterId; }
    public void setChapterId(Long chapterId) { this.chapterId = chapterId; }
    public void setPosX(double posX) { this.posX = posX; }
    public void setPosY(double posY) { this.posY = posY; }
    public void setPosZ(double posZ) { this.posZ = posZ; }
    public void setRotationY(double rotationY) { this.rotationY = rotationY; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
}
