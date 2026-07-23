package games.hugmesh.bff.model;

import org.springframework.data.cassandra.core.cql.PrimaryKeyType;
import org.springframework.data.cassandra.core.mapping.PrimaryKeyColumn;
import org.springframework.data.cassandra.core.mapping.Table;

import java.time.Instant;

/**
 * Cassandra row in {@code character_position_cache} — one per
 * (character_id, chapter_id). Cross-pod read cache so pod B can see a
 * position updated on pod A without a round-trip to Postgres on every
 * SSE fan-out.
 *
 * <p>Composite partition key: queries are always by full
 * (character_id, chapter_id) tuple — no range scans — so a single
 * compound partition gives O(1) lookups + writes. The keyspace's
 * 5-minute TTL means a long-idle row eventually drops; the durable
 * Postgres row backs the cache when it does.
 *
 * @see games.hugmesh.bff.service.PositionCacheService
 */
@Table("character_position_cache")
public class CharacterPositionCache {

    // -------------------------------------------------------------------------
    // Composite partition key — both columns required on every read/write
    // -------------------------------------------------------------------------

    /** First component of the compound partition key — the character whose position
     *  this row describes. */
    @PrimaryKeyColumn(name = "character_id", type = PrimaryKeyType.PARTITIONED, ordinal = 0)
    private long characterId;

    /** Second component of the compound partition key — the chapter the character is
     *  currently located in. A character moving between chapters gets a separate row. */
    @PrimaryKeyColumn(name = "chapter_id", type = PrimaryKeyType.PARTITIONED, ordinal = 1)
    private long chapterId;

    // -------------------------------------------------------------------------
    // Position + input state
    // -------------------------------------------------------------------------

    /** World-space X coordinate of the character's current position in Godot's
     *  3-D coordinate system (right is positive). */
    private double posX;

    /** World-space Y coordinate — the vertical axis in Godot (up is positive). */
    private double posY;

    /** World-space Z coordinate (forward into the scene is negative in Godot). */
    private double posZ;

    /** Character's current heading rotation around the Y (vertical) axis, in radians.
     *  Used by remote clients to orient the character model correctly. */
    private double rotationY;

    /** The last movement or action verb the character submitted (e.g. {@code "walk"},
     *  {@code "attack"}, {@code "idle"}). Drives the animation state machine on
     *  remote peers. */
    private String  lastAction;

    /** Whether the action key was held down ({@code true}), released ({@code false}),
     *  or not yet received ({@code null}) in the last input frame. Used by the Godot
     *  bridge to decide whether to trigger a continuous-press animation. */
    private Boolean lastPressed;

    /** Kubernetes pod identity (hostname) that last wrote this row. Allows a
     *  subscribing pod to detect that a position update came from a different pod
     *  and should be relayed to its own SSE subscribers. */
    private String  podId;

    /** Wall-clock timestamp of the last write to this row. Used together with
     *  {@link #tick} to detect stale cache entries when Cassandra's TTL has not
     *  yet evicted the row. */
    private Instant updatedAt;

    /** Monotonically increasing sequence number incremented by the authoritative pod
     *  on every position update. Remote pods compare their local tick to this value
     *  to detect out-of-order or duplicate Cassandra reads. */
    private long    tick;

    // -------------------------------------------------------------------------
    // Constructors
    // -------------------------------------------------------------------------

    /** No-arg constructor required by the Spring Data Cassandra mapper. */
    public CharacterPositionCache() {}

    /**
     * All-args constructor for building a cache row from a position update event.
     *
     * @param characterId  character whose position is being cached
     * @param chapterId    chapter the character is currently in
     * @param posX         world-space X coordinate
     * @param posY         world-space Y coordinate (vertical)
     * @param posZ         world-space Z coordinate
     * @param rotationY    heading rotation around the Y axis in radians
     * @param lastAction   last movement/action verb (e.g. {@code "walk"})
     * @param lastPressed  whether the action key was held down in the last frame
     * @param podId        Kubernetes pod hostname that produced this update
     * @param updatedAt    wall-clock time of this update
     * @param tick         monotonic sequence number for ordering updates
     */
    public CharacterPositionCache(long characterId, long chapterId,
                                  double posX, double posY, double posZ,
                                  double rotationY,
                                  String lastAction, Boolean lastPressed,
                                  String podId, Instant updatedAt, long tick) {
        this.characterId = characterId;
        this.chapterId = chapterId;
        this.posX = posX; this.posY = posY; this.posZ = posZ;
        this.rotationY = rotationY;
        this.lastAction = lastAction;
        this.lastPressed = lastPressed;
        this.podId = podId;
        this.updatedAt = updatedAt;
        this.tick = tick;
    }

    // -------------------------------------------------------------------------
    // Accessors
    // -------------------------------------------------------------------------

    public long getCharacterId() { return characterId; }
    public long getChapterId() { return chapterId; }
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
    public void setChapterId(long v) { this.chapterId = v; }
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
