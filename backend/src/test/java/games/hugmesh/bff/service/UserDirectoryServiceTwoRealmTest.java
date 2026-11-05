package games.hugmesh.bff.service;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.client.ClientRequest;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Usernames are only unique inside one realm. A two-realm BFF must keep mesh
 * {@code alice} and games {@code alice} apart: a friend request by name could
 * otherwise land on a same-named stranger, and one realm's login could overwrite the
 * other's cache entry.
 */
class UserDirectoryServiceTwoRealmTest {

    private static final String GAMES = "https://id.games.example";
    private static final String MESH = "https://id.mesh.example/";
    private static final Predicate<String> GAMES_ONLY = iss -> GAMES.equals(iss);

    /** A Casdoor that answers get-user with one id and records what it was asked. */
    private static final class FakeCasdoor {
        final List<String> requests = new ArrayList<>();
        final WebClient client;

        FakeCasdoor(String userId) {
            client = WebClient.builder().exchangeFunction(request -> answer(request, userId)).build();
        }

        private Mono<ClientResponse> answer(ClientRequest request, String userId) {
            requests.add(request.url().toString());
            String body = request.url().getPath().endsWith("/access_token")
                    ? "{\"access_token\":\"tok\"}"
                    : "{\"status\":\"ok\",\"data\":{\"id\":\"" + userId + "\"}}";
            return Mono.just(ClientResponse.create(HttpStatus.OK)
                    .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                    .body(body).build());
        }
    }

    private final FakeCasdoor gamesCasdoor = new FakeCasdoor("games-alice-id");
    private final FakeCasdoor meshCasdoor = new FakeCasdoor("mesh-alice-id");

    private UserDirectoryService twoRealms(boolean withMeshClient) {
        return new UserDirectoryService(
                new UserDirectoryService.Directory(gamesCasdoor.client, "g", "gs", "threewizards"),
                true, GAMES_ONLY,
                withMeshClient ? MESH : "",
                withMeshClient ? new UserDirectoryService.Directory(meshCasdoor.client, "m", "ms", "hugmesh") : null);
    }

    @Test
    void sameNameInBothRealms_doesNotCollide() {
        UserDirectoryService d = twoRealms(false);
        d.cacheLogin(GAMES, "alice", "games-alice-id");
        d.cacheLogin(MESH, "alice", "mesh-alice-id");

        StepVerifier.create(d.resolveByName("alice", GAMES)).expectNext("games-alice-id").verifyComplete();
        StepVerifier.create(d.resolveByName("alice", MESH)).expectNext("mesh-alice-id").verifyComplete();
    }

    @Test
    void aLoginInOneRealm_doesNotAnswerForTheOther() {
        UserDirectoryService d = twoRealms(false);
        d.cacheLogin(MESH, "alice", "mesh-alice-id");

        // Games caller: goes to the games Casdoor, never reads the mesh entry.
        StepVerifier.create(d.resolveByName("alice", GAMES)).expectNext("games-alice-id").verifyComplete();
        assertThat(gamesCasdoor.requests).isNotEmpty();
    }

    @Test
    void aTrailingSlashOnTheIssuerIsTheSameRealm() {
        UserDirectoryService d = twoRealms(false);
        d.cacheLogin("https://id.mesh.example", "alice", "mesh-alice-id");
        StepVerifier.create(d.resolveByName("alice", MESH)).expectNext("mesh-alice-id").verifyComplete();
    }

    @Test
    void meshCallerWithNoMeshClient_isAnsweredFromTheCacheAlone() {
        UserDirectoryService d = twoRealms(false);

        StepVerifier.create(d.resolveByName("alice", MESH)).verifyComplete();

        // Above all: never asked the games Casdoor, whose `alice` is someone else.
        assertThat(gamesCasdoor.requests).isEmpty();
        assertThat(meshCasdoor.requests).isEmpty();
    }

    @Test
    void meshCallerWithAMeshClient_asksOnlyTheMeshCasdoor_withItsOwnOrg() {
        UserDirectoryService d = twoRealms(true);

        StepVerifier.create(d.resolveByName("alice", MESH)).expectNext("mesh-alice-id").verifyComplete();

        assertThat(gamesCasdoor.requests).isEmpty();
        assertThat(meshCasdoor.requests).anyMatch(u -> u.contains("owner=hugmesh") && u.contains("name=alice"));
    }

    @Test
    void gamesCaller_asksOnlyTheGamesCasdoor_withItsOwnOrg() {
        UserDirectoryService d = twoRealms(true);

        StepVerifier.create(d.resolveByName("alice", GAMES)).expectNext("games-alice-id").verifyComplete();

        assertThat(meshCasdoor.requests).isEmpty();
        assertThat(gamesCasdoor.requests).anyMatch(u -> u.contains("owner=threewizards"));
    }

    @Test
    void anUnknownIssuer_reachesNoCasdoor() {
        UserDirectoryService d = twoRealms(true);
        StepVerifier.create(d.resolveByName("alice", "https://id.other.example")).verifyComplete();
        StepVerifier.create(d.resolveByName("alice", null)).verifyComplete();
        assertThat(gamesCasdoor.requests).isEmpty();
        assertThat(meshCasdoor.requests).isEmpty();
    }

    @Test
    void aNameFromTheOtherRealm_readsBackQualified() {
        UserDirectoryService d = twoRealms(false);
        d.cacheLogin(MESH, "alice", "mesh-alice-id");
        d.cacheLogin(GAMES, "bob", "games-bob-id");

        // Own realm: the bare name. Other realm: the host says it may not be "your" alice.
        assertThat(d.displayNameFor("mesh-alice-id", MESH)).isEqualTo("alice");
        assertThat(d.displayNameFor("mesh-alice-id", GAMES)).isEqualTo("alice (id.mesh.example)");
        assertThat(d.displayNameFor("games-bob-id", MESH)).isEqualTo("bob (id.games.example)");
        // Unknown id: the id, as ever.
        assertThat(d.displayNameFor("nobody", MESH)).isEqualTo("nobody");
    }

    @Test
    void oneRealm_ignoresTheIssuerEntirely() {
        // The compose lanes: one Casdoor, so names stay plain and the configured client
        // answers every caller, even if a token's iss spelling ever varied.
        UserDirectoryService d = new UserDirectoryService(
                new UserDirectoryService.Directory(gamesCasdoor.client, "g", "gs", "threewizards"),
                false, iss -> false, "", null);
        d.cacheLogin("https://a.example", "alice", "alice-id");

        StepVerifier.create(d.resolveByName("alice", "https://b.example")).expectNext("alice-id").verifyComplete();
        assertThat(d.displayNameFor("alice-id", null)).isEqualTo("alice");
        StepVerifier.create(d.resolveByName("carol", null)).expectNext("games-alice-id").verifyComplete();
    }
}
