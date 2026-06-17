package games.snapdragon.bff.controller;

import games.snapdragon.bff.config.BanGateFilter;
import games.snapdragon.bff.config.DomainGuardFilter;
import games.snapdragon.bff.config.RateLimitFilter;
import games.snapdragon.bff.config.SecurityConfig;
import games.snapdragon.bff.config.SecurityHeadersFilter;
import games.snapdragon.bff.controller.support.SecuritySliceSupport;
import games.snapdragon.bff.model.accounts.ModProfile;
import games.snapdragon.bff.repository.accounts.ModProfileRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.reactive.WebFluxTest;
import org.springframework.context.annotation.ComponentScan.Filter;
import org.springframework.context.annotation.FilterType;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Flux;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/**
 * HTTP/filter-layer contract for {@link AppealContactController} (wave-2c
 * target — only a POJO {@link AppealContactControllerTest} of resolver order
 * existed). {@code /api/security/appeal-contact} is PUBLIC by design: a revoked
 * user has no JWT (the gateway stamps {@code X-Revoked}), so the appeal-contact
 * resolver MUST be anonymous-reachable. {@code SecurityConfig} leaves
 * {@code /api/security/**} on {@code anyExchange().permitAll()}.
 *
 * <p>This slice pins that public contract at the REAL filter chain AND the
 * non-leak property the controller's Javadoc promises: the response exposes
 * ONLY the email string — no mod identity, no on-call slot label, nothing for
 * targeted phishing/recon. An authenticated caller sees the same minimal shape.
 */
@WebFluxTest(controllers = AppealContactController.class,
        properties = "app.security.appeal-contact-fallback=fallback@example.com",
        excludeFilters = @Filter(type = FilterType.ASSIGNABLE_TYPE, classes = {
                BanGateFilter.class, DomainGuardFilter.class,
                RateLimitFilter.class, SecurityHeadersFilter.class}))
@Import({SecurityConfig.class, SecuritySliceSupport.TenantBeans.class})
class AppealContactControllerHttpAuthzTest {

    @Autowired WebTestClient web;

    @MockitoBean ModProfileRepository mods;

    private static ModProfile mod(String email, String slot) {
        ModProfile m = new ModProfile();
        m.setContactEmail(email);
        m.setOncallSlot(slot);
        return m;
    }

    @Test
    void appealContact_anonymous_is200_publicByDesign() {
        when(mods.findByOncallSlot(eq(ModProfile.SLOT_PRIMARY)))
                .thenReturn(Flux.just(mod("primary@example.com", "primary")));
        web.get().uri("/api/security/appeal-contact")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.email").isEqualTo("primary@example.com")
                // Non-leak: the ONLY field is email — no mod identity / slot label.
                .jsonPath("$.casdoorUserId").doesNotExist()
                .jsonPath("$.oncallSlot").doesNotExist()
                .jsonPath("$.displayName").doesNotExist();
    }

    @Test
    void appealContact_anonymous_fallsBackToConfiguredAddress_whenNoOnCall() {
        when(mods.findByOncallSlot(eq(ModProfile.SLOT_PRIMARY))).thenReturn(Flux.empty());
        when(mods.findByOncallSlot(eq(ModProfile.SLOT_SECONDARY))).thenReturn(Flux.empty());
        web.get().uri("/api/security/appeal-contact")
                .exchange()
                .expectStatus().isOk()
                .expectBody().jsonPath("$.email").isEqualTo("fallback@example.com");
    }

    @Test
    void appealContact_authenticatedCaller_sameMinimalShape() {
        when(mods.findByOncallSlot(eq(ModProfile.SLOT_PRIMARY))).thenReturn(Flux.empty());
        when(mods.findByOncallSlot(eq(ModProfile.SLOT_SECONDARY)))
                .thenReturn(Flux.just(mod("secondary@example.com", "secondary")));
        web.mutateWith(SecuritySliceSupport.user("alice"))
                .get().uri("/api/security/appeal-contact")
                .exchange()
                .expectStatus().isOk()
                .expectBody().jsonPath("$.email").isEqualTo("secondary@example.com");
    }
}
