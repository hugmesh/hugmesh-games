package games.snapdragon.rpgbuilder.build;

import games.snapdragon.rpgbuilder.entity.*;
import games.snapdragon.rpgbuilder.repository.*;
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
 * snapdragon-bridge GDExtension (so the produced WASM gets the Rust
 * HostBridge calls + multiplayer lobby + per-player view channel for
 * free) and lays out one .tscn + .gd per room. Each Area is one
 * exported chapter — the build worker iterates Areas and runs the
 * Godot exporter once per area.
 *
 * <p>The rendered project is shaped to match the BFF's upload bundle
 * spec: {@code manifest.json} (schema_version=2), {@code register.sql}
 * (one upsert per chapter + scene_elements rows for rooms), and a
 * {@code bundle/<area-slug>/index.{html,js,wasm,pck}} per area.
 *
 * <p>The Godot CLI step (the actual export) is delegated to
 * {@link GodotCompileWorker}; this class is purely the project-tree
 * generator.
 */
@Component
public class RpgGodotProjectRenderer {

    private static final Logger log = LoggerFactory.getLogger(RpgGodotProjectRenderer.class);

    private final RpgAreaRepository areas;
    private final RpgRoomRepository rooms;
    private final RpgExitRepository exits;
    private final RpgItemRepository items;
    private final RpgNpcRepository npcs;

    public RpgGodotProjectRenderer(RpgAreaRepository areas, RpgRoomRepository rooms, RpgExitRepository exits,
                                   RpgItemRepository items, RpgNpcRepository npcs) {
        this.areas = areas;
        this.rooms = rooms;
        this.exits = exits;
        this.items = items;
        this.npcs = npcs;
    }

    public record RenderedProject(Path godotSrcRoot, List<RpgArea> orderedAreas, List<RpgRoom> roomsByArea,
                                  List<RpgExit> allExits, String displayName, String description) {}

    public Mono<RenderedProject> render(RpgProject project, Path workdir) {
        return Mono.zip(
                areas.findByProjectIdOrderBySortOrderAscNameAsc(project.getId()).collectList(),
                rooms.findByProjectIdOrderByNameAsc(project.getId()).collectList(),
                exits.findByProjectId(project.getId()).collectList(),
                items.findByProjectIdOrderByNameAsc(project.getId()).collectList(),
                npcs.findByProjectIdOrderByNameAsc(project.getId()).collectList())
                .flatMap(t -> Mono.fromCallable(() ->
                        writeTree(project, workdir, t.getT1(), t.getT2(), t.getT3(), t.getT4(), t.getT5()))
                        .subscribeOn(Schedulers.boundedElastic()));
    }

    private RenderedProject writeTree(RpgProject project, Path workdir,
                                      List<RpgArea> areaList, List<RpgRoom> roomList, List<RpgExit> exitList,
                                      List<RpgItem> itemList, List<RpgNpc> npcList) throws IOException {
        if (areaList.isEmpty()) {
            throw new IllegalStateException("project has no areas; cannot render");
        }
        if (roomList.isEmpty()) {
            throw new IllegalStateException("project has no rooms; cannot render");
        }

        Path root = workdir.resolve("godot-src");
        Files.createDirectories(root);
        copyTemplateTree(root);

        // project.godot — interpolate slug + display name
        Path projectGodot = root.resolve("project.godot");
        String pg = Files.readString(projectGodot);
        pg = pg.replace("{{slug}}", project.getSlug())
               .replace("{{displayName}}", project.getDisplayName());
        Files.writeString(projectGodot, pg);

        // Per-area + per-room scene + script files
        for (RpgArea area : areaList) {
            Path areaScripts = root.resolve("scripts/rooms").resolve(area.getSlug());
            Path areaScenes  = root.resolve("scenes/rooms").resolve(area.getSlug());
            Files.createDirectories(areaScripts);
            Files.createDirectories(areaScenes);

            List<RpgRoom> areaRooms = roomList.stream().filter(r -> r.getAreaId().equals(area.getId())).toList();
            for (RpgRoom room : areaRooms) {
                writeRoomScript(areaScripts.resolve(room.getSlug() + ".gd"), area, room,
                        exitList.stream().filter(x -> x.getFromRoomId().equals(room.getId())).toList());
                writeRoomScene(areaScenes.resolve(room.getSlug() + ".tscn"), area, room);
            }
        }

        log.info("[render] wrote Godot project tree at {} ({} areas, {} rooms, {} exits)",
                root, areaList.size(), roomList.size(), exitList.size());
        return new RenderedProject(root, areaList, roomList, exitList,
                project.getDisplayName(), project.getDescription());
    }

    private void copyTemplateTree(Path dst) throws IOException {
        // The template lives on classpath under godot-template/. We copy
        // a fixed set of files; per-room generation happens above.
        copyClasspath("godot-template/project.godot",       dst.resolve("project.godot"));
        copyClasspath("godot-template/export_presets.cfg",  dst.resolve("export_presets.cfg"));
        copyClasspath("godot-template/scenes/main.tscn",    dst.resolve("scenes/main.tscn"));
        copyClasspath("godot-template/scripts/world.gd",    dst.resolve("scripts/world.gd"));
        copyClasspath("godot-template/scripts/exits.gd",    dst.resolve("scripts/exits.gd"));
        copyClasspath("godot-template/snapdragon_bridge.gdextension",
                                                            dst.resolve("snapdragon_bridge.gdextension"));
    }

    private static void copyClasspath(String classpathPath, Path target) throws IOException {
        Files.createDirectories(target.getParent());
        try (InputStream in = new ClassPathResource(classpathPath).getInputStream()) {
            Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private void writeRoomScript(Path target, RpgArea area, RpgRoom room, List<RpgExit> roomExits) throws IOException {
        StringBuilder sb = new StringBuilder();
        sb.append("# Generated by snapdragon-rpg-builder. Do not hand-edit.\n");
        sb.append("# Room: ").append(room.getName()).append(" (").append(room.getSlug()).append(")\n");
        sb.append("extends Node3D\n\n");
        sb.append("const ROOM_SLUG := \"").append(room.getSlug()).append("\"\n");
        sb.append("const AREA_SLUG := \"").append(area.getSlug()).append("\"\n\n");
        sb.append("var exits := {\n");
        for (RpgExit x : roomExits) {
            sb.append("    \"").append(x.getDirection().name()).append("\": \"")
              .append(x.getToRoomId()).append("\",\n");
        }
        sb.append("}\n\n");
        sb.append("func _ready() -> void:\n");
        sb.append("    %WorldGreeting.text = \"").append(escape(room.getName())).append("\"\n");
        if (room.getDescription() != null && !room.getDescription().isBlank()) {
            sb.append("    %WorldDescription.text = \"").append(escape(room.getDescription())).append("\"\n");
        }
        if (room.getGdScript() != null && !room.getGdScript().isBlank()) {
            sb.append("    # author script\n");
            for (String line : room.getGdScript().split("\n")) {
                sb.append("    ").append(line).append("\n");
            }
        }
        Files.writeString(target, sb.toString(), StandardCharsets.UTF_8);
    }

    private void writeRoomScene(Path target, RpgArea area, RpgRoom room) throws IOException {
        // A minimal Node3D scene with a label child the GD script
        // resolves via %WorldGreeting / %WorldDescription. The
        // top-level main.tscn instances these via load() at runtime.
        String gdRel = "../../../scripts/rooms/" + area.getSlug() + "/" + room.getSlug() + ".gd";
        String body = """
                [gd_scene load_steps=2 format=3]

                [ext_resource type="Script" path="%s" id="1"]

                [node name="Room_%s" type="Node3D"]
                script = ExtResource("1")

                [node name="WorldGreeting" type="Label3D" parent="."]
                unique_name_in_owner = true
                text = "%s"
                position = Vector3(0, 1.5, 0)

                [node name="WorldDescription" type="Label3D" parent="."]
                unique_name_in_owner = true
                text = "%s"
                position = Vector3(0, 0.7, 0)
                """.formatted(gdRel, room.getSlug(), escape(room.getName()),
                              escape(room.getDescription() != null ? room.getDescription() : ""));
        Files.writeString(target, body, StandardCharsets.UTF_8);
    }

    private static String escape(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
