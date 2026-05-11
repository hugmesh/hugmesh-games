package games.snapdragon.vnbuilder.controller;

import games.snapdragon.vnbuilder.entity.VnProject;
import games.snapdragon.vnbuilder.service.VnProjectService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.reactive.WebFluxTest;
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

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.reactive.server.SecurityMockServerConfigurers.csrf;
import static org.springframework.security.test.web.reactive.server.SecurityMockServerConfigurers.mockJwt;

@WebFluxTest(controllers = VnProjectController.class)
@Import(VnProjectControllerTest.MockConfig.class)
@EnableReactiveMethodSecurity
class VnProjectControllerTest {

    @Autowired WebTestClient web;
    @Autowired VnProjectService svc;

    @Test
    void post_invalidSlug_returns400() {
        when(svc.create(any(), anyString()))
                .thenReturn(Mono.error(new ResponseStatusException(HttpStatus.BAD_REQUEST, "slug invalid")));

        web.mutateWith(csrf()).mutateWith(asPublisher())
                .post().uri("/api/vn/projects")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"slug\":\"BadSlug\",\"displayName\":\"VN\"}")
                .exchange()
                .expectStatus().isBadRequest();
    }

    @Test
    void post_happy_returnsCreated() {
        VnProject saved = new VnProject();
        saved.setId(UUID.randomUUID()); saved.setSlug("my-vn"); saved.setDisplayName("My VN");
        when(svc.create(any(), anyString())).thenReturn(Mono.just(saved));

        web.mutateWith(csrf()).mutateWith(asPublisher())
                .post().uri("/api/vn/projects")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"slug\":\"my-vn\",\"displayName\":\"My VN\"}")
                .exchange()
                .expectStatus().isCreated()
                .expectBody().jsonPath("$.slug").isEqualTo("my-vn");
    }

    @Test
    void unauthenticated_isRejected() {
        web.get().uri("/api/vn/projects").exchange().expectStatus().isUnauthorized();
    }

    private static JwtMutator asPublisher() {
        return mockJwt()
                .authorities(new SimpleGrantedAuthority("ROLE_PUBLISHER"))
                .jwt(j -> j.subject("alice"));
    }

    @TestConfiguration
    static class MockConfig {
        @Bean VnProjectService vnProjectService() { return mock(VnProjectService.class); }
    }
}
