package games.hugmesh.bff.controller;

import games.hugmesh.bff.config.BanGateFilter;
import games.hugmesh.bff.config.DomainGuardFilter;
import games.hugmesh.bff.config.RateLimitFilter;
import games.hugmesh.bff.config.SecurityConfig;
import games.hugmesh.bff.config.SecurityHeadersFilter;
import games.hugmesh.bff.config.TenancyResourceServerConfig;
import games.hugmesh.bff.controller.support.RealIssuer;
import games.hugmesh.bff.controller.support.SecuritySliceSupport;
import games.hugmesh.bff.model.Game;
import games.hugmesh.bff.repository.GameAccessRepository;
import games.hugmesh.bff.repository.GameLaunchRepository;
import games.hugmesh.bff.repository.GameRepository;
import games.hugmesh.bff.repository.GameSessionRepository;
import games.hugmesh.bff.repository.ModGameScopeRepository;
import games.hugmesh.bff.repository.accounts.ModProfileRepository;
import games.hugmesh.bff.repository.PlayerCharacterRepository;
import games.hugmesh.bff.repository.accounts.PlayerProfileRepository;
import games.hugmesh.bff.repository.accounts.ProducerProfileRepository;
import games.hugmesh.bff.repository.PublisherGameScopeRepository;
import games.hugmesh.bff.service.DebugModeService;
import games.hugmesh.bff.service.DomainScopeService;
import games.hugmesh.bff.service.GameSessionService;
import games.hugmesh.bff.service.ModerationService;
import games.hugmesh.bff.service.PublisherService;
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
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.LinkedHashMap;
import java.util.Map;

import static games.hugmesh.bff.controller.GamesAdminPersonaMatrix.ADMIN_CALLS;
import static games.hugmesh.bff.controller.GamesAdminPersonaMatrix.NETWORKED;
import static games.hugmesh.bff.controller.GamesAdminPersonaMatrix.allForbidden;
import static games.hugmesh.bff.controller.GamesAdminPersonaMatrix.noneRefused;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * ONE BFF, both realms: the cluster's shape. A games player signs in with a
 * games-realm account and need not be a mesh user; mesh users with ROLE_GAMES /
 * ROLE_GAMES_ADMIN reach games through the mesh shell.
 *
 * <p>Nothing here uses {@code mockJwt()}. Every call carries a real signed token and
 * goes through the real issuer resolver, per-issuer JWKS decoder and authorities
 * converter, because "this token gets this answer" is the whole claim.
 *
 * <p>The trap this pins: {@code casdoor.organization} is {@code threewizards} here,
 * as it is in the cluster. Before the policy read the issuer, that made a mesh
 * platform ROLE_ADMIN a games admin the moment the BFF accepted systems tokens, the
 * regression #47/#48 closed.
 */
@WebFluxTest(controllers = {AdminController.class, DebugModeController.class,
                GameLaunchController.class, RoleProxyController.class,
                MultiplayerSessionController.class, ModerationController.class,
                ProfileController.class, PublisherController.class},
        properties = {"casdoor.organization=threewizards",
                "casdoor.endpoint=http://127.0.0.1:9",
                "casdoor.client-id=test", "casdoor.client-secret=test",
                "app.tenancy.issuers=" + TwoRealmHttpAuthzTest.GAMES_ISS + "=default,"
                        + TwoRealmHttpAuthzTest.MESH_ISS + "=default",
                "app.security.games-realm-issuers=" + TwoRealmHttpAuthzTest.GAMES_ISS},
        excludeFilters = @Filter(type = FilterType.ASSIGNABLE_TYPE, classes = {
                BanGateFilter.class, DomainGuardFilter.class,
                RateLimitFilter.class, SecurityHeadersFilter.class}))
@Import({SecurityConfig.class, TenancyResourceServerConfig.class,
        SecuritySliceSupport.TenantBeans.class, TwoRealmHttpAuthzTest.Beans.class})
class TwoRealmHttpAuthzTest {

    static final String GAMES_ISS = "https://id.games.example";
    static final String MESH_ISS = "https://id.mesh.example";

    private static final RealIssuer GAMES = new RealIssuer(GAMES_ISS);
    private static final RealIssuer MESH = new RealIssuer(MESH_ISS);
    /** Same kid as the others, so a token from it fails on the signature itself. */
    private static final RealIssuer ROGUE = new RealIssuer("https://id.rogue.example");

    @DynamicPropertySource
    static void jwks(DynamicPropertyRegistry r) {
        r.add("app.tenancy.jwks-uris",
                () -> GAMES_ISS + "=" + GAMES.jwksUri() + "," + MESH_ISS + "=" + MESH.jwksUri());
    }

    @Autowired WebTestClient web;

    @MockitoBean PlayerCharacterRepository characters;
    @MockitoBean SessionService sessions;
    @MockitoBean GameSessionService multiplayer;
    @MockitoBean DebugModeService debug;
    @MockitoBean GameRepository games;
    @MockitoBean GameAccessRepository access;
    @MockitoBean GameLaunchRepository launches;
    @MockitoBean GameSessionRepository sessionRepo;
    @MockitoBean ModerationService moderation;
    @MockitoBean ModGameScopeRepository modScopes;
    @MockitoBean PlayerProfileRepository players;
    @MockitoBean ProducerProfileRepository producers;
    @MockitoBean ModProfileRepository modProfiles;
    @MockitoBean PublisherService publisher;
    @MockitoBean PublisherGameScopeRepository publisherScopes;

    @TestConfiguration(proxyBeanMethods = false)
    static class Beans {
        @Bean DomainScopeService domainScopeService(GameRepository g, GameSessionRepository s) {
            return new DomainScopeService(g, s);
        }
    }

    @BeforeEach
    void stubs() {
        GamesAdminPersonaMatrix.stubDownstreams(sessions, multiplayer, debug, launches, games);
        lenient().when(modScopes.findActiveByCasdoor(anyString())).thenReturn(Flux.empty());
        lenient().when(modScopes.findActiveByGame(anyString())).thenReturn(Flux.empty());
        lenient().when(modProfiles.findById(anyString())).thenReturn(Mono.empty());
        lenient().when(publisherScopes.findActiveByCasdoor(anyString())).thenReturn(Flux.empty());
        lenient().when(publisher.requireOwns(anyString(), any(), anyString())).thenReturn(Mono.empty());
        Game game = new Game();
        game.setDomain("default");
        lenient().when(games.findBySlug(eq("some-game"))).thenReturn(Mono.just(game));
    }

    private int status(HttpMethod method, String path, String token) {
        WebTestClient.RequestBodySpec spec = web.method(method).uri(path);
        if (token != null) spec = spec.header(HttpHeaders.AUTHORIZATION, "Bearer " + token);
        return spec.exchange().returnResult(byte[].class).getStatus().value();
    }

    private int get(String path, String token) {
        return status(HttpMethod.GET, path, token);
    }

    private int putMyModProfile(String token) {
        return web.put().uri("/api/profile/me/mod")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("displayName", "x", "contactEmail", "x@example.org", "oncallSlot", "weekends"))
                .exchange().returnResult(byte[].class).getStatus().value();
    }

    private Map<String, Integer> adminStatuses(String token) {
        Map<String, Integer> out = new LinkedHashMap<>();
        for (GamesAdminPersonaMatrix.Call c : ADMIN_CALLS) {
            out.put(c.toString(), status(c.method(), c.path(), token));
        }
        return out;
    }

    /** Admitted callers skip the one call that would go to Casdoor over the network. */
    private Map<String, Integer> adminStatusesWithoutNetwork(String token) {
        Map<String, Integer> out = new LinkedHashMap<>();
        for (GamesAdminPersonaMatrix.Call c : ADMIN_CALLS) {
            if (!c.equals(NETWORKED)) out.put(c.toString(), status(c.method(), c.path(), token));
        }
        return out;
    }

    // ── both issuers are accepted, with their own keys ──────────────────────

    @Test
    void aPlayerFromEitherRealmCanPlay() {
        assertThat(get("/api/profile/nothing-here", GAMES.token("g-player", "player"))).isEqualTo(404);
        assertThat(get("/api/profile/nothing-here", MESH.token("m-player", "ROLE_GAMES", "ROLE_USER"))).isEqualTo(404);
    }

    // ── tokens the BFF must refuse, whatever they claim to carry ────────────

    @Test
    void theRightKeyWithTheWrongIssuerIsRefused() {
        // Mesh key, games iss: naming the games realm must not earn its `admin` meaning.
        assertThat(get("/api/admin/stats", MESH.tokenClaiming(GAMES_ISS, "evil", "admin"))).isEqualTo(401);
        // Games key, mesh iss.
        assertThat(get("/api/admin/stats", GAMES.tokenClaiming(MESH_ISS, "evil", "ROLE_GAMES_ADMIN"))).isEqualTo(401);
    }

    @Test
    void theWrongKeyForTheRightIssuerIsRefused() {
        assertThat(get("/api/admin/stats", ROGUE.tokenClaiming(GAMES_ISS, "evil", "admin"))).isEqualTo(401);
        assertThat(get("/api/admin/stats", ROGUE.tokenClaiming(MESH_ISS, "evil", "ROLE_GAMES_ADMIN"))).isEqualTo(401);
    }

    @Test
    void anIssuerThatIsNotTrustedIsRefused() {
        assertThat(get("/api/admin/stats", ROGUE.token("evil", "admin"))).isEqualTo(401);
        assertThat(get("/api/admin/stats", ROGUE.tokenClaiming(GAMES_ISS + ".evil.example", "evil", "admin"))).isEqualTo(401);
    }

    @Test
    void noIssuerNoSignatureAndExpiredAreRefused() {
        assertThat(get("/api/admin/stats", GAMES.tokenWithoutIssuer("evil"))).isEqualTo(401);
        assertThat(get("/api/admin/stats", GAMES.unsignedToken("evil", "admin"))).isEqualTo(401);
        assertThat(get("/api/admin/stats", GAMES.expiredToken("evil", "admin"))).isEqualTo(401);
    }

    @Test
    void noTokenIsRefused() {
        assertThat(get("/api/admin/stats", null)).isEqualTo(401);
    }

    // ── games admin, per realm ──────────────────────────────────────────────

    @Test
    void gamesRealmAdmin_isAGamesAdmin() {
        Map<String, Integer> got = adminStatusesWithoutNetwork(GAMES.token("g-admin", "admin", "player"));
        assertThat(noneRefused(got)).as(got.toString()).isTrue();
        assertThat(got).containsEntry("GET /api/admin/stats", 200);
    }

    @Test
    void meshPlatformAdmin_isNotAGamesAdmin() {
        assertThat(adminStatuses(MESH.token("e2e-adminonly", "ROLE_ADMIN", "ROLE_USER"))).isEqualTo(allForbidden());
    }

    @Test
    void meshGamesAdmin_isAGamesAdmin() {
        Map<String, Integer> got = adminStatusesWithoutNetwork(
                MESH.token("e2e-gamesadmin", "ROLE_GAMES", "ROLE_ADMIN", "ROLE_GAMES_ADMIN", "ROLE_USER"));
        assertThat(noneRefused(got)).as(got.toString()).isTrue();
        assertThat(got).containsEntry("GET /api/admin/stats", 200);
    }

    @Test
    void meshSuperadminAlone_isNotAGamesAdmin() {
        assertThat(adminStatuses(MESH.token("e2e-superadmin", "ROLE_SUPERADMIN"))).isEqualTo(allForbidden());
    }

    @Test
    void meshGamesOnly_isNotAGamesAdmin() {
        assertThat(adminStatuses(MESH.token("e2e-gamesonly", "ROLE_GAMES", "ROLE_USER"))).isEqualTo(allForbidden());
    }

    @Test
    void gamesRealmModPublisherAndPlayer_areNotGamesAdmins() {
        assertThat(adminStatuses(GAMES.token("g-mod", "mod"))).isEqualTo(allForbidden());
        assertThat(adminStatuses(GAMES.token("g-pub", "publisher"))).isEqualTo(allForbidden());
        assertThat(adminStatuses(GAMES.token("g-player", "player"))).isEqualTo(allForbidden());
    }

    @Test
    void theRoleTheGmSyncWrites_countsOnlyFromTheGamesRealm() {
        Map<String, Integer> got = adminStatusesWithoutNetwork(GAMES.token("g-synced", "mesh-games-admin"));
        assertThat(noneRefused(got)).as(got.toString()).isTrue();
        assertThat(adminStatuses(MESH.token("m-synced", "mesh-games-admin"))).isEqualTo(allForbidden());
    }

    // ── games mod / publisher: colliding names, per realm ───────────────────

    @Test
    void meshPlatformMod_getsNoGamesModCapability() {
        String platformMod = MESH.token("platform-mod", "ROLE_MOD", "ROLE_USER");
        assertThat(get("/api/mod/scopes", platformMod)).isEqualTo(403);
        assertThat(get("/api/profile/me/mod", platformMod)).isEqualTo(403);
        assertThat(putMyModProfile(platformMod)).isEqualTo(403);
    }

    @Test
    void meshGamesMod_andGamesRealmMod_reachTheModSurface() {
        for (String token : new String[] {
                MESH.token("m-mod", "ROLE_GAMES", "ROLE_GAMES_MOD"),
                GAMES.token("g-mod", "mod")}) {
            assertThat(get("/api/mod/scopes", token)).isEqualTo(200);
            // Past the gate: no profile row yet is the controller's 404.
            assertThat(get("/api/profile/me/mod", token)).isEqualTo(404);
            assertThat(putMyModProfile(token)).isEqualTo(404);
        }
    }

    @Test
    void meshBarePublisher_isNotAPublisher() {
        assertThat(get("/api/publisher/scopes", MESH.token("m-bare", "ROLE_PUBLISHER"))).isEqualTo(403);
    }

    @Test
    void meshGamesPublisher_andGamesRealmPublisher_reachThePublisherSurface() {
        assertThat(get("/api/publisher/scopes", MESH.token("m-pub", "ROLE_GAMES", "ROLE_GAMES_PUBLISHER")))
                .isEqualTo(200);
        assertThat(get("/api/publisher/scopes", GAMES.token("g-pub", "publisher"))).isEqualTo(200);
    }

    // ── seeded rows name games-realm accounts, not same-named mesh users ────

    @Test
    void aSameNamedMeshPublisher_doesNotInheritTheSeededScopes() {
        // Same `name` claim on both tokens; only the games-realm one may match by name.
        assertThat(get("/api/publisher/games/some-game/mods",
                MESH.token("alice", "ROLE_GAMES_PUBLISHER"))).isEqualTo(200);
        verify(publisher).requireOwns(eq("alice"), isNull(), eq("some-game"));
        verify(publisher, never()).requireOwns(eq("alice"), eq("alice"), eq("some-game"));

        assertThat(get("/api/publisher/games/some-game/mods",
                GAMES.token("alice", "publisher"))).isEqualTo(200);
        verify(publisher).requireOwns(eq("alice"), eq("alice"), eq("some-game"));
    }
}
