package games.snapdragon.bff.controller;

import games.snapdragon.bff.model.Game;
import games.snapdragon.bff.model.GameLaunch;
import games.snapdragon.bff.repository.GameLaunchRepository;
import games.snapdragon.bff.repository.GameRepository;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Tests for the AssetController routing-decision tree. Filesystem +
 * classpath resolution is exercised end-to-end by `make e2e`; this
 * file targets the UID-gate logic (404 / slug-mismatch / feature
 * flag) that doesn't need the real resource layer.
 */
class AssetControllerTest {

    private GameLaunchRepository launches() { return mock(GameLaunchRepository.class); }
    private GameRepository games() { return mock(GameRepository.class); }
    private games.snapdragon.bff.upload.UploadStorage storage() {
        return new games.snapdragon.bff.upload.FilesystemUploadStorage(
                java.nio.file.Path.of("/var/snapdragon/games"));
    }
    /** 6-arg helper — keeps the older tests' constructor calls clean. */
    private AssetController controller(GameLaunchRepository launches,
                                       GameRepository games,
                                       games.snapdragon.bff.upload.UploadStorage storage,
                                       boolean requireLaunchUid) {
        return new AssetController(launches, games, storage, requireLaunchUid,
                /* presignedReadsEnabled */ false, /* presignedReadsTtlSec */ 300L);
    }

    /**
     * Build a {@link Game} with the requested slug + version dir. The
     * model class only exposes {@code setCurrentVersionDir} publicly
     * (Spring Data R2DBC sets the other fields via reflection), so the
     * test does the same.
     */
    private Game newGame(String slug, String currentVersionDir) {
        Game g = new Game();
        g.setCurrentVersionDir(currentVersionDir);
        try {
            java.lang.reflect.Field f = Game.class.getDeclaredField("slug");
            f.setAccessible(true);
            f.set(g, slug);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("Game.slug field renamed?", e);
        }
        return g;
    }

    @Test
    void legacyPath403sWhenRequireLaunchUidIsOn() {
        AssetController ctl = controller(launches(), games(), storage(),
                /* requireLaunchUid */ true);

        StepVerifier.create(ctl.getLevelAsset("dodecahedron", "index.wasm"))
                .expectErrorSatisfies(err -> {
                    assertThat(err).isInstanceOf(ResponseStatusException.class);
                    assertThat(((ResponseStatusException) err).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
                    assertThat(err.getMessage()).contains("launch-uid required");
                })
                .verify();
    }

    @Test
    void uidPath404sOnUnknownUid() {
        GameLaunchRepository launchRepo = launches();
        when(launchRepo.findByUidAndTerminatedAtIsNullAndExpiresAtAfter(anyString(), any(Instant.class)))
                .thenReturn(Mono.empty());

        AssetController ctl = controller(launchRepo, games(), storage(), false);

        StepVerifier.create(ctl.getLevelAssetByUid("dodecahedron", "bogus-uid", "index.wasm"))
                .expectErrorSatisfies(err -> {
                    assertThat(((ResponseStatusException) err).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
                })
                .verify();
    }

    @Test
    void legacyPath302sToPresignedUrlWhenEnabled() {
        // Game has a hot-uploaded version pinned to an s3:// locator;
        // the storage stub returns a presigned URL. AssetController
        // should 302 to it (decision-020 follow-up).
        Game game = newGame("dodecahedron", "s3://snapdragon-uploads/dodecahedron/abc123");
        GameRepository gameRepo = games();
        when(gameRepo.findBySlug("dodecahedron")).thenReturn(Mono.just(game));

        URI presigned = URI.create("https://example.com/signed/abc123/index.wasm?sig=xyz");
        AssetController ctl = new AssetController(launches(), gameRepo,
                new StubPresigningStorage(presigned),
                /* requireLaunchUid */ false,
                /* presignedReadsEnabled */ true,
                /* presignedReadsTtlSec */ 300L);

        StepVerifier.create(ctl.getLevelAsset("dodecahedron", "index.wasm"))
                .assertNext(resp -> {
                    assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.FOUND);
                    assertThat(resp.getHeaders().getFirst(HttpHeaders.LOCATION))
                            .isEqualTo(presigned.toString());
                    assertThat(resp.getHeaders().getFirst(HttpHeaders.CACHE_CONTROL))
                            .contains("private").contains("max-age=");
                })
                .verifyComplete();
    }

    @Test
    void legacyPathStreamsBytesWhenPresignedReadsDisabled() {
        // When the flag is OFF, the presigner is never consulted —
        // the stub records that fact via a getCalled() probe.
        Game game = newGame("dodecahedron", "s3://snapdragon-uploads/dodecahedron/abc123");
        GameRepository gameRepo = games();
        when(gameRepo.findBySlug("dodecahedron")).thenReturn(Mono.just(game));

        StubPresigningStorage stub = new StubPresigningStorage(
                URI.create("https://example.com/should-not-be-called"));
        AssetController ctl = new AssetController(launches(), gameRepo, stub,
                /* requireLaunchUid */ false,
                /* presignedReadsEnabled */ false,
                /* presignedReadsTtlSec */ 300L);

        // The streaming path needs storage.read to succeed; the stub
        // returns null → AssetController falls through to classpath.
        // We only assert presignedCallCount stays at zero.
        StepVerifier.create(ctl.getLevelAsset("dodecahedron", "index.wasm"))
                .assertNext(resp -> {
                    // Either 200 (classpath fallback found something) or
                    // 404 — neither matters here; the contract under
                    // test is "presigner was NOT consulted".
                })
                .verifyComplete();
        assertThat(stub.presignCalls).as("presigner consulted despite flag OFF").isZero();
    }

    /**
     * Storage stub that returns a configurable presigned URL and
     * counts how many times {@code presignReadUrl} is called — lets
     * the tests assert the flag actually gates the redirect path.
     */
    private static final class StubPresigningStorage implements games.snapdragon.bff.upload.UploadStorage {
        private final URI presignedUrl;
        int presignCalls = 0;
        StubPresigningStorage(URI url) { this.presignedUrl = url; }

        @Override
        public URI presignReadUrl(String locator, String relativePath, Duration ttl) {
            presignCalls++;
            return presignedUrl;
        }
        @Override public String installFromStaging(String slug, String prefix, Path stagingDir) { return "s3://bucket/" + slug + "/" + prefix; }
        @Override public Resource read(String locator, String relativePath) { return null; }
        @Override public boolean exists(String locator, String relativePath) { return true; }
        @Override public void deleteVersion(String locator) {}
        @Override public Map<String, String> contentTypeOverrides() { return Map.of(); }
        @Override public String label() { return "stub"; }
    }

    @Test
    void uidPath404sOnSlugMismatch() {
        // A valid uid for `dodecahedron` shouldn't be reusable to fetch
        // `cave` assets — that would let an attacker pivot a leaked uid
        // across games. Mismatch returns 404 (NOT 403) so an attacker
        // can't tell a uid exists.
        GameLaunch row = new GameLaunch();
        row.setUid("real-uid");
        row.setGameSlug("dodecahedron");
        row.setVersionDir(null);
        GameLaunchRepository launchRepo = launches();
        when(launchRepo.findByUidAndTerminatedAtIsNullAndExpiresAtAfter(anyString(), any(Instant.class)))
                .thenReturn(Mono.just(row));

        AssetController ctl = controller(launchRepo, games(), storage(), false);

        StepVerifier.create(ctl.getLevelAssetByUid("cave", "real-uid", "index.wasm"))
                .expectErrorSatisfies(err -> {
                    assertThat(((ResponseStatusException) err).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
                })
                .verify();
    }
}
