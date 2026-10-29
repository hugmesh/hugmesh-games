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


import java.util.Map;

import static games.hugmesh.bff.controller.GamesAdminPersonaMatrix.allForbidden;
import static games.hugmesh.bff.controller.GamesAdminPersonaMatrix.noneRefused;
import static games.hugmesh.bff.controller.GamesAdminPersonaMatrix.statuses;
import static games.hugmesh.bff.controller.GamesAdminPersonaMatrix.statusesWithoutNetwork;
import static games.hugmesh.bff.controller.support.SecuritySliceSupport.gamesRealmPersona;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * The same games-admin surface as {@link GamesAdminMeshPersonaHttpAuthzTest},
 * on the GAMES realm ({@code casdoor.organization} left at its default,
 * threewizards: the standalone stack and the cluster's casdoor-games). There
 * the realm's own {@code admin} role IS the games admin, so the fix for the
 * mesh must not lock it out; mod, publisher and player stay refused.
 */
@WebFluxTest(controllers = {AdminController.class, DebugModeController.class,
                GameLaunchController.class, RoleProxyController.class},
        properties = {"casdoor.endpoint=http://127.0.0.1:9",
                "casdoor.client-id=test", "casdoor.client-secret=test"},
        excludeFilters = @Filter(type = FilterType.ASSIGNABLE_TYPE, classes = {
                BanGateFilter.class, DomainGuardFilter.class,
                RateLimitFilter.class, SecurityHeadersFilter.class}))
@Import({SecurityConfig.class, SecuritySliceSupport.TenantBeans.class,
        GamesAdminGamesRealmPersonaHttpAuthzTest.Beans.class})
class GamesAdminGamesRealmPersonaHttpAuthzTest {

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
    void theSliceRunsTheGamesRealmPolicy() {
        assertThat(policy.realmAdminIsGamesAdmin()).isTrue();
    }

    @Test
    void realmAdmin_reachesEveryGamesAdminCall() {
        Map<String, Integer> got = statusesWithoutNetwork(web, gamesRealmPersona("admin", "admin", "player"));
        assertThat(noneRefused(got)).as(got.toString()).isTrue();
        assertThat(got).containsEntry("GET /api/admin/stats", 200)
                .containsEntry("GET /api/games/launches", 200);
    }

    @Test
    void realmModPublisherAndPlayer_areRefusedEveryGamesAdminCall() {
        assertThat(statuses(web, gamesRealmPersona("mod", "mod"))).isEqualTo(allForbidden());
        assertThat(statuses(web, gamesRealmPersona("publisher", "publisher"))).isEqualTo(allForbidden());
        assertThat(statuses(web, gamesRealmPersona("player", "player", "play-three-wizards")))
                .isEqualTo(allForbidden());
    }

    @Test
    void realmSuperadminWithoutAdmin_isRefused() {
        assertThat(statuses(web, gamesRealmPersona("root", "superadmin"))).isEqualTo(allForbidden());
    }
}
