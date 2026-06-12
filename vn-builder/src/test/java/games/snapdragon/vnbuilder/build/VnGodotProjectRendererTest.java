package games.snapdragon.vnbuilder.build;

import games.snapdragon.vnbuilder.entity.*;
import games.snapdragon.vnbuilder.repository.*;
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
    }

    private VnGodotProjectRenderer renderer(String bridgeDir) {
        return new VnGodotProjectRenderer(acts, scenes, lines, choices, characters, backgrounds, bridgeDir);
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
}
