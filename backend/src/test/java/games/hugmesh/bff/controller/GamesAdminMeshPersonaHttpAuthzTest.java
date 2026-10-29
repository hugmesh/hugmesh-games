package games.hugmesh.bff.controller;

import games.hugmesh.bff.config.BanGateFilter;
import games.hugmesh.bff.config.DomainGuardFilter;
import games.hugmesh.bff.config.RateLimitFilter;
import games.hugmesh.bff.config.SecurityConfig;
import games.hugmesh.bff.config.SecurityHeadersFilter;
import games.hugmesh.bff.controller.support.SecuritySliceSupport;
import games.hugmesh.bff.repository.GameAccessRepository;
import games.hugmesh.bff.repository.GameLaunchRepository;
import games.hugmesh.bff.repository.GameRepository;
import games.hugmesh.bff.repository.GameSessionRepository;
import games.hugmesh.bff.repository.PlayerCharacterRepository;
import games.hugmesh.bff.security.GamesAdminPolicy;
import games.hugmesh.bff.service.DebugModeService;
import games.hugmesh.bff.service.DomainScopeService;
import games.hugmesh.bff.service.GameSessionService;
import games.hugmesh.bff.service.SessionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webflux.test.autoconfigure.WebFluxTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan.Filter;
import org.springframework.context.annotation.FilterType;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.reactive.server.WebTestClient;

import games.hugmesh.bff.model.GameSessionDto;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static games.hugmesh.bff.controller.GamesAdminPersonaMatrix.allForbidden;
import static games.hugmesh.bff.controller.GamesAdminPersonaMatrix.noneRefused;
import static games.hugmesh.bff.controller.GamesAdminPersonaMatrix.statuses;
import static games.hugmesh.bff.controller.support.SecuritySliceSupport.meshPersona;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.reactive.server.SecurityMockServerConfigurers.csrf;

/**
 * D1 of the 2026-10-29 live browser proof, on a MESH-mounted BFF
 * ({@code casdoor.organization=hugmesh}, the systems Casdoor, as
 * hugmesh-generator tests/stack/compose.games.yaml runs it).
 *
 * <p>Measured live: e2e-adminonly (ROLE_ADMIN + ROLE_USER, no ROLE_GAMES) got
 * 200 with real data on /games/api/admin/stats, /sessions, /roles,
 * /multiplayer-sessions, /debug-mode and GET /games/api/games/launches, and its
 * /games/api/admin/role-grants reached the BFF (404). The gateway forwards
 * ROLE_ADMIN past route-access, so this BFF has to refuse. The design
 * (hugmesh-generator tests/e2e/specs/rbac-roles.ts, 12-rbac-gateway-route-access
 * .spec.ts) says only the derived ROLE_GAMES_ADMIN reaches the games admin
 * surface; e2e-superadmin (ROLE_SUPERADMIN alone) has expectGamesAdmin false.
 *
 * <p>Authorities come from the PRODUCTION converter
 * ({@link SecuritySliceSupport#meshPersona}), and the controllers' raw-JWT
 * checks use the policy bean SecurityConfig builds from the same property.
 */
@WebFluxTest(controllers = {AdminController.class, DebugModeController.class,
                GameLaunchController.class, RoleProxyController.class,
                MultiplayerSessionController.class},
        properties = {"casdoor.organization=hugmesh",
                "casdoor.endpoint=http://127.0.0.1:9",
                "casdoor.client-id=test", "casdoor.client-secret=test"},
        excludeFilters = @Filter(type = FilterType.ASSIGNABLE_TYPE, classes = {
                BanGateFilter.class, DomainGuardFilter.class,
                RateLimitFilter.class, SecurityHeadersFilter.class}))
@Import({SecurityConfig.class, SecuritySliceSupport.TenantBeans.class,
        GamesAdminMeshPersonaHttpAuthzTest.Beans.class})
class GamesAdminMeshPersonaHttpAuthzTest {

    @Autowired WebTestClient web;
    @Autowired GamesAdminPolicy policy;

    @MockitoBean PlayerCharacterRepository characters;
    @MockitoBean SessionService sessions;
    @MockitoBean GameSessionService multiplayer;
    @MockitoBean DebugModeService debug;
    @MockitoBean GameRepository games;
    @MockitoBean GameAccessRepository access;
    @MockitoBean GameLaunchRepository launches;
    @MockitoBean GameSessionRepository sessionRepo;

    @TestConfiguration(proxyBeanMethods = false)
    static class Beans {
        @Bean DomainScopeService domainScopeService(GameRepository g, GameSessionRepository s) {
            return new DomainScopeService(g, s);
        }
    }

    @BeforeEach
    void stubs() {
        GamesAdminPersonaMatrix.stubDownstreams(sessions, multiplayer, debug, launches, games);
    }

    @Test
    void theSliceRunsTheSystemsPolicy() {
        assertThat(policy.realmAdminIsGamesAdmin()).isFalse();
    }

    @Test
    void adminOnly_isRefusedEveryGamesAdminCall() {
        Map<String, Integer> got = statuses(web, meshPersona("e2e-adminonly", "ROLE_ADMIN", "ROLE_USER"));
        assertThat(got).isEqualTo(allForbidden());
    }

    @Test
    void adminOnlyLowercaseSessionSpelling_isRefusedEveryGamesAdminCall() {
        Map<String, Integer> got = statuses(web, meshPersona("e2e-adminonly", "admin", "user"));
        assertThat(got).isEqualTo(allForbidden());
    }

    @Test
    void gamesOnly_isRefusedEveryGamesAdminCall() {
        assertThat(statuses(web, meshPersona("e2e-gamesonly", "ROLE_GAMES", "ROLE_USER")))
                .isEqualTo(allForbidden());
    }

    @Test
    void plainUser_isRefusedEveryGamesAdminCall() {
        assertThat(statuses(web, meshPersona("e2e-user", "ROLE_USER"))).isEqualTo(allForbidden());
    }

    @Test
    void superadminAlone_isRefusedEveryGamesAdminCall() {
        assertThat(statuses(web, meshPersona("e2e-superadmin", "ROLE_SUPERADMIN")))
                .isEqualTo(allForbidden());
    }

    @Test
    void gamesAdmin_reachesEveryGamesAdminCall() {
        Map<String, Integer> got = statuses(web,
                meshPersona("e2e-gamesadmin", "ROLE_GAMES", "ROLE_ADMIN", "ROLE_GAMES_ADMIN", "ROLE_USER"));
        assertThat(noneRefused(got)).as(got.toString()).isTrue();
        assertThat(got).containsEntry("GET /api/admin/stats", 200)
                .containsEntry("GET /api/admin/sessions", 200)
                .containsEntry("GET /api/admin/multiplayer-sessions", 200)
                .containsEntry("GET /api/admin/debug-mode", 200)
                .containsEntry("GET /api/games/launches", 200)
                // past the gate: an unmapped admin path is the router's 404,
                // a missing launch uid the controller's 404
                .containsEntry("GET /api/admin/role-grants", 404)
                .containsEntry("DELETE /api/games/admin/launches/some-launch-uid", 404);
    }

    @Test
    void gamesPlusMod_reachesEveryGamesAdminCall() {
        // games AND mod earns the derived role without ROLE_ADMIN.
        Map<String, Integer> got = statuses(web,
                meshPersona("e2e-gamesmod", "ROLE_GAMES", "ROLE_MOD", "ROLE_GAMES_ADMIN", "ROLE_USER"));
        assertThat(noneRefused(got)).as(got.toString()).isTrue();
    }

    @Test
    void gamesAdminLiveSessionSpelling_reachesEveryGamesAdminCall() {
        // The live /bff/session for e2e-gamesadmin read admin, games, games_admin, user.
        Map<String, Integer> got = statuses(web,
                meshPersona("e2e-gamesadmin", "admin", "games", "games_admin", "user"));
        assertThat(noneRefused(got)).as(got.toString()).isTrue();
    }

    // ── raw-JWT gate: session pause/resume (creator OR games admin) ─────────

    private static final String SID = "session-abc-0123456789";

    private void stubSessionOwnedByAlice() {
        when(multiplayer.get(eq(SID))).thenReturn(Mono.just(new GameSessionDto(SID, "lobby", "g", "alice",
                4, 1, "public", Instant.now(), null, null, null, null, List.of())));
        lenient().when(multiplayer.pauseSession(eq(SID), any(), any())).thenReturn(Mono.empty());
        lenient().when(multiplayer.resumeSession(eq(SID), any())).thenReturn(Mono.empty());
    }

    private int pause(org.springframework.security.test.web.reactive.server.SecurityMockServerConfigurers.JwtMutator who) {
        return web.mutateWith(who).mutateWith(csrf())
                .post().uri("/api/sessions/" + SID + "/pause")
                .exchange().returnResult(byte[].class).getStatus().value();
    }

    @Test
    void pause_adminOnly_is403_andNeverPauses() {
        // Before: Roles.isAdmin admitted any `admin` claim, so a bare platform
        // admin could pause anyone's session on a mesh.
        stubSessionOwnedByAlice();
        assertThat(pause(meshPersona("e2e-adminonly", "ROLE_ADMIN", "ROLE_USER"))).isEqualTo(403);
        verify(multiplayer, never()).pauseSession(any(), any(), any());
    }

    @Test
    void pause_gamesOnly_is403() {
        stubSessionOwnedByAlice();
        assertThat(pause(meshPersona("e2e-gamesonly", "ROLE_GAMES", "ROLE_USER"))).isEqualTo(403);
        verify(multiplayer, never()).pauseSession(any(), any(), any());
    }

    @Test
    void pause_gamesAdmin_pausesSomeoneElsesSession() {
        stubSessionOwnedByAlice();
        assertThat(pause(meshPersona("mesh-operator",
                "ROLE_USER", "ROLE_GAMES", "ROLE_ADMIN", "ROLE_GAMES_ADMIN"))).isEqualTo(200);
        verify(multiplayer).pauseSession(eq(SID), eq("mesh-operator"), any());
    }

    @Test
    void resume_gamesPlusMod_resumesSomeoneElsesSession() {
        stubSessionOwnedByAlice();
        int status = web.mutateWith(meshPersona("e2e-gamesmod",
                        "ROLE_USER", "ROLE_GAMES", "ROLE_MOD", "ROLE_GAMES_ADMIN"))
                .mutateWith(csrf())
                .post().uri("/api/sessions/" + SID + "/resume")
                .exchange().returnResult(byte[].class).getStatus().value();
        assertThat(status).isEqualTo(200);
        verify(multiplayer).resumeSession(eq(SID), eq("e2e-gamesmod"));
    }
}
