package games.hugmesh.rpgbuilder.controller;

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

import java.util.UUID;

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
        svc.putThrows = null;
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

    private static JwtMutator asPublisher() {
        return mockJwt()
                .authorities(new SimpleGrantedAuthority("ROLE_PUBLISHER"))
                .jwt(j -> j.subject("alice"));
    }

    /** No-op stub — behaviour is set per-test via the public fields. */
    static class StubAvatarConfigService extends AvatarConfigService {
        Mono<RpgAvatarConfig> getResult = Mono.empty();
        Mono<RpgAvatarConfig> putResult = Mono.empty();
        IllegalArgumentException putThrows;

        StubAvatarConfigService() { super(null, null, null); }

        @Override
        public Mono<RpgAvatarConfig> get(UUID projectId, String ownerCasdoorId) {
            return getResult;
        }

        @Override
        public Mono<RpgAvatarConfig> put(UUID projectId, RpgAvatarConfig in, String ownerCasdoorId) {
            if (putThrows != null) throw putThrows;
            return putResult;
        }
    }

    @TestConfiguration
    static class MockConfig {
        @Bean StubAvatarConfigService avatarConfigService() { return new StubAvatarConfigService(); }
    }
}
