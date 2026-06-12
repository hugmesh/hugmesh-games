package games.snapdragon.bff.controller;

import games.snapdragon.bff.controller.GameAccessController.AccessGrantRequest;
import games.snapdragon.bff.model.Game;
import games.snapdragon.bff.model.GameAccess;
import games.snapdragon.bff.repository.GameAccessRepository;
import games.snapdragon.bff.repository.GameRepository;
import games.snapdragon.bff.security.DomainRegistry;
import games.snapdragon.bff.service.DomainScopeService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Validation rules + success paths + idempotent-grant behaviour on
 * GameAccessController. Mocks the repos so the controller's flow is
 * isolated from a real R2DBC connection.
 */
class GameAccessControllerTest {

    private GameAccessRepository accessRepo;
    private GameRepository gameRepo;
    private GameAccessController ctl;

    @BeforeEach
    void setUp() {
        accessRepo = mock(GameAccessRepository.class);
        gameRepo   = mock(GameRepository.class);
        // Single-tenant registry (no tenancy config) — every caller resolves to
        // "default" and these fixtures leave game.domain null (unscoped), so the new
        // per-game domain gate is a no-op and these specs assert the unchanged flow.
        ctl        = new GameAccessController(accessRepo, gameRepo,
                new DomainScopeService(gameRepo), new DomainRegistry("", ""));
    }

    private static Game game(long id, String slug) {
        // Game is an R2DBC entity with no public setters — Spring Data
        // populates fields via reflection when reading rows. Mocking
        // would need final-method support; reflection is simpler and
        // matches how Spring Data itself constructs the row.
        try {
            Game g = new Game();
            java.lang.reflect.Field idField   = Game.class.getDeclaredField("id");
            java.lang.reflect.Field slugField = Game.class.getDeclaredField("slug");
            idField.setAccessible(true);
            slugField.setAccessible(true);
            idField.set(g, id);
            slugField.set(g, slug);
            return g;
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException(e);
        }
    }

    @Test
    void rejectsUnknownScope() {
        Mono<?> result = ctl.grant("any-slug",
                new AccessGrantRequest("everyone", null), null);
        StepVerifier.create(result)
                .expectErrorSatisfies(err -> {
                    assertThat(err).isInstanceOf(ResponseStatusException.class);
                    assertThat(err.getMessage()).contains("scope must be one of");
                })
                .verify();
    }

    @Test
    void rejectsRoleScopeWithoutValue() {
        Mono<?> result = ctl.grant("any-slug",
                new AccessGrantRequest("role", "  "), null);
        StepVerifier.create(result)
                .expectErrorSatisfies(err -> {
                    assertThat(err).isInstanceOf(ResponseStatusException.class);
                    assertThat(err.getMessage()).contains("value is required");
                })
                .verify();
    }

    @Test
    void rejectsUserScopeWithoutValue() {
        Mono<?> result = ctl.grant("any-slug",
                new AccessGrantRequest("user", null), null);
        StepVerifier.create(result)
                .expectErrorSatisfies(err -> {
                    assertThat(err).isInstanceOf(ResponseStatusException.class);
                    assertThat(err.getMessage()).contains("value is required");
                })
                .verify();
    }

    @Test
    void revokeRejectsUnknownScope() {
        Mono<?> result = ctl.revoke("any-slug", "everyone", null, null);
        StepVerifier.create(result)
                .expectErrorSatisfies(err -> {
                    assertThat(err).isInstanceOf(ResponseStatusException.class);
                    assertThat(err.getMessage()).contains("scope must be one of");
                })
                .verify();
    }

    @Test
    void grantAllPersistsRowWithNullValueAndAdminId() {
        when(gameRepo.findBySlug("cave")).thenReturn(Mono.just(game(7L, "cave")));
        when(accessRepo.save(any(GameAccess.class)))
                .thenAnswer(inv -> Mono.just(inv.getArgument(0)));

        StepVerifier.create(ctl.grant("cave", new AccessGrantRequest("all", null), null))
                .assertNext(saved -> {
                    assertThat(saved.getGameId()).isEqualTo(7L);
                    assertThat(saved.getScope()).isEqualTo("all");
                    // scope=all stores no value — non-null would be a contract bug
                    // (the unique index treats null as the canonical "everyone" row).
                    assertThat(saved.getScopeValue()).isNull();
                    // No JWT in test; the controller falls back to a sentinel
                    // 'system:unknown' so audit rows aren't lost during boot.
                    assertThat(saved.getGrantedBy()).isEqualTo("system:unknown");
                })
                .verifyComplete();
    }

    @Test
    void grantRolePersistsValue() {
        when(gameRepo.findBySlug("cave")).thenReturn(Mono.just(game(7L, "cave")));
        when(accessRepo.save(any(GameAccess.class)))
                .thenAnswer(inv -> Mono.just(inv.getArgument(0)));

        StepVerifier.create(ctl.grant("cave", new AccessGrantRequest("role", "play-cave"), null))
                .assertNext(saved -> {
                    assertThat(saved.getScope()).isEqualTo("role");
                    assertThat(saved.getScopeValue()).isEqualTo("play-cave");
                })
                .verifyComplete();
    }

    @Test
    void grant404sOnUnknownSlug() {
        when(gameRepo.findBySlug("missing")).thenReturn(Mono.empty());

        StepVerifier.create(ctl.grant("missing", new AccessGrantRequest("all", null), null))
                .expectErrorSatisfies(err -> {
                    assertThat(err).isInstanceOf(ResponseStatusException.class);
                    assertThat(((ResponseStatusException) err).getStatusCode())
                            .isEqualTo(HttpStatus.NOT_FOUND);
                    assertThat(err.getMessage()).contains("missing");
                })
                .verify();
    }

    @Test
    void grantIsIdempotentOnUniqueConstraintViolation() {
        // The unique index `uq_game_access_triple` on (game_id, scope, scope_value)
        // means re-granting the same policy throws DuplicateKeyException. The
        // controller's onErrorResume should swallow that and complete cleanly so
        // the admin UI's "save" button is idempotent (a duplicate save isn't a
        // bug, just a redundant click).
        when(gameRepo.findBySlug("cave")).thenReturn(Mono.just(game(7L, "cave")));
        when(accessRepo.save(any(GameAccess.class)))
                .thenReturn(Mono.error(new DuplicateKeyException(
                        "duplicate key value violates unique constraint \"uq_game_access_triple\"")));
        // The controller's idempotency path then queries findByGameId
        // (with the controller's existing -1L sentinel — the lookup
        // intentionally falls through to defaultIfEmpty so a duplicate
        // grant returns a sentinel GameAccess instead of erroring).
        when(accessRepo.findByGameId(-1L)).thenReturn(Flux.empty());

        StepVerifier.create(ctl.grant("cave", new AccessGrantRequest("all", null), null))
                .assertNext(result -> assertThat(result).isNotNull())
                .verifyComplete();
    }

    @Test
    void revokeAllPassesNullValueToRepo() {
        when(gameRepo.findBySlug("cave")).thenReturn(Mono.just(game(7L, "cave")));
        when(accessRepo.deletePolicy(eq(7L), eq("all"), eq(null))).thenReturn(Mono.empty());

        StepVerifier.create(ctl.revoke("cave", "all", "ignored", null)).verifyComplete();
        verify(accessRepo, times(1)).deletePolicy(7L, "all", null);
    }

    @Test
    void revokeRoleForwardsValue() {
        when(gameRepo.findBySlug("cave")).thenReturn(Mono.just(game(7L, "cave")));
        when(accessRepo.deletePolicy(eq(7L), eq("role"), eq("play-cave"))).thenReturn(Mono.empty());

        StepVerifier.create(ctl.revoke("cave", "role", "play-cave", null)).verifyComplete();
        verify(accessRepo, times(1)).deletePolicy(7L, "role", "play-cave");
    }

    @Test
    void revoke404sOnUnknownSlug() {
        when(gameRepo.findBySlug("missing")).thenReturn(Mono.empty());

        StepVerifier.create(ctl.revoke("missing", "all", null, null))
                .expectErrorSatisfies(err -> {
                    assertThat(err).isInstanceOf(ResponseStatusException.class);
                    assertThat(((ResponseStatusException) err).getStatusCode())
                            .isEqualTo(HttpStatus.NOT_FOUND);
                })
                .verify();
    }
}
