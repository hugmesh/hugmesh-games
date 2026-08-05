package games.hugmesh.vnbuilder.controller;

import games.hugmesh.vnbuilder.entity.VnAsset;
import games.hugmesh.vnbuilder.service.AssetStorageService;
import games.hugmesh.vnbuilder.service.ProceduralAssetService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webflux.test.autoconfigure.WebFluxTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.method.configuration.EnableReactiveMethodSecurity;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.test.web.reactive.server.SecurityMockServerConfigurers.JwtMutator;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.reactive.server.SecurityMockServerConfigurers.csrf;
import static org.springframework.security.test.web.reactive.server.SecurityMockServerConfigurers.mockJwt;

/**
 * HTTP-contract specs for the generate endpoint's character-portrait kind.
 * The 400-not-500 case is the load-bearing one: {@code CharacterDocument}
 * validation throws synchronously in the controller method, and these tests
 * pin that it surfaces as the promised 400 through the real WebFlux path
 * (thrown inside the returned Mono it would arrive as a 500).
 */
@WebFluxTest(controllers = AssetController.class)
@Import(AssetControllerTest.MockConfig.class)
@EnableReactiveMethodSecurity
class AssetControllerTest {

    @Autowired WebTestClient web;
    @Autowired AssetStorageService storage;

    @Test
    void generate_characterPortrait_persistsCanonicalSvg() {
        UUID pid = UUID.randomUUID();
        VnAsset saved = new VnAsset();
        saved.setId(UUID.randomUUID()); saved.setProjectId(pid);
        saved.setSlug("dani"); saved.setKind("character-portrait");
        when(storage.storeGenerated(eq(pid), anyString(), anyString(), anyString(),
                any(byte[].class), anyString())).thenReturn(Mono.just(saved));

        web.mutateWith(csrf()).mutateWith(asPublisher())
                .post().uri("/api/vn/projects/{pid}/assets/generate", pid)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"slug\":\"dani\",\"kind\":\"character-portrait\","
                        + "\"character\":{\"kind\":\"hugmesh.character\","
                        + "\"colors\":{\"body\":\"#112233\"},\"expression\":\"happy\"}}")
                .exchange()
                .expectStatus().isCreated()
                .expectBody().jsonPath("$.kind").isEqualTo("character-portrait");

        ArgumentCaptor<byte[]> bytes = ArgumentCaptor.forClass(byte[].class);
        verify(storage).storeGenerated(eq(pid), eq("dani"), eq("character-portrait"),
                eq(ProceduralAssetService.SVG_MIME), bytes.capture(), anyString());
        assertThat(new String(bytes.getValue(), StandardCharsets.UTF_8)).startsWith("<svg");
    }

    @Test
    void generate_characterPortrait_badDocumentIs400NotA500() {
        web.mutateWith(csrf()).mutateWith(asPublisher())
                .post().uri("/api/vn/projects/{pid}/assets/generate", UUID.randomUUID())
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"slug\":\"dani\",\"kind\":\"character-portrait\","
                        + "\"character\":{\"colors\":{\"body\":\"not-a-colour\"}}}")
                .exchange()
                .expectStatus().isBadRequest();
        // Rejected before any storage side effect, not after.
        verifyNoInteractions(storage);
    }

    @Test
    void generate_unauthenticated_isRejected() {
        web.mutateWith(csrf())
                .post().uri("/api/vn/projects/{pid}/assets/generate", UUID.randomUUID())
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"slug\":\"dani\",\"kind\":\"character-portrait\"}")
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
        @Bean AssetStorageService assetStorageService() { return mock(AssetStorageService.class); }
        // Real service — the character-portrait rendering path is under test.
        @Bean ProceduralAssetService proceduralAssetService() { return new ProceduralAssetService(); }
    }
}
