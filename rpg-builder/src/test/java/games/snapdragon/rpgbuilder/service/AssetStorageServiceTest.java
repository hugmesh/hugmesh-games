package games.snapdragon.rpgbuilder.service;

import games.snapdragon.rpgbuilder.entity.RpgAsset;
import games.snapdragon.rpgbuilder.entity.RpgProject;
import games.snapdragon.rpgbuilder.repository.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.OffsetDateTime;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for the asset-list path. Mirrors {@link RpgProjectServiceTest}:
 * the ownership guard runs through a real {@link RpgProjectService} (its
 * concrete class can't be mocked under ByteBuddy on Java 25) backed by a
 * mocked project repo, so list() is exercised against the same
 * 404/403 enforcement upload()/read() use. The positive case asserts the
 * project's assets come back newest-first; the negative case asserts a
 * non-owner principal is rejected 403 AND the asset repo is never touched.
 */
class AssetStorageServiceTest {

    private RpgAssetRepository assets;
    private RpgProjectRepository projects;
    private AssetStorageService svc;

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
        svc = new AssetStorageService("/tmp/rpg-assets-test", assets, projectSvc);
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
