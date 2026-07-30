package games.hugmesh.rpgbuilder.entity;

import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

import java.util.UUID;

/**
 * A building placed on the 10x10 placement grid of a town-themed
 * outdoor room ({@code hostRoomId}). Creating a building auto-creates
 * one interior {@link RpgRoom} per floor/basement (linked back via
 * {@code rpg_rooms.building_id} + {@code floor_level}) plus the UP/DOWN
 * stair exits between adjacent levels; the renderer derives door
 * portals from these columns, never from slug conventions.
 *
 * {@code floors} / {@code basements} are immutable after create — the
 * interior room set is minted exactly once. Footprint occupies grid
 * cells [gridX, gridX+width) x [gridY, gridY+depth).
 */
@Table("rpg_buildings")
public class RpgBuilding {
    @Id
    private UUID id;
    @Column("project_id")
    private UUID projectId;
    @Column("area_id")
    private UUID areaId;
    @Column("host_room_id")
    private UUID hostRoomId;
    private String slug;
    private String name;
    /** One of item_shop, weapon_shop, armor_shop, magic_shop,
     *  accessory_shop, inn, tavern, church, house. */
    private String kind;
    @Column("grid_x")
    private Integer gridX;
    @Column("grid_y")
    private Integer gridY;
    private Integer width;
    private Integer depth;
    private Integer floors;
    private Integer basements;

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }
    public UUID getProjectId() { return projectId; }
    public void setProjectId(UUID projectId) { this.projectId = projectId; }
    public UUID getAreaId() { return areaId; }
    public void setAreaId(UUID areaId) { this.areaId = areaId; }
    public UUID getHostRoomId() { return hostRoomId; }
    public void setHostRoomId(UUID hostRoomId) { this.hostRoomId = hostRoomId; }
    public String getSlug() { return slug; }
    public void setSlug(String slug) { this.slug = slug; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getKind() { return kind; }
    public void setKind(String kind) { this.kind = kind; }
    public Integer getGridX() { return gridX; }
    public void setGridX(Integer gridX) { this.gridX = gridX; }
    public Integer getGridY() { return gridY; }
    public void setGridY(Integer gridY) { this.gridY = gridY; }
    public Integer getWidth() { return width; }
    public void setWidth(Integer width) { this.width = width; }
    public Integer getDepth() { return depth; }
    public void setDepth(Integer depth) { this.depth = depth; }
    public Integer getFloors() { return floors; }
    public void setFloors(Integer floors) { this.floors = floors; }
    public Integer getBasements() { return basements; }
    public void setBasements(Integer basements) { this.basements = basements; }
}
