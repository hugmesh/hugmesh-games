package games.hugmesh.rpgbuilder.entity;

import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

import java.util.UUID;

@Table("rpg_exits")
public class RpgExit {
    @Id
    private UUID id;
    @Column("project_id")
    private UUID projectId;
    @Column("from_room_id")
    private UUID fromRoomId;
    @Column("to_room_id")
    private UUID toRoomId;
    private Direction direction;
    private boolean locked;
    @Column("key_item_id")
    private UUID keyItemId;

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }
    public UUID getProjectId() { return projectId; }
    public void setProjectId(UUID projectId) { this.projectId = projectId; }
    public UUID getFromRoomId() { return fromRoomId; }
    public void setFromRoomId(UUID fromRoomId) { this.fromRoomId = fromRoomId; }
    public UUID getToRoomId() { return toRoomId; }
    public void setToRoomId(UUID toRoomId) { this.toRoomId = toRoomId; }
    public Direction getDirection() { return direction; }
    public void setDirection(Direction direction) { this.direction = direction; }
    public boolean isLocked() { return locked; }
    public void setLocked(boolean locked) { this.locked = locked; }
    public UUID getKeyItemId() { return keyItemId; }
    public void setKeyItemId(UUID keyItemId) { this.keyItemId = keyItemId; }
}
