package games.hugmesh.rpgbuilder.build;

import games.hugmesh.rpgbuilder.entity.*;
import games.hugmesh.rpgbuilder.repository.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Specs for {@link RpgGodotProjectRenderer} — proves the emitted Godot 4 project tree is
 * structurally complete + playable: interpolated project.godot, per-room scripts with an
 * exits map, the generated room_index.gd (room UUID → scene path, the fix that makes
 * room-to-room navigation work), and the hugmesh-bridge bin/ baked in. Plus the
 * no-areas / no-rooms guard rails.
 */
class RpgGodotProjectRendererTest {

    private final RpgAreaRepository areas = mock(RpgAreaRepository.class);
    private final RpgRoomRepository rooms = mock(RpgRoomRepository.class);
    private final RpgExitRepository exits = mock(RpgExitRepository.class);
    private final RpgItemRepository items = mock(RpgItemRepository.class);
    private final RpgNpcRepository npcs = mock(RpgNpcRepository.class);
    private final RpgAssetRepository assets = mock(RpgAssetRepository.class);

    private static RpgProject project() {
        RpgProject p = new RpgProject();
        p.setId(UUID.randomUUID());
        p.setSlug("flatland-rpg");
        p.setDisplayName("Flatland RPG");
        p.setDescription("A square's tale");
        return p;
    }

    private static RpgArea area(UUID projectId, String slug) {
        RpgArea a = new RpgArea();
        a.setId(UUID.randomUUID());
        a.setProjectId(projectId);
        a.setSlug(slug);
        a.setName(slug);
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

    private void stub(RpgProject p, List<RpgArea> areaList, List<RpgRoom> roomList, List<RpgExit> exitList) {
        stub(p, areaList, roomList, exitList, List.of());
    }

    private void stub(RpgProject p, List<RpgArea> areaList, List<RpgRoom> roomList, List<RpgExit> exitList,
                      List<RpgAsset> assetList) {
        when(areas.findByProjectIdOrderBySortOrderAscNameAsc(any())).thenReturn(Flux.fromIterable(areaList));
        when(rooms.findByProjectIdOrderByNameAsc(any())).thenReturn(Flux.fromIterable(roomList));
        when(exits.findByProjectId(any())).thenReturn(Flux.fromIterable(exitList));
        when(items.findByProjectIdOrderByNameAsc(any())).thenReturn(Flux.empty());
        when(npcs.findByProjectIdOrderByNameAsc(any())).thenReturn(Flux.empty());
        when(assets.findByProjectIdOrderByUploadedAtDesc(any())).thenReturn(Flux.fromIterable(assetList));
    }

    private RpgGodotProjectRenderer renderer(String bridgeDir) {
        return new RpgGodotProjectRenderer(areas, rooms, exits, items, npcs, assets, bridgeDir);
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

    @Test
    void rendersCompletePlayableTree(@TempDir Path work) throws IOException {
        RpgProject p = project();
        RpgArea lineland = area(p.getId(), "lineland");
        RpgRoom square = room(p.getId(), lineland.getId(), "town-square");
        RpgRoom palace = room(p.getId(), lineland.getId(), "palace");
        // an exit town-square --N--> palace, stored as the destination room UUID.
        RpgExit north = exit(square.getId(), palace.getId(), Direction.N);
        stub(p, List.of(lineland), List.of(square, palace), List.of(north));

        StepVerifier.create(renderer("").render(p, work))
                .assertNext(r -> assertThat(r.orderedAreas()).hasSize(1))
                .verifyComplete();

        Path root = work.resolve("godot-src");

        // project.godot interpolated.
        String pg = Files.readString(root.resolve("project.godot"));
        assertThat(pg).contains("Flatland RPG").doesNotContain("{{slug}}").doesNotContain("{{displayName}}");

        // per-room script with the exits map keyed by direction → destination UUID.
        String squareGd = Files.readString(root.resolve("scripts/rooms/lineland/town-square.gd"));
        assertThat(squareGd).contains("var exits :=").contains("\"N\": \"" + palace.getId() + "\"");

        // room_index.gd resolves BOTH rooms' UUIDs to their scene paths.
        String roomIndex = Files.readString(root.resolve("scripts/room_index.gd"));
        assertThat(roomIndex).contains("const ROOM_SCENES");
        assertThat(roomIndex).contains("\"" + palace.getId() + "\": \"res://scenes/rooms/lineland/palace.tscn\"");
        assertThat(roomIndex).contains("\"" + square.getId() + "\": \"res://scenes/rooms/lineland/town-square.tscn\"");

        // scenes emitted per room.
        assertThat(Files.exists(root.resolve("scenes/rooms/lineland/town-square.tscn"))).isTrue();
        assertThat(Files.exists(root.resolve("scenes/rooms/lineland/palace.tscn"))).isTrue();

        // the gdextension descriptor + a bin/ (placeholder when no bridge-dir).
        assertThat(Files.exists(root.resolve("hugmesh_bridge.gdextension"))).isTrue();
        assertThat(Files.exists(root.resolve("bin/README.md"))).isTrue();

        // the runtime art loader + the (empty here) asset index are always staged.
        assertThat(Files.exists(root.resolve("scripts/room_visuals.gd"))).isTrue();
        String assetIndex = Files.readString(root.resolve("scripts/asset_index.gd"));
        assertThat(assetIndex).contains("const ASSETS");
        // no rooms carry an icon asset here → no floor art applied.
        assertThat(squareGd).doesNotContain("apply_floor");
    }

    @Test
    void materializesReferencedRoomIconBytesIntoStagedTree(@TempDir Path work, @TempDir Path store) throws IOException {
        RpgProject p = project();
        RpgArea lineland = area(p.getId(), "lineland");
        RpgRoom square = room(p.getId(), lineland.getId(), "town-square");

        // A real SVG asset on disk (as AssetStorageService would have stored it).
        byte[] svgBytes = "<svg xmlns='http://www.w3.org/2000/svg'><rect/></svg>".getBytes();
        Path svgOnDisk = store.resolve("floor-bytes");
        Files.write(svgOnDisk, svgBytes);
        RpgAsset floor = asset(p.getId(), "cave-floor", "tileset", "image/svg+xml", svgOnDisk.toString());
        square.setIconAssetId(floor.getId());

        stub(p, List.of(lineland), List.of(square), List.of(), List.of(floor));
        renderer("").render(p, work).block();

        Path root = work.resolve("godot-src");

        // (gap 1) the actual bytes land at the stable res:// path, byte-identical.
        Path stagedArt = root.resolve("assets/" + floor.getId() + ".svg");
        assertThat(Files.exists(stagedArt)).isTrue();
        assertThat(Files.readAllBytes(stagedArt)).isEqualTo(svgBytes);

        // asset_index.gd maps the asset UUID → its staged res:// path.
        String assetIndex = Files.readString(root.resolve("scripts/asset_index.gd"));
        assertThat(assetIndex)
                .contains("\"" + floor.getId() + "\": \"res://assets/" + floor.getId() + ".svg\"");

        // (gap 2) the room script resolves + applies that art as its floor.
        String squareGd = Files.readString(root.resolve("scripts/rooms/lineland/town-square.gd"));
        assertThat(squareGd).contains("RoomVisuals.apply_floor(self, AssetIndex.ASSETS.get(\"" + floor.getId() + "\"");
        assertThat(squareGd).contains("preload(\"res://scripts/room_visuals.gd\")");
    }

    @Test
    void skipsMissingAssetBytesGracefully(@TempDir Path work) throws IOException {
        RpgProject p = project();
        RpgArea lineland = area(p.getId(), "lineland");
        RpgRoom square = room(p.getId(), lineland.getId(), "town-square");
        // Row references a storage path that does not exist on disk.
        RpgAsset ghost = asset(p.getId(), "ghost", "tileset", "image/svg+xml", "/no/such/file/here");
        square.setIconAssetId(ghost.getId());

        stub(p, List.of(lineland), List.of(square), List.of(), List.of(ghost));
        renderer("").render(p, work).block();   // must not throw

        Path root = work.resolve("godot-src");
        // nothing staged, no floor call, empty index — but the render still succeeds.
        assertThat(Files.exists(root.resolve("assets/" + ghost.getId() + ".svg"))).isFalse();
        String squareGd = Files.readString(root.resolve("scripts/rooms/lineland/town-square.gd"));
        assertThat(squareGd).doesNotContain("apply_floor");
    }

    @Test
    void bakesBridgeBinaryWhenBridgeDirProvided(@TempDir Path work, @TempDir Path bridge) throws IOException {
        Files.writeString(bridge.resolve("hugmesh_bridge.wasm"), "\0fake-wasm");
        RpgProject p = project();
        RpgArea a = area(p.getId(), "a1");
        stub(p, List.of(a), List.of(room(p.getId(), a.getId(), "r1")), List.of());

        renderer(bridge.toString()).render(p, work).block();

        Path bin = work.resolve("godot-src/bin");
        assertThat(Files.exists(bin.resolve("hugmesh_bridge.wasm"))).isTrue();
        assertThat(Files.exists(bin.resolve("README.md"))).isFalse();   // real binary, no placeholder
    }

    @Test
    void failsWhenProjectHasNoAreas(@TempDir Path work) {
        RpgProject p = project();
        stub(p, List.of(), List.of(), List.of());
        StepVerifier.create(renderer("").render(p, work))
                .expectErrorSatisfies(e -> assertThat(e).hasMessageContaining("no areas"))
                .verify();
    }

    @Test
    void failsWhenProjectHasNoRooms(@TempDir Path work) {
        RpgProject p = project();
        stub(p, List.of(area(p.getId(), "a1")), List.of(), List.of());
        StepVerifier.create(renderer("").render(p, work))
                .expectErrorSatisfies(e -> assertThat(e).hasMessageContaining("no rooms"))
                .verify();
    }
}
