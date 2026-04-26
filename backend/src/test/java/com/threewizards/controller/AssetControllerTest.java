package com.threewizards.controller;

import com.threewizards.model.GameLaunch;
import com.threewizards.repository.GameLaunchRepository;
import com.threewizards.repository.GameRepository;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.Instant;

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

    @Test
    void legacyPath403sWhenRequireLaunchUidIsOn() {
        AssetController ctl = new AssetController(launches(), games(),
                /* requireLaunchUid */ true,
                "/var/snapdragon/games");

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

        AssetController ctl = new AssetController(launchRepo, games(), false, "/var/snapdragon/games");

        StepVerifier.create(ctl.getLevelAssetByUid("dodecahedron", "bogus-uid", "index.wasm"))
                .expectErrorSatisfies(err -> {
                    assertThat(((ResponseStatusException) err).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
                })
                .verify();
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

        AssetController ctl = new AssetController(launchRepo, games(), false, "/var/snapdragon/games");

        StepVerifier.create(ctl.getLevelAssetByUid("cave", "real-uid", "index.wasm"))
                .expectErrorSatisfies(err -> {
                    assertThat(((ResponseStatusException) err).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
                })
                .verify();
    }
}
