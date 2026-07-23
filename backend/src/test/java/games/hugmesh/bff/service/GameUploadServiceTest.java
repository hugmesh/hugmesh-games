package games.hugmesh.bff.service;

import games.hugmesh.bff.repository.GameUploadRepository;
import games.hugmesh.bff.security.DomainRegistry;
import io.r2dbc.spi.ConnectionFactory;
import io.r2dbc.spi.ConnectionFactoryMetadata;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.reactive.TransactionalOperator;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import static org.mockito.Mockito.when;

import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * Validation-only tests for {@link GameUploadService}. The early
 * rejection paths (empty bytes, oversized bytes, missing manifest,
 * malformed manifest, zip-slip-unsafe entries, slug regex) all run
 * BEFORE the R2DBC chain, so the repos / connection factory / tx
 * operator can be mocks — none of them are touched.
 *
 * <p>{@link #authorizationFailureNeverTouchesStorage} additionally covers
 * the {@code requireScope} / storage-move ordering boundary: it still
 * never reaches the DB (the authorization Mono errors first), so the same
 * mocks apply.
 */
class GameUploadServiceTest {

    @TempDir Path tempDir;

    private GameUploadService newService() {
        // DatabaseClient.create(cf) reads cf.getMetadata().getName() in
        // its bind-marker setup, so the mock has to return a populated
        // ConnectionFactoryMetadata. Tests in this file never reach the
        // install path that actually USES the client; this is just to
        // keep construction happy.
        ConnectionFactory cf = mock(ConnectionFactory.class);
        ConnectionFactoryMetadata md = mock(ConnectionFactoryMetadata.class);
        when(md.getName()).thenReturn("PostgreSQL");
        when(cf.getMetadata()).thenReturn(md);
        return new GameUploadService(
                tempDir.toString(),
                "",   // register-sql least-priv role disabled — these specs exercise
                      // only the staging/validation path (no DB role switch).
                new games.hugmesh.bff.upload.FilesystemUploadStorage(tempDir),
                mock(GameUploadRepository.class),
                cf,
                mock(TransactionalOperator.class)
        );
    }

    @Test
    void rejectsEmptyUpload() {
        StepVerifier.create(newService().install(new byte[0], "admin"))
                .expectErrorSatisfies(err -> {
                    assertThat(err).isInstanceOf(ResponseStatusException.class);
                    assertThat(err.getMessage()).contains("empty");
                })
                .verify();
    }

    @Test
    void rejectsOversizedUpload() {
        // The cap is 200 MB; allocate one byte over to be sure.
        byte[] tooBig = new byte[200 * 1024 * 1024 + 1];
        StepVerifier.create(newService().install(tooBig, "admin"))
                .expectErrorSatisfies(err -> {
                    assertThat(err).isInstanceOf(ResponseStatusException.class);
                    assertThat(err.getMessage()).contains("exceeds");
                })
                .verify();
    }

    @Test
    void rejectsZipMissingManifest() throws Exception {
        byte[] zip = makeZip(entry -> {
            entry.put("bundle/index.html", "<html></html>".getBytes());
        });
        StepVerifier.create(newService().install(zip, "admin"))
                .expectErrorSatisfies(err -> {
                    assertThat(err).isInstanceOf(ResponseStatusException.class);
                    assertThat(err.getMessage()).contains("manifest.json missing");
                })
                .verify();
    }

    @Test
    void rejectsZipSlipPath() throws Exception {
        byte[] zip = makeZip(entry -> {
            entry.put("../escape.txt", "🤡".getBytes());
        });
        StepVerifier.create(newService().install(zip, "admin"))
                .expectErrorSatisfies(err -> {
                    assertThat(err).isInstanceOf(ResponseStatusException.class);
                    assertThat(err.getMessage()).contains("zip-slip");
                })
                .verify();
    }

    @Test
    void rejectsWrongSchemaVersion() throws Exception {
        byte[] zip = makeZip(entry -> {
            entry.put("manifest.json",
                    "{\"schema_version\":1,\"game\":{\"name\":\"x\",\"slug\":\"x\"}}".getBytes());
            entry.put("bundle/index.html", "<html></html>".getBytes());
        });
        StepVerifier.create(newService().install(zip, "admin"))
                .expectErrorSatisfies(err -> {
                    assertThat(err).isInstanceOf(ResponseStatusException.class);
                    assertThat(err.getMessage()).contains("schema_version must be 2");
                })
                .verify();
    }

    @Test
    void rejectsInvalidSlug() throws Exception {
        byte[] zip = makeZip(entry -> {
            // Underscore is disallowed by the regex.
            entry.put("manifest.json",
                    "{\"schema_version\":2,\"game\":{\"name\":\"X\",\"slug\":\"BAD_slug\"}}".getBytes());
            entry.put("bundle/index.html", "<html></html>".getBytes());
        });
        StepVerifier.create(newService().install(zip, "admin"))
                .expectErrorSatisfies(err -> {
                    assertThat(err).isInstanceOf(ResponseStatusException.class);
                    assertThat(err.getMessage()).contains("slug");
                })
                .verify();
    }

    @Test
    void rejectsEmptyBundleTree() throws Exception {
        byte[] zip = makeZip(entry -> {
            entry.put("manifest.json",
                    "{\"schema_version\":2,\"game\":{\"name\":\"X\",\"slug\":\"x\"}}".getBytes());
            // No bundle/* entries → the service rejects.
        });
        StepVerifier.create(newService().install(zip, "admin"))
                .expectErrorSatisfies(err -> {
                    assertThat(err).isInstanceOf(ResponseStatusException.class);
                    assertThat(err.getMessage()).contains("bundle/ tree is empty");
                })
                .verify();
    }

    /**
     * Guards the documented ordering contract in {@link GameUploadService#install}:
     * "the {@code requireScope} hook fires ... BEFORE any filesystem move or DB
     * write." A rejecting {@code requireScope} must short-circuit the chain before
     * {@link games.hugmesh.bff.upload.FilesystemUploadStorage#installFromStaging}
     * ever runs — i.e. the bundle must never land under
     * {@code <gamesDir>/<slug>/...} for an unauthorized upload.
     *
     * <p>Regression coverage for a real ordering bug: {@code writeAndRegister(...)}
     * was previously invoked as a plain (eager) Java method argument to
     * {@code .then(...)}, so its blocking {@code storage.installFromStaging} call
     * ran as a side effect of assembling that argument — before the
     * {@code requireScope} Mono earlier in the chain was even subscribed, let
     * alone completed. Wrapping the storage call in {@code Mono.fromCallable}
     * defers it until subscription, restoring correct sequencing.
     */
    @Test
    void authorizationFailureNeverTouchesStorage() throws Exception {
        byte[] zip = makeZip(entry -> {
            entry.put("manifest.json",
                    "{\"schema_version\":2,\"game\":{\"name\":\"X\",\"slug\":\"authtest\"}}".getBytes());
            entry.put("bundle/index.html", "<html></html>".getBytes());
        });
        java.util.function.Function<String, Mono<Void>> alwaysForbidden =
                slug -> Mono.error(new ResponseStatusException(HttpStatus.FORBIDDEN, "not your game"));

        StepVerifier.create(newService().install(
                        zip, "mallory", DomainRegistry.DEFAULT_DOMAIN, alwaysForbidden))
                .expectErrorSatisfies(err -> {
                    assertThat(err).isInstanceOf(ResponseStatusException.class);
                    assertThat(((ResponseStatusException) err).getStatusCode().value()).isEqualTo(403);
                })
                .verify();

        // FilesystemUploadStorage.installFromStaging() would have created
        // <gamesDir>/authtest/... on its way to moving the staged bundle in;
        // its absence proves the storage move never ran.
        assertThat(Files.exists(tempDir.resolve("authtest"))).isFalse();
    }

    // ── helpers ─────────────────────────────────────────────────────────────

    @FunctionalInterface
    private interface ZipEntries {
        void apply(java.util.Map<String, byte[]> entries);
    }

    private static byte[] makeZip(ZipEntries fn) throws Exception {
        java.util.Map<String, byte[]> entries = new java.util.LinkedHashMap<>();
        fn.apply(entries);
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (ZipOutputStream zos = new ZipOutputStream(baos)) {
            for (var e : entries.entrySet()) {
                zos.putNextEntry(new ZipEntry(e.getKey()));
                zos.write(e.getValue());
                zos.closeEntry();
            }
        }
        return baos.toByteArray();
    }
}
