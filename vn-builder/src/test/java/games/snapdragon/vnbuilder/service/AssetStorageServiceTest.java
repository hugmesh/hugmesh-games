package games.snapdragon.vnbuilder.service;

import games.snapdragon.vnbuilder.entity.VnAsset;
import games.snapdragon.vnbuilder.entity.VnProject;
import games.snapdragon.vnbuilder.repository.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.nio.charset.StandardCharsets;
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
 * Unit tests for the VN {@link AssetStorageService} list + generated-store
 * paths. The ownership guard runs through a real {@link VnProjectService}
 * (its concrete class can't be mocked under ByteBuddy on Java 25) backed by a
 * mocked project repo, so both paths are exercised against the same 403
 * enforcement upload()/read() use. Mirrors rpg-builder AssetStorageServiceTest.
 */
class AssetStorageServiceTest {

    @TempDir Path assetsDir;
    private VnAssetRepository assets;
    private VnProjectRepository projects;
    private AssetStorageService svc;

    @BeforeEach
    void setUp() {
        assets = mock(VnAssetRepository.class);
        projects = mock(VnProjectRepository.class);
        VnProjectService projectSvc = new VnProjectService(
                projects,
                mock(VnActRepository.class),
                mock(VnCharacterRepository.class),
                mock(VnBackgroundRepository.class),
                mock(VnSceneRepository.class),
                mock(VnLineRepository.class),
                mock(VnChoiceRepository.class));
        svc = new AssetStorageService(assetsDir.toString(), assets, projectSvc);
    }

    @Test void list_returnsProjectAssetsNewestFirst() {
        UUID pid = UUID.randomUUID();
        VnProject p = project(pid, "alice");
        when(projects.findById(pid)).thenReturn(Mono.just(p));

        VnAsset newer = asset(pid, "dawn-bg", OffsetDateTime.now());
        VnAsset older = asset(pid, "hero-portrait", OffsetDateTime.now().minusHours(1));
        when(assets.findByProjectIdOrderByUploadedAtDesc(pid)).thenReturn(Flux.just(newer, older));

        StepVerifier.create(svc.list(pid, "alice"))
                .expectNext(newer)
                .expectNext(older)
                .verifyComplete();
    }

    @Test void list_rejectsForeignProjectAndNeverHitsAssetRepo() {
        UUID pid = UUID.randomUUID();
        when(projects.findById(pid)).thenReturn(Mono.just(project(pid, "bob")));

        StepVerifier.create(svc.list(pid, "alice"))
                .expectErrorMatches(err -> err instanceof ResponseStatusException rse
                        && rse.getStatusCode().value() == 403)
                .verify();

        verify(assets, never()).findByProjectIdOrderByUploadedAtDesc(any());
    }

    @Test void storeGenerated_writesBytesThroughStorageAndPersistsRow() throws Exception {
        UUID pid = UUID.randomUUID();
        VnProject p = project(pid, "alice");
        when(projects.findById(pid)).thenReturn(Mono.just(p));
        when(assets.save(any())).thenAnswer(inv -> Mono.just(inv.getArgument(0)));

        byte[] svg = "<svg xmlns=\"http://www.w3.org/2000/svg\"></svg>".getBytes(StandardCharsets.UTF_8);

        VnAsset saved = svc.storeGenerated(pid, "gen-dawn", "background", "image/svg+xml", svg, "alice").block();

        assertThat(saved).isNotNull();
        assertThat(saved.getSlug()).isEqualTo("gen-dawn");
        assertThat(saved.getKind()).isEqualTo("background");
        assertThat(saved.getMimeType()).isEqualTo("image/svg+xml");
        assertThat(saved.getByteSize()).isEqualTo(svg.length);
        assertThat(saved.getUploadedBy()).isEqualTo("alice");
        // bytes actually written through the same <assetsDir>/<pid>/<id> layout uploads use
        Path onDisk = assetsDir.resolve(pid.toString()).resolve(saved.getId().toString());
        assertThat(saved.getStoragePath()).isEqualTo(onDisk.toString());
        assertThat(Files.readAllBytes(onDisk)).isEqualTo(svg);
        verify(assets).save(any());
    }

    @Test void storeGenerated_rejectsForeignProjectAndNeverSaves() {
        UUID pid = UUID.randomUUID();
        when(projects.findById(pid)).thenReturn(Mono.just(project(pid, "bob")));

        byte[] svg = "<svg/>".getBytes(StandardCharsets.UTF_8);
        StepVerifier.create(svc.storeGenerated(pid, "gen-dawn", "background", "image/svg+xml", svg, "alice"))
                .expectErrorMatches(err -> err instanceof ResponseStatusException rse
                        && rse.getStatusCode().value() == 403)
                .verify();

        verify(assets, never()).save(any());
    }

    @Test void storeGenerated_rejectsBadMime() {
        UUID pid = UUID.randomUUID();
        StepVerifier.create(svc.storeGenerated(pid, "gen", "background", "text/plain",
                        "x".getBytes(StandardCharsets.UTF_8), "alice"))
                .expectErrorMatches(err -> err instanceof ResponseStatusException rse
                        && rse.getStatusCode().value() == 400)
                .verify();

        verify(assets, never()).save(any());
    }

    @Test void read_returnsBytesForOwnedInProjectAsset() throws Exception {
        UUID pid = UUID.randomUUID();
        when(projects.findById(pid)).thenReturn(Mono.just(project(pid, "alice")));

        UUID assetId = UUID.randomUUID();
        byte[] bytes = "portrait-bytes".getBytes(StandardCharsets.UTF_8);
        Path onDisk = Files.write(assetsDir.resolve(assetId.toString()), bytes);
        VnAsset a = asset(pid, "hero-portrait", OffsetDateTime.now());
        a.setId(assetId);
        a.setStoragePath(onDisk.toString());
        when(assets.findById(assetId)).thenReturn(Mono.just(a));

        StepVerifier.create(svc.read(pid, assetId, "alice"))
                .expectNextMatches(b -> java.util.Arrays.equals(b, bytes))
                .verifyComplete();
    }

    /**
     * IDOR guard: the caller owns project {@code pid}, but the requested asset
     * row belongs to a DIFFERENT project. Even though the caller passes the
     * project-ownership check, read() must 404 rather than serve another
     * project's bytes — and never touch the disk.
     */
    @Test void read_deniesForeignProjectAsset() throws Exception {
        UUID pid = UUID.randomUUID();
        when(projects.findById(pid)).thenReturn(Mono.just(project(pid, "alice")));

        UUID foreignProjectId = UUID.randomUUID();
        UUID assetId = UUID.randomUUID();
        // Write real bytes to prove we don't fall through to reading them.
        byte[] secret = "another-tenants-secret".getBytes(StandardCharsets.UTF_8);
        Path onDisk = Files.write(assetsDir.resolve(assetId.toString()), secret);
        VnAsset foreign = asset(foreignProjectId, "foreign-portrait", OffsetDateTime.now());
        foreign.setId(assetId);
        foreign.setStoragePath(onDisk.toString());
        when(assets.findById(assetId)).thenReturn(Mono.just(foreign));

        StepVerifier.create(svc.read(pid, assetId, "alice"))
                .expectErrorMatches(err -> err instanceof ResponseStatusException rse
                        && rse.getStatusCode().value() == 404)
                .verify();
    }

    private static VnProject project(UUID id, String owner) {
        VnProject p = new VnProject();
        p.setId(id);
        p.setOwnerCasdoorId(owner);
        return p;
    }

    private static VnAsset asset(UUID projectId, String slug, OffsetDateTime uploadedAt) {
        VnAsset a = new VnAsset();
        a.setId(UUID.randomUUID());
        a.setProjectId(projectId);
        a.setSlug(slug);
        a.setKind("background");
        a.setMimeType("image/svg+xml");
        a.setByteSize(64L);
        a.setStoragePath("/tmp/vn-assets/" + projectId + "/" + a.getId());
        a.setUploadedBy("alice");
        a.setUploadedAt(uploadedAt);
        return a;
    }
}
