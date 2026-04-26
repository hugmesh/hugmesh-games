package games.snapdragon.bff.model;

import org.springframework.data.cassandra.core.cql.PrimaryKeyType;
import org.springframework.data.cassandra.core.mapping.PrimaryKeyColumn;
import org.springframework.data.cassandra.core.mapping.Table;

import java.time.Instant;

/**
 * Cassandra row in {@code character_position_cache} — one per
 * (character_id, level_id). Cross-pod read cache so pod B can see a
 * position updated on pod A without a round-trip to Postgres on every
 * SSE fan-out.
 *
 * <p>Composite partition key: queries are always by full
 * (character_id, level_id) tuple — no range scans — so a single
 * compound partition gives O(1) lookups + writes. The keyspace's
 * 5-minute TTL means a long-idle row eventually drops; the durable
 * Postgres row backs the cache when it does.
 */
@Table("character_position_cache")
public class CharacterPositionCache {

    @PrimaryKeyColumn(name = "character_id", type = PrimaryKeyType.PARTITIONED, ordinal = 0)
    private long characterId;

    @PrimaryKeyColumn(name = "level_id", type = PrimaryKeyType.PARTITIONED, ordinal = 1)
    private long levelId;

    private double posX;
    private double posY;
    private double posZ;
    private double rotationY;
    private String  lastAction;
    private Boolean lastPressed;
    private String  podId;
    private Instant updatedAt;
    private long    tick;

    public CharacterPositionCache() {}

    public CharacterPositionCache(long characterId, long levelId,
                                  double posX, double posY, double posZ,
                                  double rotationY,
                                  String lastAction, Boolean lastPressed,
                                  String podId, Instant updatedAt, long tick) {
        this.characterId = characterId;
        this.levelId = levelId;
        this.posX = posX; this.posY = posY; this.posZ = posZ;
        this.rotationY = rotationY;
        this.lastAction = lastAction;
        this.lastPressed = lastPressed;
        this.podId = podId;
        this.updatedAt = updatedAt;
        this.tick = tick;
    }

    public long getCharacterId() { return characterId; }
    public long getLevelId() { return levelId; }
    public double getPosX() { return posX; }
    public double getPosY() { return posY; }
    public double getPosZ() { return posZ; }
    public double getRotationY() { return rotationY; }
    public String getLastAction() { return lastAction; }
    public Boolean getLastPressed() { return lastPressed; }
    public String getPodId() { return podId; }
    public Instant getUpdatedAt() { return updatedAt; }
    public long getTick() { return tick; }

    public void setCharacterId(long v) { this.characterId = v; }
    public void setLevelId(long v) { this.levelId = v; }
    public void setPosX(double v) { this.posX = v; }
    public void setPosY(double v) { this.posY = v; }
    public void setPosZ(double v) { this.posZ = v; }
    public void setRotationY(double v) { this.rotationY = v; }
    public void setLastAction(String v) { this.lastAction = v; }
    public void setLastPressed(Boolean v) { this.lastPressed = v; }
    public void setPodId(String v) { this.podId = v; }
    public void setUpdatedAt(Instant v) { this.updatedAt = v; }
    public void setTick(long v) { this.tick = v; }
}
