package com.threewizards.controller;

import com.threewizards.repository.FederationModuleRepository;
import com.threewizards.repository.GameAccessRepository;
import com.threewizards.repository.GameRepository;
import com.threewizards.repository.GameUploadRepository;
import com.threewizards.service.GameUploadService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
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
    "spring.autoconfigure.exclude="
            + "org.springframework.boot.autoconfigure.cassandra.CassandraAutoConfiguration,"
            + "org.springframework.boot.autoconfigure.data.cassandra.CassandraDataAutoConfiguration,"
            + "org.springframework.boot.autoconfigure.data.cassandra.CassandraReactiveDataAutoConfiguration,"
            + "org.springframework.boot.autoconfigure.data.cassandra.CassandraReactiveRepositoriesAutoConfiguration,"
            + "org.springframework.boot.autoconfigure.data.cassandra.CassandraRepositoriesAutoConfiguration",
})
@ExtendWith(GameUploadIntegrationTest.PostgresLifecycle.class)
class GameUploadIntegrationTest {

    private static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15");

    static class PostgresLifecycle implements
            org.junit.jupiter.api.extension.BeforeAllCallback,
            org.junit.jupiter.api.extension.AfterAllCallback {
        @Override public void beforeAll(org.junit.jupiter.api.extension.ExtensionContext ctx) { postgres.start(); }
        @Override public void afterAll(org.junit.jupiter.api.extension.ExtensionContext ctx)  { postgres.stop(); }
    }

    @DynamicPropertySource
    static void registerProps(DynamicPropertyRegistry reg) {
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
        reg.add("spring.liquibase.enabled", () -> "true");
        // Production default is /var/snapdragon/games — not writable
        // by non-root. Redirect to a per-test tmp dir so the staging /
        // version-dir moves succeed.
        reg.add("app.uploads.games-dir",
                () -> System.getProperty("java.io.tmpdir") + "/snapdragon-test-" + System.nanoTime());
    }

    @Autowired private GameUploadService uploadService;
    @Autowired private GameRepository gameRepo;
    @Autowired private GameUploadRepository uploadRepo;
    @Autowired private FederationModuleRepository fedRepo;
    @Autowired private GameAccessRepository accessRepo;

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
     * Build a minimal v2 manifest bundle zip. Mirrors what the Godot
     * editor plugin's `Project → Tools → Snapdragon → Export…` produces.
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

        // register.sql runs inside the install transaction; statements
        // are split on semicolons (respecting single-quoted strings).
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
            """.formatted(slug, displayName, description, slug, displayName, slug, slug, slug);

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
