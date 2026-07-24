package games.hugmesh.bff.migration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import games.hugmesh.bff.repository.ChapterRepository;
import games.hugmesh.bff.repository.FederationModuleRepository;
import games.hugmesh.bff.repository.GameAccessRepository;
import games.hugmesh.bff.repository.GameRepository;
import io.r2dbc.spi.ConnectionFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.w3c.dom.NodeList;
import reactor.test.StepVerifier;

import javax.xml.parsers.DocumentBuilderFactory;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins migration {@code 056-shadows-of-the-net-federation.xml} — the registry
 * seed for the XR-chat room "Shadows of the Net – CHAT" — against a real
 * Postgres migrated by the full Liquibase changelog (Testcontainers, same
 * lifecycle pattern as {@link games.hugmesh.bff.controller.GameUploadIntegrationTest}).
 *
 * <p>What must hold after a fresh migration run:
 * <ol>
 *   <li>the four catalog rows land ({@code games} / {@code chapters} /
 *       {@code federation_modules} kind='wasm-game' / {@code game_access}
 *       scope='all'), wired to each other by id/slug;</li>
 *   <li>the federation metadata carries the launch capabilities the host and
 *       the matrix embedding read ({@code sse}/{@code hostAuthoritative}/
 *       {@code engine:godot}/{@code xr});</li>
 *   <li>the relay vocabulary is complete: the room's host-authoritative
 *       {@code world/state} broadcast has its {@code input_event_types}
 *       reference row (050-1) and the slug's {@code game_event_support}
 *       opt-in (056-5) — without the former, Gate 1 in
 *       {@code InputEventController} 400s every world broadcast;</li>
 *   <li>re-executing every 056 SQL statement verbatim on the already-seeded
 *       database inserts NOTHING (the NOT EXISTS / ON CONFLICT guards), i.e.
 *       the migration is idempotent on partial state per the repo's
 *       migration protocol.</li>
 * </ol>
 */
@SpringBootTest(properties = {
    // No pre-allocated R2DBC connections so the pool starts empty;
    // every connection is opened AFTER Liquibase commits, so MVCC
    // snapshots see the migrated schema.
    "spring.r2dbc.pool.initial-size=0",
    // NOTE: no spring.main.lazy-initialization here — a lazy SpringLiquibase
    // bean never runs and the schema never materialises.
    "spring.cassandra.contact-points=",
    // Boot 4: Cassandra autoconfigure classes moved to new packages.
    "spring.autoconfigure.exclude="
            + "org.springframework.boot.cassandra.autoconfigure.CassandraAutoConfiguration,"
            + "org.springframework.boot.data.cassandra.autoconfigure.DataCassandraAutoConfiguration,"
            + "org.springframework.boot.data.cassandra.autoconfigure.DataCassandraReactiveAutoConfiguration,"
            + "org.springframework.boot.data.cassandra.autoconfigure.DataCassandraReactiveRepositoriesAutoConfiguration,"
            + "org.springframework.boot.data.cassandra.autoconfigure.DataCassandraRepositoriesAutoConfiguration",
})
@ExtendWith(ShadowsOfTheNetFederationSeedTest.PostgresLifecycle.class)
class ShadowsOfTheNetFederationSeedTest {

    private static final String GAME_SLUG = "shadows-of-the-net";
    private static final String FEDERATION_SLUG = "games-shadows-of-the-net";
    private static final String BUNDLE_URL = "/_remotes/wasm-fragment/shadows-of-the-net/index.html";
    private static final String DISPLAY_NAME = "Shadows of the Net – CHAT";
    private static final String CHANGELOG_RESOURCE = "db/changelog/056-shadows-of-the-net-federation.xml";

    private static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16");

    /** Same external-DB escape hatch as GameUploadIntegrationTest. */
    private static boolean usingExternalDb() {
        return System.getenv("SG_TEST_PG_R2DBC_URL") != null;
    }

    static class PostgresLifecycle implements
            org.junit.jupiter.api.extension.BeforeAllCallback,
            org.junit.jupiter.api.extension.AfterAllCallback {
        @Override public void beforeAll(org.junit.jupiter.api.extension.ExtensionContext ctx) {
            if (!usingExternalDb()) postgres.start();
        }
        @Override public void afterAll(org.junit.jupiter.api.extension.ExtensionContext ctx) {
            if (!usingExternalDb()) postgres.stop();
        }
    }

    @DynamicPropertySource
    static void registerProps(DynamicPropertyRegistry reg) {
        if (usingExternalDb()) {
            reg.add("spring.r2dbc.url",       () -> System.getenv("SG_TEST_PG_R2DBC_URL"));
            reg.add("spring.r2dbc.username",  () -> System.getenv("SG_TEST_PG_USER"));
            reg.add("spring.r2dbc.password",  () -> System.getenv("SG_TEST_PG_PASS"));
            reg.add("spring.datasource.url",  () -> System.getenv("SG_TEST_PG_JDBC_URL"));
            reg.add("spring.datasource.username", () -> System.getenv("SG_TEST_PG_USER"));
            reg.add("spring.datasource.password", () -> System.getenv("SG_TEST_PG_PASS"));
        } else {
            reg.add("spring.r2dbc.url", () -> String.format(
                    "r2dbc:postgresql://%s:%d/%s",
                    postgres.getHost(), postgres.getFirstMappedPort(), postgres.getDatabaseName()));
            reg.add("spring.r2dbc.username", postgres::getUsername);
            reg.add("spring.r2dbc.password", postgres::getPassword);
            reg.add("spring.datasource.url", postgres::getJdbcUrl);
            reg.add("spring.datasource.username", postgres::getUsername);
            reg.add("spring.datasource.password", postgres::getPassword);
        }
        reg.add("spring.liquibase.enabled", () -> "true");
    }

    @Autowired private GameRepository gameRepo;
    @Autowired private ChapterRepository chapterRepo;
    @Autowired private FederationModuleRepository fedRepo;
    @Autowired private GameAccessRepository accessRepo;
    @Autowired private ConnectionFactory connectionFactory;

    private DatabaseClient db() {
        return DatabaseClient.create(connectionFactory);
    }

    @Test
    void seedsAllFourCatalogRowsWiredTogether() {
        Long gameId = gameRepo.findBySlug(GAME_SLUG)
                .map(g -> {
                    assertThat(g.getName()).isEqualTo(DISPLAY_NAME);
                    assertThat(g.getSlug()).isEqualTo(GAME_SLUG);
                    return g.getId();
                })
                .block();
        assertThat(gameId).as("games row seeded by 056-1").isNotNull();

        // Chapter: the wasm-fragment bundle URL and a scene_type carrying the
        // game's own slug (web-native convention — nothing legacy to reuse).
        StepVerifier.create(chapterRepo.findBySlug(GAME_SLUG))
                .assertNext(c -> {
                    assertThat(c.getAssetUrl()).isEqualTo(BUNDLE_URL);
                    assertThat(c.getSceneType()).isEqualTo(GAME_SLUG);
                    assertThat(c.getGameId())
                            .as("chapter belongs to the 056-1 game row")
                            .isEqualTo(gameId);
                })
                .verifyComplete();

        // game_access: without a scope='all' row the tile is hidden AND
        // un-launchable for every non-admin principal.
        StepVerifier.create(accessRepo.findByGameId(gameId)
                        .filter(a -> "all".equals(a.getScope()) && a.getScopeValue() == null)
                        .take(1))
                .assertNext(a -> assertThat(a.getGrantedBy()).isEqualTo("system:migration-056"))
                .verifyComplete();
    }

    @Test
    void federationRowIsWasmGameWithXrCapableMetadata() throws Exception {
        var module = fedRepo.findBySlug(FEDERATION_SLUG).block();
        assertThat(module).as("federation_modules row seeded by 056-3").isNotNull();
        assertThat(module.getKind()).isEqualTo("wasm-game");
        assertThat(module.getGameSlug()).isEqualTo(GAME_SLUG);
        assertThat(module.getRemoteEntryUrl()).isEqualTo(BUNDLE_URL);
        assertThat(module.isEnabled()).isTrue();

        JsonNode meta = new ObjectMapper().readTree(module.getMetadata());
        assertThat(meta.path("sse").asBoolean()).as("metadata.sse").isTrue();
        assertThat(meta.path("hostAuthoritative").asBoolean()).as("metadata.hostAuthoritative").isTrue();
        assertThat(meta.path("engine").asText()).as("metadata.engine").isEqualTo("godot");
        assertThat(meta.path("xr").asBoolean()).as("metadata.xr").isTrue();
    }

    /**
     * The relay vocabulary end to end: the room's ONLY /api/input traffic is
     * the host's {@code kind='world' type='state'} room-state broadcast. Gate 1
     * (input_event_types) must know the pair — seeded by 050-1, NOT by 056, so
     * this doubles as a regression pin on that cross-changeset dependency —
     * and 056-5 must opt the slug in for the per-game gate.
     */
    @Test
    void worldStateVocabularyIsCompleteForTheRoom() {
        StepVerifier.create(db()
                        .sql("SELECT COUNT(*) AS c FROM input_event_types WHERE kind = 'world' AND type = 'state'")
                        .map(row -> row.get("c", Long.class)).one())
                .assertNext(c -> assertThat(c).as("050-1 world/state reference row").isEqualTo(1L))
                .verifyComplete();

        StepVerifier.create(db()
                        .sql("SELECT direction, enabled FROM game_event_support "
                                + "WHERE game_slug = :slug AND event_kind = 'world' AND event_type = 'state'")
                        .bind("slug", GAME_SLUG)
                        .map(row -> row.get("direction", String.class) + "|" + row.get("enabled", Boolean.class))
                        .one())
                .assertNext(v -> assertThat(v).isEqualTo("both|true"))
                .verifyComplete();
    }

    /**
     * Idempotency proof, not vacuous: re-run every {@code <sql>} statement of
     * the 056 changelog VERBATIM (parsed from the classpath XML, so the test
     * can never drift from the migration) against the already-seeded schema,
     * then assert each seeded row still exists exactly once.
     */
    @Test
    void reRunningEveryChangesetSqlOnSeededStateInsertsNothing() throws Exception {
        for (String sql : changesetSqlStatements()) {
            db().sql(sql).fetch().rowsUpdated().block();
        }

        assertRowCount("SELECT COUNT(*) AS c FROM games WHERE slug = '" + GAME_SLUG + "'", 1L);
        assertRowCount("SELECT COUNT(*) AS c FROM chapters WHERE slug = '" + GAME_SLUG + "'", 1L);
        assertRowCount("SELECT COUNT(*) AS c FROM federation_modules WHERE slug = '" + FEDERATION_SLUG + "'", 1L);
        assertRowCount("SELECT COUNT(*) AS c FROM game_access ga JOIN games g ON g.id = ga.game_id "
                + "WHERE g.slug = '" + GAME_SLUG + "' AND ga.scope = 'all' AND ga.scope_value IS NULL", 1L);
        assertRowCount("SELECT COUNT(*) AS c FROM game_event_support "
                + "WHERE game_slug = '" + GAME_SLUG + "' AND event_kind = 'world' AND event_type = 'state'", 1L);
    }

    private void assertRowCount(String countSql, long expected) {
        StepVerifier.create(db().sql(countSql).map(row -> row.get("c", Long.class)).one())
                .assertNext(c -> assertThat(c).as(countSql).isEqualTo(expected))
                .verifyComplete();
    }

    /** Every {@code <sql>} element body from the 056 changelog, in order. */
    private static List<String> changesetSqlStatements() throws Exception {
        List<String> statements = new ArrayList<>();
        try (InputStream in = ShadowsOfTheNetFederationSeedTest.class
                .getClassLoader().getResourceAsStream(CHANGELOG_RESOURCE)) {
            assertThat(in).as("changelog resource " + CHANGELOG_RESOURCE + " on the classpath").isNotNull();
            var factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(true);
            var doc = factory.newDocumentBuilder().parse(in);
            NodeList sqlNodes = doc.getElementsByTagNameNS("*", "sql");
            assertThat(sqlNodes.getLength())
                    .as("056 carries the five seed statements")
                    .isEqualTo(5);
            for (int i = 0; i < sqlNodes.getLength(); i++) {
                statements.add(sqlNodes.item(i).getTextContent().trim());
            }
        }
        return statements;
    }
}
