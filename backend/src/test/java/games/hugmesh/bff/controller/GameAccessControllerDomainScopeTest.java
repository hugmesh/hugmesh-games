package games.hugmesh.bff.controller;

import games.hugmesh.bff.model.Game;
import games.hugmesh.bff.model.GameAccess;
import games.hugmesh.bff.repository.GameAccessRepository;
import games.hugmesh.bff.repository.GameRepository;
import games.hugmesh.bff.repository.GameSessionRepository;
import games.hugmesh.bff.security.DomainRegistry;
import games.hugmesh.bff.service.DomainScopeService;
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
 * Controller-level proof that {@link GameAccessController} enforces caller-domain ==
 * target-game-domain (the multi-tenancy merger). A brand-X admin must not read,
 * grant, or revoke access on a brand-Y game; SUPERADMIN bypasses; and on the
 * single-Casdoor cluster (default registry) the gate is inert.
 *
 * <p>POJO test in the repo idiom: the controller is {@code new}-ed with Mockito repos
 * + a real {@link DomainScopeService} (over the same mock {@code GameRepository}) +
 * a multi-brand {@link DomainRegistry}; JWTs are hand-built with an {@code iss} that
 * the registry maps to a brand. {@code @PreAuthorize} is not exercised here (it gates
 * the role at runtime, orthogonal to the per-game domain gate under test).
 */
class GameAccessControllerDomainScopeTest {

    private static final String ISS_X = "https://casdoor.brandx.example";
    private static final String ISS_Y = "https://casdoor.brandy.example";

    private GameRepository gameRepo;
    private GameAccessRepository accessRepo;

    private GameAccessController controllerFor(DomainRegistry registry) {
        gameRepo = mock(GameRepository.class);
        accessRepo = mock(GameAccessRepository.class);
        lenient().when(accessRepo.findByGameId(any())).thenReturn(Flux.just(new GameAccess()));
        lenient().when(accessRepo.save(any())).thenAnswer(inv -> Mono.just(inv.getArgument(0)));
        lenient().when(accessRepo.deletePolicy(any(), any(), any())).thenReturn(Mono.empty());
        DomainScopeService scope = new DomainScopeService(gameRepo, mock(GameSessionRepository.class));
        return new GameAccessController(accessRepo, gameRepo, scope, registry);
    }

    private static DomainRegistry multiBrand() {
        return new DomainRegistry("", ISS_X + "=brandx," + ISS_Y + "=brandy");
    }

    private void stubGame(String slug, String domain) {
        Game g = new Game();
        g.setDomain(domain);
        when(gameRepo.findBySlug(eq(slug))).thenReturn(Mono.just(g));
    }

    private static Jwt admin(String issuer, String sub) {
        return Jwt.withTokenValue("t").header("alg", "RS256")
                .issuer(issuer).subject(sub)
                .issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(300))
                .claim("roles", List.of("admin")).build();
    }

    private static Jwt superadmin(String issuer, String sub) {
        return Jwt.withTokenValue("t").header("alg", "RS256")
                .issuer(issuer).subject(sub)
                .issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(300))
                .claim("roles", List.of("admin", "superadmin")).build();
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
    // CROSS-USER — brand-X admin vs brand-X and brand-Y games (ALLOW+DENY)
    // ===================================================================
    @Nested
    class CrossUserIsolation {

        @Test
        void listAllowsOwnBrandDeniesForeignBrand() {
            GameAccessController c = controllerFor(multiBrand());
            Jwt brandxAdmin = admin(ISS_X, "admin-x");

            stubGame("gx", "brandx");
            StepVerifier.create(c.list("gx", brandxAdmin)).expectNextCount(1).verifyComplete();

            stubGame("gy", "brandy");
            assertForbidden(c.list("gy", brandxAdmin));
        }

        @Test
        void grantDeniesForeignBrandAndNeverSaves() {
            GameAccessController c = controllerFor(multiBrand());
            stubGame("gy", "brandy");

            assertForbidden(c.grant("gy",
                    new GameAccessController.AccessGrantRequest("all", null),
                    admin(ISS_X, "admin-x")));
            verify(accessRepo, never()).save(any());
        }

        @Test
        void grantAllowsOwnBrand() {
            GameAccessController c = controllerFor(multiBrand());
            stubGame("gx", "brandx");
            StepVerifier.create(c.grant("gx",
                            new GameAccessController.AccessGrantRequest("all", null),
                            admin(ISS_X, "admin-x")))
                    .expectNextCount(1).verifyComplete();
            verify(accessRepo).save(any());
        }

        @Test
        void revokeDeniesForeignBrandAndNeverDeletes() {
            GameAccessController c = controllerFor(multiBrand());
            stubGame("gy", "brandy");

            assertForbidden(c.revoke("gy", "all", null, admin(ISS_X, "admin-x")));
            verify(accessRepo, never()).deletePolicy(any(), any(), any());
        }
    }

    // ===================================================================
    // MULTIUSER-SITUATIONAL — SUPERADMIN cross-domain bypass
    // ===================================================================
    @Nested
    class MultiUserSituational {

        @Test
        void superadminManagesAnyBrandsGame() {
            GameAccessController c = controllerFor(multiBrand());
            stubGame("gy", "brandy");
            Jwt root = superadmin(ISS_X, "root");

            StepVerifier.create(c.list("gy", root)).expectNextCount(1).verifyComplete();
            StepVerifier.create(c.grant("gy",
                            new GameAccessController.AccessGrantRequest("all", null), root))
                    .expectNextCount(1).verifyComplete();
            StepVerifier.create(c.revoke("gy", "all", null, root)).verifyComplete();
        }
    }

    // ===================================================================
    // SINGLE-TENANT — default registry + 'default' games: gate inert
    // ===================================================================
    @Nested
    class SingleTenant {

        @Test
        void defaultRegistryAndDefaultGameAllowsEveryAdmin() {
            // No tenancy config => every token resolves to "default"; every game is
            // 'default'. The gate must never fire — byte-for-byte the pre-merger flow.
            GameAccessController c = controllerFor(new DomainRegistry("", ""));
            stubGame("g", "default");
            Jwt anyAdmin = admin(ISS_X, "admin"); // issuer irrelevant in single-tenant

            StepVerifier.create(c.list("g", anyAdmin)).expectNextCount(1).verifyComplete();
            StepVerifier.create(c.grant("g",
                            new GameAccessController.AccessGrantRequest("all", null), anyAdmin))
                    .expectNextCount(1).verifyComplete();
            StepVerifier.create(c.revoke("g", "all", null, anyAdmin)).verifyComplete();
        }
    }

    // ===================================================================
    // scope='role' values are stored and revoked in Roles.canonical form
    // ===================================================================
    @Nested
    class RoleScopeCanonicalisation {

        @Test
        void grantStoresRoleValueCanonicalised() {
            // The mesh's A4 free-text fallback invites "ROLE_GAMES" (the systems Casdoor
            // spelling); the visibility query compares scope_value to Roles.fromJwt's
            // canonical names, so the row must persist as "games".
            GameAccessController c = controllerFor(new DomainRegistry("", ""));
            stubGame("g", "default");
            StepVerifier.create(c.grant("g",
                            new GameAccessController.AccessGrantRequest("role", " ROLE_Games "),
                            admin(ISS_X, "admin")))
                    .assertNext(row -> {
                        assertThat(row.getScope()).isEqualTo("role");
                        assertThat(row.getScopeValue()).isEqualTo("games");
                    })
                    .verifyComplete();
        }

        @Test
        void grantKeepsUserValueVerbatim() {
            // 'user' values are Casdoor subject ids, never role names: untouched.
            GameAccessController c = controllerFor(new DomainRegistry("", ""));
            stubGame("g", "default");
            StepVerifier.create(c.grant("g",
                            new GameAccessController.AccessGrantRequest("user", "ROLE_Alice-01"),
                            admin(ISS_X, "admin")))
                    .assertNext(row -> assertThat(row.getScopeValue()).isEqualTo("ROLE_Alice-01"))
                    .verifyComplete();
        }

        @Test
        void revokeAddressesTheCanonicalRoleValue() {
            // Write and delete must agree: ?scope=role&value=ROLE_GAMES deletes the
            // stored "games" row, not a never-existing "ROLE_GAMES" one.
            GameAccessController c = controllerFor(new DomainRegistry("", ""));
            stubGame("g", "default");
            StepVerifier.create(c.revoke("g", "role", "ROLE_GAMES", admin(ISS_X, "admin"))).verifyComplete();
            verify(accessRepo).deletePolicy(any(), eq("role"), eq("games"));
            StepVerifier.create(c.revoke("g", "user", "ROLE_Alice-01", admin(ISS_X, "admin"))).verifyComplete();
            verify(accessRepo).deletePolicy(any(), eq("user"), eq("ROLE_Alice-01"));
        }
    }
}
