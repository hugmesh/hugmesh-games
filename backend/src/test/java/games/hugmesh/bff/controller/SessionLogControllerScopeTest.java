package games.hugmesh.bff.controller;

import games.hugmesh.bff.model.ModGameScope;
import games.hugmesh.bff.repository.ModGameScopeRepository;
import games.hugmesh.bff.repository.PublisherGameScopeRepository;
import games.hugmesh.bff.repository.SessionLogRepository;
import games.hugmesh.bff.security.GamesAdminPolicy;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.Jwt;
import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;

import java.util.List;

import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link SessionLogController#scopedFor}: a games admin sees anyone's whole
 * history; everyone else (mod / publisher) only the games they are scoped to.
 * On a mesh a bare platform admin who is also a mod is NOT a games admin, so
 * they get the scoped view (hugmesh-generator rbac-roles.ts e2e-adminonly).
 */
class SessionLogControllerScopeTest {

    private SessionLogRepository logs;
    private ModGameScopeRepository modScopes;
    private PublisherGameScopeRepository publisherScopes;

    @BeforeEach
    void setUp() {
        logs = mock(SessionLogRepository.class);
        modScopes = mock(ModGameScopeRepository.class);
        publisherScopes = mock(PublisherGameScopeRepository.class);
        ModGameScope g1 = new ModGameScope();
        g1.setGameSlug("g1");
        when(modScopes.findActiveByCasdoor(anyString())).thenReturn(Flux.just(g1));
        when(publisherScopes.findActiveByCasdoor(anyString())).thenReturn(Flux.empty());
        when(logs.findByCasdoorAndGameSlug(anyString(), anyString(), anyInt())).thenReturn(Flux.empty());
        when(logs.findByCasdoor(anyString(), anyInt())).thenReturn(Flux.empty());
    }

    private static Jwt jwt(String sub, String... roles) {
        return Jwt.withTokenValue("t").header("alg", "RS256").subject(sub).claim("roles", List.of(roles)).build();
    }

    @Test
    void meshAdminOnlyModSeesOnlyTheirScopedGames() {
        SessionLogController c = new SessionLogController(logs, modScopes, publisherScopes,
                GamesAdminPolicy.systemsRealm());
        StepVerifier.create(c.scopedFor(jwt("adminonly-mod", "ROLE_ADMIN", "ROLE_MOD", "ROLE_USER"), "target", 10))
                .verifyComplete();
        verify(logs).findByCasdoorAndGameSlug(eq("target"), eq("g1"), eq(10));
        verify(logs, never()).findByCasdoor(anyString(), anyInt());
    }

    @Test
    void meshGamesAdminSeesTheWholeHistory() {
        SessionLogController c = new SessionLogController(logs, modScopes, publisherScopes,
                GamesAdminPolicy.systemsRealm());
        StepVerifier.create(c.scopedFor(jwt("ga", "ROLE_GAMES", "ROLE_ADMIN", "ROLE_GAMES_ADMIN"), "target", 10))
                .verifyComplete();
        verify(logs).findByCasdoor(eq("target"), eq(10));
        verify(logs, never()).findByCasdoorAndGameSlug(anyString(), anyString(), anyInt());
    }

    @Test
    void gamesRealmAdminSeesTheWholeHistory() {
        SessionLogController c = new SessionLogController(logs, modScopes, publisherScopes,
                GamesAdminPolicy.gamesRealm());
        StepVerifier.create(c.scopedFor(jwt("admin", "admin"), "target", 10)).verifyComplete();
        verify(logs).findByCasdoor(eq("target"), eq(10));
    }
}
