package games.snapdragon.vnbuilder.build;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import games.snapdragon.vnbuilder.entity.*;
import games.snapdragon.vnbuilder.repository.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Specs for {@link VnGodotProjectRenderer} — proves the emitted VN Godot project is
 * structurally complete (interpolated project.godot, the snapdragon-bridge baked into
 * bin/ so the WASM is multiplayer-aware) and that the no-acts / no-scenes guard rails
 * fire. Mirrors {@code RpgGodotProjectRendererTest}.
 */
class VnGodotProjectRendererTest {

    private final VnActRepository acts = mock(VnActRepository.class);
    private final VnSceneRepository scenes = mock(VnSceneRepository.class);
    private final VnLineRepository lines = mock(VnLineRepository.class);
    private final VnChoiceRepository choices = mock(VnChoiceRepository.class);
    private final VnCharacterRepository characters = mock(VnCharacterRepository.class);
    private final VnBackgroundRepository backgrounds = mock(VnBackgroundRepository.class);
    private final VnAssetRepository assets = mock(VnAssetRepository.class);
    private static final ObjectMapper JSON = new ObjectMapper();

    private static VnProject project() {
        VnProject p = new VnProject();
        p.setId(UUID.randomUUID());
        p.setSlug("flatland-vn");
        p.setDisplayName("Flatland VN");
        p.setDescription("Romance of many dimensions");
        return p;
    }

    private static VnAct act(String slug) {
        VnAct a = new VnAct();
        a.setId(UUID.randomUUID());
        a.setSlug(slug);
        a.setName(slug);
        return a;
    }

    private static VnScene scene(UUID actId, String slug) {
        VnScene s = new VnScene();
        s.setId(UUID.randomUUID());
        s.setActId(actId);
        s.setSlug(slug);
        s.setName(slug);
        return s;
    }

    private void stub(List<VnAct> actList, List<VnScene> sceneList) {
        when(acts.findByProjectIdOrderBySortOrderAscNameAsc(any())).thenReturn(Flux.fromIterable(actList));
        when(scenes.findByProjectIdOrderBySortOrderAscNameAsc(any())).thenReturn(Flux.fromIterable(sceneList));
        when(characters.findByProjectIdOrderByDisplayNameAsc(any())).thenReturn(Flux.empty());
        when(backgrounds.findByProjectIdOrderByNameAsc(any())).thenReturn(Flux.empty());
        when(lines.findBySceneIdOrderByLineIndexAsc(any())).thenReturn(Flux.empty());
        when(choices.findByLineIdOrderBySortOrderAsc(any())).thenReturn(Flux.empty());
        when(assets.findById(any(UUID.class))).thenReturn(Mono.empty());
    }

    private VnGodotProjectRenderer renderer(String bridgeDir) {
        return new VnGodotProjectRenderer(acts, scenes, lines, choices, characters, backgrounds, assets, bridgeDir);
    }

    @Test
    void rendersCompleteTreeWithBridgePlaceholder(@TempDir Path work) throws IOException {
        VnProject p = project();
        VnAct act1 = act("act1");
        stub(List.of(act1), List.of(scene(act1.getId(), "opening")));

        StepVerifier.create(renderer("").render(p, work))
                .assertNext(r -> assertThat(r.orderedActs()).hasSize(1))
                .verifyComplete();

        Path root = work.resolve("godot-src");
        assertThat(Files.readString(root.resolve("project.godot")))
                .contains("Flatland VN").doesNotContain("{{displayName}}");
        assertThat(Files.exists(root.resolve("snapdragon_bridge.gdextension"))).isTrue();
        assertThat(Files.exists(root.resolve("bin/README.md"))).isTrue();
    }

    @Test
    void bakesBridgeBinaryWhenBridgeDirProvided(@TempDir Path work, @TempDir Path bridge) throws IOException {
        Files.writeString(bridge.resolve("snapdragon_bridge.wasm"), "\0fake-wasm");
        VnProject p = project();
        VnAct act1 = act("act1");
        stub(List.of(act1), List.of(scene(act1.getId(), "opening")));

        renderer(bridge.toString()).render(p, work).block();

        Path bin = work.resolve("godot-src/bin");
        assertThat(Files.exists(bin.resolve("snapdragon_bridge.wasm"))).isTrue();
        assertThat(Files.exists(bin.resolve("README.md"))).isFalse();
    }

    @Test
    void failsWhenProjectHasNoActs(@TempDir Path work) {
        stub(List.of(), List.of());
        StepVerifier.create(renderer("").render(project(), work))
                .expectErrorSatisfies(e -> assertThat(e).hasMessageContaining("no acts"))
                .verify();
    }

    @Test
    void failsWhenProjectHasNoScenes(@TempDir Path work) {
        stub(List.of(act("act1")), List.of());
        StepVerifier.create(renderer("").render(project(), work))
                .expectErrorSatisfies(e -> assertThat(e).hasMessageContaining("no scenes"))
                .verify();
    }

    /**
     * The core of gap (1): the renderer must copy the ACTUAL asset bytes for a
     * referenced background + portrait into the staged tree at a stable res://
     * path AND write that resolvable path (not just the UUID) into
     * scenes_data.json so the runtime can load the texture.
     */
    @Test
    void materializesReferencedAssetsAndWritesResolvablePaths(@TempDir Path work, @TempDir Path store) throws IOException {
        VnProject p = project();
        VnAct act1 = act("act1");

        // Background asset (svg) + portrait asset (png), each with real bytes
        // on disk that the renderer reads via the row's storage_path.
        UUID bgAssetId = UUID.randomUUID();
        byte[] bgBytes = "<svg>background</svg>".getBytes(StandardCharsets.UTF_8);
        Path bgFile = store.resolve(bgAssetId.toString());
        Files.write(bgFile, bgBytes);
        VnAsset bgAsset = asset(bgAssetId, p.getId(), "image/svg+xml", bgFile.toString());

        UUID portraitAssetId = UUID.randomUUID();
        byte[] portraitBytes = new byte[]{(byte) 0x89, 'P', 'N', 'G', 1, 2, 3, 4};
        Path portraitFile = store.resolve(portraitAssetId.toString());
        Files.write(portraitFile, portraitBytes);
        VnAsset portraitAsset = asset(portraitAssetId, p.getId(), "image/png", portraitFile.toString());

        VnBackground bg = new VnBackground();
        bg.setId(UUID.randomUUID());
        bg.setSlug("dawn-bg"); bg.setName("Dawn"); bg.setAssetId(bgAssetId);

        VnCharacter hero = new VnCharacter();
        hero.setId(UUID.randomUUID());
        hero.setSlug("hero"); hero.setDisplayName("Hero"); hero.setColor("#aabbcc");
        hero.setDefaultPortraitAssetId(portraitAssetId);

        VnScene opening = scene(act1.getId(), "opening");
        opening.setBackgroundId(bg.getId());
        opening.setEntryPoint(true);

        stub(List.of(act1), List.of(opening));
        when(characters.findByProjectIdOrderByDisplayNameAsc(any())).thenReturn(Flux.just(hero));
        when(backgrounds.findByProjectIdOrderByNameAsc(any())).thenReturn(Flux.just(bg));
        when(assets.findById(bgAssetId)).thenReturn(Mono.just(bgAsset));
        when(assets.findById(portraitAssetId)).thenReturn(Mono.just(portraitAsset));

        renderer("").render(p, work).block();

        Path root = work.resolve("godot-src");

        // (a) bytes actually landed at the expected stable res:// path
        Path stagedBg = root.resolve("assets").resolve(bgAssetId + ".svg");
        Path stagedPortrait = root.resolve("assets").resolve(portraitAssetId + ".png");
        assertThat(Files.readAllBytes(stagedBg)).isEqualTo(bgBytes);
        assertThat(Files.readAllBytes(stagedPortrait)).isEqualTo(portraitBytes);

        // (b) scenes_data.json carries the RESOLVABLE res:// path, not just the UUID
        JsonNode data = JSON.readTree(root.resolve("acts/act1/scenes_data.json").toFile());
        JsonNode bgNode = data.get("backgrounds").get(bg.getId().toString());
        assertThat(bgNode.get("asset_id").asText()).isEqualTo(bgAssetId.toString());
        assertThat(bgNode.get("asset_path").asText()).isEqualTo("res://assets/" + bgAssetId + ".svg");

        JsonNode charNode = data.get("characters").get(hero.getId().toString());
        assertThat(charNode.get("portrait_asset_id").asText()).isEqualTo(portraitAssetId.toString());
        assertThat(charNode.get("portrait_path").asText()).isEqualTo("res://assets/" + portraitAssetId + ".png");
    }

    /**
     * Graceful degradation for gap (1): a background that references an asset
     * whose row/bytes are gone must NOT crash the render — the tree stages with
     * a null asset_path and no assets/ file for that id.
     */
    @Test
    void missingReferencedAssetDegradesGracefully(@TempDir Path work) throws IOException {
        VnProject p = project();
        VnAct act1 = act("act1");
        UUID danglingId = UUID.randomUUID();

        VnBackground bg = new VnBackground();
        bg.setId(UUID.randomUUID());
        bg.setSlug("ghost-bg"); bg.setName("Ghost"); bg.setAssetId(danglingId);

        VnScene opening = scene(act1.getId(), "opening");
        opening.setBackgroundId(bg.getId());
        opening.setEntryPoint(true);

        stub(List.of(act1), List.of(opening));
        when(backgrounds.findByProjectIdOrderByNameAsc(any())).thenReturn(Flux.just(bg));
        when(assets.findById(danglingId)).thenReturn(Mono.empty()); // no row

        renderer("").render(p, work).block();

        Path root = work.resolve("godot-src");
        assertThat(Files.exists(root.resolve("assets").resolve(danglingId + ".svg"))).isFalse();

        JsonNode data = JSON.readTree(root.resolve("acts/act1/scenes_data.json").toFile());
        JsonNode bgNode = data.get("backgrounds").get(bg.getId().toString());
        assertThat(bgNode.get("asset_id").asText()).isEqualTo(danglingId.toString());
        assertThat(bgNode.get("asset_path").isNull()).isTrue();
    }

    /**
     * IDOR guard for gap (1): a background/character that references an asset
     * UUID owned by ANOTHER project must NOT have that foreign project's bytes
     * copied into this bundle. The renderer treats a project-scope mismatch
     * exactly like a missing row — no assets/ file, null asset_path — so a
     * crafted cross-project reference leaks nothing.
     */
    @Test
    void foreignProjectAssetIsNotMaterialized(@TempDir Path work, @TempDir Path store) throws IOException {
        VnProject p = project();
        VnAct act1 = act("act1");

        // An asset that physically exists on disk but is owned by a DIFFERENT project.
        UUID foreignAssetId = UUID.randomUUID();
        UUID foreignProjectId = UUID.randomUUID();
        byte[] secretBytes = "<svg>another-tenant-secret</svg>".getBytes(StandardCharsets.UTF_8);
        Path foreignFile = store.resolve(foreignAssetId.toString());
        Files.write(foreignFile, secretBytes);
        VnAsset foreignAsset = asset(foreignAssetId, foreignProjectId, "image/svg+xml", foreignFile.toString());

        // This project's background references the foreign asset's UUID.
        VnBackground bg = new VnBackground();
        bg.setId(UUID.randomUUID());
        bg.setSlug("stolen-bg"); bg.setName("Stolen"); bg.setAssetId(foreignAssetId);

        VnScene opening = scene(act1.getId(), "opening");
        opening.setBackgroundId(bg.getId());
        opening.setEntryPoint(true);

        stub(List.of(act1), List.of(opening));
        when(backgrounds.findByProjectIdOrderByNameAsc(any())).thenReturn(Flux.just(bg));
        when(assets.findById(foreignAssetId)).thenReturn(Mono.just(foreignAsset));

        renderer("").render(p, work).block();

        Path root = work.resolve("godot-src");

        // (a) the foreign bytes were NOT staged into this bundle under any extension
        assertThat(Files.exists(root.resolve("assets").resolve(foreignAssetId + ".svg"))).isFalse();

        // (b) scenes_data.json carries no resolvable path for the foreign asset
        JsonNode data = JSON.readTree(root.resolve("acts/act1/scenes_data.json").toFile());
        JsonNode bgNode = data.get("backgrounds").get(bg.getId().toString());
        assertThat(bgNode.get("asset_id").asText()).isEqualTo(foreignAssetId.toString());
        assertThat(bgNode.get("asset_path").isNull()).isTrue();
    }

    private static VnAsset asset(UUID id, UUID projectId, String mime, String storagePath) {
        VnAsset a = new VnAsset();
        a.setId(id);
        a.setProjectId(projectId);
        a.setSlug("asset-" + id);
        a.setKind(mime.contains("svg") ? "background" : "portrait");
        a.setMimeType(mime);
        a.setStoragePath(storagePath);
        return a;
    }
}
