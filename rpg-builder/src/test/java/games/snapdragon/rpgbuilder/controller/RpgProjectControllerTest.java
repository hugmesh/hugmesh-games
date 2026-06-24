package games.snapdragon.rpgbuilder.controller;

import games.snapdragon.rpgbuilder.entity.RpgProject;
import games.snapdragon.rpgbuilder.service.RpgProjectService;
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

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.reactive.server.SecurityMockServerConfigurers.csrf;
import static org.springframework.security.test.web.reactive.server.SecurityMockServerConfigurers.mockJwt;

/**
 * Verifies the form-error contract the editor relies on: every
 * service-layer ResponseStatusException reaches the wire as the matching
 * HTTP status + body. mockJwt() injects a real JwtAuthenticationToken
 * so {@code Roles.casdoorIdOf(auth)} resolves cleanly inside the controller.
 */
@WebFluxTest(controllers = RpgProjectController.class)
@Import(RpgProjectControllerTest.MockConfig.class)
@EnableReactiveMethodSecurity
class RpgProjectControllerTest {

    @Autowired WebTestClient web;
    @Autowired RpgProjectService svc;

    @Test
    void post_invalidSlug_returns400WithReason() {
        when(svc.create(any(), anyString()))
                .thenReturn(Mono.error(new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "slug must match ^[a-z0-9][a-z0-9-]{0,63}$")));

        web.mutateWith(csrf()).mutateWith(asPublisher())
                .post().uri("/api/rpg/projects")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"slug\":\"Bad Slug\",\"displayName\":\"Anything\"}")
                .exchange()
                .expectStatus().isBadRequest();
    }

    @Test
    void post_blankName_returns400() {
        when(svc.create(any(), anyString()))
                .thenReturn(Mono.error(new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "displayName is required")));

        web.mutateWith(csrf()).mutateWith(asPublisher())
                .post().uri("/api/rpg/projects")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"slug\":\"good-slug\",\"displayName\":\"\"}")
                .exchange()
                .expectStatus().isBadRequest();
    }

    @Test
    void post_happy_returnsCreatedJson() {
        RpgProject saved = new RpgProject();
        saved.setId(UUID.randomUUID());
        saved.setSlug("my-rpg");
        saved.setDisplayName("My RPG");
        when(svc.create(any(), anyString())).thenReturn(Mono.just(saved));

        web.mutateWith(csrf()).mutateWith(asPublisher())
                .post().uri("/api/rpg/projects")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"slug\":\"my-rpg\",\"displayName\":\"My RPG\"}")
                .exchange()
                .expectStatus().isCreated()
                .expectBody().jsonPath("$.slug").isEqualTo("my-rpg");
    }

    @Test
    void unauthenticated_isRejected() {
        web.get().uri("/api/rpg/projects")
                .exchange()
                .expectStatus().isUnauthorized();
    }

    private static JwtMutator asPublisher() {
        return mockJwt()
                .authorities(new SimpleGrantedAuthority("ROLE_PUBLISHER"))
                .jwt(j -> j.subject("alice"));
    }

    @TestConfiguration
    static class MockConfig {
        @Bean RpgProjectService rpgProjectService() { return mock(RpgProjectService.class); }
    }
}
