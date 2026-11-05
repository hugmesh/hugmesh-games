package games.hugmesh.bff.upload;

import com.fasterxml.jackson.databind.ObjectMapper;
import games.hugmesh.bff.controller.support.RealIssuer;
import games.hugmesh.bff.service.GameSessionService;
import games.hugmesh.bff.service.GameUploadService;
import io.r2dbc.spi.ConnectionFactory;
import org.junit.jupiter.api.BeforeEach;
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
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.EntityExchangeResult;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.testcontainers.containers.PostgreSQLContainer;
import reactor.core.publisher.Mono;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * hugmesh-games#17 end to end, on a real Postgres and the whole BFF on a real port:
 * a game installed through {@link GameUploadService} with a {@code register.sql} in
 * the export plugin's current shape keeps {@code world}, {@code control} and
 * {@code instant_loadout}, and input gate 2 then accepts its relay events on
 * {@code POST /api/input}.
 *
 * <p><b>The fixture is the plugin's shape, built from the plugin's source.</b>
 * {@link #pluginRegisterSql} mirrors {@code manifest_builder.gd}'s
 * {@code to_register_sql()} statement for statement (banner, games upsert,
 * game_access default, features merge). Its features object is
 * {@code _merged_features(overrides)}: {@code _DEFAULT_FEATURES} as parsed from
 * {@code manifest_builder.gd} by {@link ManifestBuilderFeaturesGuardTest#pluginDefaults()},
 * then the manifest's overrides. So if the plugin's defaults lose a flag, this test
 * installs a game without it. {@link ManifestBuilderFeaturesGuardTest} pins the
 * statement text ({@code features = features || '...'}) on the plugin side; this class
 * pins what that statement does on Postgres and at the gate. The GDScript itself is not
 * run here.
 *
 * <p>Four installs, one slug each (gate 2's lookup cache is off, {@code
 * gate-cache-ttl=0s}, and no slug is shared, so the tests cannot couple):
 * <ol>
 *   <li>the plugin's eleven-key merge: flags land on Postgres, gate 2 accepts
 *       {@code world:state}, {@code control:move} and {@code control:cast} (202, one
 *       {@code input_events} row each);</li>
 *   <li>a merge naming a strict subset: every key it does not name keeps the column
 *       default (read from {@code information_schema}, not hard-coded). This is the
 *       case that tells {@code ||} from {@code =}; the eleven-key upload alone cannot,
 *       since it names every default key;</li>
 *   <li>a merge whose manifest sets {@code instant_loadout: false}: {@code control:move}
 *       still passes, {@code control:cast} gets 405 (gate 2b), so the {@code control:cast}
 *       acceptance in case 1 is not just gate 2b being skipped;</li>
 *   <li>negative control, the plugin's OLD output (pre-028 defaults, whole-object
 *       replace): {@code kind:world} and {@code kind:control} get 405 — the regression
 *       the issue describes, reproduced through the same path.</li>
 * </ol>
 *
 * <p>Events are posted SESSION-scoped, as the relay does: {@code input_events.session_id}
 * is NOT NULL, so a session-less post that cleared gate 2 would still fail at the
 * insert, and "not 405" would not mean "accepted". The session is created through
 * {@link GameSessionService#create}, which seats the poster as creator, so gate 0
 * (member owns the character; only the creator may post {@code kind:world}) and the
 * session-to-game binding pass on real rows.
 *
 * <p>Needs Docker (Testcontainers), like {@code GameUploadIntegrationTest}.
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
    "app.sessions.reap-enabled=false",
    // Read-through: gate 2 must see the row the install just wrote.
    "app.input.gate-cache-ttl=0s",
    "casdoor.organization=threewizards",
    "casdoor.endpoint=http://127.0.0.1:9",
    "casdoor.client-id=test", "casdoor.client-secret=test",
    "app.tenancy.issuers=" + ExportPluginFeaturesInstallIntegrationTest.ISS + "=default",
    "app.security.games-realm-issuers=" + ExportPluginFeaturesInstallIntegrationTest.ISS,
})
@ExtendWith(ExportPluginFeaturesInstallIntegrationTest.PostgresLifecycle.class)
class ExportPluginFeaturesInstallIntegrationTest {

    static final String ISS = "https://id.games.example";

    private static final RealIssuer ISSUER = new RealIssuer(ISS);
    private static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16");

    /**
     * The export plugin's {@code _DEFAULT_FEATURES} before #26 (git 425e8e1^): the
     * pre-028 {@code spell_loadout} key, no {@code instant_loadout}, {@code world} or
     * {@code control}. Only the negative control uses it.
     */
    private static final Map<String, Boolean> OLD_PLUGIN_DEFAULTS = orderedFlags(
            "spell_loadout", "quests", "items", "char_progression", "audio",
            "gamepad", "mouse", "keyboard", "touch");

    private static final ObjectMapper JSON = new ObjectMapper();

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
        reg.add("app.tenancy.jwks-uris", () -> ISS + "=" + ISSUER.jwksUri());
        // With ONE trusted issuer the BFF keeps the single jwk-set-uri decoder, whose
        // default points at a Casdoor on localhost:8000. Point it at the test issuer.
        reg.add("spring.security.oauth2.resourceserver.jwt.jwk-set-uri", ISSUER::jwksUri);
    }

    @LocalServerPort int port;
    @Autowired GameUploadService uploads;
    @Autowired GameSessionService sessions;
    @Autowired ConnectionFactory connectionFactory;

    private DatabaseClient db() {
        return DatabaseClient.create(connectionFactory);
    }

    /** Same fix-up as {@code GameUploadIntegrationTest}: seeds insert explicit ids. */
    @BeforeEach
    void syncGamesIdSequence() {
        db().sql("SELECT setval(pg_get_serial_sequence('games','id'), "
                        + "GREATEST((SELECT COALESCE(MAX(id),1) FROM games), 1))")
                .fetch().rowsUpdated().block();
    }

    // ── 1: the plugin's current output ────────────────────────────────────────

    @Test
    void pluginShapedInstall_keepsTheRelayFlags_andGate2AcceptsItsRelayEvents() throws IOException {
        String slug = "it17-plugin";
        // A manifest that overrides one flag, as character-forge does with gamepad.
        Map<String, Boolean> features = mergedFeatures(Map.of("gamepad", false));
        assertThat(features).as("_merged_features names the plugin's eleven keys").hasSize(11);

        install(slug, pluginRegisterSql(slug, features, true));

        Map<String, String> row = featuresOf(slug);
        assertThat(row.get("world")).as("games.features.world after install").isEqualTo("true");
        assertThat(row.get("control")).as("games.features.control after install").isEqualTo("true");
        assertThat(row.get("instant_loadout")).as("games.features.instant_loadout after install").isEqualTo("true");
        assertThat(row.get("gamepad")).as("the manifest's override is written").isEqualTo("false");
        assertThat(row).as("no stale pre-028 key").doesNotContainKey("spell_loadout");

        Poster p = playerInSession(slug);
        assertThat(p.post(slug, "world", "state").getStatus().value()).as("kind:world").isEqualTo(202);
        assertThat(p.post(slug, "control", "move").getStatus().value()).as("kind:control").isEqualTo(202);
        assertThat(p.post(slug, "control", "cast").getStatus().value()).as("control:cast").isEqualTo(202);
        assertThat(inputEventsIn(p.sessionId)).as("one input_events row per accepted event").isEqualTo(3);
    }

    // ── 2: a key the upload does not name keeps the column default ───────────

    @Test
    void mergeNamingASubset_leavesEveryUnnamedKeyAtTheColumnDefault() throws IOException {
        String slug = "it17-subset";
        install(slug, pluginRegisterSql(slug, Map.of("gamepad", false), true));

        Map<String, String> row = featuresOf(slug);
        Map<String, String> def = columnDefault();
        assertThat(def).as("063-1's column default").containsKeys("world", "control", "instant_loadout");
        def.forEach((k, v) -> {
            if (!"gamepad".equals(k)) {
                assertThat(row.get(k)).as("unnamed key %s keeps the column default", k).isEqualTo(v);
            }
        });
        assertThat(row.get("gamepad")).as("the named key is written").isEqualTo("false");

        Poster p = playerInSession(slug);
        assertThat(p.post(slug, "world", "state").getStatus().value()).as("kind:world").isEqualTo(202);
    }

    // ── 2b: a manifest that turns instant_loadout off ────────────────────────

    @Test
    void mergeWithInstantLoadoutOff_refusesControlCastButNotOtherControlEvents() throws IOException {
        String slug = "it17-noloadout";
        install(slug, pluginRegisterSql(slug, mergedFeatures(Map.of("instant_loadout", false)), true));

        assertThat(featuresOf(slug).get("instant_loadout")).as("the override is written").isEqualTo("false");

        Poster p = playerInSession(slug);
        assertThat(p.post(slug, "control", "move").getStatus().value())
                .as("control:move still passes gate 2a").isEqualTo(202);
        assertThat(p.post(slug, "control", "cast").getStatus().value())
                .as("control:cast needs instant_loadout (gate 2b)").isEqualTo(405);
        assertThat(inputEventsIn(p.sessionId)).as("only control:move persisted").isEqualTo(1);
    }

    // ── 3: negative control, the plugin's old replace output ─────────────────

    @Test
    void oldReplaceFormWithoutWorld_isRefused405ForKindWorldAndControl() throws IOException {
        String slug = "it17-replace";
        install(slug, pluginRegisterSql(slug, OLD_PLUGIN_DEFAULTS, false));

        Map<String, String> row = featuresOf(slug);
        assertThat(row).as("the replace dropped the relay flags").doesNotContainKeys("world", "control");

        Poster p = playerInSession(slug);
        assertThat(p.post(slug, "world", "state").getStatus().value())
                .as("kind:world after a replace upload").isEqualTo(405);
        assertThat(p.post(slug, "control", "move").getStatus().value()).as("kind:control").isEqualTo(405);
        assertThat(inputEventsIn(p.sessionId)).as("nothing persisted").isZero();
    }

    // ── fixture: the plugin's register.sql ───────────────────────────────────

    /** {@code _merged_features(overrides)}: the plugin's defaults, then the overrides. */
    private static Map<String, Boolean> mergedFeatures(Map<String, Boolean> overrides) throws IOException {
        Map<String, Boolean> out = new LinkedHashMap<>(ManifestBuilderFeaturesGuardTest.pluginDefaults());
        out.putAll(overrides);
        return out;
    }

    /**
     * {@code to_register_sql()} for a manifest with no levels, no federation block and
     * the given features, in the plugin's current form ({@code merge = true}:
     * {@code features = features || '...'}) or its old one ({@code merge = false}:
     * {@code features = '...'}). Slugs here are fixed lower-case literals, so no
     * escaping is needed.
     */
    private static String pluginRegisterSql(String slug, Map<String, Boolean> features, boolean merge)
            throws IOException {
        String flags = JSON.writeValueAsString(features);
        String sql = "-- Generated by HugMesh Export plugin (manifest_builder.to_register_sql)\n"
                + "-- The upload endpoint (GameUploadController) runs this inside the same transaction\n"
                + "-- as the bundle install + symlink flip so the catalog stays consistent.\n\n"
                + "INSERT INTO games (name, description, slug, sort_order, created_at)\n"
                + "VALUES ('IT " + slug + "', 'hugmesh-games#17 fixture', '" + slug + "', 0, NOW())\n"
                + "ON CONFLICT (slug) DO UPDATE SET name = EXCLUDED.name, description = EXCLUDED.description;\n\n"
                + "INSERT INTO game_access (game_id, scope, scope_value, granted_by, granted_at)\n"
                + "SELECT g.id, 'all', NULL, 'system:upload', NOW() FROM games g WHERE g.slug = '" + slug + "'\n"
                + "ON CONFLICT (game_id, scope, scope_value) DO NOTHING;\n\n";
        sql += merge
                ? "UPDATE games SET features = features || '" + flags + "'::jsonb WHERE slug = '" + slug + "';\n\n"
                : "UPDATE games SET features = '" + flags + "'::jsonb WHERE slug = '" + slug + "';\n\n";
        return sql;
    }

    private void install(String slug, String registerSql) throws IOException {
        String manifest = """
            {
              "schema_version": 2,
              "game": { "slug": "%s", "name": "IT %s", "description": "hugmesh-games#17 fixture" }
            }
            """.formatted(slug, slug);
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (ZipOutputStream zos = new ZipOutputStream(baos)) {
            zos.putNextEntry(new ZipEntry("manifest.json"));
            zos.write(manifest.getBytes(StandardCharsets.UTF_8));
            zos.closeEntry();
            zos.putNextEntry(new ZipEntry("register.sql"));
            zos.write(registerSql.getBytes(StandardCharsets.UTF_8));
            zos.closeEntry();
            zos.putNextEntry(new ZipEntry("bundle/index.html"));
            zos.write("<!doctype html><title>fixture</title>".getBytes(StandardCharsets.UTF_8));
            zos.closeEntry();
        }
        GameUploadService.InstalledUpload installed = uploads.install(baos.toByteArray(), "it-admin").block();
        assertThat(installed).isNotNull();
        assertThat(installed.slug()).isEqualTo(slug);
    }

    // ── reads ────────────────────────────────────────────────────────────────

    /** flag -> its JSON text ({@code true}, {@code false}, ...) on the games row. */
    private Map<String, String> featuresOf(String slug) {
        return db().sql("SELECT f.key AS k, f.value::text AS v FROM games g "
                        + "CROSS JOIN LATERAL jsonb_each(g.features) f WHERE g.slug = :s")
                .bind("s", slug)
                .map(r -> Map.entry(r.get("k", String.class), r.get("v", String.class)))
                .all().collectMap(Map.Entry::getKey, Map.Entry::getValue).block();
    }

    /** The games.features column DEFAULT as Postgres evaluates it, flag -> JSON text. */
    private Map<String, String> columnDefault() {
        String expr = db().sql("SELECT column_default FROM information_schema.columns "
                        + "WHERE table_name = 'games' AND column_name = 'features'")
                .map(r -> r.get("column_default", String.class)).one().block();
        assertThat(expr).as("games.features has a column default").isNotBlank();
        return db().sql("SELECT f.key AS k, f.value::text AS v FROM jsonb_each((" + expr + ")::jsonb) f")
                .map(r -> Map.entry(r.get("k", String.class), r.get("v", String.class)))
                .all().collectMap(Map.Entry::getKey, Map.Entry::getValue).block();
    }

    private long inputEventsIn(String sessionId) {
        return db().sql("SELECT COUNT(*) AS c FROM input_events WHERE session_id = :s")
                .bind("s", sessionId)
                .map(r -> ((Number) r.get("c")).longValue()).one().block();
    }

    // ── the relay poster ─────────────────────────────────────────────────────

    /** A signed-in player who created a session for {@code slug} with their own character. */
    private Poster playerInSession(String slug) {
        String sub = "it17-player-" + slug;
        Long characterId = db().sql("""
                    INSERT INTO player_characters
                        (name, level, xp, xp_to_next, max_hp, current_hp, max_mp, current_mp,
                         attack, defense, speed, magic_power, current_chapter_id, created_at)
                    VALUES (:n, 1, 0, 100, 100, 100, 50, 50, 10, 5, 8, 12, 1, NOW())
                    RETURNING id
                    """)
                .bind("n", "IT " + slug)
                .map(r -> r.get("id", Long.class)).one().block();
        String sessionId = sessions.create(sub, slug, "it17 " + slug, 2, "public", characterId, null)
                .map(dto -> dto.id())
                .switchIfEmpty(Mono.error(new IllegalStateException("no session")))
                .block();
        return new Poster(ISSUER.token(sub, "player"), characterId, sessionId);
    }

    private final class Poster {
        final String token;
        final Long characterId;
        final String sessionId;

        Poster(String token, Long characterId, String sessionId) {
            this.token = token;
            this.characterId = characterId;
            this.sessionId = sessionId;
        }

        EntityExchangeResult<String> post(String slug, String kind, String type) {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("gameSlug", slug);
            body.put("sessionId", sessionId);
            body.put("kind", kind);
            body.put("type", type);
            body.put("payload", Map.of("it", 17));
            return WebTestClient.bindToServer().baseUrl("http://127.0.0.1:" + port).build()
                    .post().uri("/api/input/{id}", characterId)
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(body)
                    .exchange()
                    .expectBody(String.class)
                    .returnResult();
        }
    }

    private static Map<String, Boolean> orderedFlags(String... keys) {
        Map<String, Boolean> out = new LinkedHashMap<>();
        for (String k : keys) {
            out.put(k, true);
        }
        return out;
    }
}
