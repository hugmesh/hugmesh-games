package games.hugmesh.bff.controller;

import games.hugmesh.bff.model.FederationManifestDto;
import games.hugmesh.bff.model.FederationModule;
import games.hugmesh.bff.repository.FederationModuleRepository;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;

import java.lang.reflect.Field;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Manifest shape + repo-passthrough tests for the federation endpoint.
 * Repo ordering / WHERE-enabled-true filtering are R2DBC concerns
 * exercised by `make e2e`; these tests verify the controller's
 * DTO mapping and the empty-list case.
 */
class FederationControllerTest {

    @Test
    void manifestMapsEachRowToFederationRemoteDto() {
        FederationModule loginRow = moduleStub("login", "Login UI", "remote-shell",
                "/_remotes/login/remoteEntry.json", "./LoginComponent", null, "0.1.0");
        FederationModule gameRow = moduleStub("games-dodecahedron", "Dodecahedron Demo",
                "remote-game", "/_remotes/games-dodecahedron/remoteEntry.json",
                "./GameRoot", "dodecahedron", "0.1.0");

        FederationModuleRepository repo = mock(FederationModuleRepository.class);
        when(repo.findAllByEnabledTrueOrderByKindAscSlugAsc())
                .thenReturn(Flux.just(loginRow, gameRow));

        FederationController controller = new FederationController(repo);

        StepVerifier.create(controller.manifest())
                .assertNext((FederationManifestDto m) -> {
                    assertThat(m.generatedAt()).isNotNull();
                    assertThat(m.remotes()).hasSize(2);
                    var first = m.remotes().get(0);
                    assertThat(first.slug()).isEqualTo("login");
                    assertThat(first.kind()).isEqualTo("remote-shell");
                    assertThat(first.gameSlug()).isNull();
                    var second = m.remotes().get(1);
                    assertThat(second.slug()).isEqualTo("games-dodecahedron");
                    assertThat(second.gameSlug()).isEqualTo("dodecahedron");
                })
                .verifyComplete();
    }

    @Test
    void manifestReturnsEmptyRemotesListWhenNoRowsEnabled() {
        FederationModuleRepository repo = mock(FederationModuleRepository.class);
        when(repo.findAllByEnabledTrueOrderByKindAscSlugAsc()).thenReturn(Flux.empty());

        FederationController controller = new FederationController(repo);

        StepVerifier.create(controller.manifest())
                .assertNext(m -> {
                    assertThat(m.remotes()).isEmpty();
                    assertThat(m.generatedAt()).isNotNull();
                })
                .verifyComplete();
    }

    private static FederationModule moduleStub(String slug, String displayName, String kind,
                                                String remoteEntryUrl, String exposedModule,
                                                String gameSlug, String version) {
        FederationModule m = new FederationModule();
        try {
            setField(m, "slug", slug);
            setField(m, "displayName", displayName);
            setField(m, "kind", kind);
            setField(m, "remoteEntryUrl", remoteEntryUrl);
            setField(m, "exposedModule", exposedModule);
            setField(m, "gameSlug", gameSlug);
            setField(m, "version", version);
            setField(m, "enabled", true);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        return m;
    }

    private static void setField(Object target, String name, Object value) throws Exception {
        Field f = target.getClass().getDeclaredField(name);
        f.setAccessible(true);
        f.set(target, value);
    }
}
