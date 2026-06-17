package games.snapdragon.bff.controller;

import games.snapdragon.bff.config.BanGateFilter;
import games.snapdragon.bff.config.DomainGuardFilter;
import games.snapdragon.bff.config.RateLimitFilter;
import games.snapdragon.bff.config.SecurityConfig;
import games.snapdragon.bff.config.SecurityHeadersFilter;
import games.snapdragon.bff.controller.support.SecuritySliceSupport;
import games.snapdragon.bff.model.GameSessionDto;
import games.snapdragon.bff.service.GameSessionService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.reactive.WebFluxTest;
import org.springframework.context.annotation.ComponentScan.Filter;
import org.springframework.context.annotation.FilterType;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.reactive.server.SecurityMockServerConfigurers.csrf;
import static org.springframework.security.test.web.reactive.server.SecurityMockServerConfigurers.mockJwt;

/**
 * HTTP/filter-layer owner-vs-admin authority + cross-user DENY matrix for
 * {@link MultiplayerSessionController} (mission target #5).
 *
 * <p>{@code /api/sessions/**} is {@code authenticated()} in {@code SecurityConfig}
 * (in {@code PLAYER_PATHS}), so anonymous → 401. The owner / admin authority is
 * enforced INSIDE the controller and asserted here at the HTTP boundary:
 * <ul>
 *   <li><strong>close</strong> — only the creator may close a session; a
 *       non-creator (even an authenticated player) is 403 and the session is
 *       never closed (cross-user DENY);</li>
 *   <li><strong>pause / resume</strong> — creator OR admin; a non-creator
 *       non-admin is 403 and the session is never paused.</li>
 * </ul>
 *
 * <p><b>FINDING (documented, not fixed — see PR body):</b> the pause/resume
 * admin bypass is gated on the LEGACY {@code isAdmin} boolean claim
 * ({@code jwt.getClaimAsBoolean("isAdmin")}), NOT the {@code roles} array the
 * rest of the BFF uses. A modern admin token carrying {@code roles:["admin"]}
 * but no {@code isAdmin:true} is treated as a non-admin here and gets 403 on a
 * session it does not own — see {@link #pause_modernRoleAdmin_isNotTreatedAsAdmin}.
 */
@WebFluxTest(controllers = MultiplayerSessionController.class,
        excludeFilters = @Filter(type = FilterType.ASSIGNABLE_TYPE, classes = {
                BanGateFilter.class, DomainGuardFilter.class,
                RateLimitFilter.class, SecurityHeadersFilter.class}))
@Import({SecurityConfig.class, SecuritySliceSupport.TenantBeans.class})
class MultiplayerSessionControllerHttpAuthzTest {

    @Autowired WebTestClient web;

    @MockitoBean GameSessionService service;

    // 16+ chars matching the controller's {id:[A-Za-z0-9_\-]{16,86}} path regex.
    private static final String SID = "session-abc-0123456789";

    private static GameSessionDto sessionOwnedBy(String creator) {
        return new GameSessionDto(SID, "lobby", "g", creator, 4, 1, "public",
                Instant.now(), null, null, null, null, List.of());
    }

    private void stubGet(String creator) {
        when(service.get(eq(SID))).thenReturn(Mono.just(sessionOwnedBy(creator)));
    }

    // ── close : only the creator ──────────────────────────────────────────────

    @Test
    void close_anonymous_is401() {
        web.mutateWith(csrf())
                .delete().uri("/api/sessions/" + SID)
                .exchange()
                .expectStatus().isUnauthorized();
        verify(service, never()).close(any());
    }

    @Test
    void close_nonCreator_is403_andNeverCloses() {
        stubGet("alice");          // alice owns it
        web.mutateWith(SecuritySliceSupport.user("mallory"))   // mallory does not
                .mutateWith(csrf())
                .delete().uri("/api/sessions/" + SID)
                .exchange()
                .expectStatus().isForbidden();
        verify(service, never()).close(any());
    }

    @Test
    void close_creator_is200_andCloses() {
        stubGet("alice");
        when(service.close(eq(SID))).thenReturn(Mono.empty());

        web.mutateWith(SecuritySliceSupport.user("alice"))
                .mutateWith(csrf())
                .delete().uri("/api/sessions/" + SID)
                .exchange()
                .expectStatus().isOk();
        verify(service).close(eq(SID));
    }

    // ── pause : creator OR (legacy-isAdmin) admin ─────────────────────────────

    @Test
    void pause_anonymous_is401() {
        web.mutateWith(csrf())
                .post().uri("/api/sessions/" + SID + "/pause")
                .exchange()
                .expectStatus().isUnauthorized();
        verify(service, never()).pauseSession(any(), any(), any());
    }

    @Test
    void pause_nonCreatorNonAdmin_is403_andNeverPauses() {
        stubGet("alice");
        web.mutateWith(SecuritySliceSupport.user("mallory"))
                .mutateWith(csrf())
                .post().uri("/api/sessions/" + SID + "/pause")
                .exchange()
                .expectStatus().isForbidden();
        verify(service, never()).pauseSession(any(), any(), any());
    }

    @Test
    void pause_creator_is200() {
        stubGet("alice");
        when(service.pauseSession(eq(SID), eq("alice"), any()))
                .thenReturn(Mono.just(sessionOwnedBy("alice")));

        web.mutateWith(SecuritySliceSupport.user("alice"))
                .mutateWith(csrf())
                .post().uri("/api/sessions/" + SID + "/pause")
                .exchange()
                .expectStatus().isOk();
        verify(service).pauseSession(eq(SID), eq("alice"), any());
    }

    @Test
    void pause_legacyIsAdminClaim_canPauseAnyOnesSession() {
        // The controller's admin bypass reads the legacy isAdmin boolean claim.
        stubGet("alice");
        when(service.pauseSession(eq(SID), eq("modder"), any()))
                .thenReturn(Mono.just(sessionOwnedBy("alice")));

        web.mutateWith(mockJwt()
                        .jwt(j -> j.subject("modder").claim("isAdmin", true)
                                .claim("roles", List.of("admin")))
                        .authorities(new org.springframework.security.core.authority.SimpleGrantedAuthority("ROLE_USER"),
                                new org.springframework.security.core.authority.SimpleGrantedAuthority("ROLE_ADMIN")))
                .mutateWith(csrf())
                .post().uri("/api/sessions/" + SID + "/pause")
                .exchange()
                .expectStatus().isOk();
        verify(service).pauseSession(eq(SID), eq("modder"), any());
    }

    @Test
    void pause_modernRoleAdmin_isNotTreatedAsAdmin() {
        // FINDING: a modern admin token (roles:["admin"], NO isAdmin boolean) is
        // NOT recognized as admin by this controller's pause gate, so it is 403
        // on a session it does not own. Pins the inconsistency.
        stubGet("alice");
        web.mutateWith(SecuritySliceSupport.admin("root"))   // roles-based admin, no isAdmin claim
                .mutateWith(csrf())
                .post().uri("/api/sessions/" + SID + "/pause")
                .exchange()
                .expectStatus().isForbidden();
        verify(service, never()).pauseSession(any(), any(), any());
    }

    @Test
    void resume_nonCreatorNonAdmin_is403() {
        stubGet("alice");
        web.mutateWith(SecuritySliceSupport.user("mallory"))
                .mutateWith(csrf())
                .post().uri("/api/sessions/" + SID + "/resume")
                .exchange()
                .expectStatus().isForbidden();
        verify(service, never()).resumeSession(any(), any());
    }
}
