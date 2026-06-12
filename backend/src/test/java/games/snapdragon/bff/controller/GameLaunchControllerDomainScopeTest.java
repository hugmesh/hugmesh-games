package games.snapdragon.bff.controller;

import games.snapdragon.bff.model.Game;
import games.snapdragon.bff.model.GameLaunch;
import games.snapdragon.bff.repository.GameAccessRepository;
import games.snapdragon.bff.repository.GameLaunchRepository;
import games.snapdragon.bff.repository.GameRepository;
import games.snapdragon.bff.repository.GameSessionRepository;
import games.snapdragon.bff.security.DomainRegistry;
import games.snapdragon.bff.service.DomainScopeService;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Controller-level proof that {@link GameLaunchController} enforces caller-domain ==
 * target-game-domain on its two admin-bypass surfaces: {@code launch} (slug in hand)
 * and {@code adminRevoke} (uid → launch → game_slug → domain). A brand-X caller must
 * not launch or revoke a brand-Y game; SUPERADMIN bypasses; single-tenant is inert.
 */
class GameLaunchControllerDomainScopeTest {

    private static final String ISS_X = "https://casdoor.brandx.example";
    private static final String ISS_Y = "https://casdoor.brandy.example";

    private GameRepository games;
    private GameAccessRepository access;
    private GameLaunchRepository launches;

    private GameLaunchController controllerFor(DomainRegistry registry) {
        games = mock(GameRepository.class);
        access = mock(GameAccessRepository.class);
        launches = mock(GameLaunchRepository.class);
        lenient().when(launches.save(any())).thenAnswer(inv -> Mono.just(inv.getArgument(0)));
        DomainScopeService scope = new DomainScopeService(games, mock(GameSessionRepository.class));
        return new GameLaunchController(games, access, launches, scope, registry);
    }

    private static DomainRegistry multiBrand() {
        return new DomainRegistry("", ISS_X + "=brandx," + ISS_Y + "=brandy");
    }

    /** A game with the given domain + a populated id (so isVisible can match). */
    private Game stubGame(String slug, String domain, long id) {
        Game g = new Game();
        g.setDomain(domain);
        try {
            var f = Game.class.getDeclaredField("id");
            f.setAccessible(true);
            f.set(g, id);
        } catch (ReflectiveOperationException e) { throw new RuntimeException(e); }
        when(games.findBySlug(eq(slug))).thenReturn(Mono.just(g));
        return g;
    }

    private GameLaunch stubLaunch(String uid, String gameSlug) {
        GameLaunch row = new GameLaunch();
        row.setUid(uid);
        row.setGameSlug(gameSlug);
        when(launches.findByUid(eq(uid))).thenReturn(Mono.just(row));
        return row;
    }

    private static Jwt admin(String issuer) {
        return jwt(issuer, "admin-x", List.of("admin"));
    }

    private static Jwt superadmin(String issuer) {
        return jwt(issuer, "root", List.of("admin", "superadmin"));
    }

    private static Jwt player(String issuer, String sub) {
        return jwt(issuer, sub, List.of("player"));
    }

    private static Jwt jwt(String issuer, String sub, List<String> roles) {
        return Jwt.withTokenValue("t").header("alg", "RS256")
                .issuer(issuer).subject(sub)
                .issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(300))
                .claim("roles", roles).build();
    }

    private static void assertForbidden(org.reactivestreams.Publisher<?> p) {
        StepVerifier.create(p)
                .expectErrorSatisfies(err -> {
                    assertThat(err).isInstanceOf(ResponseStatusException.class);
                    assertThat(((ResponseStatusException) err).getStatusCode().value()).isEqualTo(403);
                })
                .verify();
    }

    // ===================================================================
    // launch — slug in hand
    // ===================================================================
    @Nested
    class Launch {

        @Test
        void deniesForeignBrandBeforeVisibilityCheck() {
            GameLaunchController c = controllerFor(multiBrand());
            stubGame("gy", "brandy", 9L);
            assertForbidden(c.launch("gy", admin(ISS_X)));
            verify(access, never()).findVisibleGameIdsForPrincipal(any(), any());  // gate fired first
            verify(launches, never()).save(any());
        }

        @Test
        void allowsOwnBrand() {
            GameLaunchController c = controllerFor(multiBrand());
            stubGame("gx", "brandx", 1L);
            when(access.findVisibleGameIdsForPrincipal(any(), any())).thenReturn(Flux.just(1L));
            StepVerifier.create(c.launch("gx", admin(ISS_X)))
                    .assertNext(resp -> assertThat(resp.gameSlug()).isEqualTo("gx"))
                    .verifyComplete();
            verify(launches).save(any());
        }
    }

    // ===================================================================
    // adminRevoke — uid → launch → game_slug → domain
    // ===================================================================
    @Nested
    class AdminRevoke {

        @Test
        void deniesForeignBrandLaunch() {
            GameLaunchController c = controllerFor(multiBrand());
            stubLaunch("uid-y", "gy");
            stubGame("gy", "brandy", 9L);
            assertForbidden(c.adminRevoke("uid-y", admin(ISS_X)));
            verify(launches, never()).save(any());
        }

        @Test
        void allowsOwnBrandLaunch() {
            GameLaunchController c = controllerFor(multiBrand());
            stubLaunch("uid-x", "gx");
            stubGame("gx", "brandx", 1L);
            StepVerifier.create(c.adminRevoke("uid-x", admin(ISS_X))).verifyComplete();
            verify(launches).save(any());                       // terminated_at stamped
        }

        @Test
        void superadminRevokesAnyBrandsLaunch() {
            GameLaunchController c = controllerFor(multiBrand());
            stubLaunch("uid-y", "gy");
            // game NOT stubbed — the superadmin bypass must not resolve it.
            StepVerifier.create(c.adminRevoke("uid-y", superadmin(ISS_X))).verifyComplete();
            verify(launches).save(any());
        }
    }

    // ===================================================================
    // SINGLE-TENANT — default registry + 'default' game: gate inert
    // ===================================================================
    @Nested
    class SingleTenant {

        @Test
        void defaultGameLaunchIsAllowed() {
            GameLaunchController c = controllerFor(new DomainRegistry("", ""));
            stubGame("g", "default", 1L);
            when(access.findVisibleGameIdsForPrincipal(any(), any())).thenReturn(Flux.just(1L));
            StepVerifier.create(c.launch("g", admin(ISS_X)))
                    .assertNext(resp -> assertThat(resp.gameSlug()).isEqualTo("g"))
                    .verifyComplete();
        }
    }

    // ===================================================================
    // activeLaunches — admin dashboard is brand-FILTERED (result-set scope)
    // ===================================================================
    @Nested
    class ActiveLaunches {

        private GameLaunch launch(String uid, String gameSlug) {
            GameLaunch l = new GameLaunch();
            l.setUid(uid);
            l.setGameSlug(gameSlug);
            return l;
        }

        @Test
        void filtersToCallerBrandPlusGlobalDropsForeign() {
            GameLaunchController c = controllerFor(multiBrand());
            when(launches.findByTerminatedAtIsNullOrderByStartedAtDesc())
                    .thenReturn(Flux.just(launch("lx", "gx"), launch("ly", "gy"), launch("lg", "gg")));
            stubGame("gx", "brandx", 1L);
            stubGame("gy", "brandy", 2L);
            stubGame("gg", "default", 3L);

            // brand-X admin: keep gx (own brand) + gg (global), drop gy (foreign).
            StepVerifier.create(c.activeLaunches(admin(ISS_X)))
                    .expectNextMatches(l -> l.getGameSlug().equals("gx"))
                    .expectNextMatches(l -> l.getGameSlug().equals("gg"))
                    .verifyComplete();
        }

        @Test
        void superadminSeesAllLaunches() {
            GameLaunchController c = controllerFor(multiBrand());
            when(launches.findByTerminatedAtIsNullOrderByStartedAtDesc())
                    .thenReturn(Flux.just(launch("lx", "gx"), launch("ly", "gy")));
            // games NOT stubbed — superadmin bypass must not need to resolve them.
            StepVerifier.create(c.activeLaunches(superadmin(ISS_X))).expectNextCount(2).verifyComplete();
        }
    }

    // ===================================================================
    // terminate() — self-scoped by casdoor id, NO brand gate (contract point 5)
    // ===================================================================
    @Nested
    class Terminate {

        @Test
        void ownerSelfTerminatesAcrossBrandsWithNoBrandGate() {
            GameLaunchController c = controllerFor(multiBrand());
            // a brandX player's OWN launch, but on a brandY game — a brand gate (if it
            // existed) would 403 this. terminate() must NOT gate: ownership-only.
            GameLaunch row = stubLaunch("uid-self", "gy");
            row.setCasdoorUserId("player-x");
            // game NOT stubbed — terminate resolves no game (proves it doesn't brand-check).
            StepVerifier.create(c.terminate("uid-self", player(ISS_X, "player-x"))).verifyComplete();
            verify(launches).save(any());                       // terminated_at stamped
        }

        @Test
        void nonOwnerIsDeniedOnOwnershipNotBrand() {
            GameLaunchController c = controllerFor(multiBrand());
            GameLaunch row = stubLaunch("uid-other", "gx");
            row.setCasdoorUserId("someone-else");
            // a brandX player whose subject != the launch owner — 403 on OWNERSHIP,
            // and never reaches a brand check (game not stubbed → no findBySlug needed).
            assertForbidden(c.terminate("uid-other", player(ISS_X, "player-x")));
            verify(launches, never()).save(any());
        }
    }
}
