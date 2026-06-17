package games.snapdragon.bff.controller;

import games.snapdragon.bff.config.BanGateFilter;
import games.snapdragon.bff.config.DomainGuardFilter;
import games.snapdragon.bff.config.RateLimitFilter;
import games.snapdragon.bff.config.SecurityConfig;
import games.snapdragon.bff.config.SecurityHeadersFilter;
import games.snapdragon.bff.controller.support.SecuritySliceSupport;
import games.snapdragon.bff.repository.GameUploadRepository;
import games.snapdragon.bff.service.DomainScopeService;
import games.snapdragon.bff.service.GameUploadService;
import games.snapdragon.bff.service.GameUploadService.InstalledUpload;
import games.snapdragon.bff.service.PublisherService;
import games.snapdragon.bff.upload.RegisterSqlPolicy;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.reactive.WebFluxTest;
import org.springframework.context.annotation.ComponentScan.Filter;
import org.springframework.context.annotation.FilterType;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.http.client.MultipartBodyBuilder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.web.reactive.function.BodyInserters;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.time.Instant;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.reactive.server.SecurityMockServerConfigurers.csrf;

/**
 * HTTP/filter-layer authz + register.sql smuggle regression for
 * {@link GameUploadController} (mission target #6 + PR #77's SQLi fix).
 *
 * <p>Two things asserted at the WebTestClient boundary:
 * <ul>
 *   <li><strong>role gate</strong> — {@code POST /api/admin/games/upload} is
 *       {@code hasAnyRole('ADMIN','PUBLISHER')} AND under {@code /api/admin/**};
 *       anonymous → 401, a plain USER → 403, and the upload service is never
 *       invoked for a denied caller;</li>
 *   <li><strong>register.sql smuggle → 4xx</strong> — PR #77 closed a
 *       lexer-desync SQL-injection in {@code register.sql}. This pins, at the
 *       HTTP layer, that a bundle whose {@code register.sql} smuggles a
 *       {@code DROP TABLE} (a non-allowlisted verb) is rejected with HTTP 400 by
 *       {@link RegisterSqlPolicy}, while the legitimate generator statements
 *       upload cleanly.</li>
 * </ul>
 *
 * <p>Because the policy vetting lives inside the service's DB-transaction path
 * (Testcontainers Postgres does NOT provision in this runtime — the host Docker
 * API mismatch the mission flagged), the upload service is a mock whose
 * {@code install} runs the REAL {@link RegisterSqlPolicy#vet} on the
 * register.sql extracted from the uploaded multipart bytes. That keeps this a
 * faithful HTTP→controller→policy regression (the controller surfaces the
 * policy's {@code ResponseStatusException} as a 4xx) without standing up a DB.
 * The full HTTP→DB transaction is exercised by {@code GameUploadIntegrationTest}
 * (Testcontainers) when Docker is available.
 */
@WebFluxTest(controllers = GameUploadController.class,
        excludeFilters = @Filter(type = FilterType.ASSIGNABLE_TYPE, classes = {
                BanGateFilter.class, DomainGuardFilter.class,
                RateLimitFilter.class, SecurityHeadersFilter.class}))
@Import({SecurityConfig.class, SecuritySliceSupport.TenantBeans.class})
class GameUploadControllerHttpAuthzTest {

    @Autowired WebTestClient web;

    @MockitoBean GameUploadService uploadService;
    @MockitoBean GameUploadRepository uploads;
    @MockitoBean PublisherService publisherService;
    @MockitoBean DomainScopeService domainScope;

    private static final String URI = "/api/admin/games/upload";

    /** Drives a multipart POST with a {@code bundle} part carrying the zip. */
    private WebTestClient.ResponseSpec postBundle(WebTestClient client, byte[] zip) {
        MultipartBodyBuilder mb = new MultipartBodyBuilder();
        mb.part("bundle", new org.springframework.core.io.ByteArrayResource(zip) {
            @Override public String getFilename() { return "bundle.zip"; }
        }).contentType(org.springframework.http.MediaType.APPLICATION_OCTET_STREAM);
        return client.post().uri(URI)
                .contentType(org.springframework.http.MediaType.MULTIPART_FORM_DATA)
                .body(BodyInserters.fromMultipartData(mb.build()))
                .exchange();
    }

    /** Wire the mocked install to run the REAL policy on the uploaded register.sql. */
    private void installRunsRealRegisterSqlPolicy() {
        when(uploadService.install(any(byte[].class), anyString(), anyString(), any()))
                .thenAnswer(inv -> {
                    byte[] bytes = inv.getArgument(0);
                    String sql = extractRegisterSql(bytes);
                    if (sql != null) {
                        // The REAL gate PR #77 hardened — throws ResponseStatusException(400)
                        // on a disallowed statement; the controller surfaces it as HTTP 400.
                        RegisterSqlPolicy.vet(sql);
                    }
                    return Mono.just(new InstalledUpload("ok", "hash", "v1", Instant.now()));
                });
    }

    // ── role gate ─────────────────────────────────────────────────────────────

    @Test
    void upload_anonymous_is401_andNeverInstalls() {
        postBundle(web.mutateWith(csrf()), legitZip())
                .expectStatus().isUnauthorized();
        verify(uploadService, never()).install(any(), anyString(), anyString(), any());
    }

    @Test
    void upload_plainUser_is403_andNeverInstalls() {
        postBundle(web.mutateWith(SecuritySliceSupport.user("eve")).mutateWith(csrf()), legitZip())
                .expectStatus().isForbidden();
        verify(uploadService, never()).install(any(), anyString(), anyString(), any());
    }

    @Test
    void upload_admin_legitBundle_is200() {
        installRunsRealRegisterSqlPolicy();
        postBundle(web.mutateWith(SecuritySliceSupport.admin("root")).mutateWith(csrf()), legitZip())
                .expectStatus().isOk();
        verify(uploadService).install(any(), anyString(), anyString(), any());
    }

    // ── register.sql smuggle → 4xx (PR #77 regression) ────────────────────────

    @Test
    void upload_admin_smuggledDropTable_is4xx() {
        installRunsRealRegisterSqlPolicy();

        postBundle(web.mutateWith(SecuritySliceSupport.admin("root")).mutateWith(csrf()),
                maliciousZip("INSERT INTO games (slug, name) VALUES ('x', 'X');\nDROP TABLE games;\n"))
                .expectStatus().is4xxClientError();
    }

    @Test
    void upload_admin_smuggledNonCatalogWrite_is4xx() {
        // Privilege-escalation smuggle: granting oneself a publisher scope via a
        // hand-crafted register.sql. The policy's table allowlist rejects it.
        installRunsRealRegisterSqlPolicy();

        postBundle(web.mutateWith(SecuritySliceSupport.admin("root")).mutateWith(csrf()),
                maliciousZip("INSERT INTO publisher_game_scopes (casdoor_id, game_slug) "
                        + "VALUES ('attacker', 'rival-game');\n"))
                .expectStatus().is4xxClientError();
    }

    @Test
    void upload_admin_smuggledDomainCapture_is4xx() {
        // Tenant-capture smuggle: writing the server-owned domain column.
        installRunsRealRegisterSqlPolicy();

        postBundle(web.mutateWith(SecuritySliceSupport.admin("root")).mutateWith(csrf()),
                maliciousZip("UPDATE games SET domain = 'brand-y' WHERE slug = 'x';\n"))
                .expectStatus().is4xxClientError();
    }

    /** Direct proof the policy itself rejects the smuggle (the gate the HTTP path rides). */
    @Test
    void registerSqlPolicy_rejectsSmuggle_directControl() {
        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                        RegisterSqlPolicy.vet("INSERT INTO games (slug) VALUES ('x');\nDROP TABLE games;\n"))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(e -> org.assertj.core.api.Assertions.assertThat(
                        ((ResponseStatusException) e).getStatusCode())
                        .isEqualTo(HttpStatus.BAD_REQUEST));
    }

    // ── zip fixtures ───────────────────────────────────────────────────────────

    private static byte[] legitZip() {
        return zip("""
                INSERT INTO games (slug, name, description, sort_order, created_at)
                  VALUES ('demo', 'Demo', 'a demo', 0, NOW())
                  ON CONFLICT (slug) DO UPDATE SET name = EXCLUDED.name;
                """);
    }

    private static byte[] maliciousZip(String registerSql) {
        return zip(registerSql);
    }

    private static byte[] zip(String registerSql) {
        String manifest = """
                { "schema_version": 2, "game": { "slug": "demo", "name": "Demo", "description": "d" } }
                """;
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (ZipOutputStream zos = new ZipOutputStream(baos)) {
            zos.putNextEntry(new ZipEntry("manifest.json"));
            zos.write(manifest.getBytes());
            zos.closeEntry();
            zos.putNextEntry(new ZipEntry("register.sql"));
            zos.write(registerSql.getBytes());
            zos.closeEntry();
            zos.putNextEntry(new ZipEntry("bundle/index.html"));
            zos.write("<!doctype html>".getBytes());
            zos.closeEntry();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        return baos.toByteArray();
    }

    private static String extractRegisterSql(byte[] zipBytes) {
        try (ZipInputStream zis = new ZipInputStream(new ByteArrayInputStream(zipBytes))) {
            ZipEntry e;
            while ((e = zis.getNextEntry()) != null) {
                if ("register.sql".equals(e.getName())) {
                    return new String(zis.readAllBytes());
                }
            }
        } catch (Exception ex) {
            throw new RuntimeException(ex);
        }
        return null;
    }
}
