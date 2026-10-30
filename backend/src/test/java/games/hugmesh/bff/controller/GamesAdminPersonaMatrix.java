package games.hugmesh.bff.controller;

import games.hugmesh.bff.repository.GameLaunchRepository;
import games.hugmesh.bff.repository.GameRepository;
import games.hugmesh.bff.service.DebugModeService;
import games.hugmesh.bff.service.GameSessionService;
import games.hugmesh.bff.service.SessionService;
import org.springframework.http.HttpMethod;
import org.springframework.security.test.web.reactive.server.SecurityMockServerConfigurers.JwtMutator;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.springframework.security.test.web.reactive.server.SecurityMockServerConfigurers.csrf;

/**
 * The games-admin surface the 2026-10-29 live browser proof hit with a bare
 * platform admin (e2e-adminonly), as one table: every endpoint below must give
 * a games admin a non-refusal and everyone else 403. Shared by the mesh and the
 * games-realm slice tests so both realms run the same rows.
 */
final class GamesAdminPersonaMatrix {

    private GamesAdminPersonaMatrix() {}

    /** One request: method + path. */
    record Call(HttpMethod method, String path) {
        @Override public String toString() { return method + " " + path; }
    }

    /** Every games-admin-only call the live proof reached as e2e-adminonly, plus
     *  the admin revoke and a path under /api/admin/** that no controller maps
     *  (the proof's GET /games/api/admin/role-grants reached the BFF and got 404). */
    static final List<Call> ADMIN_CALLS = List.of(
            new Call(HttpMethod.GET, "/api/admin/stats"),
            new Call(HttpMethod.GET, "/api/admin/sessions"),
            new Call(HttpMethod.GET, "/api/admin/multiplayer-sessions"),
            new Call(HttpMethod.GET, "/api/admin/debug-mode"),
            new Call(HttpMethod.GET, "/api/admin/roles"),
            new Call(HttpMethod.GET, "/api/admin/role-grants"),
            new Call(HttpMethod.GET, "/api/games/launches"),
            new Call(HttpMethod.DELETE, "/api/games/admin/launches/some-launch-uid"));

    /** RoleProxyController answers an ADMITTED call by calling Casdoor over the
     *  network, so admitted personas skip it ({@link #statusesWithoutNetwork});
     *  a refused call never reaches the controller, so refused personas run it. */
    static final Call NETWORKED = new Call(HttpMethod.GET, "/api/admin/roles");

    /** Stub every downstream the allowed calls touch so an admitted call answers
     *  2xx/404 instead of a mock NPE. */
    static void stubDownstreams(SessionService sessions, GameSessionService multiplayer,
                                DebugModeService debug, GameLaunchRepository launches,
                                GameRepository games) {
        lenient().when(sessions.activeSessions()).thenReturn(Flux.empty());
        lenient().when(sessions.computeStats()).thenReturn(Mono.empty());
        lenient().when(multiplayer.listAllOpenForOversight(any())).thenReturn(Flux.empty());
        lenient().when(debug.isWasmConsoleEnabled()).thenReturn(false);
        lenient().when(launches.findByTerminatedAtIsNullOrderByStartedAtDesc()).thenReturn(Flux.empty());
        lenient().when(launches.findByUid(anyString())).thenReturn(Mono.empty());
        lenient().when(games.findBySlug(anyString())).thenReturn(Mono.empty());
    }

    /** Status per call for one persona, in {@link #ADMIN_CALLS} order. Only for
     *  personas expected to be REFUSED (see {@link #NETWORKED}). */
    static Map<String, Integer> statuses(WebTestClient web, JwtMutator persona) {
        return run(web, persona, ADMIN_CALLS);
    }

    /** Every call but {@link #NETWORKED}: for personas expected to be admitted. */
    static Map<String, Integer> statusesWithoutNetwork(WebTestClient web, JwtMutator persona) {
        return run(web, persona, ADMIN_CALLS.stream().filter(c -> !c.equals(NETWORKED)).toList());
    }

    private static Map<String, Integer> run(WebTestClient web, JwtMutator persona, List<Call> calls) {
        Map<String, Integer> out = new LinkedHashMap<>();
        for (Call c : calls) {
            int status = web.mutateWith(persona).mutateWith(csrf())
                    .method(c.method()).uri(c.path())
                    .exchange()
                    .returnResult(byte[].class)
                    .getStatus().value();
            out.put(c.toString(), status);
        }
        return out;
    }

    /** Every call refused with 403. */
    static Map<String, Integer> allForbidden() {
        Map<String, Integer> out = new LinkedHashMap<>();
        for (Call c : ADMIN_CALLS) out.put(c.toString(), 403);
        return out;
    }

    /** True when no call was refused (no 401 / 403). */
    static boolean noneRefused(Map<String, Integer> statuses) {
        return statuses.values().stream().noneMatch(s -> s == 401 || s == 403);
    }
}
