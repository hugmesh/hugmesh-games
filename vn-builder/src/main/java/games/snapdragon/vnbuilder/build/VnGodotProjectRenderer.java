package games.snapdragon.vnbuilder.build;

import com.fasterxml.jackson.databind.ObjectMapper;
import games.snapdragon.vnbuilder.entity.*;
import games.snapdragon.vnbuilder.repository.*;
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
 * snapdragon-bridge so the resulting WASM is multiplayer-aware (the
 * built-in MultiplayerLobby + per-player view channel).
 *
 * <p>Per-Act export: each Act becomes a Chapter in the BFF, with its
 * own bundle/index.html. The runtime picks the {@code is_entry_point}
 * scene of the act on first load.
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

    public VnGodotProjectRenderer(VnActRepository acts, VnSceneRepository scenes, VnLineRepository lines,
                                  VnChoiceRepository choices, VnCharacterRepository characters,
                                  VnBackgroundRepository backgrounds) {
        this.acts = acts; this.scenes = scenes; this.lines = lines;
        this.choices = choices; this.characters = characters; this.backgrounds = backgrounds;
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

        // Per-act scenes_data.json (one per chapter), inlining the
        // act's scenes + lines + choices so the runtime can drive the
        // VN without a separate database round-trip.
        for (VnAct act : actList) {
            List<VnScene> actScenes = sceneList.stream().filter(s -> s.getActId().equals(act.getId())).toList();
            Map<String, Object> dataset = new LinkedHashMap<>();
            dataset.put("project_slug", project.getSlug());
            dataset.put("act_slug", act.getSlug());
            dataset.put("act_name", act.getName());
            dataset.put("characters", toCharactersMap(chars));
            dataset.put("backgrounds", toBackgroundsMap(bgs));
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

    private Map<String, Object> toCharactersMap(List<VnCharacter> cs) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (VnCharacter c : cs) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("display_name", c.getDisplayName());
            m.put("color", c.getColor());
            m.put("portrait_asset_id", c.getDefaultPortraitAssetId() == null ? null : c.getDefaultPortraitAssetId().toString());
            out.put(c.getId().toString(), m);
        }
        return out;
    }

    private Map<String, Object> toBackgroundsMap(List<VnBackground> bs) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (VnBackground b : bs) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("name", b.getName());
            m.put("asset_id", b.getAssetId() == null ? null : b.getAssetId().toString());
            out.put(b.getId().toString(), m);
        }
        return out;
    }

    private void copyTemplateTree(Path dst) throws IOException {
        copyClasspath("godot-template/project.godot", dst.resolve("project.godot"));
        copyClasspath("godot-template/export_presets.cfg", dst.resolve("export_presets.cfg"));
        copyClasspath("godot-template/scenes/main.tscn", dst.resolve("scenes/main.tscn"));
        copyClasspath("godot-template/scripts/scene_player.gd", dst.resolve("scripts/scene_player.gd"));
        copyClasspath("godot-template/scripts/text_box.gd", dst.resolve("scripts/text_box.gd"));
        copyClasspath("godot-template/snapdragon_bridge.gdextension", dst.resolve("snapdragon_bridge.gdextension"));
    }

    private static void copyClasspath(String classpathPath, Path target) throws IOException {
        Files.createDirectories(target.getParent());
        try (InputStream in = new ClassPathResource(classpathPath).getInputStream()) {
            Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }
}
