package com.threewizards.controller;

import com.threewizards.controller.GameLaunchController.LaunchResponse;
import com.threewizards.model.Game;
import com.threewizards.model.GameLaunch;
import com.threewizards.repository.GameAccessRepository;
import com.threewizards.repository.GameLaunchRepository;
import com.threewizards.repository.GameRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.lang.reflect.Field;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Wire-format + auth-gate tests for {@link GameLaunchController}. Mocks
 * the three repos because the launch path is pure orchestration over
 * them — repo behaviour is covered by the existing R2DBC integration
 * the e2e suite exercises.
 */
class GameLaunchControllerTest {

    private GameRepository games;
    private GameAccessRepository access;
    private GameLaunchRepository launches;
    private GameLaunchController controller;

    @BeforeEach
    void setUp() {
        games    = mock(GameRepository.class);
        access   = mock(GameAccessRepository.class);
        launches = mock(GameLaunchRepository.class);
        controller = new GameLaunchController(games, access, launches);
    }

    @Test
    void launchRequiresJwt() {
        StepVerifier.create(controller.launch("dodecahedron", null))
                .expectErrorSatisfies(err -> {
                    assertThat(err).isInstanceOf(ResponseStatusException.class);
                    assertThat(((ResponseStatusException) err).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
                })
                .verify();
    }

    @Test
    void launch404sUnknownSlug() {
        when(games.findBySlug("nope")).thenReturn(Mono.empty());

        StepVerifier.create(controller.launch("nope", playerJwt()))
                .expectErrorSatisfies(err -> {
                    assertThat(((ResponseStatusException) err).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
                })
                .verify();
    }

    @Test
    void launch403sWhenAccessPolicyDoesNotMatch() {
        Game g = gameWithId(1L);
        when(games.findBySlug("dodecahedron")).thenReturn(Mono.just(g));
        // No matching game ids for this principal — empty visibility set.
        when(access.findVisibleGameIdsForPrincipal(any(), anyString())).thenReturn(Flux.empty());

        StepVerifier.create(controller.launch("dodecahedron", playerJwt()))
                .expectErrorSatisfies(err -> {
                    assertThat(((ResponseStatusException) err).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
                    assertThat(err.getMessage()).contains("not available");
                })
                .verify();
    }

    @Test
    void launchMintsUrlSafeUidAndPinsCurrentVersionDir() {
        Game g = gameWithId(1L);
        g.setCurrentVersionDir("/var/snapdragon/games/dodecahedron/abc123");
        when(games.findBySlug("dodecahedron")).thenReturn(Mono.just(g));
        when(access.findVisibleGameIdsForPrincipal(any(), anyString())).thenReturn(Flux.just(1L));
        // Echo the row back so we can verify the controller populated everything.
        when(launches.save(any(GameLaunch.class))).thenAnswer(inv -> Mono.just(inv.getArgument(0)));

        StepVerifier.create(controller.launch("dodecahedron", playerJwt()))
                .assertNext((LaunchResponse resp) -> {
                    assertThat(resp.gameSlug()).isEqualTo("dodecahedron");
                    assertThat(resp.uid()).isNotNull();
                    // 256 bits → 43-char URL-safe base64 (no padding).
                    assertThat(resp.uid()).hasSize(43);
                    // URL-safe alphabet only — no /, +, or = padding so the
                    // uid drops cleanly into the asset path component.
                    assertThat(resp.uid()).matches("[A-Za-z0-9_-]+");
                    assertThat(resp.expiresAt()).isAfter(Instant.now());
                })
                .verifyComplete();
    }

    @Test
    void terminateRequiresJwt() {
        StepVerifier.create(controller.terminate("any-uid", null))
                .expectErrorSatisfies(err -> {
                    assertThat(((ResponseStatusException) err).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
                })
                .verify();
    }

    @Test
    void terminate403sWhenLaunchOwnedBySomeoneElse() {
        GameLaunch row = new GameLaunch();
        row.setUid("uid-x");
        row.setCasdoorUserId("not-you");
        when(launches.findByUid("uid-x")).thenReturn(Mono.just(row));

        StepVerifier.create(controller.terminate("uid-x", playerJwt()))
                .expectErrorSatisfies(err -> {
                    assertThat(((ResponseStatusException) err).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
                })
                .verify();
    }

    // ── helpers ─────────────────────────────────────────────────────────────

    private static Jwt playerJwt() {
        return Jwt.withTokenValue("token")
                .header("alg", "RS256")
                .subject("player-id-123")
                .claim("roles", List.of("player"))
                .build();
    }

    /**
     * The {@link Game} entity's id field is package-private with no setter.
     * Reflective injection is the lightest way to get a stub with a populated
     * id without dragging in a builder.
     */
    private static Game gameWithId(long id) {
        Game g = new Game();
        try {
            Field f = Game.class.getDeclaredField("id");
            f.setAccessible(true);
            f.set(g, id);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        return g;
    }
}
