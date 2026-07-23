package games.hugmesh.vnbuilder.build;

import com.fasterxml.jackson.databind.ObjectMapper;
import games.hugmesh.vnbuilder.entity.*;
import games.hugmesh.vnbuilder.repository.*;
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
 * Renders a VN project tree into a Godot 4 project that mirrors
 * godot-2d-visual-novel's runtime shape (ScenePlayer + TextBox +
 * CharacterDisplayer + Background) but loads its scene graph from a
 * single {@code scenes_data.json} produced here. The runtime side
 * lives in {@code scripts/scene_player.gd} (template) and uses our
 * hugmesh-bridge so the resulting WASM is multiplayer-aware (the
 * built-in MultiplayerLobby + per-player view channel).
 *
 * <p>Per-Act export: each Act becomes a Chapter in the BFF, with its
 * own bundle/index.html. The runtime picks the {@code is_entry_point}
 * scene of the act on first load.
 *
 * <p><b>Asset materialization.</b> Backgrounds and character portraits
 * reference an uploaded/generated {@link VnAsset} by UUID. The renderer
 * copies each referenced asset's actual bytes out of the AssetStorage
 * layout (the asset row's absolute {@code storage_path}) into a shared
 * {@code res://assets/<asset-id>.<ext>} inside the staged tree, and
 * writes that <i>resolvable</i> {@code res://} path (not just the raw
 * UUID) into every act's {@code scenes_data.json} so the runtime can
 * load the texture. A referenced asset that is missing (no row / bytes
 * gone from disk) is skipped: no path is emitted and the scene simply
 * renders art-less. Assets are project-scoped (shared across acts) so
 * they are materialized once into a single {@code res://assets/} dir.
 */
@Component
public class VnGodotProjectRenderer {

    private static final Logger log = LoggerFactory.getLogger(VnGodotProjectRenderer.class);
    private static final ObjectMapper JSON = new ObjectMapper();

    private final VnActRepository acts;
    private final VnSceneRepository scenes;
    private final VnLineRepository lines;
    private final VnChoiceRepository choices;
    private final VnCharacterRepository characters;
    private final VnBackgroundRepository backgrounds;
    private final VnAssetRepository assets;

    /** Directory holding the compiled hugmesh-bridge binaries to bake into the
     *  rendered project's {@code bin/} (see the RPG renderer for the rationale). Empty
     *  (dev default) → skipped (non-multiplayer bundle). */
    private final String bridgeDir;

    public VnGodotProjectRenderer(VnActRepository acts, VnSceneRepository scenes, VnLineRepository lines,
                                  VnChoiceRepository choices, VnCharacterRepository characters,
                                  VnBackgroundRepository backgrounds, VnAssetRepository assets,
                                  @org.springframework.beans.factory.annotation.Value("${app.build.bridge-dir:}")
                                  String bridgeDir) {
        this.acts = acts; this.scenes = scenes; this.lines = lines;
        this.choices = choices; this.characters = characters; this.backgrounds = backgrounds;
        this.assets = assets;
        this.bridgeDir = bridgeDir;
    }

    public record RenderedProject(Path godotSrcRoot, List<VnAct> orderedActs, List<VnScene> allScenes,
                                  String displayName, String description) {}

    public Mono<RenderedProject> render(VnProject project, Path workdir) {
        return Mono.zip(
                acts.findByProjectIdOrderBySortOrderAscNameAsc(project.getId()).collectList(),
                scenes.findByProjectIdOrderBySortOrderAscNameAsc(project.getId()).collectList(),
                characters.findByProjectIdOrderByDisplayNameAsc(project.getId()).collectList(),
                backgrounds.findByProjectIdOrderByNameAsc(project.getId()).collectList())
                .flatMap(t -> Mono.fromCallable(() ->
                        writeTree(project, workdir, t.getT1(), t.getT2(), t.getT3(), t.getT4()))
                        .subscribeOn(Schedulers.boundedElastic()));
    }

    private RenderedProject writeTree(VnProject project, Path workdir,
                                      List<VnAct> actList, List<VnScene> sceneList,
                                      List<VnCharacter> chars, List<VnBackground> bgs) throws IOException {
        if (actList.isEmpty()) throw new IllegalStateException("project has no acts; cannot render");
        if (sceneList.isEmpty()) throw new IllegalStateException("project has no scenes; cannot render");

        Path root = workdir.resolve("godot-src");
        Files.createDirectories(root);
        copyTemplateTree(root);

        Path projectGodot = root.resolve("project.godot");
        String pg = Files.readString(projectGodot);
        pg = pg.replace("{{slug}}", project.getSlug())
               .replace("{{displayName}}", project.getDisplayName());
        Files.writeString(projectGodot, pg);

        // Copy the actual bytes for every referenced background/portrait
        // asset into a shared res://assets/ dir once (assets are project-
        // scoped, not per-act) and remember each asset's resolvable
        // res:// path so scenes_data.json can carry it.
        Map<UUID, String> assetPaths = materializeAssets(project.getId(), root, chars, bgs);

        // Per-act scenes_data.json (one per chapter), inlining the
        // act's scenes + lines + choices so the runtime can drive the
        // VN without a separate database round-trip.
        for (VnAct act : actList) {
            List<VnScene> actScenes = sceneList.stream().filter(s -> s.getActId().equals(act.getId())).toList();
            Map<String, Object> dataset = new LinkedHashMap<>();
            dataset.put("project_slug", project.getSlug());
            dataset.put("act_slug", act.getSlug());
            dataset.put("act_name", act.getName());
            dataset.put("characters", toCharactersMap(chars, assetPaths));
            dataset.put("backgrounds", toBackgroundsMap(bgs, assetPaths));
            dataset.put("scenes", toScenesList(actScenes));
            Path actDir = root.resolve("acts").resolve(act.getSlug());
            Files.createDirectories(actDir);
            JSON.writerWithDefaultPrettyPrinter().writeValue(
                    actDir.resolve("scenes_data.json").toFile(), dataset);
        }

        log.info("[render] wrote VN Godot project tree at {} ({} acts, {} scenes)", root, actList.size(), sceneList.size());
        return new RenderedProject(root, actList, sceneList,
                project.getDisplayName(), project.getDescription());
    }

    private List<Map<String, Object>> toScenesList(List<VnScene> scs) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (VnScene s : scs) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", s.getId().toString());
            m.put("slug", s.getSlug());
            m.put("name", s.getName());
            m.put("background_id", s.getBackgroundId() == null ? null : s.getBackgroundId().toString());
            m.put("entry_point", s.isEntryPoint());
            // Lines + choices are pulled here in a synchronous lookup to
            // keep the renderer single-pass; the underlying queries fan
            // out to repos but the render's already on a bounded-
            // elastic worker so blocking is acceptable.
            List<VnLine> ll = lines.findBySceneIdOrderByLineIndexAsc(s.getId()).collectList().block();
            List<Map<String, Object>> linesOut = new ArrayList<>();
            if (ll != null) for (VnLine l : ll) {
                Map<String, Object> lm = new LinkedHashMap<>();
                lm.put("index", l.getLineIndex());
                lm.put("character_id", l.getCharacterId() == null ? null : l.getCharacterId().toString());
                lm.put("text", l.getText());
                List<VnChoice> cc = choices.findByLineIdOrderBySortOrderAsc(l.getId()).collectList().block();
                List<Map<String, Object>> choicesOut = new ArrayList<>();
                if (cc != null) for (VnChoice c : cc) {
                    Map<String, Object> cm = new LinkedHashMap<>();
                    cm.put("label", c.getLabel());
                    cm.put("target_scene_id", c.getTargetSceneId().toString());
                    choicesOut.add(cm);
                }
                lm.put("choices", choicesOut);
                linesOut.add(lm);
            }
            m.put("lines", linesOut);
            out.add(m);
        }
        return out;
    }

    private Map<String, Object> toCharactersMap(List<VnCharacter> cs, Map<UUID, String> assetPaths) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (VnCharacter c : cs) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("display_name", c.getDisplayName());
            m.put("color", c.getColor());
            UUID aid = c.getDefaultPortraitAssetId();
            m.put("portrait_asset_id", aid == null ? null : aid.toString());
            // Resolvable res:// path the runtime loads the portrait from;
            // null when the asset is unset or could not be materialized.
            m.put("portrait_path", aid == null ? null : assetPaths.get(aid));
            out.put(c.getId().toString(), m);
        }
        return out;
    }

    private Map<String, Object> toBackgroundsMap(List<VnBackground> bs, Map<UUID, String> assetPaths) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (VnBackground b : bs) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("name", b.getName());
            UUID aid = b.getAssetId();
            m.put("asset_id", aid == null ? null : aid.toString());
            // Resolvable res:// path the runtime loads the background from;
            // null when the asset is unset or could not be materialized.
            m.put("asset_path", aid == null ? null : assetPaths.get(aid));
            out.put(b.getId().toString(), m);
        }
        return out;
    }

    /**
     * Copy the raw bytes of every referenced background/portrait asset
     * out of AssetStorage (the asset row's absolute {@code storage_path})
     * into {@code <root>/assets/<asset-id>.<ext>} and return a map of
     * asset id → resolvable {@code res://assets/<asset-id>.<ext>} path.
     *
     * <p>Blocking reads are fine here: {@code writeTree} already runs on a
     * bounded-elastic worker (see {@link #render}) and the existing
     * {@code toScenesList} lookups block the same way.
     *
     * <p>Graceful degradation: a referenced asset whose row is gone, or
     * whose bytes are missing on disk, is logged and skipped — it simply
     * gets no {@code res://} path so the runtime renders that scene art-less.
     *
     * <p><b>Project-scope guard (IDOR defence):</b> a referenced asset row
     * whose {@code projectId} does not match {@code projectId} being rendered
     * is treated exactly like a missing row — logged and skipped — so a
     * background/character that references another project's asset UUID can
     * never pull that project's bytes into this bundle.
     */
    private Map<UUID, String> materializeAssets(UUID projectId, Path root, List<VnCharacter> chars, List<VnBackground> bgs)
            throws IOException {
        // Dedupe: one background + many characters may share an asset id.
        Set<UUID> referenced = new LinkedHashSet<>();
        for (VnBackground b : bgs) if (b.getAssetId() != null) referenced.add(b.getAssetId());
        for (VnCharacter c : chars) if (c.getDefaultPortraitAssetId() != null) referenced.add(c.getDefaultPortraitAssetId());
        if (referenced.isEmpty()) return Map.of();

        Path assetsDir = root.resolve("assets");
        Files.createDirectories(assetsDir);
        Map<UUID, String> out = new LinkedHashMap<>();
        for (UUID id : referenced) {
            VnAsset asset = assets.findById(id).block();
            if (asset == null) {
                log.warn("[render] referenced asset {} has no row; scene will render art-less", id);
                continue;
            }
            if (!projectId.equals(asset.getProjectId())) {
                // IDOR guard: never materialize an asset owned by a different
                // project into this project's bundle. Same graceful skip the
                // missing-row branch uses so the scene just renders art-less.
                log.warn("[render] referenced asset {} belongs to project {} not {}; "
                        + "skipping (cross-project reference)", id, asset.getProjectId(), projectId);
                continue;
            }
            byte[] bytes;
            try {
                bytes = Files.readAllBytes(Path.of(asset.getStoragePath()));
            } catch (IOException e) {
                log.warn("[render] asset {} bytes missing at {} ({}); skipping",
                        id, asset.getStoragePath(), e.getMessage());
                continue;
            }
            String filename = id + "." + extForMime(asset.getMimeType());
            Files.write(assetsDir.resolve(filename), bytes);
            out.put(id, "res://assets/" + filename);
        }
        return out;
    }

    /** Map a stored asset mime to the file extension the runtime decoder
     *  keys off (see scene_player.gd's buffer-decode switch). */
    private static String extForMime(String mime) {
        if (mime == null) return "bin";
        return switch (mime) {
            case "image/png" -> "png";
            case "image/jpeg" -> "jpg";
            case "image/webp" -> "webp";
            case "image/svg+xml" -> "svg";
            default -> "bin";
        };
    }

    private void copyTemplateTree(Path dst) throws IOException {
        copyClasspath("godot-template/project.godot", dst.resolve("project.godot"));
        copyClasspath("godot-template/export_presets.cfg", dst.resolve("export_presets.cfg"));
        copyClasspath("godot-template/scenes/main.tscn", dst.resolve("scenes/main.tscn"));
        copyClasspath("godot-template/scripts/scene_player.gd", dst.resolve("scripts/scene_player.gd"));
        copyClasspath("godot-template/scripts/text_box.gd", dst.resolve("scripts/text_box.gd"));
        copyClasspath("godot-template/hugmesh_bridge.gdextension", dst.resolve("hugmesh_bridge.gdextension"));
        copyBridgeBinaries(dst.resolve("bin"));
    }

    /**
     * Bake the compiled hugmesh-bridge binaries into {@code bin/} so the produced
     * WASM carries the Rust HostBridge. Missing binaries (dev / unset
     * {@code app.build.bridge-dir}) → a {@code bin/README.md} placeholder + warning;
     * the export still produces a (non-multiplayer) bundle. Mirrors the RPG renderer.
     */
    private void copyBridgeBinaries(Path binDir) throws IOException {
        Files.createDirectories(binDir);
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
                    "# bin/\n\nhugmesh-bridge binaries were unavailable at render time; "
                            + "the produced WASM loads without the HostBridge GDExtension.\n",
                    StandardCharsets.UTF_8);
        }
    }

    private static void copyClasspath(String classpathPath, Path target) throws IOException {
        Files.createDirectories(target.getParent());
        try (InputStream in = new ClassPathResource(classpathPath).getInputStream()) {
            Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }
}
