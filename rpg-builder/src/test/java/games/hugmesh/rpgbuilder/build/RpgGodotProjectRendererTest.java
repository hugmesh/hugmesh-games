package games.hugmesh.rpgbuilder.build;

import games.hugmesh.rpgbuilder.entity.*;
import games.hugmesh.rpgbuilder.repository.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Specs for {@link RpgGodotProjectRenderer} — proves the emitted Godot 4 project
 * is a structurally-complete, playable, THEMED 3D world: a baked {@code const
 * ROOM} consumed by room_builder.gd (floor/light/env per theme + portals per
 * exit from the shared direction table), the full template glue (player /
 * remote_avatars / game_bridge / shop_ui), biome textures + .import sidecars for
 * ONLY the used themes, areas.json, room_index (ROOM_SCENES + AREA_START), and
 * town buildings (door portal → floor-0, interior exit portal → host room,
 * stairs at UP/DOWN exits, baked SHOP_STOCK). Repository interfaces are mocked
 * (interfaces, not concrete classes).
 *
 * <p>GDScript cannot be executed here, so the template scripts are covered by
 * the properties that ARE checkable off the rendered tree: every preload
 * resolves, NPCs route through the shared avatar factory, and the gesture verb
 * relays off ONE whitelist on a key nothing else claims.
 */
class RpgGodotProjectRendererTest {

    private final RpgAreaRepository areas = mock(RpgAreaRepository.class);
    private final RpgRoomRepository rooms = mock(RpgRoomRepository.class);
    private final RpgExitRepository exits = mock(RpgExitRepository.class);
    private final RpgItemRepository items = mock(RpgItemRepository.class);
    private final RpgNpcRepository npcs = mock(RpgNpcRepository.class);
    private final RpgAssetRepository assets = mock(RpgAssetRepository.class);
    private final RpgBuildingRepository buildings = mock(RpgBuildingRepository.class);
    private final RpgShopStockRepository shopStock = mock(RpgShopStockRepository.class);
    private final RpgAvatarConfigRepository avatarConfigs = mock(RpgAvatarConfigRepository.class);

    private static RpgProject project() {
        RpgProject p = new RpgProject();
        p.setId(UUID.randomUUID());
        p.setSlug("flatland-rpg");
        p.setDisplayName("Flatland RPG");
        p.setDescription("A square's tale");
        return p;
    }

    private static RpgArea area(UUID projectId, String slug, String theme) {
        RpgArea a = new RpgArea();
        a.setId(UUID.randomUUID());
        a.setProjectId(projectId);
        a.setSlug(slug);
        a.setName(slug);
        a.setTheme(theme);
        return a;
    }

    private static RpgRoom room(UUID projectId, UUID areaId, String slug) {
        RpgRoom r = new RpgRoom();
        r.setId(UUID.randomUUID());
        r.setProjectId(projectId);
        r.setAreaId(areaId);
        r.setSlug(slug);
        r.setName(slug);
        return r;
    }

    private static RpgExit exit(UUID from, UUID to, Direction dir) {
        RpgExit x = new RpgExit();
        x.setId(UUID.randomUUID());
        x.setFromRoomId(from);
        x.setToRoomId(to);
        x.setDirection(dir);
        return x;
    }

    private static RpgBuilding building(UUID projectId, UUID areaId, UUID hostRoomId, String slug, String kind,
                                        int gx, int gy, int floors) {
        RpgBuilding b = new RpgBuilding();
        b.setId(UUID.randomUUID());
        b.setProjectId(projectId);
        b.setAreaId(areaId);
        b.setHostRoomId(hostRoomId);
        b.setSlug(slug);
        b.setName(slug);
        b.setKind(kind);
        b.setGridX(gx);
        b.setGridY(gy);
        b.setWidth(1);
        b.setDepth(1);
        b.setFloors(floors);
        b.setBasements(0);
        return b;
    }

    private static RpgRoom interiorRoom(UUID projectId, UUID areaId, UUID buildingId, String slug, int floorLevel) {
        RpgRoom r = room(projectId, areaId, slug);
        r.setBuildingId(buildingId);
        r.setFloorLevel(floorLevel);
        return r;
    }

    private static RpgItem item(UUID projectId, String slug) {
        RpgItem it = new RpgItem();
        it.setId(UUID.randomUUID());
        it.setProjectId(projectId);
        it.setSlug(slug);
        it.setName(slug);
        return it;
    }

    private static RpgShopStock stock(UUID projectId, UUID buildingId, UUID itemId, int buy, int sell, int qty) {
        RpgShopStock s = new RpgShopStock();
        s.setId(UUID.randomUUID());
        s.setProjectId(projectId);
        s.setBuildingId(buildingId);
        s.setItemId(itemId);
        s.setBuyPrice(buy);
        s.setSellPrice(sell);
        s.setStockQty(qty);
        return s;
    }

    private static RpgAsset asset(UUID projectId, String slug, String kind, String mime, String storagePath) {
        RpgAsset a = new RpgAsset();
        a.setId(UUID.randomUUID());
        a.setProjectId(projectId);
        a.setSlug(slug);
        a.setKind(kind);
        a.setMimeType(mime);
        a.setStoragePath(storagePath);
        return a;
    }

    private void stub(List<RpgArea> areaList, List<RpgRoom> roomList, List<RpgExit> exitList) {
        stub(areaList, roomList, exitList, List.of(), List.of(), List.of(), List.of());
    }

    private void stub(List<RpgArea> areaList, List<RpgRoom> roomList, List<RpgExit> exitList,
                      List<RpgAsset> assetList, List<RpgBuilding> buildingList, List<RpgShopStock> stockList,
                      List<RpgItem> itemList) {
        when(areas.findByProjectIdOrderBySortOrderAscNameAsc(any())).thenReturn(Flux.fromIterable(areaList));
        when(rooms.findByProjectIdOrderByNameAsc(any())).thenReturn(Flux.fromIterable(roomList));
        when(exits.findByProjectId(any())).thenReturn(Flux.fromIterable(exitList));
        when(items.findByProjectIdOrderByNameAsc(any())).thenReturn(Flux.fromIterable(itemList));
        when(npcs.findByProjectIdOrderByNameAsc(any())).thenReturn(Flux.empty());
        when(assets.findByProjectIdOrderByUploadedAtDesc(any())).thenReturn(Flux.fromIterable(assetList));
        when(buildings.findByProjectIdOrderByNameAsc(any())).thenReturn(Flux.fromIterable(buildingList));
        when(shopStock.findByProjectId(any())).thenReturn(Flux.fromIterable(stockList));
        // Default: no avatar-config row → the renderer must still bake a
        // default scripts/avatar_config.gd (template preload never dangles).
        when(avatarConfigs.findByProjectId(any())).thenReturn(Mono.empty());
    }

    private RpgGodotProjectRenderer renderer(String bridgeDir) {
        return new RpgGodotProjectRenderer(areas, rooms, exits, items, npcs, assets,
                buildings, shopStock, avatarConfigs, bridgeDir);
    }

    @Test
    void escapesControlCharsInNpcDialogueSoRoomScriptStaysParseable(@TempDir Path work) throws IOException {
        // A multi-line NPC dialogue must not emit a raw newline inside the GDScript
        // double-quoted string literal in const ROOM — that would be an unterminated
        // literal and fail the whole room script's parse (and the export).
        RpgProject p = project();
        RpgArea area = area(p.getId(), "lineland", null);
        RpgRoom square = room(p.getId(), area.getId(), "town-square");
        RpgNpc greeter = new RpgNpc();
        greeter.setId(UUID.randomUUID());
        greeter.setProjectId(p.getId());
        greeter.setSlug("greeter");
        greeter.setName("Old\tGreeter");
        greeter.setDefaultRoomId(square.getId());
        greeter.setDialogueText("Hello, traveller.\nMind the \"gap\".\r\nSafe travels.");
        stub(List.of(area), List.of(square), List.of());
        when(npcs.findByProjectIdOrderByNameAsc(any())).thenReturn(Flux.just(greeter));

        renderer("").render(p, work).block();

        String gd = Files.readString(work.resolve("godot-src/scripts/rooms/lineland/town-square.gd"));
        // Escaped forms present, raw control chars absent from the baked dialogue.
        assertThat(gd)
                .contains("Hello, traveller.\\nMind the \\\"gap\\\".\\r\\nSafe travels.")
                .contains("Old\\tGreeter");
        // The dialogue value itself must not carry a real newline (would break parse).
        int dlgIdx = gd.indexOf("Mind the");
        assertThat(dlgIdx).isGreaterThan(0);
        assertThat(gd.substring(dlgIdx - 40, dlgIdx)).doesNotContain("\n");
        // The richer npc entry still lands the escaped dialogue in ONE dict
        // alongside the new uuid + portrait keys ("" — no portrait staged).
        assertThat(gd)
                .contains("\"uuid\": \"" + greeter.getId() + "\"")
                .contains("\"portrait\": \"\"");
        int entryStart = gd.indexOf("{\"slug\": \"greeter\"");
        int entryEnd = gd.indexOf("},", entryStart);
        assertThat(entryStart).isGreaterThan(0);
        String entry = gd.substring(entryStart, entryEnd);
        assertThat(entry).contains("\"uuid\":").contains("\"dialogue\":").contains("\"portrait\":");
    }

    @Test
    void rendersCompletePlayableThemedTree(@TempDir Path work) throws IOException {
        RpgProject p = project();
        RpgArea lineland = area(p.getId(), "lineland", null);   // no theme → plain
        RpgRoom square = room(p.getId(), lineland.getId(), "town-square");
        RpgRoom palace = room(p.getId(), lineland.getId(), "palace");
        RpgExit north = exit(square.getId(), palace.getId(), Direction.N);
        stub(List.of(lineland), List.of(square, palace), List.of(north));

        StepVerifier.create(renderer("").render(p, work))
                .assertNext(r -> assertThat(r.orderedAreas()).hasSize(1))
                .verifyComplete();

        Path root = work.resolve("godot-src");

        String pg = Files.readString(root.resolve("project.godot"));
        assertThat(pg).contains("Flatland RPG").doesNotContain("{{slug}}").doesNotContain("{{displayName}}");

        // room script bakes a const ROOM consumed by room_builder — theme, floor,
        // env/light (via builder) and a per-exit portal from the shared table.
        String squareGd = Files.readString(root.resolve("scripts/rooms/lineland/town-square.gd"));
        assertThat(squareGd)
                .contains("RoomBuilder.build(self, ROOM)")
                .contains("\"theme\": \"plain\"")          // plain still gets floor/light/env (never black)
                .contains("\"floor_size\": 28.0")
                .contains("\"entry_spawns\"")
                .contains("\"dir\": \"N\", \"target\": \"" + palace.getId() + "\"")
                .contains("\"target_name\": \"palace\"")
                .contains("\"portal\": Vector3(");

        // room_index resolves both rooms + AREA_START picks the lexicographically
        // smallest outdoor room (palace < town-square).
        String roomIndex = Files.readString(root.resolve("scripts/room_index.gd"));
        assertThat(roomIndex).contains("const ROOM_SCENES").contains("const AREA_START");
        assertThat(roomIndex).contains("\"" + palace.getId() + "\": \"res://scenes/rooms/lineland/palace.tscn\"");
        assertThat(roomIndex).contains("\"lineland\": \"res://scenes/rooms/lineland/palace.tscn\"");

        // scenes emitted per room (trivial root + script — geometry is runtime-built).
        assertThat(Files.exists(root.resolve("scenes/rooms/lineland/town-square.tscn"))).isTrue();

        // full template glue present in the tree.
        assertThat(Files.exists(root.resolve("scripts/player.gd"))).isTrue();
        assertThat(Files.exists(root.resolve("scenes/player.tscn"))).isTrue();
        assertThat(Files.exists(root.resolve("scripts/remote_avatars.gd"))).isTrue();
        assertThat(Files.exists(root.resolve("scripts/game_bridge.gd"))).isTrue();
        assertThat(Files.exists(root.resolve("scripts/room_builder.gd"))).isTrue();
        assertThat(Files.exists(root.resolve("scripts/shop_ui.gd"))).isTrue();

        // main.tscn topology: RemoteAvatars + Player instance, NO MultiplayerLobby.
        String main = Files.readString(root.resolve("scenes/main.tscn"));
        assertThat(main)
                .contains("name=\"GameBridge\"")
                .contains("name=\"HostBridge\"")
                .contains("name=\"RemoteAvatars\"")
                .contains("name=\"Player\"")
                .contains("name=\"ShopUI\"")
                .doesNotContain("MultiplayerLobby");

        // areas.json sidecar shape.
        String areasJson = Files.readString(root.resolve("areas.json"));
        assertThat(areasJson)
                .contains("\"slug\": \"lineland\"")
                .contains("\"theme\": \"plain\"")
                .contains("\"startRoomSlug\": \"palace\"");

        assertThat(Files.exists(root.resolve("hugmesh_bridge.gdextension"))).isTrue();
        assertThat(Files.exists(root.resolve("bin/README.md"))).isTrue();
        // no icon asset here → no floor-art override on top of the biome floor.
        assertThat(squareGd).doesNotContain("apply_floor");
    }

    @Test
    void copiesTexturesAndImportSidecarsForUsedThemesOnly(@TempDir Path work) throws IOException {
        RpgProject p = project();
        RpgArea caves = area(p.getId(), "caves", "cave");
        RpgArea halls = area(p.getId(), "halls", "dungeon");
        RpgRoom c1 = room(p.getId(), caves.getId(), "c1");
        RpgRoom d1 = room(p.getId(), halls.getId(), "d1");
        stub(List.of(caves, halls), List.of(c1, d1), List.of());

        renderer("").render(p, work).block();
        Path root = work.resolve("godot-src");

        // cave + dungeon textures copied WITH a .import sidecar each.
        for (String theme : new String[]{"cave", "dungeon"}) {
            Path albedo = root.resolve("assets/biomes/" + theme + "/floor_albedo.jpg");
            assertThat(Files.exists(albedo)).as(theme + " albedo copied").isTrue();
            String imp = Files.readString(Path.of(albedo + ".import"));
            assertThat(imp)
                    .contains("importer=\"texture\"")
                    .contains("type=\"CompressedTexture2D\"")
                    .contains("compress/mode=1")
                    .contains("compress/lossy_quality=0.8")
                    .contains("mipmaps/generate=true")
                    .contains("detect_3d/compress_to=0");
        }
        // shared door art copied too.
        assertThat(Files.exists(root.resolve("assets/biomes/_shared/door_albedo.jpg"))).isTrue();
        // UNUSED themes are NOT copied.
        assertThat(Files.exists(root.resolve("assets/biomes/forest"))).isFalse();
        assertThat(Files.exists(root.resolve("assets/biomes/desert"))).isFalse();

        // deliberate cave↔dungeon theme divergence baked so the e2e colour assert
        // separates them.
        assertThat(Files.readString(root.resolve("scripts/rooms/caves/c1.gd"))).contains("\"theme\": \"cave\"");
        assertThat(Files.readString(root.resolve("scripts/rooms/halls/d1.gd"))).contains("\"theme\": \"dungeon\"");
    }

    @Test
    void rendersTownBuildingsInteriorsShopStockAndStairs(@TempDir Path work) throws IOException {
        RpgProject p = project();
        RpgArea town = area(p.getId(), "bramblewick", "town");
        RpgRoom market = room(p.getId(), town.getId(), "market");

        // A one-floor item shop with stock, and a two-floor inn with stairs.
        RpgBuilding goods = building(p.getId(), town.getId(), market.getId(), "goods", "item_shop", 1, 1, 1);
        RpgRoom goodsF0 = interiorRoom(p.getId(), town.getId(), goods.getId(), "goods-f0", 0);
        RpgItem potion = item(p.getId(), "potion");
        RpgShopStock potionStock = stock(p.getId(), goods.getId(), potion.getId(), 50, 25, -1);

        RpgBuilding inn = building(p.getId(), town.getId(), market.getId(), "inn", "inn", 4, 4, 2);
        RpgRoom innF0 = interiorRoom(p.getId(), town.getId(), inn.getId(), "inn-f0", 0);
        RpgRoom innF1 = interiorRoom(p.getId(), town.getId(), inn.getId(), "inn-f1", 1);
        RpgExit up = exit(innF0.getId(), innF1.getId(), Direction.UP);
        RpgExit down = exit(innF1.getId(), innF0.getId(), Direction.DOWN);

        stub(List.of(town), List.of(market, goodsF0, innF0, innF1), List.of(up, down),
                List.of(), List.of(goods, inn), List.of(potionStock), List.of(potion));

        renderer("").render(p, work).block();
        Path root = work.resolve("godot-src");

        // host room: town floor (44) + a door portal per building → its floor-0 interior.
        String marketGd = Files.readString(root.resolve("scripts/rooms/bramblewick/market.gd"));
        assertThat(marketGd)
                .contains("\"floor_size\": 44.0")
                .contains("\"buildings\": [")
                .contains("\"kind\": \"item_shop\"")
                .contains("\"door_target\": \"" + goodsF0.getId() + "\"")
                .contains("\"door_target\": \"" + innF0.getId() + "\"");

        // shop floor-0: baked SHOP_STOCK + shop dict + an exit door back to the host.
        String goodsGd = Files.readString(root.resolve("scripts/rooms/bramblewick/goods-f0.gd"));
        assertThat(goodsGd)
                .contains("const SHOP_STOCK := [")
                .contains("\"slug\": \"potion\"")
                .contains("\"buy\": 50")
                .contains("\"sell\": 25")
                .contains("\"shop\": {\"kind\": \"item_shop\"")
                .contains("\"exit_door\": {\"dir\": \"EXIT_DOOR\", \"target\": \"" + market.getId() + "\"");

        // inn floor-0: rest beat + UP stair portal to floor-1.
        String innF0Gd = Files.readString(root.resolve("scripts/rooms/bramblewick/inn-f0.gd"));
        assertThat(innF0Gd)
                .contains("\"rest\": true")
                .contains("\"dir\": \"UP\", \"target\": \"" + innF1.getId() + "\"");

        // inn floor-1: DOWN stair portal back to floor-0.
        String innF1Gd = Files.readString(root.resolve("scripts/rooms/bramblewick/inn-f1.gd"));
        assertThat(innF1Gd).contains("\"dir\": \"DOWN\", \"target\": \"" + innF0.getId() + "\"");

        // interior rooms are never a boot target — AREA_START points at the outdoor room.
        String roomIndex = Files.readString(root.resolve("scripts/room_index.gd"));
        assertThat(roomIndex).contains("\"bramblewick\": \"res://scenes/rooms/bramblewick/market.tscn\"");
    }

    @Test
    void portalAndSpawnDeriveFromOneSharedDirectionTable(@TempDir Path work) throws IOException {
        RpgProject p = project();
        RpgArea a = area(p.getId(), "a1", "desert");
        RpgRoom r1 = room(p.getId(), a.getId(), "r1");
        RpgRoom r2 = room(p.getId(), a.getId(), "r2");
        // N exit: portal at -Z wall, and the reciprocal-arrival (S) spawn is inward on +Z.
        RpgExit n = exit(r1.getId(), r2.getId(), Direction.N);
        stub(List.of(a), List.of(r1, r2), List.of(n));

        renderer("").render(p, work).block();
        String r1gd = Files.readString(work.resolve("godot-src/scripts/rooms/a1/r1.gd"));

        // 28 m floor → half-extent 14; N portal at z=-(14-0.6)=-13.4; S arrival spawn at z=+(14-4)=+10.
        assertThat(r1gd)
                .contains("\"dir\": \"N\"")
                .contains("\"portal\": Vector3(0.000, 1.500, -13.400)")
                .contains("\"S\": Vector3(0.000, 1.000, 10.000)")  // entry spawn for arriving from N (reciprocal S)
                .contains("\"N\": Vector3(0.000, 1.000, -10.000)");
    }

    @Test
    void iconAssetStillWinsAsFloorArtOnTopOfBiomeFloor(@TempDir Path work, @TempDir Path store) throws IOException {
        RpgProject p = project();
        RpgArea lineland = area(p.getId(), "lineland", "cave");
        RpgRoom square = room(p.getId(), lineland.getId(), "town-square");
        byte[] svgBytes = "<svg xmlns='http://www.w3.org/2000/svg'><rect/></svg>".getBytes();
        Path svgOnDisk = store.resolve("floor-bytes");
        Files.write(svgOnDisk, svgBytes);
        RpgAsset floor = asset(p.getId(), "cave-floor", "tileset", "image/svg+xml", svgOnDisk.toString());
        square.setIconAssetId(floor.getId());
        stub(List.of(lineland), List.of(square), List.of(), List.of(floor), List.of(), List.of(), List.of());

        renderer("").render(p, work).block();
        Path root = work.resolve("godot-src");

        assertThat(Files.readAllBytes(root.resolve("assets/" + floor.getId() + ".svg"))).isEqualTo(svgBytes);
        String squareGd = Files.readString(root.resolve("scripts/rooms/lineland/town-square.gd"));
        // biome floor is the default (RoomBuilder), icon art wins on top via room_visuals.
        assertThat(squareGd)
                .contains("RoomBuilder.build(self, ROOM)")
                .contains("RoomVisuals.apply_floor(self, AssetIndex.ASSETS.get(\"" + floor.getId() + "\"");
    }

    @Test
    void bakesBridgeBinaryWhenBridgeDirProvided(@TempDir Path work, @TempDir Path bridge) throws IOException {
        Files.writeString(bridge.resolve("hugmesh_bridge.wasm"), "\0fake-wasm");
        RpgProject p = project();
        RpgArea a = area(p.getId(), "a1", null);
        stub(List.of(a), List.of(room(p.getId(), a.getId(), "r1")), List.of());

        renderer(bridge.toString()).render(p, work).block();

        Path bin = work.resolve("godot-src/bin");
        assertThat(Files.exists(bin.resolve("hugmesh_bridge.wasm"))).isTrue();
        assertThat(Files.exists(bin.resolve("README.md"))).isFalse();
    }

    // ── Character maker: avatar config + dialogue templates + portraits ─────

    private static RpgAvatarConfig avatarRow(UUID projectId, String body, String head, String hat,
                                             double height, String bodyColor, String headColor,
                                             String hatColor) {
        RpgAvatarConfig c = new RpgAvatarConfig();
        c.setId(UUID.randomUUID());
        c.setProjectId(projectId);
        c.setBodyShape(body);
        c.setHeadShape(head);
        c.setHatType(hat);
        c.setHeightScale(height);
        c.setBodyColor(bodyColor);
        c.setHeadColor(headColor);
        c.setHatColor(hatColor);
        return c;
    }

    @Test
    void bakesAvatarConfigWithColorsAsFloatsNeverRawHex(@TempDir Path work) throws IOException {
        RpgProject p = project();
        RpgArea a = area(p.getId(), "a1", null);
        stub(List.of(a), List.of(room(p.getId(), a.getId(), "r1")), List.of());
        when(avatarConfigs.findByProjectId(any())).thenReturn(Mono.just(
                avatarRow(p.getId(), "stocky", "round", "top_hat", 1.1, "#e21422", "#e6b58c", "#1a1a1a")));

        renderer("").render(p, work).block();

        String cfg = Files.readString(work.resolve("godot-src/scripts/avatar_config.gd"));
        assertThat(cfg)
                .contains("\"body_shape\": \"stocky\"")
                .contains("\"head_shape\": \"round\"")
                .contains("\"hat_type\": \"top_hat\"")
                .contains("\"height_scale\": 1.100")
                // #e21422 → floats; the raw hex string never reaches GDScript.
                .contains("\"body_color\": Color(0.886, 0.078, 0.133)")
                // The skin tone lives in the head_color COLUMN but bakes under
                // its accurate spec name — avatar_factory.gd reads the RIG now,
                // so the AVATAR dict is informational and can be named honestly.
                .contains("\"skin_color\": Color(0.902, 0.710, 0.549)")
                .contains("\"hat_color\": Color(0.102, 0.102, 0.102)")
                .doesNotContain("#e21422").doesNotContain("#e6b58c").doesNotContain("#1a1a1a");
    }

    @Test
    void writesDefaultAvatarConfigWhenNoRowSoPreloadNeverDangles(@TempDir Path work) throws IOException {
        RpgProject p = project();
        RpgArea a = area(p.getId(), "a1", null);
        stub(List.of(a), List.of(room(p.getId(), a.getId(), "r1")), List.of());
        // stub() default: avatarConfigs.findByProjectId → Mono.empty()

        renderer("").render(p, work).block();

        String cfg = Files.readString(work.resolve("godot-src/scripts/avatar_config.gd"));
        assertThat(cfg)
                .contains("const AVATAR := {")
                .contains("\"body_shape\": \"slim\"")
                .contains("\"head_shape\": \"round\"")
                .contains("\"hat_type\": \"none\"")
                .contains("\"height_scale\": 1.000")
                .contains("\"body_color\": Color(");
    }

    @Test
    void avatarConfigDefenceInDepthFallsBackOffWhitelistTokensAndClampsHeight(@TempDir Path work)
            throws IOException {
        // Impossible past the DB CHECKs, but the renderer must never bake
        // arbitrary text: off-whitelist tokens fall back to the default
        // token, an off-range height is clamped, a malformed colour falls
        // back to the default palette.
        RpgProject p = project();
        RpgArea a = area(p.getId(), "a1", null);
        stub(List.of(a), List.of(room(p.getId(), a.getId(), "r1")), List.of());
        when(avatarConfigs.findByProjectId(any())).thenReturn(Mono.just(
                avatarRow(p.getId(), "gigantic\"]; evil()", "round", "fedora", 9.9,
                        "#zzzzzz", "#e6b58c", "not-a-colour")));

        renderer("").render(p, work).block();

        String cfg = Files.readString(work.resolve("godot-src/scripts/avatar_config.gd"));
        assertThat(cfg)
                .contains("\"body_shape\": \"slim\"")
                .contains("\"hat_type\": \"none\"")
                .contains("\"height_scale\": 1.400")
                .doesNotContain("gigantic").doesNotContain("fedora")
                .doesNotContain("#zzzzzz").doesNotContain("not-a-colour");
    }

    @Test
    void shipsCharacterTemplatesAndDialogueUiNode(@TempDir Path work) throws IOException {
        RpgProject p = project();
        RpgArea a = area(p.getId(), "a1", null);
        stub(List.of(a), List.of(room(p.getId(), a.getId(), "r1")), List.of());

        renderer("").render(p, work).block();
        Path root = work.resolve("godot-src");

        assertThat(Files.exists(root.resolve("scripts/avatar_factory.gd"))).isTrue();
        assertThat(Files.exists(root.resolve("scripts/dialogue_ui.gd"))).isTrue();
        // main.tscn gained the DialogueUI CanvasLayer sibling of ShopUI.
        String main = Files.readString(root.resolve("scenes/main.tscn"));
        assertThat(main)
                .contains("name=\"DialogueUI\"")
                .contains("res://scripts/dialogue_ui.gd");
        // player + remote avatars assemble visuals via the shared factory.
        String player = Files.readString(root.resolve("scripts/player.gd"));
        assertThat(player).contains("AvatarFactory").contains("AvatarConfig.AVATAR");
        String remote = Files.readString(root.resolve("scripts/remote_avatars.gd"));
        assertThat(remote).contains("AvatarFactory").contains("AvatarConfig.AVATAR");
        // TALK_NPC control + SD_DIALOGUE_EVENT with controlled fields only.
        String bridge = Files.readString(root.resolve("scripts/game_bridge.gd"));
        assertThat(bridge).contains("TALK_NPC:").contains("SD_DIALOGUE_EVENT").contains("portraitLoaded");
    }

    /** Every {@code .gd} shipped in the rendered tree, template-copied or baked. */
    private static List<Path> shippedScripts(Path root) throws IOException {
        try (Stream<Path> walk = Files.walk(root.resolve("scripts"))) {
            return walk.filter(f -> f.toString().endsWith(".gd")).sorted().toList();
        }
    }

    @Test
    void everyPreloadInAShippedScriptResolvesInTheRenderedTree(@TempDir Path work) throws IOException {
        // A dangling preload is a HARD parse error in the produced bundle, not a
        // soft fallback — it takes the whole game down at boot. The template
        // scripts preload each other AND the baked avatar_config.gd, so the set
        // copyTemplateTree emits has to be closed under preload.
        RpgProject p = project();
        RpgArea a = area(p.getId(), "a1", "town");
        stub(List.of(a), List.of(room(p.getId(), a.getId(), "r1")), List.of());

        renderer("").render(p, work).block();
        Path root = work.resolve("godot-src");

        Pattern preload = Pattern.compile("preload\\(\"res://([^\"]+)\"\\)");
        List<String> dangling = new ArrayList<>();
        for (Path gd : shippedScripts(root)) {
            Matcher m = preload.matcher(Files.readString(gd));
            while (m.find()) {
                if (!Files.exists(root.resolve(m.group(1)))) {
                    dangling.add(root.relativize(gd) + " -> " + m.group(1));
                }
            }
        }
        assertThat(dangling).as("preloads with no target in the rendered tree").isEmpty();
        // Guard against the assertion passing vacuously on an empty walk.
        assertThat(shippedScripts(root)).hasSizeGreaterThan(10);
    }

    @Test
    void npcsAreBuiltByTheSharedGeneratorWithPerNpcVariation(@TempDir Path work) throws IOException {
        RpgProject p = project();
        RpgArea town = area(p.getId(), "bramblewick", "town");
        RpgRoom green = room(p.getId(), town.getId(), "bramblewick-green");
        RpgNpc guide = new RpgNpc();
        guide.setId(UUID.randomUUID());
        guide.setProjectId(p.getId());
        guide.setSlug("guide");
        guide.setName("Guide Bramble");
        guide.setDefaultRoomId(green.getId());
        RpgNpc smith = new RpgNpc();
        smith.setId(UUID.randomUUID());
        smith.setProjectId(p.getId());
        smith.setSlug("smith");
        smith.setName("Smith Thorn");
        smith.setDefaultRoomId(green.getId());
        stub(List.of(town), List.of(green), List.of());
        when(npcs.findByProjectIdOrderByNameAsc(any())).thenReturn(Flux.just(guide, smith));

        renderer("").render(p, work).block();
        Path root = work.resolve("godot-src");

        String builder = Files.readString(root.resolve("scripts/room_builder.gd"));
        // NPCs come off the SAME factory as the hero and the remote avatars,
        // not the old hard-coded cylinder body + sphere head.
        assertThat(builder)
                .contains("AvatarFactory.build(AvatarConfig.AVATAR")
                .doesNotContain("Color(0.5, 0.4, 0.6)");
        // The dialogue/TALK_NPC flow hangs off these three exactly as before.
        assertThat(builder)
                .contains("var holder := Area3D.new()")
                .contains("shape.radius = 3.0")
                .contains("name_label.billboard = BaseMaterial3D.BILLBOARD_ENABLED")
                .contains("ui.set_active_npc(npc)")
                .contains("ui.clear_active_npc()");
        // Variation is seeded from baked data, never from runtime randomness.
        assertThat(builder).contains("_npc_variation(str(n.get(\"uuid\", \"\"))");
        int npcSection = builder.indexOf("static func _build_npc");
        assertThat(builder.substring(npcSection, builder.indexOf("static func _npc_variation")))
                .doesNotContain("RandomNumberGenerator").doesNotContain("randf");

        // Both NPCs bake a distinct uuid — that IS the per-NPC seed.
        String gd = Files.readString(root.resolve("scripts/rooms/bramblewick/bramblewick-green.gd"));
        assertThat(gd)
                .contains("\"uuid\": \"" + guide.getId() + "\"")
                .contains("\"uuid\": \"" + smith.getId() + "\"");
        assertThat(guide.getId()).isNotEqualTo(smith.getId());

        // room_builder now preloads the baked config, so the symbols it reads
        // have to be in the file the renderer writes.
        String cfg = Files.readString(root.resolve("scripts/avatar_config.gd"));
        assertThat(cfg).contains("const AVATAR := {").contains("const RIG := ").contains("const STYLE := ");
    }

    @Test
    void gestureVerbRelaysThroughOneWhitelistOnAnUncontestedKey(@TempDir Path work) throws IOException {
        RpgProject p = project();
        RpgArea a = area(p.getId(), "a1", null);
        stub(List.of(a), List.of(room(p.getId(), a.getId(), "r1")), List.of());

        renderer("").render(p, work).block();
        Path root = work.resolve("godot-src");

        String poser = Files.readString(root.resolve("scripts/avatar_poser.gd"));
        assertThat(poser).contains(
                "const GESTURES := [\"wave\", \"point\", \"nod\", \"bow\", \"clap\", \"shrug\", \"cheer\", \"dance\"]");

        // Emitted as a control verb on the EXISTING transport (send_control →
        // /api/controls → movement echo), like TALK_NPC and SHOP_BUY.
        String bridge = Files.readString(root.resolve("scripts/game_bridge.gd"));
        assertThat(bridge)
                .contains("send_control(\"GESTURE:\" + gesture, true)")
                .contains("if not AvatarPoser.GESTURES.has(gesture):")
                .doesNotContain("SD_GESTURE");

        // Received on both sides of the echo: own body and every remote avatar.
        assertThat(Files.readString(root.resolve("scripts/player.gd")))
                .contains("action.begins_with(\"GESTURE:\")")
                .contains("_poser.play_gesture(action.substr(\"GESTURE:\".length()))");
        assertThat(Files.readString(root.resolve("scripts/remote_avatars.gd")))
                .contains("action.begins_with(\"GESTURE:\")")
                .contains("poser.play_gesture(action.substr(\"GESTURE:\".length()))");

        // The eight names live in exactly ONE file. A second copy is a second
        // whitelist, and the copy is what drifts.
        List<String> withNames = new ArrayList<>();
        for (Path gd : shippedScripts(root)) {
            if (Files.readString(gd).contains("\"wave\"")) withNames.add(gd.getFileName().toString());
        }
        assertThat(withNames).containsExactly("avatar_poser.gd");

        // The emote key is bound and collides with nothing: a duplicate key in
        // the KEY_ACTIONS literal silently drops the earlier action.
        int mapStart = bridge.indexOf("const KEY_ACTIONS := {");
        assertThat(mapStart).isGreaterThan(0);
        String keyMap = bridge.substring(mapStart, bridge.indexOf("}", mapStart));
        List<String> bound = new ArrayList<>();
        Matcher m = Pattern.compile("(KEY_[A-Z0-9_]+):").matcher(keyMap);
        while (m.find()) bound.add(m.group(1));
        assertThat(bound).contains("KEY_G").doesNotHaveDuplicates();
        assertThat(keyMap).contains("KEY_G: \"GESTURE\"");
    }

    @Test
    void npcPortraitIsStagedThreadedIntoRoomAndSidecarredLossless(@TempDir Path work, @TempDir Path store)
            throws IOException {
        RpgProject p = project();
        RpgArea town = area(p.getId(), "bramblewick", "town");
        RpgRoom green = room(p.getId(), town.getId(), "bramblewick-green");
        byte[] svgBytes = "<svg xmlns='http://www.w3.org/2000/svg'><circle/></svg>".getBytes();
        Path svgOnDisk = store.resolve("portrait-bytes");
        Files.write(svgOnDisk, svgBytes);
        RpgAsset portrait = asset(p.getId(), "greeter-portrait", "portrait", "image/svg+xml",
                svgOnDisk.toString());
        RpgNpc greeter = new RpgNpc();
        greeter.setId(UUID.randomUUID());
        greeter.setProjectId(p.getId());
        greeter.setSlug("town-greeter");
        greeter.setName("Guide Bramble");
        greeter.setDefaultRoomId(green.getId());
        greeter.setDialogueText("Well met, traveller!");
        greeter.setPortraitAssetId(portrait.getId());
        stub(List.of(town), List.of(green), List.of(), List.of(portrait), List.of(), List.of(), List.of());
        when(npcs.findByProjectIdOrderByNameAsc(any())).thenReturn(Flux.just(greeter));

        renderer("").render(p, work).block();
        Path root = work.resolve("godot-src");

        // bytes staged + baked ROOM entry carries uuid AND the staged res:// path.
        String resPath = "res://assets/" + portrait.getId() + ".svg";
        assertThat(Files.readAllBytes(root.resolve("assets/" + portrait.getId() + ".svg")))
                .isEqualTo(svgBytes);
        String gd = Files.readString(root.resolve("scripts/rooms/bramblewick/bramblewick-green.gd"));
        assertThat(gd)
                .contains("\"uuid\": \"" + greeter.getId() + "\"")
                .contains("\"portrait\": \"" + resPath + "\"");

        // portrait .import sidecar: the 2D UI class — LOSSLESS, no mipmaps,
        // never treated as a 3D surface; svg pins its import scale.
        String imp = Files.readString(root.resolve("assets/" + portrait.getId() + ".svg.import"));
        assertThat(imp)
                .contains("importer=\"texture\"")
                .contains("type=\"CompressedTexture2D\"")
                .contains("source_file=\"" + resPath + "\"")
                .contains("compress/mode=0")
                .contains("mipmaps/generate=false")
                .contains("detect_3d/compress_to=0")
                .contains("svg/scale=1.0")
                .doesNotContain("compress/mode=1");

        // The 3D biome-texture sidecar profile is untouched (lossy + mipmaps).
        String biomeImp = Files.readString(
                root.resolve("assets/biomes/town/floor_albedo.jpg.import"));
        assertThat(biomeImp).contains("compress/mode=1").contains("mipmaps/generate=true");
    }

    @Test
    void failsWhenProjectHasNoAreas(@TempDir Path work) {
        RpgProject p = project();
        stub(List.of(), List.of(), List.of());
        StepVerifier.create(renderer("").render(p, work))
                .expectErrorSatisfies(e -> assertThat(e).hasMessageContaining("no areas"))
                .verify();
    }

    @Test
    void failsWhenProjectHasNoRooms(@TempDir Path work) {
        RpgProject p = project();
        stub(List.of(area(p.getId(), "a1", null)), List.of(), List.of());
        StepVerifier.create(renderer("").render(p, work))
                .expectErrorSatisfies(e -> assertThat(e).hasMessageContaining("no rooms"))
                .verify();
    }
}
