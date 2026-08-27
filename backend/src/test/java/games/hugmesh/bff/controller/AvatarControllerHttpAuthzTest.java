package games.hugmesh.bff.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import games.hugmesh.bff.config.BanGateFilter;
import games.hugmesh.bff.config.DomainGuardFilter;
import games.hugmesh.bff.config.RateLimitFilter;
import games.hugmesh.bff.config.SecurityConfig;
import games.hugmesh.bff.config.SecurityHeadersFilter;
import games.hugmesh.bff.controller.support.SecuritySliceSupport;
import games.hugmesh.bff.model.UserAvatar;
import games.hugmesh.bff.repository.PlayerCharacterRepository;
import games.hugmesh.bff.repository.UserAvatarRepository;
import games.hugmesh.bff.service.UserAvatarService;
import games.hugmesh.character.CharacterDocument;
import games.hugmesh.character.CharacterSpec;
import io.r2dbc.postgresql.codec.Json;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webflux.test.autoconfigure.WebFluxTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan.Filter;
import org.springframework.context.annotation.FilterType;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.reactive.server.SecurityMockServerConfigurers.csrf;

/**
 * HTTP/filter-layer matrix for {@link AvatarController}, driving the REAL
 * {@link UserAvatarService} over mocked repositories so ownership, the cap,
 * the atomic-select delegation, and the promised 400s are all proven at the
 * WebTestClient boundary (repo taxonomy: user / cross-user ALLOW+DENY /
 * fail-closed).
 *
 * <p>{@code /api/avatars/**} sits under {@code anyExchange().authenticated()}
 * — anonymous is 401 everywhere. The one deliberate asymmetry under test:
 * {@code image.svg} must render for a NON-owner authenticated caller (the
 * avatar-as-capability-URL design), while every mutation is owner-only.
 */
@WebFluxTest(controllers = AvatarController.class,
        excludeFilters = @Filter(type = FilterType.ASSIGNABLE_TYPE, classes = {
                BanGateFilter.class, DomainGuardFilter.class,
                RateLimitFilter.class, SecurityHeadersFilter.class}))
@Import({SecurityConfig.class, SecuritySliceSupport.TenantBeans.class, UserAvatarService.class,
        AvatarControllerHttpAuthzTest.Jackson2Bridge.class})
class AvatarControllerHttpAuthzTest {

    /**
     * Boot 4 auto-configures Jackson 3 (tools.jackson) only; the Jackson 2
     * {@code ObjectMapper} the service injects is a {@code WebConfig} bean the
     * slice does not load — bridge it here exactly as WebConfig does.
     */
    @TestConfiguration(proxyBeanMethods = false)
    static class Jackson2Bridge {
        @Bean
        ObjectMapper jackson2ObjectMapper() { return new ObjectMapper(); }
    }

    @Autowired WebTestClient web;
    @Autowired ObjectMapper mapper;

    @MockitoBean UserAvatarRepository avatars;
    @MockitoBean PlayerCharacterRepository characters;

    private UserAvatar row(String id, String owner) {
        UserAvatar a = new UserAvatar();
        a.setId(id);
        a.setCasdoorId(owner);
        a.setName("Saved");
        try {
            a.setDocument(Json.of(mapper.writeValueAsString(
                    CharacterDocument.toMap(CharacterSpec.defaults(), "Saved"))));
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new RuntimeException(e);
        }
        a.setCreatedAt(Instant.now());
        a.setUpdatedAt(Instant.now());
        return a;
    }

    // ── unauthenticated: 401 everywhere, repos untouched ─────────────────────

    @Test
    void list_anonymous_is401() {
        web.get().uri("/api/avatars").exchange().expectStatus().isUnauthorized();
        verify(avatars, never()).findByCasdoorIdOrderByCreatedAtAsc(anyString());
    }

    @Test
    void create_anonymous_is401_andNeverSaves() {
        web.mutateWith(csrf()).post().uri("/api/avatars")
                .bodyValue(Map.of("name", "Hero"))
                .exchange()
                .expectStatus().isUnauthorized();
        verify(avatars, never()).save(any());
    }

    @Test
    void imageSvg_anonymous_is401() {
        // Any AUTHENTICATED user may read the image — but authentication is
        // still required; the capability-URL design does not mean public.
        web.get().uri("/api/avatars/a1/image.svg").exchange().expectStatus().isUnauthorized();
        verify(avatars, never()).findById(anyString());
    }

    @Test
    void bind_anonymous_is401() {
        web.mutateWith(csrf()).put().uri("/api/characters/7/avatar")
                .bodyValue(Map.of("avatarId", "a1"))
                .exchange()
                .expectStatus().isUnauthorized();
        verify(characters, never()).findById(any(Long.class));
    }

    // ── create + list round-trip ─────────────────────────────────────────────

    @Test
    void create_thenList_roundTrips_withServerMintedIdAndOwner() {
        when(avatars.countByCasdoorId("alice")).thenReturn(Mono.just(0L));
        when(avatars.save(any(UserAvatar.class)))
                .thenAnswer(inv -> Mono.just(inv.getArgument(0, UserAvatar.class)));

        web.mutateWith(SecuritySliceSupport.user("alice"))
                .mutateWith(csrf())
                .post().uri("/api/avatars")
                .bodyValue(Map.of(
                        "name", "Hero",
                        "document", Map.of(
                                "kind", "hugmesh.character",
                                "shape", Map.of("outfit", "tunic"),
                                // Mass-assignment probes: unknown top-level keys are
                                // dropped by the tolerant reader, never persisted.
                                "id", "attacker-chosen",
                                "casdoorId", "someone-else")))
                .exchange()
                .expectStatus().isCreated()
                .expectBody()
                .jsonPath("$.name").isEqualTo("Hero")
                .jsonPath("$.document.shape.outfit").isEqualTo("tunic")
                .jsonPath("$.active").isEqualTo(false);

        ArgumentCaptor<UserAvatar> saved = ArgumentCaptor.forClass(UserAvatar.class);
        verify(avatars).save(saved.capture());
        // Server-minted 43-char uid + owner stamped from the JWT subject — the
        // body's id/casdoorId probes went nowhere.
        assertThat(saved.getValue().getId()).hasSize(43).isNotEqualTo("attacker-chosen");
        assertThat(saved.getValue().getCasdoorId()).isEqualTo("alice");

        when(avatars.findByCasdoorIdOrderByCreatedAtAsc("alice"))
                .thenReturn(Flux.just(saved.getValue()));
        web.mutateWith(SecuritySliceSupport.user("alice"))
                .get().uri("/api/avatars")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$[0].id").isEqualTo(saved.getValue().getId())
                .jsonPath("$[0].document.shape.outfit").isEqualTo("tunic");
    }

    // ── document / name validation: synchronous 400 ──────────────────────────

    @Test
    void create_outOfRangeProportion_is400_andNeverSaves() {
        web.mutateWith(SecuritySliceSupport.user("alice"))
                .mutateWith(csrf())
                .post().uri("/api/avatars")
                .bodyValue(Map.of("name", "Bad",
                        "document", Map.of("proportions", Map.of("height", 9.9))))
                .exchange()
                .expectStatus().isBadRequest()
                .expectBody(String.class)
                .value(body -> assertThat(body).contains("heightScale must be between"));
        verify(avatars, never()).save(any());
    }

    @Test
    void create_unknownToken_is400() {
        web.mutateWith(SecuritySliceSupport.user("alice"))
                .mutateWith(csrf())
                .post().uri("/api/avatars")
                .bodyValue(Map.of("name", "Bad",
                        "document", Map.of("shape", Map.of("body", "dragon"))))
                .exchange()
                .expectStatus().isBadRequest()
                .expectBody(String.class)
                .value(body -> assertThat(body).contains("unknown bodyShape"));
    }

    @Test
    void create_blankName_is400() {
        web.mutateWith(SecuritySliceSupport.user("alice"))
                .mutateWith(csrf())
                .post().uri("/api/avatars")
                .bodyValue(Map.of("name", "  "))
                .exchange()
                .expectStatus().isBadRequest()
                .expectBody(String.class)
                .value(body -> assertThat(body).contains("name is required"));
        verify(avatars, never()).save(any());
    }

    // ── cap ──────────────────────────────────────────────────────────────────

    @Test
    void create_atCap_is409() {
        when(avatars.countByCasdoorId("alice"))
                .thenReturn(Mono.just((long) UserAvatarService.MAX_AVATARS_PER_USER));
        web.mutateWith(SecuritySliceSupport.user("alice"))
                .mutateWith(csrf())
                .post().uri("/api/avatars")
                .bodyValue(Map.of("name", "Thirteenth"))
                .exchange()
                .expectStatus().isEqualTo(409);
        verify(avatars, never()).save(any());
    }

    // ── cross-user DENY on mutations ─────────────────────────────────────────

    @Test
    void update_foreign_is403_andNeverSaves() {
        when(avatars.findById("a1")).thenReturn(Mono.just(row("a1", "bob")));
        web.mutateWith(SecuritySliceSupport.user("eve"))
                .mutateWith(csrf())
                .put().uri("/api/avatars/a1")
                .bodyValue(Map.of("name", "Stolen"))
                .exchange()
                .expectStatus().isForbidden();
        verify(avatars, never()).save(any());
    }

    @Test
    void delete_foreign_is403_andNeverDeletes() {
        when(avatars.findById("a1")).thenReturn(Mono.just(row("a1", "bob")));
        web.mutateWith(SecuritySliceSupport.user("eve"))
                .mutateWith(csrf())
                .delete().uri("/api/avatars/a1")
                .exchange()
                .expectStatus().isForbidden();
        verify(avatars, never()).delete(any());
    }

    @Test
    void select_foreign_is403_andNeverActivates() {
        when(avatars.findById("a1")).thenReturn(Mono.just(row("a1", "bob")));
        web.mutateWith(SecuritySliceSupport.user("eve"))
                .mutateWith(csrf())
                .post().uri("/api/avatars/a1/select")
                .exchange()
                .expectStatus().isForbidden();
        verify(avatars, never()).activateExclusively(anyString(), anyString());
    }

    // ── select: owner ALLOW via the single-statement swap ────────────────────

    @Test
    void select_owner_is204_andDelegatesToAtomicSwap() {
        when(avatars.findById("a1")).thenReturn(Mono.just(row("a1", "alice")));
        when(avatars.activateExclusively("alice", "a1")).thenReturn(Mono.just(2L));
        web.mutateWith(SecuritySliceSupport.user("alice"))
                .mutateWith(csrf())
                .post().uri("/api/avatars/a1/select")
                .exchange()
                .expectStatus().isNoContent();
        // The swap is the single UPDATE — never a read-then-write save().
        verify(avatars).activateExclusively(eq("alice"), eq("a1"));
        verify(avatars, never()).save(any());
    }

    // ── image.svg: readable by any authenticated user ────────────────────────

    @Test
    void imageSvg_nonOwnerAuthenticated_is200Svg() {
        // The deliberate asymmetry: carol does NOT own bob's avatar and still
        // gets the render — an avatar exists to be seen (chat, forum, profile).
        when(avatars.findById("a1")).thenReturn(Mono.just(row("a1", "bob")));
        web.mutateWith(SecuritySliceSupport.user("carol"))
                .get().uri("/api/avatars/a1/image.svg?expression=happy&w=128&h=192")
                .exchange()
                .expectStatus().isOk()
                .expectHeader().contentTypeCompatibleWith(MediaType.valueOf("image/svg+xml"))
                .expectBody(String.class)
                .value(body -> assertThat(body).startsWith("<svg"));
    }

    @Test
    void imageSvg_mouthOpenness_isAcceptedAndChangesTheFrame() {
        when(avatars.findById("a1")).thenReturn(Mono.just(row("a1", "bob")));
        String closed = web.mutateWith(SecuritySliceSupport.user("carol"))
                .get().uri("/api/avatars/a1/image.svg?expression=happy&portrait=true")
                .exchange().expectStatus().isOk()
                .expectBody(String.class).returnResult().getResponseBody();
        String open = web.mutateWith(SecuritySliceSupport.user("carol"))
                .get().uri("/api/avatars/a1/image.svg?expression=happy&portrait=true&mouth=0.9")
                .exchange().expectStatus().isOk()
                .expectBody(String.class).returnResult().getResponseBody();
        assertThat(open).isNotEqualTo(closed);
        assertThat(closed).contains("<path data-part='Mouth'");
        assertThat(open).contains("<ellipse data-part='Mouth'");
    }

    // ── model.glb: the same rig as a glTF binary, same read rule as the SVG ──
    @Test
    void modelGlb_nonOwnerAuthenticated_is200Glb() {
        when(avatars.findById("a1")).thenReturn(Mono.just(row("a1", "bob")));
        web.mutateWith(SecuritySliceSupport.user("carol"))
                .get().uri("/api/avatars/a1/model.glb")
                .exchange()
                .expectStatus().isOk()
                .expectHeader().contentTypeCompatibleWith(MediaType.valueOf("model/gltf-binary"))
                .expectBody(byte[].class)
                .value(body -> assertThat(new String(body, 0, 4, java.nio.charset.StandardCharsets.US_ASCII)).isEqualTo("glTF"));
    }

    @Test
    void modelGlb_anonymous_is401() {
        web.get().uri("/api/avatars/a1/model.glb").exchange().expectStatus().isUnauthorized();
    }

    @Test
    void imageSvg_unknownId_is404() {
        when(avatars.findById("ghost")).thenReturn(Mono.empty());
        web.mutateWith(SecuritySliceSupport.user("carol"))
                .get().uri("/api/avatars/ghost/image.svg")
                .exchange()
                .expectStatus().isNotFound();
    }

    @Test
    void imageSvg_unknownExpression_is400_beforeAnyLookup() {
        web.mutateWith(SecuritySliceSupport.user("carol"))
                .get().uri("/api/avatars/a1/image.svg?expression=smug")
                .exchange()
                .expectStatus().isBadRequest()
                .expectBody(String.class)
                .value(body -> assertThat(body).contains("unknown expression"));
        verify(avatars, never()).findById(anyString());
    }

    @Test
    void imageSvg_outOfRangeWidth_is400() {
        // The renderer's own MIN_DIM..MAX_DIM guard, surfaced as the promised
        // 400 through the controller-local handler even though the throw
        // happens inside the reactive chain.
        when(avatars.findById("a1")).thenReturn(Mono.just(row("a1", "bob")));
        web.mutateWith(SecuritySliceSupport.user("carol"))
                .get().uri("/api/avatars/a1/image.svg?w=8")
                .exchange()
                .expectStatus().isBadRequest()
                .expectBody(String.class)
                .value(body -> assertThat(body).contains("width"));
    }
}
