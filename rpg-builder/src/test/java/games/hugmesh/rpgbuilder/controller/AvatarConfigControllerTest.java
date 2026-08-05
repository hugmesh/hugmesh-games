package games.hugmesh.rpgbuilder.controller;

import games.hugmesh.character.CharacterDocument;
import games.hugmesh.character.CharacterSpec;
import games.hugmesh.rpgbuilder.entity.RpgAvatarConfig;
import games.hugmesh.rpgbuilder.service.AvatarConfigService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webflux.test.autoconfigure.WebFluxTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.method.configuration.EnableReactiveMethodSecurity;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.test.web.reactive.server.SecurityMockServerConfigurers.JwtMutator;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.reactive.server.SecurityMockServerConfigurers.csrf;
import static org.springframework.security.test.web.reactive.server.SecurityMockServerConfigurers.mockJwt;

/**
 * Wire contract for the avatar-config endpoints: role gate, GET/PUT
 * status + JSON shape, and — critically — that the synchronous
 * whitelist IllegalArgumentException maps to a 400 through the
 * controller's OWN @ExceptionHandler ({@code @ExceptionHandler} is
 * controller-local; RpgProjectController#badArg does not cover this
 * class). The service is a stubbed no-op subclass, not a Mockito mock
 * of the concrete class (Java 25 / ByteBuddy caution — the
 * AssetStorageServiceTest precedent).
 *
 * <p>The rendition endpoints (avatar.svg / .glb / .json / -char3d.json /
 * avatar-preview.svg) are pinned here too, because their ownership guard
 * exists ONLY through {@code avatars.spec()} → {@code get()} — nothing
 * else stands between a rendition URL and another tenant's character. The
 * stub therefore intercepts at {@code get()}/{@code putSpec()} (the
 * guarded seams) and deliberately does NOT override {@code spec()}: a
 * refactor that routes a rendition around the guarded entry point stops
 * hitting the stub, falls into the null-repository superclass, and fails
 * loudly instead of silently passing.
 */
@WebFluxTest(controllers = AvatarConfigController.class)
@Import(AvatarConfigControllerTest.MockConfig.class)
@EnableReactiveMethodSecurity
class AvatarConfigControllerTest {

    @Autowired WebTestClient web;
    @Autowired StubAvatarConfigService svc;

    // The stub bean is context-shared across tests — reset its per-test
    // programmed behaviour so ordering can't leak a throw into a happy path.
    @org.junit.jupiter.api.BeforeEach
    void resetStub() {
        svc.getResult = Mono.empty();
        svc.putResult = Mono.empty();
        svc.putSpecResult = Mono.empty();
        svc.putThrows = null;
        svc.lastPutSpec = null;
        svc.getCalls = 0;
        svc.putCalls = 0;
        svc.putSpecCalls = 0;
    }

    private static RpgAvatarConfig cfg(UUID pid) {
        RpgAvatarConfig c = new RpgAvatarConfig();
        c.setId(UUID.randomUUID());
        c.setProjectId(pid);
        c.setBodyShape("stocky");
        c.setHeadShape("round");
        c.setHatType("top_hat");
        c.setHeightScale(1.1);
        c.setBodyColor("#e21422");
        c.setHeadColor("#e6b58c");
        c.setHatColor("#1a1a1a");
        return c;
    }

    @Test
    void get_returnsConfigJson() {
        UUID pid = UUID.randomUUID();
        svc.getResult = Mono.just(cfg(pid));

        web.mutateWith(asPublisher())
                .get().uri("/api/rpg/projects/" + pid + "/avatar-config")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.projectId").isEqualTo(pid.toString())
                .jsonPath("$.bodyShape").isEqualTo("stocky")
                .jsonPath("$.hatType").isEqualTo("top_hat")
                .jsonPath("$.heightScale").isEqualTo(1.1)
                .jsonPath("$.bodyColor").isEqualTo("#e21422");
    }

    @Test
    void put_happy_returnsSavedRow() {
        UUID pid = UUID.randomUUID();
        svc.putResult = Mono.just(cfg(pid));

        web.mutateWith(csrf()).mutateWith(asPublisher())
                .put().uri("/api/rpg/projects/" + pid + "/avatar-config")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"bodyShape\":\"stocky\",\"hatType\":\"top_hat\",\"heightScale\":1.1,\"bodyColor\":\"#e21422\"}")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.id").isNotEmpty()
                .jsonPath("$.bodyShape").isEqualTo("stocky");
    }

    @Test
    void put_unknownToken_returns400ViaOwnExceptionHandler() {
        // The whitelist reject is thrown SYNCHRONOUSLY by the service
        // (a Mono.error would bypass @ExceptionHandler and 500) and must
        // be caught by THIS controller's handler.
        svc.putThrows = new IllegalArgumentException(
                "unknown bodyShape 'gigantic' — expected one of round, slim, stocky");

        web.mutateWith(csrf()).mutateWith(asPublisher())
                .put().uri("/api/rpg/projects/" + UUID.randomUUID() + "/avatar-config")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"bodyShape\":\"gigantic\"}")
                .exchange()
                .expectStatus().isBadRequest()
                .expectBody(String.class).value(body ->
                        org.assertj.core.api.Assertions.assertThat(body).contains("bodyShape"));
    }

    @Test
    void put_foreignProject_returns403() {
        svc.putResult = Mono.error(new ResponseStatusException(HttpStatus.FORBIDDEN,
                "project owned by another user"));

        web.mutateWith(csrf()).mutateWith(asPublisher())
                .put().uri("/api/rpg/projects/" + UUID.randomUUID() + "/avatar-config")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{}")
                .exchange()
                .expectStatus().isForbidden();
    }

    @Test
    void get_asPlainUser_isForbidden() {
        web.mutateWith(mockJwt()
                        .authorities(new SimpleGrantedAuthority("ROLE_USER"))
                        .jwt(j -> j.subject("alice")))
                .get().uri("/api/rpg/projects/" + UUID.randomUUID() + "/avatar-config")
                .exchange()
                .expectStatus().isForbidden();
    }

    @Test
    void unauthenticated_isRejected() {
        web.get().uri("/api/rpg/projects/" + UUID.randomUUID() + "/avatar-config")
                .exchange()
                .expectStatus().isUnauthorized();
    }

    // ── Renditions ──────────────────────────────────────────────────────────
    // Each of the five rendition endpoints is pinned individually: shared
    // plumbing today does not survive a refactor, and a single endpoint
    // dropping the guard is a cross-tenant character leak.

    @Test
    void avatarSvg_returnsWellFormedSvg() {
        UUID pid = UUID.randomUUID();
        svc.getResult = Mono.just(cfg(pid));

        web.mutateWith(asPublisher())
                .get().uri("/api/rpg/projects/" + pid + "/avatar.svg?expression=happy&portrait=true")
                .exchange()
                .expectStatus().isOk()
                .expectHeader().contentTypeCompatibleWith(MediaType.valueOf("image/svg+xml"))
                .expectBody(String.class).value(AvatarConfigControllerTest::assertWellFormedSvg);
    }

    @Test
    void avatarSvg_foreignProject_returns403() {
        svc.getResult = deny(HttpStatus.FORBIDDEN, "project owned by another user");

        web.mutateWith(asPublisher())
                .get().uri("/api/rpg/projects/" + UUID.randomUUID() + "/avatar.svg")
                .exchange()
                .expectStatus().isForbidden();
    }

    @Test
    void avatarSvg_unknownProject_returns404() {
        // Newly reachable: RpgProjectService.get raises 404 via switchIfEmpty.
        // Before that it completed empty and this URL was a 200 with no body.
        svc.getResult = deny(HttpStatus.NOT_FOUND, "project not found");

        web.mutateWith(asPublisher())
                .get().uri("/api/rpg/projects/" + UUID.randomUUID() + "/avatar.svg")
                .exchange()
                .expectStatus().isNotFound();
    }

    @Test
    void avatarSvg_unknownExpression_returns400() {
        // Rejected by the whitelist BEFORE the service is consulted — the
        // empty stub result would otherwise make this a 200.
        web.mutateWith(asPublisher())
                .get().uri("/api/rpg/projects/" + UUID.randomUUID() + "/avatar.svg?expression=nonsense")
                .exchange()
                .expectStatus().isBadRequest()
                .expectBody(String.class).value(body -> assertThat(body).contains("expression"));
    }

    @Test
    void avatarSvg_oversizedWidth_returns400() {
        UUID pid = UUID.randomUUID();
        svc.getResult = Mono.just(cfg(pid));

        web.mutateWith(asPublisher())
                .get().uri("/api/rpg/projects/" + pid + "/avatar.svg?w=9999")
                .exchange()
                .expectStatus().isBadRequest();
    }

    @Test
    void avatarSvg_asPlainUser_isForbidden() {
        web.mutateWith(mockJwt()
                        .authorities(new SimpleGrantedAuthority("ROLE_USER"))
                        .jwt(j -> j.subject("alice")))
                .get().uri("/api/rpg/projects/" + UUID.randomUUID() + "/avatar.svg")
                .exchange()
                .expectStatus().isForbidden();
    }

    @Test
    void avatarSvg_unauthenticated_isRejected() {
        web.get().uri("/api/rpg/projects/" + UUID.randomUUID() + "/avatar.svg")
                .exchange()
                .expectStatus().isUnauthorized();
    }

    @Test
    void avatarGlb_returnsBinaryGltfAttachment() {
        UUID pid = UUID.randomUUID();
        svc.getResult = Mono.just(cfg(pid));

        web.mutateWith(asPublisher())
                .get().uri("/api/rpg/projects/" + pid + "/avatar.glb")
                .exchange()
                .expectStatus().isOk()
                .expectHeader().contentType(MediaType.valueOf("model/gltf-binary"))
                .expectHeader().value("Content-Disposition", v ->
                        assertThat(v).startsWith("attachment").contains("avatar-" + pid + ".glb"))
                .expectBody(byte[].class).value(bytes -> {
                    // glTF magic 0x46546C67 little-endian = "glTF" on the wire.
                    assertThat(bytes.length).isGreaterThan(12);
                    assertThat(new byte[] {bytes[0], bytes[1], bytes[2], bytes[3]})
                            .containsExactly(0x67, 0x6C, 0x54, 0x46);
                });
    }

    @Test
    void avatarGlb_foreignProject_returns403() {
        svc.getResult = deny(HttpStatus.FORBIDDEN, "project owned by another user");

        web.mutateWith(asPublisher())
                .get().uri("/api/rpg/projects/" + UUID.randomUUID() + "/avatar.glb")
                .exchange()
                .expectStatus().isForbidden();
    }

    @Test
    void avatarGlb_unknownProject_returns404() {
        svc.getResult = deny(HttpStatus.NOT_FOUND, "project not found");

        web.mutateWith(asPublisher())
                .get().uri("/api/rpg/projects/" + UUID.randomUUID() + "/avatar.glb")
                .exchange()
                .expectStatus().isNotFound();
    }

    @Test
    void avatarGlb_unauthenticated_isRejected() {
        web.get().uri("/api/rpg/projects/" + UUID.randomUUID() + "/avatar.glb")
                .exchange()
                .expectStatus().isUnauthorized();
    }

    @Test
    void avatarDocument_returnsPortableCharacterDocument() {
        UUID pid = UUID.randomUUID();
        svc.getResult = Mono.just(cfg(pid));

        web.mutateWith(asPublisher())
                .get().uri("/api/rpg/projects/" + pid + "/avatar.json")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.kind").isEqualTo("hugmesh.character")
                .jsonPath("$.schemaVersion").isEqualTo(1)
                .jsonPath("$.shape.body").isEqualTo("stocky")
                .jsonPath("$.colors.body").isEqualTo("#e21422");
    }

    @Test
    void avatarDocument_foreignProject_returns403() {
        svc.getResult = deny(HttpStatus.FORBIDDEN, "project owned by another user");

        web.mutateWith(asPublisher())
                .get().uri("/api/rpg/projects/" + UUID.randomUUID() + "/avatar.json")
                .exchange()
                .expectStatus().isForbidden();
    }

    @Test
    void importDocument_roundTrip_persistsParsedSpec() {
        UUID pid = UUID.randomUUID();
        svc.putSpecResult = Mono.just(cfg(pid));
        Map<String, Object> doc = CharacterDocument.toMap(
                CharacterSpec.builder()
                        .bodyShape("round")
                        .hatType("crown")
                        .color("eye", "#112233")
                        .build(),
                "hero");

        web.mutateWith(csrf()).mutateWith(asPublisher())
                .post().uri("/api/rpg/projects/" + pid + "/avatar.json")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(doc)
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.projectId").isEqualTo(pid.toString());

        // The document survived the wire: what reached the persistence seam
        // is the spec that was exported, not a default.
        assertThat(svc.putSpecCalls).isEqualTo(1);
        assertThat(svc.lastPutSpec.bodyShape()).isEqualTo("round");
        assertThat(svc.lastPutSpec.hatType()).isEqualTo("crown");
        assertThat(svc.lastPutSpec.color("eye")).isEqualTo("#112233");
    }

    @Test
    void importDocument_invalidDocument_returns400() {
        web.mutateWith(csrf()).mutateWith(asPublisher())
                .post().uri("/api/rpg/projects/" + UUID.randomUUID() + "/avatar.json")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("kind", "hugmesh.character",
                        "shape", Map.of("body", "gigantic")))
                .exchange()
                .expectStatus().isBadRequest()
                .expectBody(String.class).value(body -> assertThat(body).contains("bodyShape"));

        assertThat(svc.putSpecCalls).isZero();
    }

    @Test
    void importDocument_foreignProject_returns403() {
        svc.putSpecResult = deny(HttpStatus.FORBIDDEN, "project owned by another user");

        web.mutateWith(csrf()).mutateWith(asPublisher())
                .post().uri("/api/rpg/projects/" + UUID.randomUUID() + "/avatar.json")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("kind", "hugmesh.character"))
                .exchange()
                .expectStatus().isForbidden();
    }

    @Test
    void avatarChar3d_returnsHumanoidArchetype() {
        UUID pid = UUID.randomUUID();
        svc.getResult = Mono.just(cfg(pid));

        web.mutateWith(asPublisher())
                .get().uri("/api/rpg/projects/" + pid + "/avatar-char3d.json")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.body.archetype").isEqualTo("humanoid");
    }

    @Test
    void avatarChar3d_foreignProject_returns403() {
        svc.getResult = deny(HttpStatus.FORBIDDEN, "project owned by another user");

        web.mutateWith(asPublisher())
                .get().uri("/api/rpg/projects/" + UUID.randomUUID() + "/avatar-char3d.json")
                .exchange()
                .expectStatus().isForbidden();
    }

    @Test
    void previewSvg_rendersWithoutPersisting() {
        web.mutateWith(csrf()).mutateWith(asPublisher())
                .post().uri("/api/rpg/avatar-preview.svg?expression=wink")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"bodyShape\":\"stocky\",\"bodyColor\":\"#e21422\"}")
                .exchange()
                .expectStatus().isOk()
                .expectHeader().contentTypeCompatibleWith(MediaType.valueOf("image/svg+xml"))
                .expectBody(String.class).value(AvatarConfigControllerTest::assertWellFormedSvg);

        // Stateless BY CONTRACT: the preview must never touch the service —
        // no read, no upsert. A refactor that stages the preview would trip
        // one of these counters.
        assertThat(svc.getCalls).isZero();
        assertThat(svc.putCalls).isZero();
        assertThat(svc.putSpecCalls).isZero();
    }

    @Test
    void previewSvg_invalidBodyField_returns400() {
        web.mutateWith(csrf()).mutateWith(asPublisher())
                .post().uri("/api/rpg/avatar-preview.svg")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"bodyShape\":\"gigantic\"}")
                .exchange()
                .expectStatus().isBadRequest()
                .expectBody(String.class).value(body -> assertThat(body).contains("bodyShape"));
    }

    @Test
    void previewSvg_outOfRangeDimensions_return400() {
        web.mutateWith(csrf()).mutateWith(asPublisher())
                .post().uri("/api/rpg/avatar-preview.svg?w=9999")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{}")
                .exchange()
                .expectStatus().isBadRequest();

        web.mutateWith(csrf()).mutateWith(asPublisher())
                .post().uri("/api/rpg/avatar-preview.svg?h=8")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{}")
                .exchange()
                .expectStatus().isBadRequest();
    }

    @Test
    void previewSvg_asPlainUser_isForbidden() {
        web.mutateWith(csrf()).mutateWith(mockJwt()
                        .authorities(new SimpleGrantedAuthority("ROLE_USER"))
                        .jwt(j -> j.subject("alice")))
                .post().uri("/api/rpg/avatar-preview.svg")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{}")
                .exchange()
                .expectStatus().isForbidden();
    }

    @Test
    void previewSvg_unauthenticated_isRejected() {
        // Pins that the CLASS-LEVEL @PreAuthorize covers the stateless
        // preview too: no principal → 401, even though the handler itself
        // never reads the Authentication. csrf() is applied so the pinned
        // status reflects authentication, not the CSRF filter.
        web.mutateWith(csrf())
                .post().uri("/api/rpg/avatar-preview.svg")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{}")
                .exchange()
                .expectStatus().isUnauthorized();
    }

    private static Mono<RpgAvatarConfig> deny(HttpStatus status, String reason) {
        return Mono.error(new ResponseStatusException(status, reason));
    }

    /** Well-formed XML with an {@code <svg>} root — not merely "starts with &lt;". */
    private static void assertWellFormedSvg(String body) {
        try {
            javax.xml.parsers.DocumentBuilderFactory f =
                    javax.xml.parsers.DocumentBuilderFactory.newInstance();
            f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            org.w3c.dom.Document d = f.newDocumentBuilder()
                    .parse(new org.xml.sax.InputSource(new java.io.StringReader(body)));
            assertThat(d.getDocumentElement().getTagName()).isEqualTo("svg");
        } catch (Exception e) {
            throw new AssertionError("response body is not well-formed XML: " + e.getMessage(), e);
        }
    }

    private static JwtMutator asPublisher() {
        return mockJwt()
                .authorities(new SimpleGrantedAuthority("ROLE_PUBLISHER"))
                .jwt(j -> j.subject("alice"));
    }

    /** No-op stub — behaviour is set per-test via the public fields. */
    static class StubAvatarConfigService extends AvatarConfigService {
        Mono<RpgAvatarConfig> getResult = Mono.empty();
        Mono<RpgAvatarConfig> putResult = Mono.empty();
        Mono<RpgAvatarConfig> putSpecResult = Mono.empty();
        IllegalArgumentException putThrows;
        CharacterSpec lastPutSpec;
        int getCalls;
        int putCalls;
        int putSpecCalls;

        StubAvatarConfigService() { super(null, null, null); }

        // spec() is deliberately NOT overridden: the renditions must reach
        // this stub through the real spec() → get() delegation, so bypassing
        // the guarded entry point NPEs on the null repository instead of
        // quietly returning a programmed value.
        @Override
        public Mono<RpgAvatarConfig> get(UUID projectId, String ownerCasdoorId) {
            getCalls++;
            return getResult;
        }

        @Override
        public Mono<RpgAvatarConfig> put(UUID projectId, RpgAvatarConfig in, String ownerCasdoorId) {
            putCalls++;
            if (putThrows != null) throw putThrows;
            return putResult;
        }

        @Override
        public Mono<RpgAvatarConfig> putSpec(UUID projectId, CharacterSpec spec, String ownerCasdoorId) {
            putSpecCalls++;
            lastPutSpec = spec;
            return putSpecResult;
        }
    }

    @TestConfiguration
    static class MockConfig {
        @Bean StubAvatarConfigService avatarConfigService() { return new StubAvatarConfigService(); }
    }
}
