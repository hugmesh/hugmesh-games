package games.hugmesh.rpgbuilder.service;

import games.hugmesh.rpgbuilder.entity.RpgAsset;
import games.hugmesh.rpgbuilder.entity.RpgProject;
import games.hugmesh.rpgbuilder.repository.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for the asset-list + procedural-generate paths. Mirrors
 * {@link RpgProjectServiceTest}: the ownership guard runs through a real
 * {@link RpgProjectService} (its concrete class can't be mocked under ByteBuddy
 * on Java 25) backed by a mocked project repo, so list()/generate() are
 * exercised against the same 404/403 enforcement upload()/read() use. The
 * positive list case asserts newest-first; the negative asserts a non-owner
 * principal is rejected 403 AND the asset repo is never touched. The generate
 * cases assert a real row is persisted through the SAME storage layout an
 * upload uses, and that a foreign project is rejected without writing bytes.
 */
class AssetStorageServiceTest {

    private RpgAssetRepository assets;
    private RpgProjectRepository projects;
    private AssetStorageService svc;
    @TempDir Path assetsDir;

    @BeforeEach
    void setUp() {
        assets = mock(RpgAssetRepository.class);
        projects = mock(RpgProjectRepository.class);
        RpgProjectService projectSvc = new RpgProjectService(
                projects,
                mock(RpgAreaRepository.class),
                mock(RpgRoomRepository.class),
                mock(RpgExitRepository.class),
                mock(RpgItemRepository.class),
                mock(RpgNpcRepository.class),
                mock(RpgScriptRepository.class));
        svc = new AssetStorageService(assetsDir.toString(), assets, projectSvc, new ProceduralAssetService());
    }

    @Test void list_returnsProjectAssetsNewestFirst() {
        UUID pid = UUID.randomUUID();
        RpgProject p = new RpgProject();
        p.setId(pid);
        p.setOwnerCasdoorId("alice");
        when(projects.findById(pid)).thenReturn(Mono.just(p));

        RpgAsset newer = asset(pid, "town-icon", OffsetDateTime.now());
        RpgAsset older = asset(pid, "square-portrait", OffsetDateTime.now().minusHours(1));
        when(assets.findByProjectIdOrderByUploadedAtDesc(pid)).thenReturn(Flux.just(newer, older));

        StepVerifier.create(svc.list(pid, "alice"))
                .expectNext(newer)
                .expectNext(older)
                .verifyComplete();
    }

    @Test void list_rejectsForeignProjectAndNeverHitsAssetRepo() {
        UUID pid = UUID.randomUUID();
        RpgProject p = new RpgProject();
        p.setId(pid);
        p.setOwnerCasdoorId("bob");
        when(projects.findById(pid)).thenReturn(Mono.just(p));

        StepVerifier.create(svc.list(pid, "alice"))
                .expectErrorMatches(err -> err instanceof ResponseStatusException rse
                        && rse.getStatusCode().value() == 403)
                .verify();

        verify(assets, never()).findByProjectIdOrderByUploadedAtDesc(any());
    }

    // A missing project makes the ownership guard's findById emit empty, so the
    // flatMapMany never runs: list() completes empty (same shape as read()) and
    // the asset repo is never touched. (Not a thrown 404 — empty completion.)
    @Test void list_missingProjectCompletesEmptyWithoutTouchingAssetRepo() {
        UUID pid = UUID.randomUUID();
        when(projects.findById(pid)).thenReturn(Mono.empty());

        StepVerifier.create(svc.list(pid, "alice"))
                .verifyComplete();

        verify(assets, never()).findByProjectIdOrderByUploadedAtDesc(any());
    }

    @Test void generate_persistsRowWiredThroughStorage() throws Exception {
        UUID pid = UUID.randomUUID();
        RpgProject p = new RpgProject();
        p.setId(pid);
        p.setOwnerCasdoorId("alice");
        when(projects.findById(pid)).thenReturn(Mono.just(p));
        when(assets.save(any())).thenAnswer(inv -> Mono.just(inv.getArgument(0)));

        RpgAsset saved = svc.generate(pid, "cave-floor", "tileset", "stone", "alice").block();

        assertThat(saved).isNotNull();
        assertThat(saved.getMimeType()).isEqualTo("image/svg+xml");
        assertThat(saved.getKind()).isEqualTo("tileset");
        assertThat(saved.getUploadedBy()).isEqualTo("alice");
        assertThat(saved.getByteSize()).isGreaterThan(0);
        // stored through the SAME <assetsDir>/<project-id>/<asset-id> layout an upload uses.
        Path expected = assetsDir.resolve(pid.toString()).resolve(saved.getId().toString());
        assertThat(saved.getStoragePath()).isEqualTo(expected.toString());
        assertThat(Files.exists(expected)).isTrue();
        assertThat(Files.readString(expected)).startsWith("<svg").contains("</svg>");
    }

    @Test void generate_rejectsUnsupportedKind() {
        UUID pid = UUID.randomUUID();
        StepVerifier.create(svc.generate(pid, "cave-floor", "audio", null, "alice"))
                .expectErrorMatches(err -> err instanceof ResponseStatusException rse
                        && rse.getStatusCode().value() == 400)
                .verify();
        verify(projects, never()).findById(any(UUID.class));
    }

    @Test void generate_rejectsForeignProjectAndWritesNoBytes() {
        UUID pid = UUID.randomUUID();
        RpgProject p = new RpgProject();
        p.setId(pid);
        p.setOwnerCasdoorId("bob");
        when(projects.findById(pid)).thenReturn(Mono.just(p));

        StepVerifier.create(svc.generate(pid, "cave-floor", "tileset", null, "alice"))
                .expectErrorMatches(err -> err instanceof ResponseStatusException rse
                        && rse.getStatusCode().value() == 403)
                .verify();

        verify(assets, never()).save(any());
        assertThat(Files.exists(assetsDir.resolve(pid.toString()))).isFalse();
    }

    private static RpgAsset asset(UUID projectId, String slug, OffsetDateTime uploadedAt) {
        RpgAsset a = new RpgAsset();
        a.setId(UUID.randomUUID());
        a.setProjectId(projectId);
        a.setSlug(slug);
        a.setKind("room icon");
        a.setMimeType("image/png");
        a.setByteSize(1234L);
        a.setStoragePath("/tmp/rpg-assets-test/" + projectId + "/" + a.getId());
        a.setUploadedBy("alice");
        a.setUploadedAt(uploadedAt);
        return a;
    }
}
