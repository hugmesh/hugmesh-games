package games.hugmesh.bff.migration;

import games.hugmesh.bff.controller.InputEventController.InputGateLookup;
import io.r2dbc.spi.ConnectionFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins changeset {@code 063-input-gate-feature-flags.xml} (hugmesh-games#13,
 * decision 103b = A) against a real Postgres migrated by the full Liquibase
 * changelog — same Testcontainers lifecycle as
 * {@link SessionMemberLivenessMigrationTest}.
 *
 * <p>Gate 2 in {@code InputEventController} refuses an event whose kind flag
 * is ABSENT, so this migration is what keeps every shipped title working on
 * the day the gate lands. What must hold:
 * <ol>
 *   <li>every seeded game carries {@code world: true} and {@code control: true};</li>
 *   <li>every seeded game carries {@code instant_loadout} and none still carries
 *       the pre-028 {@code spell_loadout} key — games inserted after 028-7
 *       (044-062) got the stale default until 063; dodecahedron's explicit
 *       false (026-2) survives;</li>
 *   <li>the column DEFAULT writes {@code instant_loadout}, {@code world} and
 *       {@code control}, not {@code spell_loadout};</li>
 *   <li>re-running 063's SQL never overwrites an explicit {@code false};</li>
 *   <li>{@link InputGateLookup} reads those flags back, only a JSON
 *       {@code true} counts as on, and every slug a shipped client sends has a
 *       games row whose relay flags are on.</li>
 * </ol>
 *
 * <p><b>Order independence.</b> Two tests re-run 063's SQL against the live
 * table. Every assertion about what LIQUIBASE left behind therefore reads
 * {@link #migrated}, a snapshot taken before the first test body runs, and
 * {@link #rerun063()} puts every seeded row back afterwards. Without that, a
 * re-run test that happened to go first would backfill the seeded rows and
 * the per-row assertions would pass even if 063 had never been applied
 * (measured on PR #14: with the 063 include removed, a whole-class run failed
 * 1 of 9). {@link #changesetsOf063RanAsExecutedNotMarkRan()} pins that
 * Liquibase actually executed all three changesets.
 *
 * <p>Needs Docker. {@link InputGateFeatureFlagsChangesetXmlTest} carries the
 * offline assertions over the same file for a host without it.
 */
@SpringBootTest(properties = {
    "spring.r2dbc.pool.initial-size=0",
    "app.sessions.reap-enabled=false",
    // Read-through: every lookup in this test must see the row it just wrote.
    "app.input.gate-cache-ttl=0s",
    "spring.cassandra.contact-points=",
    "spring.autoconfigure.exclude="
            + "org.springframework.boot.cassandra.autoconfigure.CassandraAutoConfiguration,"
            + "org.springframework.boot.data.cassandra.autoconfigure.DataCassandraAutoConfiguration,"
            + "org.springframework.boot.data.cassandra.autoconfigure.DataCassandraReactiveAutoConfiguration,"
            + "org.springframework.boot.data.cassandra.autoconfigure.DataCassandraReactiveRepositoriesAutoConfiguration,"
            + "org.springframework.boot.data.cassandra.autoconfigure.DataCassandraRepositoriesAutoConfiguration",
})
@ExtendWith(InputGateFeatureFlagsMigrationTest.PostgresLifecycle.class)
class InputGateFeatureFlagsMigrationTest {

    private static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16");

    /**
     * The slug every shipped client puts in {@code gameSlug}: the fourteen
     * web-native titles' {@code GAME.slug} (games/<t>/<t>.js, sent by
     * games/_shared/game-frame.js) and shadows-of-the-net's {@code GAME_SLUG}
     * (session_bridge.gd). The Godot chess / connect-four ports and
     * three-wizards post to /api/controls, not /api/input.
     */
    private static final List<String> CLIENT_SLUGS = List.of(
            "asteroids-duel", "battleship", "bomberman", "chess", "connect-four",
            "dots-and-boxes", "go", "gomoku", "match-three", "minesweeper",
            "reversi", "sudoku", "tetris", "tron", "shadows-of-the-net");

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
            reg.add("spring.r2dbc.url",           () -> System.getenv("SG_TEST_PG_R2DBC_URL"));
            reg.add("spring.r2dbc.username",      () -> System.getenv("SG_TEST_PG_USER"));
            reg.add("spring.r2dbc.password",      () -> System.getenv("SG_TEST_PG_PASS"));
            reg.add("spring.datasource.url",      () -> System.getenv("SG_TEST_PG_JDBC_URL"));
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

    @Autowired private ConnectionFactory connectionFactory;
    @Autowired private InputGateLookup gates;

    private DatabaseClient db() {
        return DatabaseClient.create(connectionFactory);
    }

    /** The keys the per-row assertions read, as {@code features->>key}. */
    private static final List<String> SNAPSHOT_KEYS =
            List.of("world", "control", "instant_loadout", "spell_loadout");

    /**
     * slug -> (key -> {@code features->>key}, "null" when absent) for every
     * seeded games row, exactly as Liquibase left it: captured before the
     * first test body in this class runs, so no re-run of 063 can reach it.
     */
    private static Map<String, Map<String, String>> migrated;

    @BeforeEach
    void snapshotMigratedStateOnce() {
        if (migrated != null) {
            return;
        }
        Map<String, Map<String, String>> snap = new HashMap<>();
        db().sql("SELECT slug, features->>'world' AS world, features->>'control' AS control, "
                        + "features->>'instant_loadout' AS instant_loadout, "
                        + "features->>'spell_loadout' AS spell_loadout "
                        + "FROM games WHERE slug NOT LIKE 'it-063-%'")
                .map(r -> {
                    Map<String, String> row = new HashMap<>();
                    for (String k : SNAPSHOT_KEYS) {
                        row.put(k, String.valueOf(r.get(k, String.class)));
                    }
                    return Map.entry(r.get("slug", String.class), Map.copyOf(row));
                })
                .all().doOnNext(e -> snap.put(e.getKey(), e.getValue())).blockLast();
        migrated = Map.copyOf(snap);
    }

    /** slug -> features::text for every games row the changelog seeded. */
    private Map<String, String> seededFeatures() {
        return db().sql("SELECT slug, features::text AS f FROM games WHERE slug NOT LIKE 'it-063-%'")
                .map(r -> Map.entry(r.get("slug", String.class), r.get("f", String.class)))
                .all().collectMap(Map.Entry::getKey, Map.Entry::getValue).block();
    }

    private String flag(String slug, String key) {
        return db().sql("SELECT features->>'" + key + "' AS v FROM games WHERE slug = :s")
                .bind("s", slug).map(r -> String.valueOf(r.get("v", String.class))).one().block();
    }

    private void insertGame(String slug, String featuresJsonOrNull) {
        String cols = featuresJsonOrNull == null ? "" : ", features";
        String vals = featuresJsonOrNull == null ? "" : ", '" + featuresJsonOrNull + "'::jsonb";
        db().sql("INSERT INTO games (id, name, slug, sort_order" + cols + ") "
                        + "SELECT COALESCE(MAX(id), 0) + 1, 'fixture " + slug + "', '" + slug + "', 0" + vals
                        + " FROM games")
                .fetch().rowsUpdated().block();
    }

    /**
     * Runs 063's data statements again and returns the seeded rows' features
     * as they stood right AFTER the re-run, then restores every seeded row to
     * what it was before, so no other test in this class can observe the
     * re-run (see the class Javadoc on order independence).
     */
    private Map<String, String> rerun063() throws Exception {
        Map<String, String> before = seededFeatures();
        try {
            for (String sql : InputGateFeatureFlagsChangesetXmlTest.dataStatements()) {
                db().sql(sql).fetch().rowsUpdated().block();
            }
            return seededFeatures();
        } finally {
            for (Map.Entry<String, String> e : before.entrySet()) {
                db().sql("UPDATE games SET features = CAST(:f AS jsonb) WHERE slug = :s")
                        .bind("f", e.getValue()).bind("s", e.getKey())
                        .fetch().rowsUpdated().block();
            }
        }
    }

    // ── 0: Liquibase really executed 063 ──────────────────────────────────────

    @Test
    void changesetsOf063RanAsExecutedNotMarkRan() {
        Map<String, String> ran = db().sql("""
                    SELECT id, exectype FROM databasechangelog
                     WHERE id LIKE '063-%' AND author = 'hugmesh'
                    """)
                .map(r -> Map.entry(r.get("id", String.class), r.get("exectype", String.class)))
                .all().collectMap(Map.Entry::getKey, Map.Entry::getValue).block();
        assertThat(ran).isEqualTo(Map.of(
                "063-1-games-features-default", "EXECUTED",
                "063-2-games-features-spell-loadout-rename", "EXECUTED",
                "063-3-games-features-world-control", "EXECUTED"));
    }

    // ── 1 + 2: every seeded row ───────────────────────────────────────────────

    @Test
    void everySeededGameHasWorldAndControlTrue() {
        assertThat(migrated).as("the changelog seeds the catalog").hasSizeGreaterThanOrEqualTo(19);
        migrated.forEach((slug, f) -> {
            assertThat(f.get("world")).as(slug + " world").isEqualTo("true");
            assertThat(f.get("control")).as(slug + " control").isEqualTo("true");
        });
    }

    @Test
    void everySeededGameHasInstantLoadoutAndNoStaleSpellLoadout() {
        assertThat(migrated).isNotEmpty();
        migrated.forEach((slug, f) -> {
            assertThat(f.get("spell_loadout")).as(slug + " still carries spell_loadout").isEqualTo("null");
            assertThat(f.get("instant_loadout")).as(slug + " instant_loadout")
                    .isEqualTo("dodecahedron".equals(slug) ? "false" : "true");
        });
    }

    // ── 3: the column default ─────────────────────────────────────────────────

    @Test
    void theColumnDefaultWritesInstantLoadoutWorldAndControl() {
        String def = db().sql("""
                    SELECT column_default FROM information_schema.columns
                     WHERE table_name = 'games' AND column_name = 'features'
                    """).map(r -> r.get("column_default", String.class)).one().block();
        assertThat(def).contains("\"instant_loadout\"").contains("\"world\"").contains("\"control\"")
                .doesNotContain("spell_loadout");

        insertGame("it-063-default", null);
        assertThat(flag("it-063-default", "instant_loadout")).isEqualTo("true");
        assertThat(flag("it-063-default", "world")).isEqualTo("true");
        assertThat(flag("it-063-default", "control")).isEqualTo("true");
        assertThat(flag("it-063-default", "spell_loadout")).isEqualTo("null");
    }

    // ── 4: an explicit false always wins ──────────────────────────────────────

    @Test
    void rerunning063NeverOverwritesAnExplicitFalse() throws Exception {
        insertGame("it-063-explicit", "{\"world\": false, \"control\": false, \"instant_loadout\": false, \"spell_loadout\": true}");
        insertGame("it-063-legacy", "{\"spell_loadout\": false, \"gamepad\": true}");
        insertGame("it-063-bare", "{\"gamepad\": true}");
        // Only ONE of the two keys present: the row IS selected by 063-3's
        // WHERE, so this is the case where operand order decides whether the
        // explicit false survives. (it-063-explicit has both keys and is never
        // selected, so it cannot catch a swapped `features || '{...}'`.)
        insertGame("it-063-half", "{\"world\": false}");

        rerun063();

        assertThat(flag("it-063-explicit", "world")).isEqualTo("false");
        assertThat(flag("it-063-explicit", "control")).isEqualTo("false");
        assertThat(flag("it-063-explicit", "instant_loadout"))
                .as("an existing instant_loadout beats the stale key").isEqualTo("false");
        assertThat(flag("it-063-explicit", "spell_loadout")).isEqualTo("null");

        assertThat(flag("it-063-legacy", "instant_loadout"))
                .as("the stale key's value is carried over, not defaulted").isEqualTo("false");
        assertThat(flag("it-063-legacy", "spell_loadout")).isEqualTo("null");
        assertThat(flag("it-063-legacy", "world")).isEqualTo("true");

        assertThat(flag("it-063-bare", "world")).isEqualTo("true");
        assertThat(flag("it-063-bare", "control")).isEqualTo("true");
        assertThat(flag("it-063-bare", "gamepad")).isEqualTo("true");

        assertThat(flag("it-063-half", "world")).as("explicit false kept when the row is rewritten").isEqualTo("false");
        assertThat(flag("it-063-half", "control")).isEqualTo("true");
    }

    @Test
    void rerunning063OnMigratedStateChangesNothing() throws Exception {
        Map<String, String> before = seededFeatures();
        assertThat(rerun063()).isEqualTo(before);
    }

    // ── 5: the lookup gate 2 reads through ────────────────────────────────────

    @Test
    void everyClientSlugHasAGamesRowWithItsRelayFlagsOn() {
        for (String slug : CLIENT_SLUGS) {
            assertThat(migrated).as(slug + " has a games row after migration").containsKey(slug);
            assertThat(migrated.get(slug).get("control")).as(slug + " control, as migrated").isEqualTo("true");
            assertThat(migrated.get(slug).get("world")).as(slug + " world, as migrated").isEqualTo("true");
            Map<String, Boolean> f = gates.features(slug).block();
            assertThat(f).as(slug + " has a games row with flags").isNotEmpty();
            assertThat(f.get("control")).as(slug + " control").isTrue();
            assertThat(f.get("world")).as(slug + " world").isTrue();
        }
    }

    @Test
    void theLookupReadsOnlyJsonTrueAsOn() {
        insertGame("it-063-types", "{\"gamepad\": true, \"mouse\": false, \"keyboard\": \"yes\", \"touch\": 1, \"audio\": null}");
        Map<String, Boolean> f = gates.features("it-063-types").block();
        assertThat(f).containsEntry("gamepad", true).containsEntry("mouse", false)
                .containsEntry("keyboard", false).containsEntry("touch", false)
                .containsEntry("audio", false);
    }

    @Test
    void anUnknownSlugReadsAsNoFlags() {
        assertThat(gates.features("no-such-game-063").block()).isEmpty();
    }

    @Test
    void theSupportLookupReturnsOnlyEnabledTriples() {
        assertThat(gates.enabledEvents("chess").block())
                .isEqualTo(Set.of("control:move", "world:state"));
        db().sql("INSERT INTO game_event_support (game_slug, event_kind, event_type, direction, enabled) "
                        + "VALUES ('it-063-support', 'control', 'move', 'both', false), "
                        + "       ('it-063-support', 'world', 'state', 'both', true)")
                .fetch().rowsUpdated().block();
        assertThat(gates.enabledEvents("it-063-support").block()).isEqualTo(Set.of("world:state"));
        assertThat(gates.enabledEvents("tetris").block()).as("tetris has no rows on main").isEmpty();
    }
}
