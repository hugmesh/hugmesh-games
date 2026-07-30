package games.hugmesh.rpgbuilder.build;

import games.hugmesh.character.GdRigBaker;
import games.hugmesh.rpgbuilder.entity.*;
import games.hugmesh.rpgbuilder.repository.*;
import games.hugmesh.rpgbuilder.service.AvatarConfigService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/**
 * Walks a saved RPG project tree and emits a Godot 4 project under
 * {@code <workdir>/<build-id>/godot-src/}. The output references our
 * hugmesh-bridge GDExtension (so the produced WASM gets the Rust
 * HostBridge calls + multiplayer for free) and lays out one .tscn + .gd
 * per room. Each Area is one exported chapter.
 *
 * <p>Every room becomes a REAL themed 3D world: a textured StaticBody3D
 * floor, gapped perimeter walls, a per-theme WorldEnvironment +
 * DirectionalLight, deterministic theme props, Area3D exit portals, NPCs
 * and items — all built at runtime by {@code scripts/room_builder.gd}
 * from a baked {@code const ROOM} the renderer derives from the DB rows.
 * Town outdoor rooms additionally render the buildings placed on them
 * (grid-positioned mesh assemblies + a door portal into the building's
 * floor-0 interior room); building interior rooms render an enclosed
 * town-themed space with stairs (the UP/DOWN {@code rpg_exits}), an exit
 * door back to the host room, and — for shop kinds — a counter +
 * shopkeeper + a ShopTrigger with the baked {@code const SHOP_STOCK}.
 * Interiors/doors/stairs are derived EXCLUSIVELY from
 * {@code building_id}/{@code floor_level} columns + {@code rpg_exits}
 * rows, never from slug conventions.
 */
@Component
public class RpgGodotProjectRenderer {

    private static final Logger log = LoggerFactory.getLogger(RpgGodotProjectRenderer.class);

    /** Themes that ship vendored biome textures under classpath biome-assets/. */
    private static final Set<String> BIOME_THEMES =
            Set.of("desert", "forest", "dungeon", "cave", "town", "castle");
    /** Building kinds that get a generated shop panel (counter + stock). */
    private static final Set<String> SHOP_KINDS =
            Set.of("item_shop", "weapon_shop", "armor_shop", "magic_shop", "accessory_shop");
    /** Building kinds whose interior offers a BFF-live rest/heal. */
    private static final Set<String> REST_KINDS = Set.of("inn", "tavern", "church");
    /** Placement grid: GRID×GRID cells of CELL metres, centred on the town floor. */
    private static final int GRID = 10;
    private static final double CELL = 4.0;
    private static final double TOWN_FLOOR = 44.0;
    private static final double OUTDOOR_FLOOR = 28.0;
    private static final double INTERIOR_FLOOR = 20.0;

    private final RpgAreaRepository areas;
    private final RpgRoomRepository rooms;
    private final RpgExitRepository exits;
    private final RpgItemRepository items;
    private final RpgNpcRepository npcs;
    private final RpgAssetRepository assets;
    private final RpgBuildingRepository buildings;
    private final RpgShopStockRepository shopStock;
    private final RpgAvatarConfigRepository avatarConfigs;
    /** Directory holding the compiled hugmesh-bridge binaries
     *  ({@code hugmesh_bridge.wasm} + {@code libhugmesh_bridge.so}) to bake into
     *  the rendered project's {@code bin/} so the produced WASM carries the multiplayer
     *  HostBridge. Empty (dev default) → skipped with a warning; the export still
     *  succeeds (Godot loads the bundle without the bridge, non-multiplayer). */
    private final String bridgeDir;

    public RpgGodotProjectRenderer(RpgAreaRepository areas, RpgRoomRepository rooms, RpgExitRepository exits,
                                   RpgItemRepository items, RpgNpcRepository npcs, RpgAssetRepository assets,
                                   RpgBuildingRepository buildings, RpgShopStockRepository shopStock,
                                   RpgAvatarConfigRepository avatarConfigs,
                                   @org.springframework.beans.factory.annotation.Value("${app.build.bridge-dir:}")
                                   String bridgeDir) {
        this.areas = areas;
        this.rooms = rooms;
        this.exits = exits;
        this.items = items;
        this.npcs = npcs;
        this.assets = assets;
        this.buildings = buildings;
        this.shopStock = shopStock;
        this.avatarConfigs = avatarConfigs;
        this.bridgeDir = bridgeDir;
    }

    public record RenderedProject(Path godotSrcRoot, List<RpgArea> orderedAreas, List<RpgRoom> roomsByArea,
                                  List<RpgExit> allExits, String displayName, String description) {}

    public Mono<RenderedProject> render(RpgProject project, Path workdir) {
        // Mono.zip caps at 8 sources — the avatar-config load rides a
        // zipWith on top of the existing tuple rather than a 9th arg.
        return Mono.zip(
                areas.findByProjectIdOrderBySortOrderAscNameAsc(project.getId()).collectList(),
                rooms.findByProjectIdOrderByNameAsc(project.getId()).collectList(),
                exits.findByProjectId(project.getId()).collectList(),
                items.findByProjectIdOrderByNameAsc(project.getId()).collectList(),
                npcs.findByProjectIdOrderByNameAsc(project.getId()).collectList(),
                assets.findByProjectIdOrderByUploadedAtDesc(project.getId()).collectList(),
                buildings.findByProjectIdOrderByNameAsc(project.getId()).collectList(),
                shopStock.findByProjectId(project.getId()).collectList())
                .zipWith(avatarConfigs.findByProjectId(project.getId())
                        .defaultIfEmpty(games.hugmesh.rpgbuilder.service.AvatarConfigService
                                .defaultConfig(project.getId())))
                .flatMap(z -> Mono.fromCallable(() ->
                        writeTree(project, workdir, z.getT1().getT1(), z.getT1().getT2(), z.getT1().getT3(),
                                z.getT1().getT4(), z.getT1().getT5(), z.getT1().getT6(), z.getT1().getT7(),
                                z.getT1().getT8(), z.getT2()))
                        .subscribeOn(Schedulers.boundedElastic()));
    }

    private RenderedProject writeTree(RpgProject project, Path workdir,
                                      List<RpgArea> areaList, List<RpgRoom> roomList, List<RpgExit> exitList,
                                      List<RpgItem> itemList, List<RpgNpc> npcList, List<RpgAsset> assetList,
                                      List<RpgBuilding> buildingList, List<RpgShopStock> stockList,
                                      RpgAvatarConfig avatarConfig)
            throws IOException {
        if (areaList.isEmpty()) {
            throw new IllegalStateException("project has no areas; cannot render");
        }
        if (roomList.isEmpty()) {
            throw new IllegalStateException("project has no rooms; cannot render");
        }

        Path root = workdir.resolve("godot-src");
        Files.createDirectories(root);
        copyTemplateTree(root);

        Path projectGodot = root.resolve("project.godot");
        String pg = Files.readString(projectGodot);
        pg = pg.replace("{{slug}}", project.getSlug())
               .replace("{{displayName}}", project.getDisplayName());
        Files.writeString(projectGodot, pg);

        Map<UUID, String> assetPaths = materializeAssets(root, roomList, npcList, itemList, assetList);
        writeAssetIndex(root.resolve("scripts/asset_index.gd"), assetPaths);
        // Always written (default hero when the project has no config
        // row) so the template preload in player.gd never dangles.
        writeAvatarConfig(root.resolve("scripts/avatar_config.gd"), avatarConfig);

        // Lookup helpers (renderer derives topology from columns + rows, never slugs).
        Map<UUID, RpgArea> areaById = new HashMap<>();
        for (RpgArea a : areaList) areaById.put(a.getId(), a);
        Map<UUID, RpgRoom> roomById = new HashMap<>();
        for (RpgRoom r : roomList) roomById.put(r.getId(), r);
        Map<UUID, RpgBuilding> buildingById = new HashMap<>();
        for (RpgBuilding b : buildingList) buildingById.put(b.getId(), b);
        // building id → its floor-0 interior room (the door-portal target).
        Map<UUID, RpgRoom> floorZeroByBuilding = new HashMap<>();
        for (RpgRoom r : roomList) {
            if (r.getBuildingId() != null && r.getFloorLevel() != null && r.getFloorLevel() == 0) {
                floorZeroByBuilding.put(r.getBuildingId(), r);
            }
        }
        // stock rows grouped by building.
        Map<UUID, List<RpgShopStock>> stockByBuilding = new HashMap<>();
        for (RpgShopStock s : stockList) {
            stockByBuilding.computeIfAbsent(s.getBuildingId(), k -> new ArrayList<>()).add(s);
        }
        Map<UUID, RpgItem> itemById = new HashMap<>();
        for (RpgItem it : itemList) itemById.put(it.getId(), it);
        // buildings placed on each host room.
        Map<UUID, List<RpgBuilding>> buildingsByHostRoom = new HashMap<>();
        for (RpgBuilding b : buildingList) {
            if (b.getHostRoomId() != null) {
                buildingsByHostRoom.computeIfAbsent(b.getHostRoomId(), k -> new ArrayList<>()).add(b);
            }
        }

        // Which biome themes are actually used → copy only those textures.
        Set<String> usedThemes = new TreeSet<>();
        for (RpgRoom r : roomList) {
            String theme = themeOf(r, areaById.get(r.getAreaId()));
            if (BIOME_THEMES.contains(theme)) usedThemes.add(theme);
        }
        copyBiomeTextures(root, usedThemes);

        for (RpgArea area : areaList) {
            Path areaScripts = root.resolve("scripts/rooms").resolve(area.getSlug());
            Path areaScenes  = root.resolve("scenes/rooms").resolve(area.getSlug());
            Files.createDirectories(areaScripts);
            Files.createDirectories(areaScenes);

            List<RpgRoom> areaRooms = roomList.stream().filter(r -> r.getAreaId().equals(area.getId())).toList();
            for (RpgRoom room : areaRooms) {
                List<RpgExit> roomExits = exitList.stream()
                        .filter(x -> x.getFromRoomId().equals(room.getId())).toList();
                List<RpgNpc> roomNpcs = npcList.stream()
                        .filter(n -> room.getId().equals(n.getDefaultRoomId())).toList();
                writeRoomScript(areaScripts.resolve(room.getSlug() + ".gd"), area, room, roomExits, roomNpcs,
                        itemList, assetPaths, roomById, buildingById, floorZeroByBuilding,
                        buildingsByHostRoom.getOrDefault(room.getId(), List.of()),
                        stockByBuilding, itemById);
                writeRoomScene(areaScenes.resolve(room.getSlug() + ".tscn"), area, room);
            }
        }

        writeRoomIndex(root.resolve("scripts/room_index.gd"), areaList, roomList);
        writeAreasJson(root.resolve("areas.json"), areaList, roomList, areaById);

        log.info("[render] wrote Godot project tree at {} ({} areas, {} rooms, {} exits, {} buildings, {} themes)",
                root, areaList.size(), roomList.size(), exitList.size(), buildingList.size(), usedThemes.size());
        return new RenderedProject(root, areaList, roomList, exitList,
                project.getDisplayName(), project.getDescription());
    }

    // ---- theme + geometry helpers ----------------------------------------

    /** Effective theme: interior rooms are always 'town'; else room override → area → plain. */
    private static String themeOf(RpgRoom room, RpgArea area) {
        if (room.getBuildingId() != null) return "town";
        if (room.getTheme() != null && !room.getTheme().isBlank()) return room.getTheme();
        if (area != null && area.getTheme() != null && !area.getTheme().isBlank()) return area.getTheme();
        return "plain";
    }

    private static String vec3(double x, double y, double z) {
        return String.format(Locale.ROOT, "Vector3(%.3f, %.3f, %.3f)", x, y, z);
    }

    /**
     * The ONE shared direction → (portal position, arrival-spawn position)
     * table (RESOLUTION 3). Portal placement, wall gaps and inward arrival
     * spawns all derive from here so per-room they never disagree. {@code h}
     * is the floor half-extent.
     */
    private static String portalPos(Direction dir, double h) {
        double pm = 0.6;   // portal sits at the wall
        double e = h - pm;
        return switch (dir) {
            case N  -> vec3(0, 1.5, -e);
            case S  -> vec3(0, 1.5, e);
            case E  -> vec3(e, 1.5, 0);
            case W  -> vec3(-e, 1.5, 0);
            case NE -> vec3(e, 1.5, -e);
            case NW -> vec3(-e, 1.5, -e);
            case SE -> vec3(e, 1.5, e);
            case SW -> vec3(-e, 1.5, e);
            case UP -> vec3(h * 0.4, 1.0, -h * 0.4);
            case DOWN -> vec3(-h * 0.4, 1.0, h * 0.4);
            case TELEPORT -> vec3(0, 1.0, h * 0.3);
        };
    }

    private static String spawnPos(Direction dir, double h) {
        double si = 4.0;   // inward beyond the reciprocal portal so we don't bounce back
        double e = h - si;
        return switch (dir) {
            case N  -> vec3(0, 1.0, -e);
            case S  -> vec3(0, 1.0, e);
            case E  -> vec3(e, 1.0, 0);
            case W  -> vec3(-e, 1.0, 0);
            case NE -> vec3(e, 1.0, -e);
            case NW -> vec3(-e, 1.0, -e);
            case SE -> vec3(e, 1.0, e);
            case SW -> vec3(-e, 1.0, e);
            case UP -> vec3(h * 0.4 - 2, 1.0, -h * 0.4);
            case DOWN -> vec3(-h * 0.4 + 2, 1.0, h * 0.4);
            case TELEPORT -> vec3(0, 1.0, 0);
        };
    }

    // ---- room script (bakes const ROOM) ----------------------------------

    private void writeRoomScript(Path target, RpgArea area, RpgRoom room, List<RpgExit> roomExits,
                                 List<RpgNpc> roomNpcs, List<RpgItem> itemList, Map<UUID, String> assetPaths,
                                 Map<UUID, RpgRoom> roomById, Map<UUID, RpgBuilding> buildingById,
                                 Map<UUID, RpgRoom> floorZeroByBuilding, List<RpgBuilding> hostBuildings,
                                 Map<UUID, List<RpgShopStock>> stockByBuilding,
                                 Map<UUID, RpgItem> itemById) throws IOException {
        boolean interior = room.getBuildingId() != null;
        RpgBuilding building = interior ? buildingById.get(room.getBuildingId()) : null;
        boolean isHost = !hostBuildings.isEmpty();
        double floorSize = interior ? INTERIOR_FLOOR : (isHost ? TOWN_FLOOR : OUTDOOR_FLOOR);
        double h = floorSize / 2.0;
        String theme = themeOf(room, area);
        boolean shop = interior && building != null
                && Integer.valueOf(0).equals(room.getFloorLevel())
                && SHOP_KINDS.contains(building.getKind());
        boolean rest = interior && building != null
                && Integer.valueOf(0).equals(room.getFloorLevel())
                && REST_KINDS.contains(building.getKind());

        String floorAssetId = (room.getIconAssetId() != null && assetPaths.containsKey(room.getIconAssetId()))
                ? room.getIconAssetId().toString() : null;

        // deterministic placement from a stable room-slug hash.
        Random rng = new Random(room.getSlug().hashCode());
        double reach = h - 4.0;

        StringBuilder sb = new StringBuilder();
        sb.append("# Generated by hugmesh-rpg-builder. Do not hand-edit.\n");
        // Collapse newlines in the name so a multi-line room name can't spill onto a
        // live code line below this comment.
        sb.append("# Room: ").append(room.getName().replace("\n", " ").replace("\r", " "))
          .append(" (").append(room.getSlug()).append(")\n");
        sb.append("extends Node3D\n\n");
        sb.append("const RoomBuilder := preload(\"res://scripts/room_builder.gd\")\n");
        if (floorAssetId != null) {
            sb.append("const AssetIndex := preload(\"res://scripts/asset_index.gd\")\n");
            sb.append("const RoomVisuals := preload(\"res://scripts/room_visuals.gd\")\n");
        }
        sb.append("\nconst ROOM_SLUG := \"").append(room.getSlug()).append("\"\n");
        sb.append("const AREA_SLUG := \"").append(area.getSlug()).append("\"\n\n");

        // SHOP_STOCK const (baked, top-level for test/documentation visibility).
        if (shop) {
            List<RpgShopStock> stock = stockByBuilding.getOrDefault(building.getId(), List.of());
            sb.append("const SHOP_STOCK := [\n");
            for (RpgShopStock s : stock) {
                RpgItem it = itemById.get(s.getItemId());
                if (it == null) continue;
                sb.append("    {\"slug\": \"").append(escape(it.getSlug()))
                  .append("\", \"name\": \"").append(escape(it.getName()))
                  .append("\", \"buy\": ").append(s.getBuyPrice() == null ? 0 : s.getBuyPrice())
                  .append(", \"sell\": ").append(s.getSellPrice() == null ? 0 : s.getSellPrice())
                  .append(", \"qty\": ").append(s.getStockQty() == null ? -1 : s.getStockQty())
                  .append("},\n");
            }
            sb.append("]\n\n");
        }

        sb.append("const ROOM := {\n");
        sb.append("    \"uuid\": \"").append(room.getId()).append("\",\n");
        sb.append("    \"name\": \"").append(escape(room.getName())).append("\",\n");
        sb.append("    \"theme\": \"").append(theme).append("\",\n");
        sb.append("    \"interior\": ").append(interior).append(",\n");
        sb.append("    \"floor_size\": ").append(String.format(Locale.ROOT, "%.1f", floorSize)).append(",\n");
        sb.append("    \"seed\": ").append(room.getSlug().hashCode()).append(",\n");

        // wall gaps: N/S/E/W exits + the interior exit-door (south).
        LinkedHashSet<String> gaps = new LinkedHashSet<>();
        for (RpgExit x : roomExits) {
            Direction d = x.getDirection();
            if (d == Direction.N || d == Direction.S || d == Direction.E || d == Direction.W) {
                gaps.add(d.name());
            }
        }
        if (interior) gaps.add("S");
        sb.append("    \"wall_gaps\": [");
        sb.append(String.join(", ", gaps.stream().map(g -> "\"" + g + "\"").toList()));
        sb.append("],\n");

        sb.append("    \"default_spawn\": ").append(vec3(0, 1, 0)).append(",\n");
        sb.append("    \"entry_spawns\": {\n");
        for (Direction d : Direction.values()) {
            sb.append("        \"").append(d.name()).append("\": ").append(spawnPos(d, h)).append(",\n");
        }
        sb.append("    },\n");

        // exits → portals.
        sb.append("    \"exits\": [\n");
        for (RpgExit x : roomExits) {
            RpgRoom tgt = roomById.get(x.getToRoomId());
            String tname = tgt != null ? tgt.getName() : "";
            sb.append("        {\"dir\": \"").append(x.getDirection().name())
              .append("\", \"target\": \"").append(x.getToRoomId())
              .append("\", \"target_name\": \"").append(escape(tname))
              .append("\", \"portal\": ").append(portalPos(x.getDirection(), h)).append("},\n");
        }
        sb.append("    ],\n");

        // interior exit door → host room (derived, NOT an rpg_exit row).
        if (interior && building != null) {
            RpgRoom host = roomById.get(building.getHostRoomId());
            String hname = host != null ? host.getName() : "";
            sb.append("    \"exit_door\": {\"dir\": \"EXIT_DOOR\", \"target\": \"")
              .append(building.getHostRoomId()).append("\", \"target_name\": \"").append(escape(hname))
              .append("\", \"portal\": ").append(vec3(0, 1.5, h - 0.6)).append("},\n");
        }

        // buildings on a town host room → mesh assembly + door portal.
        if (isHost) {
            sb.append("    \"buildings\": [\n");
            for (RpgBuilding b : hostBuildings) {
                RpgRoom f0 = floorZeroByBuilding.get(b.getId());
                if (f0 == null) continue;   // no interior minted → skip (never happens via service)
                double[] origin = buildingOrigin(b);
                sb.append("        {\"slug\": \"").append(escape(b.getSlug()))
                  .append("\", \"name\": \"").append(escape(b.getName()))
                  .append("\", \"kind\": \"").append(b.getKind())
                  .append("\", \"w\": ").append(b.getWidth() == null ? 1 : b.getWidth())
                  .append(", \"d\": ").append(b.getDepth() == null ? 1 : b.getDepth())
                  .append(", \"floors\": ").append(b.getFloors() == null ? 1 : b.getFloors())
                  .append(", \"origin\": ").append(vec3(origin[0], 0, origin[1]))
                  .append(", \"door_target\": \"").append(f0.getId()).append("\"},\n");
            }
            sb.append("    ],\n");
        }

        // NPCs (deterministic placement). uuid → the TALK_NPC:<uuid>
        // control echo; portrait → the staged res:// path the dialogue
        // panel loads ("" when the NPC has none). Bytes were already
        // staged by materializeAssets — this just threads the path in.
        if (!roomNpcs.isEmpty()) {
            sb.append("    \"npcs\": [\n");
            for (RpgNpc n : roomNpcs) {
                double x = (rng.nextDouble() * 2 - 1) * reach;
                double z = (rng.nextDouble() * 2 - 1) * reach;
                sb.append("        {\"slug\": \"").append(escape(n.getSlug()))
                  .append("\", \"uuid\": \"").append(n.getId())
                  .append("\", \"name\": \"").append(escape(n.getName()))
                  .append("\", \"dialogue\": \"").append(escape(n.getDialogueText() == null ? "" : n.getDialogueText()))
                  .append("\", \"portrait\": \"").append(portraitPathFor(n, assetPaths))
                  .append("\", \"pos\": ").append(vec3(x, 0, z)).append("},\n");
            }
            sb.append("    ],\n");
        }

        // Items whose gd_script/description hint places them here would need a
        // room link; RpgItem has no room column, so items are surfaced only when
        // referenced by shop stock. A future room→item link can extend this.

        if (shop) {
            sb.append("    \"shop\": {\"kind\": \"").append(building.getKind())
              .append("\", \"name\": \"").append(escape(building.getName()))
              .append("\", \"stock\": SHOP_STOCK},\n");
        }
        if (rest) {
            sb.append("    \"rest\": true,\n");
        }

        sb.append("}\n\n");
        sb.append("func _ready() -> void:\n");
        sb.append("    RoomBuilder.build(self, ROOM)\n");
        if (floorAssetId != null) {
            sb.append("    RoomVisuals.apply_floor(self, AssetIndex.ASSETS.get(\"")
              .append(floorAssetId).append("\", \"\"))\n");
        }
        if (room.getGdScript() != null && !room.getGdScript().isBlank()) {
            sb.append("    # author script\n");
            for (String line : room.getGdScript().split("\n")) {
                sb.append("    ").append(line).append("\n");
            }
        }
        Files.writeString(target, sb.toString(), StandardCharsets.UTF_8);
    }

    /** Staged res:// path of an NPC's portrait, or "" when absent/unstaged. */
    private static String portraitPathFor(RpgNpc n, Map<UUID, String> assetPaths) {
        if (n.getPortraitAssetId() == null) return "";
        return assetPaths.getOrDefault(n.getPortraitAssetId(), "");
    }

    /** Building footprint centre in room-local metres (10×10 grid, 4 m cells, centred). */
    private static double[] buildingOrigin(RpgBuilding b) {
        double half = GRID * CELL / 2.0;
        int gx = b.getGridX() == null ? 0 : b.getGridX();
        int gy = b.getGridY() == null ? 0 : b.getGridY();
        int w = b.getWidth() == null ? 1 : b.getWidth();
        int d = b.getDepth() == null ? 1 : b.getDepth();
        double cx = -half + (gx + w / 2.0) * CELL;
        double cz = -half + (gy + d / 2.0) * CELL;
        return new double[]{cx, cz};
    }

    private void writeRoomScene(Path target, RpgArea area, RpgRoom room) throws IOException {
        String gdRel = "res://scripts/rooms/" + area.getSlug() + "/" + room.getSlug() + ".gd";
        String body = """
                [gd_scene load_steps=2 format=3]

                [ext_resource type="Script" path="%s" id="1"]

                [node name="Room_%s" type="Node3D"]
                script = ExtResource("1")
                """.formatted(gdRel, room.getSlug());
        Files.writeString(target, body, StandardCharsets.UTF_8);
    }

    // ---- room_index.gd (ROOM_SCENES + AREA_START) ------------------------

    private void writeRoomIndex(Path target, List<RpgArea> areaList, List<RpgRoom> roomList) throws IOException {
        Map<UUID, String> areaSlugById = new HashMap<>();
        for (RpgArea a : areaList) areaSlugById.put(a.getId(), a.getSlug());

        StringBuilder sb = new StringBuilder();
        sb.append("# Generated by hugmesh-rpg-builder. Do not hand-edit.\n");
        sb.append("# room UUID → scene path; area slug → its start room scene.\n");
        sb.append("const ROOM_SCENES := {\n");
        for (RpgRoom room : roomList) {
            String areaSlug = areaSlugById.get(room.getAreaId());
            if (areaSlug == null) continue;
            sb.append("    \"").append(room.getId()).append("\": \"")
              .append("res://scenes/rooms/").append(areaSlug).append("/").append(room.getSlug()).append(".tscn")
              .append("\",\n");
        }
        sb.append("}\n\n");

        // AREA_START: per area, the start room = its lexicographically-smallest
        // OUTDOOR room slug (building interiors are never a boot target).
        sb.append("const AREA_START := {\n");
        for (RpgArea a : areaList) {
            RpgRoom start = roomList.stream()
                    .filter(r -> r.getAreaId().equals(a.getId()) && r.getBuildingId() == null)
                    .min(Comparator.comparing(RpgRoom::getSlug))
                    .orElse(roomList.stream().filter(r -> r.getAreaId().equals(a.getId()))
                            .min(Comparator.comparing(RpgRoom::getSlug)).orElse(null));
            if (start == null) continue;
            sb.append("    \"").append(a.getSlug()).append("\": \"")
              .append("res://scenes/rooms/").append(a.getSlug()).append("/").append(start.getSlug()).append(".tscn")
              .append("\",\n");
        }
        sb.append("}\n");
        Files.writeString(target, sb.toString(), StandardCharsets.UTF_8);
    }

    /** Sidecar the build worker reads to emit chapters with human names + sort. */
    private void writeAreasJson(Path target, List<RpgArea> areaList, List<RpgRoom> roomList,
                                Map<UUID, RpgArea> areaById) throws IOException {
        StringBuilder sb = new StringBuilder();
        sb.append("[\n");
        for (int i = 0; i < areaList.size(); i++) {
            RpgArea a = areaList.get(i);
            RpgRoom start = roomList.stream()
                    .filter(r -> r.getAreaId().equals(a.getId()) && r.getBuildingId() == null)
                    .min(Comparator.comparing(RpgRoom::getSlug))
                    .orElse(null);
            String theme = a.getTheme() != null && !a.getTheme().isBlank() ? a.getTheme() : "plain";
            sb.append("  {\"slug\": \"").append(jsonEscape(a.getSlug()))
              .append("\", \"name\": \"").append(jsonEscape(a.getName()))
              .append("\", \"sortOrder\": ").append(a.getSortOrder())
              .append(", \"theme\": \"").append(jsonEscape(theme))
              .append("\", \"startRoomSlug\": ")
              .append(start != null ? "\"" + jsonEscape(start.getSlug()) + "\"" : "null")
              .append("}");
            sb.append(i < areaList.size() - 1 ? ",\n" : "\n");
        }
        sb.append("]\n");
        Files.writeString(target, sb.toString(), StandardCharsets.UTF_8);
    }

    // ---- biome textures + .import sidecars --------------------------------

    private void copyBiomeTextures(Path root, Set<String> usedThemes) throws IOException {
        String[] textures = {"floor_albedo.jpg", "floor_normal.jpg", "wall_albedo.jpg", "wall_normal.jpg"};
        for (String theme : usedThemes) {
            for (String tex : textures) {
                String cp = "biome-assets/biomes/" + theme + "/" + tex;
                Path dst = root.resolve("assets/biomes/" + theme + "/" + tex);
                String resPath = "res://assets/biomes/" + theme + "/" + tex;
                copyTextureWithImport(cp, dst, resPath);
            }
        }
        // _shared door art is used by portals in any theme.
        if (!usedThemes.isEmpty()) {
            for (String tex : new String[]{"door_albedo.jpg", "door_normal.jpg"}) {
                copyTextureWithImport("biome-assets/biomes/_shared/" + tex,
                        root.resolve("assets/biomes/_shared/" + tex),
                        "res://assets/biomes/_shared/" + tex);
            }
        }
    }

    /**
     * Copy a vendored biome jpg into the staged tree and write the minimal
     * {@code .import} sidecar (RESOLUTION 5, empirically verified): lossy webp
     * (compress/mode=1, lossy_quality=0.8), mipmaps, {@code detect_3d/compress_to=0}.
     * Godot fills in uid/path/dest_files during the export-time reimport.
     */
    private void copyTextureWithImport(String classpath, Path dst, String resPath) throws IOException {
        ClassPathResource src = new ClassPathResource(classpath);
        if (!src.exists()) {
            log.warn("[render] biome texture {} not on classpath; skipping", classpath);
            return;
        }
        Files.createDirectories(dst.getParent());
        try (InputStream in = src.getInputStream()) {
            Files.copy(in, dst, StandardCopyOption.REPLACE_EXISTING);
        }
        String importBody = ""
                + "[remap]\n\n"
                + "importer=\"texture\"\n"
                + "type=\"CompressedTexture2D\"\n\n"
                + "[deps]\n\n"
                + "source_file=\"" + resPath + "\"\n\n"
                + "[params]\n\n"
                + "compress/mode=1\n"
                + "compress/high_quality=false\n"
                + "compress/lossy_quality=0.8\n"
                + "compress/normal_map=0\n"
                + "compress/channel_pack=0\n"
                + "mipmaps/generate=true\n"
                + "detect_3d/compress_to=0\n";
        Files.writeString(Path.of(dst.toString() + ".import"), importBody, StandardCharsets.UTF_8);
    }

    // ---- asset materialization (PR #113) + UI .import sidecars ------------

    private Map<UUID, String> materializeAssets(Path root, List<RpgRoom> roomList,
                                                List<RpgNpc> npcList, List<RpgItem> itemList,
                                                List<RpgAsset> assetList) throws IOException {
        Map<UUID, RpgAsset> byId = new HashMap<>();
        for (RpgAsset a : assetList) byId.put(a.getId(), a);
        List<UUID> referenced = new ArrayList<>();
        for (RpgRoom r : roomList) referenced.add(r.getIconAssetId());
        for (RpgNpc n : npcList)   referenced.add(n.getPortraitAssetId());
        for (RpgItem it : itemList) referenced.add(it.getIconAssetId());

        Path assetsDir = root.resolve("assets");
        Map<UUID, String> staged = new LinkedHashMap<>();
        for (UUID id : referenced) {
            if (id == null || staged.containsKey(id)) continue;
            RpgAsset asset = byId.get(id);
            if (asset == null || asset.getStoragePath() == null) {
                log.warn("[render] referenced asset {} has no row/storage path; skipping", id);
                continue;
            }
            Path srcp = Path.of(asset.getStoragePath());
            if (!Files.isRegularFile(srcp)) {
                log.warn("[render] asset {} bytes missing on disk at {}; skipping", id, srcp);
                continue;
            }
            String fileName = id + extFor(asset.getMimeType());
            Files.createDirectories(assetsDir);
            Files.copy(srcp, assetsDir.resolve(fileName), StandardCopyOption.REPLACE_EXISTING);
            String resPath = "res://assets/" + fileName;
            staged.put(id, resPath);
            // Sidecar for every shipped image (repo idiom) — but these
            // are the 2D UI / icon class (portraits, room icons), NOT
            // 3D surfaces: lossless (compress/mode=0), no mipmaps, so
            // pixel-art icons drawn with TEXTURE_FILTER_NEAREST stay
            // crisp and a flat TextureRect portrait isn't webp-muddied.
            // The 3D biome textures keep their lossy+mipmapped body in
            // copyTextureWithImport, untouched.
            if (!fileName.endsWith(".bin")) {
                writeUiTextureImport(assetsDir.resolve(fileName), resPath, asset.getMimeType());
            }
        }
        if (!staged.isEmpty()) {
            log.info("[render] materialized {} referenced asset(s) into res://assets/", staged.size());
        }
        return staged;
    }

    /**
     * Minimal {@code .import} sidecar for a staged 2D UI/icon texture
     * (portrait, room icon): lossless, mipmap-free, never treated as a
     * 3D surface. SVG additionally pins the Godot 4 import scale.
     */
    private static void writeUiTextureImport(Path dst, String resPath, String mime) throws IOException {
        StringBuilder body = new StringBuilder()
                .append("[remap]\n\n")
                .append("importer=\"texture\"\n")
                .append("type=\"CompressedTexture2D\"\n\n")
                .append("[deps]\n\n")
                .append("source_file=\"").append(resPath).append("\"\n\n")
                .append("[params]\n\n")
                .append("compress/mode=0\n")
                .append("mipmaps/generate=false\n")
                .append("detect_3d/compress_to=0\n");
        if ("image/svg+xml".equals(mime)) {
            body.append("svg/scale=1.0\n");
        }
        Files.writeString(Path.of(dst + ".import"), body.toString(), StandardCharsets.UTF_8);
    }

    private void writeAssetIndex(Path target, Map<UUID, String> assetPaths) throws IOException {
        StringBuilder sb = new StringBuilder();
        sb.append("# Generated by hugmesh-rpg-builder. Do not hand-edit.\n");
        sb.append("# Maps an asset UUID (referenced by a room/npc/item) to its staged art path.\n");
        sb.append("const ASSETS := {\n");
        for (Map.Entry<UUID, String> e : assetPaths.entrySet()) {
            sb.append("    \"").append(e.getKey()).append("\": \"").append(e.getValue()).append("\",\n");
        }
        sb.append("}\n");
        Files.writeString(target, sb.toString(), StandardCharsets.UTF_8);
    }

    // ---- avatar config (character maker) ----------------------------------

    /**
     * Bakes {@code scripts/avatar_config.gd} — the per-project hero the
     * template player.gd / remote_avatars.gd assemble via
     * avatar_factory.gd. Injection guard: NO caller-supplied string is
     * ever baked. Enum tokens are re-validated against the service
     * whitelists (defence-in-depth: an off-whitelist DB value —
     * impossible past the CHECK — falls back to the default token,
     * never arbitrary text), {@code height_scale} is clamped and
     * {@code %.3f}-formatted, and colours are parsed {@code #rrggbb} →
     * three floats emitted as {@code Color(r, g, b)} — the raw hex
     * string never reaches GDScript.
     */
    private void writeAvatarConfig(Path target, RpgAvatarConfig cfg) throws IOException {
        // Delegates to GdRigBaker so the GDScript, the 2D SVG and the glTF
        // export all derive from one HumanoidRig. Written UNCONDITIONALLY —
        // player.gd preloads this path, and a dangling preload is a hard
        // parse error in the produced bundle, not a soft fallback.
        //
        // toSpecLenient re-validates on the way through — the last gate before
        // values become generated code — but degrades PER FIELD rather than
        // rejecting, so one malformed colour cannot discard the author's whole
        // character, and no off-whitelist text can ever be emitted either way.
        Files.writeString(target, GdRigBaker.bake(AvatarConfigService.toSpecLenient(cfg)),
                StandardCharsets.UTF_8);
    }

    private static String safeToken(String value, Set<String> allowed, String dflt) {
        return value != null && allowed.contains(value) ? value : dflt;
    }

    /** #rrggbb → {@code Color(r, g, b)} floats; non-conforming input falls back to the default hex. */
    private static String gdColor(String hex, String fallbackHex) {
        String h = hex != null && hex.matches("^#[0-9a-fA-F]{6}$") ? hex : fallbackHex;
        double r = Integer.parseInt(h.substring(1, 3), 16) / 255.0;
        double g = Integer.parseInt(h.substring(3, 5), 16) / 255.0;
        double b = Integer.parseInt(h.substring(5, 7), 16) / 255.0;
        return String.format(Locale.ROOT, "Color(%.3f, %.3f, %.3f)", r, g, b);
    }

    private static String extFor(String mime) {
        if (mime == null) return ".bin";
        return switch (mime) {
            case "image/svg+xml" -> ".svg";
            case "image/png"     -> ".png";
            case "image/jpeg"    -> ".jpg";
            case "image/webp"    -> ".webp";
            default              -> ".bin";
        };
    }

    private void copyTemplateTree(Path dst) throws IOException {
        copyClasspath("godot-template/project.godot",       dst.resolve("project.godot"));
        copyClasspath("godot-template/export_presets.cfg",  dst.resolve("export_presets.cfg"));
        copyClasspath("godot-template/scenes/main.tscn",    dst.resolve("scenes/main.tscn"));
        copyClasspath("godot-template/scenes/player.tscn",  dst.resolve("scenes/player.tscn"));
        copyClasspath("godot-template/scripts/world.gd",    dst.resolve("scripts/world.gd"));
        copyClasspath("godot-template/scripts/exits.gd",    dst.resolve("scripts/exits.gd"));
        copyClasspath("godot-template/scripts/room_index.gd", dst.resolve("scripts/room_index.gd"));
        copyClasspath("godot-template/scripts/asset_index.gd", dst.resolve("scripts/asset_index.gd"));
        copyClasspath("godot-template/scripts/room_visuals.gd", dst.resolve("scripts/room_visuals.gd"));
        copyClasspath("godot-template/scripts/room_builder.gd", dst.resolve("scripts/room_builder.gd"));
        copyClasspath("godot-template/scripts/player.gd",   dst.resolve("scripts/player.gd"));
        copyClasspath("godot-template/scripts/remote_avatars.gd", dst.resolve("scripts/remote_avatars.gd"));
        copyClasspath("godot-template/scripts/game_bridge.gd", dst.resolve("scripts/game_bridge.gd"));
        copyClasspath("godot-template/scripts/shop_ui.gd",  dst.resolve("scripts/shop_ui.gd"));
        copyClasspath("godot-template/scripts/avatar_factory.gd", dst.resolve("scripts/avatar_factory.gd"));
        copyClasspath("godot-template/scripts/avatar_poser.gd", dst.resolve("scripts/avatar_poser.gd"));
        copyClasspath("godot-template/scripts/dialogue_ui.gd", dst.resolve("scripts/dialogue_ui.gd"));
        copyClasspath("godot-template/hugmesh_bridge.gdextension",
                                                            dst.resolve("hugmesh_bridge.gdextension"));
        copyBridgeBinaries(dst.resolve("bin"));
    }

    /**
     * Bake the compiled hugmesh-bridge binaries into {@code bin/} so the
     * {@code hugmesh_bridge.gdextension} descriptor resolves and the produced WASM
     * carries the Rust HostBridge (multiplayer lobby + per-player view channel). When
     * {@code app.build.bridge-dir} is unset or a binary is missing we log + drop a
     * {@code bin/README.md} placeholder instead of failing — the export still produces
     * a (non-multiplayer) bundle, matching the documented Gentoo-editor caveat.
     */
    private void copyBridgeBinaries(Path binDir) throws IOException {
        Files.createDirectories(binDir);
        // (descriptor key → expected filename in bridge-dir)
        String[] artifacts = {"hugmesh_bridge.wasm", "libhugmesh_bridge.so"};
        boolean any = false;
        if (bridgeDir != null && !bridgeDir.isBlank()) {
            Path src = Path.of(bridgeDir);
            for (String name : artifacts) {
                Path from = src.resolve(name);
                if (Files.isRegularFile(from)) {
                    Files.copy(from, binDir.resolve(name), StandardCopyOption.REPLACE_EXISTING);
                    any = true;
                }
            }
        }
        if (!any) {
            log.warn("[render] no hugmesh-bridge binary copied (app.build.bridge-dir='{}'); "
                    + "the export will produce a NON-multiplayer bundle", bridgeDir);
            Files.writeString(binDir.resolve("README.md"),
                    "# bin/\n\nThe hugmesh-bridge binaries were not available at render time "
                            + "(app.build.bridge-dir unset or empty). The produced WASM will load "
                            + "without the HostBridge GDExtension.\n", StandardCharsets.UTF_8);
        }
    }

    private static void copyClasspath(String classpathPath, Path target) throws IOException {
        Files.createDirectories(target.getParent());
        try (InputStream in = new ClassPathResource(classpathPath).getInputStream()) {
            Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static String escape(String s) {
        // Output is baked verbatim into GDScript double-quoted string literals, which
        // (unlike triple-quoted strings) cannot contain a raw newline/CR/tab — a
        // multi-line NPC dialogue would otherwise emit an unterminated literal and
        // fail the whole room script's parse. GDScript interprets \n/\r/\t escapes.
        return s == null ? "" : s.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t");
    }

    private static String jsonEscape(String s) {
        // JSON strings (areas.json is parsed by the worker) likewise forbid raw
        // control characters — escape them so an area/room name with a newline can't
        // produce malformed JSON.
        return s == null ? "" : s.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t");
    }
}
