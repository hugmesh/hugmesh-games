package games.hugmesh.rpgbuilder.service;

import games.hugmesh.rpgbuilder.entity.Direction;
import games.hugmesh.rpgbuilder.entity.RpgBuilding;
import games.hugmesh.rpgbuilder.entity.RpgExit;
import games.hugmesh.rpgbuilder.entity.RpgRoom;
import games.hugmesh.rpgbuilder.entity.RpgShopStock;
import games.hugmesh.rpgbuilder.repository.RpgAreaRepository;
import games.hugmesh.rpgbuilder.repository.RpgBuildingRepository;
import games.hugmesh.rpgbuilder.repository.RpgExitRepository;
import games.hugmesh.rpgbuilder.repository.RpgItemRepository;
import games.hugmesh.rpgbuilder.repository.RpgRoomRepository;
import games.hugmesh.rpgbuilder.repository.RpgShopStockRepository;
import games.hugmesh.rpgbuilder.util.SlugUtil;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.reactive.TransactionalOperator;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * FF-style town buildings: grid placement on a town-themed outdoor
 * host room, auto-created interior floor rooms (real {@code rpg_rooms}
 * linked via building_id + floor_level) with UP/DOWN stair exits, and
 * per-building shop stock.
 *
 * Invariants enforced here (the DB can't express them):
 * <ul>
 *   <li>host room belongs to the project + area, and the area theme is
 *       'town';</li>
 *   <li>footprint fits the {@link #GRID}x{@link #GRID} grid
 *       (gridX+width &lt;= GRID, gridY+depth &lt;= GRID) and does not
 *       overlap a sibling building on the same host room;</li>
 *   <li>interior slugs `&lt;area&gt;-&lt;building&gt;-f0/-f1/-b1...`
 *       are pre-checked so a collision is a 409, not a raw
 *       duplicate-key 500;</li>
 *   <li>floors/basements are immutable after create (the interior
 *       room set is minted exactly once);</li>
 *   <li>delete order stock → exits touching interior rooms (either
 *       end — to_room_id has no DB cascade) → interior rooms →
 *       building, in one transaction;</li>
 *   <li>stock rows only on shop kinds, only for items of the same
 *       project, with buy &gt; 0, sell in [0, buy].</li>
 * </ul>
 */
@Service
public class RpgBuildingService {

    /** Placement grid is GRID x GRID cells per host room (cell = 4m in
     *  the renderer; town outdoor floor 44x44). */
    public static final int GRID = 10;

    public static final Set<String> KINDS = Set.of(
            "item_shop", "weapon_shop", "armor_shop", "magic_shop", "accessory_shop",
            "inn", "tavern", "church", "house");

    /**
     * Kinds that may carry shop stock. Must match the set the Godot renderer
     * bakes a shop counter + SHOP_STOCK for (RpgGodotProjectRenderer.SHOP_KINDS)
     * — the 5 pure shops. inn/tavern/church render a rest zone, not a shop, so
     * stock on them would be silently dropped at render time; reject it here.
     */
    public static final Set<String> SHOP_KINDS = Set.of(
            "item_shop", "weapon_shop", "armor_shop", "magic_shop", "accessory_shop");

    private final RpgProjectService projects;
    private final RpgAreaRepository areas;
    private final RpgRoomRepository rooms;
    private final RpgExitRepository exits;
    private final RpgItemRepository items;
    private final RpgBuildingRepository buildings;
    private final RpgShopStockRepository stocks;
    /** Wraps create/update/delete's multi-write chains in one reactive transaction — see
     *  {@link games.hugmesh.rpgbuilder.config.RpgBuilderTxConfig}. */
    private final TransactionalOperator tx;

    public RpgBuildingService(RpgProjectService projects, RpgAreaRepository areas, RpgRoomRepository rooms,
                              RpgExitRepository exits, RpgItemRepository items,
                              RpgBuildingRepository buildings, RpgShopStockRepository stocks,
                              TransactionalOperator tx) {
        this.projects = projects;
        this.areas = areas;
        this.rooms = rooms;
        this.exits = exits;
        this.items = items;
        this.buildings = buildings;
        this.stocks = stocks;
        this.tx = tx;
    }

    public Flux<RpgBuilding> list(UUID projectId, String principal) {
        return projects.get(projectId, principal)
                .flatMapMany(p -> buildings.findByProjectIdOrderByNameAsc(projectId));
    }

    public Mono<RpgBuilding> create(UUID projectId, RpgBuilding in, String principal) {
        if (!SlugUtil.isValid(in.getSlug())) {
            return badRequest("building slug invalid");
        }
        if (in.getName() == null || in.getName().isBlank()) {
            return badRequest("building name required");
        }
        if (in.getKind() == null || !KINDS.contains(in.getKind())) {
            return badRequest("building kind must be one of " + sorted(KINDS));
        }
        if (in.getHostRoomId() == null) {
            return badRequest("hostRoomId required");
        }
        // Defaults mirror the DB column defaults (R2DBC writes every
        // column, so nulls must be resolved here).
        in.setGridX(orDefault(in.getGridX(), 0));
        in.setGridY(orDefault(in.getGridY(), 0));
        in.setWidth(orDefault(in.getWidth(), 1));
        in.setDepth(orDefault(in.getDepth(), 1));
        in.setFloors(orDefault(in.getFloors(), 1));
        in.setBasements(orDefault(in.getBasements(), 0));
        if (in.getFloors() < 1) return badRequest("floors must be >= 1");
        if (in.getBasements() < 0) return badRequest("basements must be >= 0");
        if (in.getWidth() < 1 || in.getDepth() < 1) return badRequest("width and depth must be >= 1");
        if (in.getGridX() < 0 || in.getGridY() < 0
                || in.getGridX() + in.getWidth() > GRID || in.getGridY() + in.getDepth() > GRID) {
            return badRequest("building footprint must fit the " + GRID + "x" + GRID + " grid"
                    + " (gridX+width and gridY+depth <= " + GRID + ")");
        }

        return projects.get(projectId, principal)
                .flatMap(p -> rooms.findById(in.getHostRoomId())
                        .filter(host -> projectId.equals(host.getProjectId()))
                        .switchIfEmpty(badRequest("host room not found in this project")))
                .flatMap(host -> {
                    if (host.getBuildingId() != null) {
                        return badRequest("host room is itself a building interior");
                    }
                    if (in.getAreaId() != null && !in.getAreaId().equals(host.getAreaId())) {
                        return badRequest("host room does not belong to the given area");
                    }
                    in.setAreaId(host.getAreaId());
                    return areas.findById(host.getAreaId())
                            .switchIfEmpty(badRequest("area not found"));
                })
                .flatMap(area -> {
                    if (!"town".equals(area.getTheme())) {
                        return badRequest("buildings can only be placed in town-themed areas"
                                + " (area '" + area.getSlug() + "' has theme '" + area.getTheme() + "')");
                    }
                    List<String> interiorSlugs = interiorSlugs(area.getSlug(), in.getSlug(),
                            in.getFloors(), in.getBasements());
                    for (String s : interiorSlugs) {
                        if (s.length() > 64) {
                            return badRequest("interior room slug '" + s + "' exceeds 64 chars"
                                    + " — shorten the building or area slug");
                        }
                    }
                    return buildings.findByHostRoomId(in.getHostRoomId()).collectList()
                            .flatMap(siblings -> {
                                for (RpgBuilding other : siblings) {
                                    if (footprintsOverlap(in, other)) {
                                        return conflict("building footprint overlaps '" + other.getSlug() + "'");
                                    }
                                }
                                return rooms.findByProjectIdAndSlugIn(projectId, interiorSlugs).collectList();
                            })
                            .flatMap(clashes -> {
                                if (!clashes.isEmpty()) {
                                    return conflict("interior room slug already in use: "
                                            + clashes.getFirst().getSlug());
                                }
                                in.setProjectId(projectId);
                                return buildings.save(in)
                                        .onErrorMap(e -> new ResponseStatusException(HttpStatus.CONFLICT,
                                                "building slug already taken: " + in.getSlug(), e));
                            })
                            .flatMap(saved -> createInterior(saved, area.getSlug()).thenReturn(saved));
                })
                .as(tx::transactional);
    }

    /** Auto-create one interior room per level (basements bottom-up,
     *  then floors) plus UP/DOWN stair exits between adjacent levels. */
    private Mono<Void> createInterior(RpgBuilding b, String areaSlug) {
        List<Integer> levels = new ArrayList<>();
        for (int i = b.getBasements(); i >= 1; i--) levels.add(-i);
        for (int f = 0; f < b.getFloors(); f++) levels.add(f);
        return Flux.fromIterable(levels)
                .concatMap(level -> {
                    RpgRoom room = new RpgRoom();
                    room.setProjectId(b.getProjectId());
                    room.setAreaId(b.getAreaId());
                    room.setSlug(interiorSlug(areaSlug, b.getSlug(), level));
                    room.setName(floorName(level));
                    room.setDescription("Inside " + b.getName() + ".");
                    room.setBuildingId(b.getId());
                    room.setFloorLevel(level);
                    // theme stays NULL: interiors inherit the area theme.
                    return rooms.save(room);
                })
                .collectList()
                .flatMap(saved -> {
                    List<Mono<RpgExit>> stairSaves = new ArrayList<>();
                    for (int i = 1; i < saved.size(); i++) {
                        RpgRoom lower = saved.get(i - 1);
                        RpgRoom upper = saved.get(i);
                        stairSaves.add(exits.save(stair(b.getProjectId(), lower, upper, Direction.UP)));
                        stairSaves.add(exits.save(stair(b.getProjectId(), upper, lower, Direction.DOWN)));
                    }
                    return Flux.concat(stairSaves).then();
                });
    }

    public Mono<RpgBuilding> update(UUID projectId, UUID buildingId, RpgBuilding in, String principal) {
        return projects.get(projectId, principal)
                .flatMap(p -> inProject(projectId, buildingId))
                .flatMap(existing -> {
                    // floors/basements mint the interior room set once —
                    // immutable ever after. Slug/host/area anchor the
                    // interior slugs and door portals — also immutable.
                    if (in.getFloors() != null && !in.getFloors().equals(existing.getFloors())) {
                        return badRequest("floors is immutable after create — delete and re-create the building");
                    }
                    if (in.getBasements() != null && !in.getBasements().equals(existing.getBasements())) {
                        return badRequest("basements is immutable after create — delete and re-create the building");
                    }
                    if (in.getSlug() != null && !in.getSlug().equals(existing.getSlug())) {
                        return badRequest("building slug is immutable");
                    }
                    if (in.getHostRoomId() != null && !in.getHostRoomId().equals(existing.getHostRoomId())) {
                        return badRequest("hostRoomId is immutable");
                    }
                    if (in.getAreaId() != null && !in.getAreaId().equals(existing.getAreaId())) {
                        return badRequest("areaId is immutable");
                    }
                    if (in.getName() != null) {
                        if (in.getName().isBlank()) return badRequest("building name required");
                        existing.setName(in.getName());
                    }
                    existing.setGridX(orDefault(in.getGridX(), existing.getGridX()));
                    existing.setGridY(orDefault(in.getGridY(), existing.getGridY()));
                    existing.setWidth(orDefault(in.getWidth(), existing.getWidth()));
                    existing.setDepth(orDefault(in.getDepth(), existing.getDepth()));
                    if (existing.getWidth() < 1 || existing.getDepth() < 1) {
                        return badRequest("width and depth must be >= 1");
                    }
                    if (existing.getGridX() < 0 || existing.getGridY() < 0
                            || existing.getGridX() + existing.getWidth() > GRID
                            || existing.getGridY() + existing.getDepth() > GRID) {
                        return badRequest("building footprint must fit the " + GRID + "x" + GRID + " grid"
                                + " (gridX+width and gridY+depth <= " + GRID + ")");
                    }
                    Mono<RpgBuilding> kindChecked;
                    if (in.getKind() != null && !in.getKind().equals(existing.getKind())) {
                        if (!KINDS.contains(in.getKind())) {
                            return badRequest("building kind must be one of " + sorted(KINDS));
                        }
                        if (!SHOP_KINDS.contains(in.getKind())) {
                            // A kind change away from shopdom would strand
                            // stock rows the renderer no longer draws.
                            kindChecked = stocks.findByBuildingId(buildingId).next()
                                    .flatMap(s -> Mono.<RpgBuilding>error(new ResponseStatusException(
                                            HttpStatus.BAD_REQUEST,
                                            "cannot change kind to '" + in.getKind() + "' while stock rows exist")))
                                    .switchIfEmpty(Mono.fromSupplier(() -> {
                                        existing.setKind(in.getKind());
                                        return existing;
                                    }));
                        } else {
                            existing.setKind(in.getKind());
                            kindChecked = Mono.just(existing);
                        }
                    } else {
                        kindChecked = Mono.just(existing);
                    }
                    return kindChecked.flatMap(b ->
                            buildings.findByHostRoomId(b.getHostRoomId()).collectList()
                                    .flatMap(siblings -> {
                                        for (RpgBuilding other : siblings) {
                                            if (!other.getId().equals(b.getId()) && footprintsOverlap(b, other)) {
                                                return conflict("building footprint overlaps '" + other.getSlug() + "'");
                                            }
                                        }
                                        return buildings.save(b);
                                    }));
                })
                .as(tx::transactional);
    }

    public Mono<Void> delete(UUID projectId, UUID buildingId, String principal) {
        // Mono.defer at each stage pins the delete ORDER to actual
        // execution (stock → exits → rooms → building) rather than
        // publisher-assembly time.
        return projects.get(projectId, principal)
                .flatMap(p -> inProject(projectId, buildingId))
                .flatMap(b -> stocks.deleteByBuildingId(buildingId)
                        .then(Mono.defer(() -> rooms.findByBuildingIdOrderByFloorLevelAsc(buildingId).collectList()))
                        .flatMap(interior -> {
                            List<UUID> ids = interior.stream().map(RpgRoom::getId).toList();
                            if (ids.isEmpty()) return Mono.empty();
                            // to_room_id has no DB cascade — clear every
                            // exit touching an interior room (stairs AND
                            // user-created exits) before the rooms go.
                            return exits.deleteByFromRoomIdInOrToRoomIdIn(ids, ids)
                                    .then(Mono.defer(() -> rooms.deleteAllById(ids)));
                        })
                        .then(Mono.defer(() -> buildings.deleteById(buildingId))))
                .as(tx::transactional);
    }

    // ── Shop stock ──────────────────────────────────────────────────────────

    public Flux<RpgShopStock> listStock(UUID projectId, UUID buildingId, String principal) {
        return projects.get(projectId, principal)
                .flatMap(p -> inProject(projectId, buildingId))
                .flatMapMany(b -> stocks.findByBuildingId(buildingId));
    }

    public Mono<RpgShopStock> createStock(UUID projectId, UUID buildingId, RpgShopStock in, String principal) {
        if (in.getItemId() == null) return badRequest("itemId required");
        if (in.getBuyPrice() == null || in.getBuyPrice() <= 0) return badRequest("buyPrice must be > 0");
        if (in.getSellPrice() == null || in.getSellPrice() < 0) return badRequest("sellPrice must be >= 0");
        if (in.getSellPrice() > in.getBuyPrice()) return badRequest("sellPrice must be <= buyPrice");
        if (in.getStockQty() == null) in.setStockQty(-1); // -1 = unlimited
        if (in.getStockQty() < -1) return badRequest("stockQty must be >= 0, or -1 for unlimited");
        return projects.get(projectId, principal)
                .flatMap(p -> inProject(projectId, buildingId))
                .flatMap(b -> {
                    if (!SHOP_KINDS.contains(b.getKind())) {
                        return badRequest("building kind '" + b.getKind() + "' cannot stock items"
                                + " — shop kinds are " + sorted(SHOP_KINDS));
                    }
                    return items.findById(in.getItemId())
                            // Foreign-project items are invisible here, so a
                            // stock row can't leak another publisher's data.
                            .filter(item -> projectId.equals(item.getProjectId()))
                            .switchIfEmpty(badRequest("item not found in this project"));
                })
                .flatMap(item -> {
                    in.setProjectId(projectId);
                    in.setBuildingId(buildingId);
                    return stocks.save(in)
                            .onErrorMap(e -> new ResponseStatusException(HttpStatus.CONFLICT,
                                    "item already stocked in this building", e));
                });
    }

    public Mono<Void> deleteStock(UUID projectId, UUID buildingId, UUID stockId, String principal) {
        return projects.get(projectId, principal)
                .flatMap(p -> stocks.findById(stockId)
                        .filter(s -> projectId.equals(s.getProjectId()) && buildingId.equals(s.getBuildingId()))
                        .switchIfEmpty(Mono.error(new ResponseStatusException(HttpStatus.NOT_FOUND, "stock row not found"))))
                .flatMap(s -> stocks.deleteById(stockId));
    }

    // ── Helpers ─────────────────────────────────────────────────────────────

    /** Building of this project, or 404 (cross-project rows invisible). */
    private Mono<RpgBuilding> inProject(UUID projectId, UUID buildingId) {
        return buildings.findById(buildingId)
                .filter(b -> projectId.equals(b.getProjectId()))
                .switchIfEmpty(Mono.error(new ResponseStatusException(HttpStatus.NOT_FOUND, "building not found")));
    }

    static boolean footprintsOverlap(RpgBuilding a, RpgBuilding b) {
        return a.getGridX() < b.getGridX() + b.getWidth() && b.getGridX() < a.getGridX() + a.getWidth()
                && a.getGridY() < b.getGridY() + b.getDepth() && b.getGridY() < a.getGridY() + a.getDepth();
    }

    static List<String> interiorSlugs(String areaSlug, String buildingSlug, int floors, int basements) {
        List<String> slugs = new ArrayList<>();
        for (int i = basements; i >= 1; i--) slugs.add(interiorSlug(areaSlug, buildingSlug, -i));
        for (int f = 0; f < floors; f++) slugs.add(interiorSlug(areaSlug, buildingSlug, f));
        return slugs;
    }

    /** Interior room slugs are namespaced by area AND building so two
     *  same-slug buildings in different areas can't collide on the
     *  project-wide room slug uniqueness. NOTE: slugs are cosmetic —
     *  the renderer keys on building_id/floor_level, never on these. */
    static String interiorSlug(String areaSlug, String buildingSlug, int level) {
        String suffix = level < 0 ? "b" + (-level) : "f" + level;
        return areaSlug + "-" + buildingSlug + "-" + suffix;
    }

    static String floorName(int level) {
        if (level == 0) return "Ground floor";
        if (level > 0) return "Floor " + (level + 1);
        return "Basement " + (-level);
    }

    private static RpgExit stair(UUID projectId, RpgRoom from, RpgRoom to, Direction direction) {
        RpgExit exit = new RpgExit();
        exit.setProjectId(projectId);
        exit.setFromRoomId(from.getId());
        exit.setToRoomId(to.getId());
        exit.setDirection(direction);
        return exit;
    }

    private static Integer orDefault(Integer value, Integer fallback) {
        return value == null ? fallback : value;
    }

    private static String sorted(Set<String> values) {
        return String.join(", ", values.stream().sorted().toList());
    }

    private static <T> Mono<T> badRequest(String reason) {
        return Mono.error(new ResponseStatusException(HttpStatus.BAD_REQUEST, reason));
    }

    private static <T> Mono<T> conflict(String reason) {
        return Mono.error(new ResponseStatusException(HttpStatus.CONFLICT, reason));
    }
}
