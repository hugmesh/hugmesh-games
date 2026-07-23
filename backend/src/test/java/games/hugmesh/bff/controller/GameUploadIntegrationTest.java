package games.hugmesh.bff.controller;

import games.hugmesh.bff.repository.FederationModuleRepository;
import games.hugmesh.bff.repository.GameAccessRepository;
import games.hugmesh.bff.repository.GameRepository;
import games.hugmesh.bff.repository.GameUploadRepository;
import games.hugmesh.bff.service.GameUploadService;
import io.r2dbc.spi.ConnectionFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.server.ResponseStatusException;
import org.testcontainers.containers.PostgreSQLContainer;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration test for the upload pipeline. Spins up an ephemeral
 * Postgres via Testcontainers, lets Liquibase run migrations, then
 * exercises {@code GameUploadService.install} directly with a fixture
 * bundle and verifies the four downstream tables landed:
 *
 *   • {@code games_uploads} — one row per uploaded bundle
 *   • {@code games}         — current_version_dir column updated
 *   • {@code federation_modules} — register.sql inserts one row
 *   • {@code game_access}   — register.sql inserts {scope:'all'}
 *
 * <p>Closes PLAN section C.1's deferred follow-up. The HTTP-layer
 * surface (multipart parsing, JWT/CSRF, controller wiring) is covered
 * implicitly by `make e2e`'s admin-uploads page; pinning the service
 * directly keeps the integration test focused on the install
 * transaction's actual contract — what arrives in the DB once a
 * validated zip enters the pipeline.
 *
 * <p>Two non-obvious test setup bits:
 * <ol>
 *   <li>{@code spring.main.lazy-initialization=true} — without this,
 *       {@code GameTimeService.@PostConstruct} races Liquibase and
 *       can query game_time_state before the table exists.</li>
 *   <li>Cassandra auto-config is excluded — the BFF's full bean
 *       graph wants a Cassandra contact point at boot, and a Cassandra
 *       container alongside Postgres is overkill for upload paths.</li>
 * </ol>
 */
@SpringBootTest(properties = {
    // No pre-allocated R2DBC connections so the pool starts empty;
    // every connection is opened AFTER Liquibase commits, so MVCC
    // snapshots see the migrated schema.
    "spring.r2dbc.pool.initial-size=0",
    "spring.cassandra.contact-points=",
    // Boot 4: Cassandra autoconfigure classes moved to new packages.
    "spring.autoconfigure.exclude="
            + "org.springframework.boot.cassandra.autoconfigure.CassandraAutoConfiguration,"
            + "org.springframework.boot.data.cassandra.autoconfigure.DataCassandraAutoConfiguration,"
            + "org.springframework.boot.data.cassandra.autoconfigure.DataCassandraReactiveAutoConfiguration,"
            + "org.springframework.boot.data.cassandra.autoconfigure.DataCassandraReactiveRepositoriesAutoConfiguration,"
            + "org.springframework.boot.data.cassandra.autoconfigure.DataCassandraRepositoriesAutoConfiguration",
})
@ExtendWith(GameUploadIntegrationTest.PostgresLifecycle.class)
class GameUploadIntegrationTest {

    // Postgres 16 — the version this SQL-injection regression is anchored to
    // (standard_conforming_strings=on makes a backslash a LITERAL char, which is
    // exactly what desynced the old register.sql lexer).
    private static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16");

    /**
     * Escape hatch for environments where Testcontainers can't negotiate the
     * Docker API (e.g. a very new daemon that rejects docker-java's default API
     * floor). When {@code SG_TEST_PG_R2DBC_URL} (+ {@code _JDBC_URL}/{@code
     * _USER}/{@code _PASS}) is set, the test runs against that already-running
     * Postgres 16 instead of starting a container. CI leaves it unset and uses
     * Testcontainers as normal.
     */
    private static boolean usingExternalDb() {
        return System.getenv("SG_TEST_PG_R2DBC_URL") != null;
    }

    static class PostgresLifecycle implements
            org.junit.jupiter.api.extension.BeforeAllCallback,
            org.junit.jupiter.api.extension.AfterAllCallback {
        @Override public void beforeAll(org.junit.jupiter.api.extension.ExtensionContext ctx) {
            if (!usingExternalDb()) postgres.start();
        }
        @Override public void afterAll(org.junit.jupiter.api.extension.ExtensionContext ctx)  {
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
            // JdbcDataSourceConfig reads spring.datasource.* — same DB,
            // Liquibase migrates via this; R2DBC connects via the URL above.
            reg.add("spring.datasource.url", postgres::getJdbcUrl);
            reg.add("spring.datasource.username", postgres::getUsername);
            reg.add("spring.datasource.password", postgres::getPassword);
        }
        reg.add("spring.liquibase.enabled", () -> "true");
        // Production default is /var/hugmesh/games — not writable
        // by non-root. Redirect to a per-test tmp dir so the staging /
        // version-dir moves succeed.
        reg.add("app.uploads.games-dir",
                () -> System.getProperty("java.io.tmpdir") + "/hugmesh-test-" + System.nanoTime());
    }

    @Autowired private GameUploadService uploadService;
    @Autowired private GameRepository gameRepo;
    @Autowired private GameUploadRepository uploadRepo;
    @Autowired private FederationModuleRepository fedRepo;
    @Autowired private GameAccessRepository accessRepo;
    @Autowired private ConnectionFactory connectionFactory;

    /**
     * Re-sync {@code games_id_seq} with {@code MAX(id)} before each test.
     *
     * <p>The wave-game seed migrations (045–048, 050+) insert {@code games} rows
     * with EXPLICIT ids and never advance the identity sequence, so on a freshly
     * migrated database the sequence trails {@code MAX(id)}. The first
     * sequence-allocated INSERT (which every upload's register.sql performs)
     * would then collide on {@code games_pkey}. Production never hits this — real
     * installs upsert by slug and the seq is bumped over time — but a fresh
     * Testcontainers DB does, so the fixup keeps the install path exercisable.
     * (Flagged as a residual migration concern in the PR.)
     */
    @org.junit.jupiter.api.BeforeEach
    void syncGamesIdSequence() {
        DatabaseClient db = DatabaseClient.create(connectionFactory);
        db.sql("SELECT setval(pg_get_serial_sequence('games','id'), "
                + "GREATEST((SELECT COALESCE(MAX(id),1) FROM games), 1))")
          .fetch().rowsUpdated().block();
    }

    @Test
    void uploadInstallsBundleAndRegistersAllFourTables() throws IOException {
        byte[] zip = buildFixtureZip("test-fixture",
                "Test Fixture",
                "Integration test bundle (synthetic; no real Godot bytes).");

        StepVerifier.create(uploadService.install(zip, "test-admin"))
                .assertNext(installed -> {
                    assertThat(installed.slug()).isEqualTo("test-fixture");
                    assertThat(installed.contentHash()).isNotEmpty();
                    assertThat(installed.versionDir()).isNotEmpty();
                })
                .verifyComplete();

        // games — verify the row exists with the new current_version_dir.
        StepVerifier.create(gameRepo.findBySlug("test-fixture"))
                .assertNext(g -> {
                    assertThat(g.getSlug()).isEqualTo("test-fixture");
                    assertThat(g.getCurrentVersionDir())
                            .as("games.current_version_dir set after upload")
                            .isNotNull();
                    assertThat(g.getDomain())
                            .as("games.domain stamped 'default' on the no-domain install path")
                            .isEqualTo("default");
                })
                .verifyComplete();

        // games_uploads — newest row, not superseded.
        StepVerifier.create(uploadRepo.findBySlugOrderByUploadedAtDesc("test-fixture").take(1))
                .assertNext(u -> {
                    assertThat(u.getSlug()).isEqualTo("test-fixture");
                    assertThat(u.getContentHash()).isNotEmpty();
                    assertThat(u.getSupersededAt()).isNull();
                })
                .verifyComplete();

        // federation_modules — register.sql INSERTed a remote-game row.
        StepVerifier.create(fedRepo.findBySlug("test-fixture"))
                .assertNext(f -> {
                    assertThat(f.getKind()).isEqualTo("remote-game");
                    assertThat(f.isEnabled()).isTrue();
                })
                .verifyComplete();

        // game_access — register.sql INSERTed scope='all' for everyone.
        StepVerifier.create(gameRepo.findBySlug("test-fixture")
                        .flatMapMany(g -> accessRepo.findByGameId(g.getId()))
                        .filter(a -> "all".equals(a.getScope())))
                .assertNext(a -> assertThat(a.getScopeValue()).isNull())
                .verifyComplete();
    }

    /**
     * The multi-brand stamp: an upload carrying an explicit brand domain lands that
     * brand on the {@code games.domain} column — INDEPENDENT of the uploaded
     * register.sql (the fixture never sets domain). Proves the server, not the
     * uploader, owns the tenant assignment so a hand-crafted register.sql can't claim
     * a foreign brand.
     */
    @Test
    void uploadStampsExplicitBrandDomainIndependentOfRegisterSql() throws IOException {
        byte[] zip = buildFixtureZip("brandx-fixture",
                "Brand X Fixture",
                "Integration test bundle owned by brandx.");

        StepVerifier.create(uploadService.install(zip, "brandx-admin", "brandx", slug -> reactor.core.publisher.Mono.empty()))
                .assertNext(installed -> assertThat(installed.slug()).isEqualTo("brandx-fixture"))
                .verifyComplete();

        StepVerifier.create(gameRepo.findBySlug("brandx-fixture"))
                .assertNext(g -> assertThat(g.getDomain())
                        .as("games.domain stamped with the uploader's brand, not register.sql")
                        .isEqualTo("brandx"))
                .verifyComplete();
    }

    /**
     * FIRST-WRITER-WINS: a re-upload never MOVES an existing game's brand. Install a
     * game as 'default' (global), then re-upload the same slug carrying an explicit
     * 'brandx' domain — the row must STAY 'default'. This is the defence against a
     * brand-X uploader "capturing" a global title by re-uploading it; the brand is
     * fixed at creation and only a (future) superadmin operation may move it.
     */
    @Test
    void reuploadDoesNotMoveAnExistingGamesDomain() throws IOException {
        byte[] first = buildFixtureZip("firstwriter",
                "First Writer", "Installed as a global ('default') game.");
        StepVerifier.create(uploadService.install(first, "admin"))   // 2-arg => domain 'default'
                .expectNextCount(1).verifyComplete();

        byte[] second = buildFixtureZip("firstwriter",
                "First Writer v2", "Re-uploaded carrying an explicit brandx domain.");
        StepVerifier.create(uploadService.install(second, "brandx-admin", "brandx",
                        slug -> reactor.core.publisher.Mono.empty()))
                .expectNextCount(1).verifyComplete();

        StepVerifier.create(gameRepo.findBySlug("firstwriter"))
                .assertNext(g -> assertThat(g.getDomain())
                        .as("re-upload preserves the original 'default' brand (first-writer-wins)")
                        .isEqualTo("default"))
                .verifyComplete();
    }

    // ── A03: register.sql lexer-desync SQL injection (end-to-end) ────────────

    /**
     * THE EXPLOIT, end-to-end. A PUBLISHER who legitimately owns {@code rival}
     * uploads a bundle whose register.sql ends its first INSERT value with
     * {@code 'a\'} — under Postgres' default {@code standard_conforming_strings}
     * the backslash is DATA, so the literal closes and the statement ends at the
     * first {@code ;}. A smuggled second statement then writes a
     * {@code publisher_game_scopes} row granting the attacker a scope on a game
     * they don't own (privilege escalation / tenant-isolation break). The old
     * single-quote-with-backslash-escape lexer mis-read {@code \'} as an escaped
     * quote, returned the whole blob as ONE "statement" that {@code vet()}
     * allowed (prefix {@code INSERT INTO games}), and {@code db.sql(blob)} ran
     * BOTH over Postgres' multi-statement simple-query protocol.
     *
     * <p>Failing-first: on the unfixed code this upload SUCCEEDS and the
     * smuggled scope row lands. After the fix the upload 400s, the transaction
     * rolls back, and NO {@code publisher_game_scopes} row exists.
     */
    @Test
    void publisherCannotSmuggleScopeRowViaBackslashLexerDesync() throws IOException {
        // register.sql: a benign-looking INSERT INTO games whose name value ends
        // in `\'`, then a smuggled INSERT INTO publisher_game_scopes (all NOT
        // NULL columns supplied so it WOULD succeed on the unfixed path), then a
        // trailing line comment swallowing the dangling `'`.
        String evil = "INSERT INTO games (name,slug) VALUES ('a\\', 'rival'); "
                + "INSERT INTO publisher_game_scopes (casdoor_id, game_slug, granted_by_casdoor_id) "
                + "VALUES ('attacker','rival-game','attacker'); --";
        byte[] zip = buildBundleWithRegisterSql("rival", "Rival", "rival game", evil);

        // PUBLISHER path: the scope hook SUCCEEDS (publisher legitimately owns
        // 'rival') — exactly the reachability the audit calls out.
        StepVerifier.create(uploadService.install(zip, "attacker", "default", slug -> Mono.empty()))
                .expectError(ResponseStatusException.class)
                .verify();

        // The smuggled privilege-escalation row must NOT exist.
        assertScopeRowCount("attacker", "rival-game", 0);
    }

    /**
     * The destructive variant: the same backslash desync smuggles a
     * {@code DELETE FROM games}. After the fix the upload 400s + rolls back and
     * the catalog tables are left intact (the seed games from earlier migrations
     * and any installed game survive).
     */
    @Test
    void publisherCannotSmuggleDestructiveStatementViaLexerDesync() throws IOException {
        // First land a legitimate game so we can prove DELETE didn't run.
        byte[] legit = buildFixtureZip("survivor", "Survivor", "must survive the attack");
        StepVerifier.create(uploadService.install(legit, "admin"))
                .expectNextCount(1).verifyComplete();
        assertGameExists("survivor", true);

        String evil = "INSERT INTO games (name,slug) VALUES ('a\\', 'rival2'); "
                + "DELETE FROM games; --";
        byte[] zip = buildBundleWithRegisterSql("rival2", "Rival2", "rival game", evil);

        StepVerifier.create(uploadService.install(zip, "attacker", "default", slug -> Mono.empty()))
                .expectError(ResponseStatusException.class)
                .verify();

        // The smuggled DELETE must NOT have run — the prior game is intact.
        assertGameExists("survivor", true);
    }

    /** A legitimate apostrophe (doubled-quote) value still installs end-to-end. */
    @Test
    void legitDoubledQuoteValueStillInstalls() throws IOException {
        String legit = "INSERT INTO games (name, slug, description, sort_order, created_at) "
                + "VALUES ('Tom''s Quest', 'toms-quest', 'has an apostrophe', 7, NOW()) "
                + "ON CONFLICT (slug) DO UPDATE SET name = EXCLUDED.name;";
        byte[] zip = buildBundleWithRegisterSql("toms-quest", "Tom's Quest", "apostrophe game", legit);

        StepVerifier.create(uploadService.install(zip, "admin"))
                .assertNext(installed -> assertThat(installed.slug()).isEqualTo("toms-quest"))
                .verifyComplete();
        assertGameExists("toms-quest", true);
    }

    private void assertScopeRowCount(String casdoorId, String gameSlug, long expected) {
        DatabaseClient db = DatabaseClient.create(connectionFactory);
        StepVerifier.create(
                db.sql("SELECT COUNT(*) AS c FROM publisher_game_scopes "
                        + "WHERE casdoor_id = :cid AND game_slug = :slug")
                  .bind("cid", casdoorId)
                  .bind("slug", gameSlug)
                  .map(row -> ((Number) row.get("c")).longValue())
                  .one())
                .assertNext(c -> assertThat(c)
                        .as("smuggled publisher_game_scopes row must not exist")
                        .isEqualTo(expected))
                .verifyComplete();
    }

    private void assertGameExists(String slug, boolean shouldExist) {
        DatabaseClient db = DatabaseClient.create(connectionFactory);
        StepVerifier.create(
                db.sql("SELECT COUNT(*) AS c FROM games WHERE slug = :slug")
                  .bind("slug", slug)
                  .map(row -> ((Number) row.get("c")).longValue())
                  .one())
                .assertNext(c -> assertThat(c > 0)
                        .as("games row '%s' existence", slug)
                        .isEqualTo(shouldExist))
                .verifyComplete();
    }

    /**
     * Build a v2 bundle zip with a caller-supplied {@code register.sql} body
     * (the injection vectors above need a hand-crafted SQL payload, not the
     * generator template {@link #buildFixtureZip} emits).
     */
    private byte[] buildBundleWithRegisterSql(String slug, String displayName,
                                              String description, String registerSql) throws IOException {
        String manifest = """
            {
              "schema_version": 2,
              "game": {
                "slug": "%s",
                "name": "%s",
                "description": "%s"
              }
            }
            """.formatted(slug, displayName, description);
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (ZipOutputStream zos = new ZipOutputStream(baos)) {
            zos.putNextEntry(new ZipEntry("manifest.json"));
            zos.write(manifest.getBytes());
            zos.closeEntry();
            zos.putNextEntry(new ZipEntry("register.sql"));
            zos.write(registerSql.getBytes());
            zos.closeEntry();
            zos.putNextEntry(new ZipEntry("bundle/index.html"));
            zos.write("<!doctype html><title>fixture</title>".getBytes());
            zos.closeEntry();
        }
        return baos.toByteArray();
    }

    /**
     * Build a minimal v2 manifest bundle zip. Mirrors what the Godot
     * editor plugin's `Project → Tools → HugMesh → Export…` produces.
     */
    private byte[] buildFixtureZip(String slug, String displayName, String description) throws IOException {
        String manifest = """
            {
              "schema_version": 2,
              "game": {
                "slug": "%s",
                "name": "%s",
                "description": "%s"
              }
            }
            """.formatted(slug, displayName, description);

        // register.sql runs inside the install transaction; statements are split on
        // semicolons (respecting single-quoted strings). slug / displayName / description
        // are interpolated into single-quoted SQL string literals, so any apostrophe in
        // them (e.g. a description like "...('default')...") must be SQL-escaped by doubling
        // the quote — otherwise the literal closes early and the statement is malformed
        // (a syntax error, NOT anything the production publisher-authored register.sql hits).
        String slugSql = slug.replace("'", "''");
        String nameSql = displayName.replace("'", "''");
        String descSql = description.replace("'", "''");
        String registerSql = """
            INSERT INTO games (slug, name, description, sort_order, created_at)
                VALUES ('%s', '%s', '%s', 99, NOW())
                ON CONFLICT (slug) DO UPDATE
                  SET name = EXCLUDED.name, description = EXCLUDED.description;

            INSERT INTO federation_modules (slug, display_name, kind, remote_entry_url,
                                             exposed_module, game_slug, version, enabled, metadata)
                VALUES ('%s', '%s', 'remote-game',
                        '/_remotes/%s/remoteEntry.json',
                        './GameRoot', '%s', '1.0.0', true, '{}'::jsonb)
                ON CONFLICT (slug) DO UPDATE
                  SET remote_entry_url = EXCLUDED.remote_entry_url, version = EXCLUDED.version;

            INSERT INTO game_access (game_id, scope, scope_value, granted_by)
                SELECT id, 'all', NULL, 'system:upload'
                  FROM games WHERE slug = '%s'
                ON CONFLICT DO NOTHING;
            """.formatted(slugSql, nameSql, descSql, slugSql, nameSql, slugSql, slugSql, slugSql);

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (ZipOutputStream zos = new ZipOutputStream(baos)) {
            zos.putNextEntry(new ZipEntry("manifest.json"));
            zos.write(manifest.getBytes());
            zos.closeEntry();

            zos.putNextEntry(new ZipEntry("register.sql"));
            zos.write(registerSql.getBytes());
            zos.closeEntry();

            zos.putNextEntry(new ZipEntry("bundle/index.html"));
            zos.write("<!doctype html><title>fixture</title>".getBytes());
            zos.closeEntry();
        }
        return baos.toByteArray();
    }
}
