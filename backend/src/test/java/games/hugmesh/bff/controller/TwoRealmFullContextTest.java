package games.hugmesh.bff.controller;

import games.hugmesh.bff.controller.support.RealIssuer;
import games.hugmesh.bff.service.UserDirectoryService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.AfterAllCallback;
import org.junit.jupiter.api.extension.BeforeAllCallback;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.testcontainers.containers.PostgreSQLContainer;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The whole BFF on a real port and a real database, trusting both realms, as the
 * cluster runs it. The slices prove what each token gets; this proves the two-issuer
 * configuration wires up at all outside a slice (bean graph, the boot checks, every
 * filter), and that a request by username stays in the caller's realm.
 *
 * <p>A BFF that cannot start on the production shape would pass every slice test.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
    "spring.r2dbc.pool.initial-size=0",
    "spring.cassandra.contact-points=",
    "spring.autoconfigure.exclude="
            + "org.springframework.boot.cassandra.autoconfigure.CassandraAutoConfiguration,"
            + "org.springframework.boot.data.cassandra.autoconfigure.DataCassandraAutoConfiguration,"
            + "org.springframework.boot.data.cassandra.autoconfigure.DataCassandraReactiveAutoConfiguration,"
            + "org.springframework.boot.data.cassandra.autoconfigure.DataCassandraReactiveRepositoriesAutoConfiguration,"
            + "org.springframework.boot.data.cassandra.autoconfigure.DataCassandraRepositoriesAutoConfiguration",
    "app.revocation.enabled=false",
    "app.rate-limit.distributed.enabled=false",
    "casdoor.organization=threewizards",
    "casdoor.endpoint=http://127.0.0.1:9",
    "casdoor.client-id=test", "casdoor.client-secret=test",
    "app.tenancy.issuers=" + TwoRealmFullContextTest.GAMES_ISS + "=default,"
            + TwoRealmFullContextTest.MESH_ISS + "=default",
    "app.security.games-realm-issuers=" + TwoRealmFullContextTest.GAMES_ISS,
})
@ExtendWith(TwoRealmFullContextTest.PostgresLifecycle.class)
class TwoRealmFullContextTest {

    static final String GAMES_ISS = "https://id.games.example";
    static final String MESH_ISS = "https://id.mesh.example";

    private static final RealIssuer GAMES = new RealIssuer(GAMES_ISS);
    private static final RealIssuer MESH = new RealIssuer(MESH_ISS);
    private static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16");

    static class PostgresLifecycle implements BeforeAllCallback, AfterAllCallback {
        @Override public void beforeAll(ExtensionContext ctx) { postgres.start(); }
        @Override public void afterAll(ExtensionContext ctx) { postgres.stop(); }
    }

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry reg) {
        reg.add("spring.r2dbc.url", () -> String.format("r2dbc:postgresql://%s:%d/%s",
                postgres.getHost(), postgres.getFirstMappedPort(), postgres.getDatabaseName()));
        reg.add("spring.r2dbc.username", postgres::getUsername);
        reg.add("spring.r2dbc.password", postgres::getPassword);
        reg.add("spring.datasource.url", postgres::getJdbcUrl);
        reg.add("spring.datasource.username", postgres::getUsername);
        reg.add("spring.datasource.password", postgres::getPassword);
        reg.add("spring.liquibase.enabled", () -> "true");
        reg.add("app.uploads.games-dir",
                () -> System.getProperty("java.io.tmpdir") + "/hugmesh-test-" + System.nanoTime());
        reg.add("app.tenancy.jwks-uris",
                () -> GAMES_ISS + "=" + GAMES.jwksUri() + "," + MESH_ISS + "=" + MESH.jwksUri());
    }

    @LocalServerPort int port;
    @Autowired UserDirectoryService directory;

    private WebTestClient web() {
        return WebTestClient.bindToServer().baseUrl("http://127.0.0.1:" + port).build();
    }

    private int get(String path, String token) {
        return web().get().uri(path).header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .exchange().returnResult(byte[].class).getStatus().value();
    }

    @Test
    void bothRealmsSignIn_andTheAdminRuleFollowsTheIssuer() {
        // Same username on both tokens; only the role's realm differs.
        assertThat(get("/api/admin/stats", GAMES.token("alice", "admin"))).isEqualTo(200);
        assertThat(get("/api/admin/stats", MESH.token("alice", "ROLE_ADMIN"))).isEqualTo(403);
        assertThat(get("/api/admin/stats", MESH.token("carol", "ROLE_GAMES", "ROLE_GAMES_ADMIN"))).isEqualTo(200);
    }

    @Test
    void foreignTokensAreRefusedAtTheRealChain() {
        assertThat(get("/api/relationships/friends", MESH.tokenClaiming(GAMES_ISS, "evil", "admin"))).isEqualTo(401);
        assertThat(get("/api/relationships/friends", GAMES.tokenClaiming("https://id.other.example", "evil"))).isEqualTo(401);
    }

    @Test
    void aFriendRequestByName_staysInTheCallersRealm() {
        // Two different people called alice. Signing in is what files each one's name.
        String gamesAlice = GAMES.tokenNamed("games-alice-subject", "alice", "player");
        String meshAlice = MESH.tokenNamed("mesh-alice-subject", "alice", "ROLE_GAMES");
        assertThat(get("/api/relationships/friends", gamesAlice)).isEqualTo(200);
        assertThat(get("/api/relationships/friends", meshAlice)).isEqualTo(200);

        assertThat(friendRequest(GAMES.tokenNamed("games-bob-subject", "bob", "player"), "alice"))
                .containsEntry("otherCasdoorId", "games-alice-subject")
                .containsEntry("otherName", "alice");
        assertThat(friendRequest(MESH.tokenNamed("mesh-bob-subject", "bob", "ROLE_GAMES"), "alice"))
                .containsEntry("otherCasdoorId", "mesh-alice-subject")
                .containsEntry("otherName", "alice");
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> friendRequest(String token, String other) {
        return web().post().uri("/api/relationships/friends/request")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("other", other))
                .exchange().expectStatus().isOk()
                .expectBody(Map.class).returnResult().getResponseBody();
    }

    @Test
    void theMultiIssuerPathWarmsTheDirectory_perRealm() {
        // cacheLogin used to run on the single-issuer path only.
        assertThat(get("/api/relationships/friends", GAMES.tokenNamed("dora-subject", "dora", "player"))).isEqualTo(200);
        assertThat(directory.displayNameFor("dora-subject", GAMES_ISS)).isEqualTo("dora");
        assertThat(directory.displayNameFor("dora-subject", MESH_ISS)).isEqualTo("dora (id.games.example)");
    }
}
