package games.hugmesh.bff.controller;

import games.hugmesh.bff.config.BanGateFilter;
import games.hugmesh.bff.config.DomainGuardFilter;
import games.hugmesh.bff.config.RateLimitFilter;
import games.hugmesh.bff.config.SecurityConfig;
import games.hugmesh.bff.config.SecurityHeadersFilter;
import games.hugmesh.bff.controller.support.SecuritySliceSupport;
import games.hugmesh.bff.model.GameSessionDto;
import games.hugmesh.bff.service.GameSessionService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webflux.test.autoconfigure.WebFluxTest;
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
 * <p><b>FIXED (was a documented finding):</b> the pause/resume admin bypass
 * previously read the LEGACY {@code isAdmin} boolean claim
 * ({@code jwt.getClaimAsBoolean("isAdmin")}) instead of the {@code roles} array
 * the rest of the BFF uses. It now uses the canonical
 * {@code Roles.isAdmin(jwt)} (roles-claim / {@code ROLE_ADMIN}) check, so a
 * modern admin token ({@code roles:["admin"]}) IS admin
 * ({@link #pause_modernRoleAdmin_isTreatedAsAdmin}) and the legacy boolean
 * claim ALONE is NOT a bypass
 * ({@link #pause_legacyIsAdminClaimAlone_isNotTreatedAsAdmin}).
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

    // ── pause : creator OR (roles-claim) admin ────────────────────────────────

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
    void pause_legacyIsAdminClaimAlone_isNotTreatedAsAdmin() {
        // FIXED (was pause_legacyIsAdminClaim_canPauseAnyOnesSession): the
        // pause gate no longer trusts the LEGACY isAdmin boolean claim. A token
        // carrying ONLY isAdmin:true (no roles array, no ROLE_ADMIN authority)
        // is a plain player to this controller — so it is 403 on a session it
        // does not own and never pauses. The legacy boolean is NOT a bypass.
        stubGet("alice");
        web.mutateWith(mockJwt()
                        .jwt(j -> j.subject("modder").claim("isAdmin", true))
                        .authorities(new org.springframework.security.core.authority.SimpleGrantedAuthority("ROLE_USER")))
                .mutateWith(csrf())
                .post().uri("/api/sessions/" + SID + "/pause")
                .exchange()
                .expectStatus().isForbidden();
        verify(service, never()).pauseSession(any(), any(), any());
    }

    @Test
    void pause_modernRoleAdmin_isTreatedAsAdmin() {
        // FIXED (was pause_modernRoleAdmin_isNotTreatedAsAdmin): a modern admin
        // token (roles:["admin"] -> ROLE_ADMIN authority, NO isAdmin boolean) IS
        // recognized as admin by the pause gate via the canonical Roles.isAdmin
        // check the rest of the BFF uses — so it can pause a session it does not
        // own (admin moderation bypass).
        stubGet("alice");
        when(service.pauseSession(eq(SID), eq("root"), any()))
                .thenReturn(Mono.just(sessionOwnedBy("alice")));

        web.mutateWith(SecuritySliceSupport.admin("root"))   // roles-based admin, no isAdmin claim
                .mutateWith(csrf())
                .post().uri("/api/sessions/" + SID + "/pause")
                .exchange()
                .expectStatus().isOk();
        verify(service).pauseSession(eq(SID), eq("root"), any());
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

    // ── member liveness : the roster poll IS the heartbeat ────────────────────
    //
    // `GET /api/sessions/{id}` is the one signal a running room reliably
    // produces for an IDENTIFIED member — the Godot session bridge polls it
    // every 5 s over the host shell's authenticated HttpClient, independent of
    // player input. The alternatives do not work: a position-derived heartbeat
    // never fires because GameStateService.applyControl maps only MOVE_*/JUMP
    // so every SOTN_* action leaves the position unchanged, and the SSE stream
    // carries no identity because `new EventSource(url)` sends no credentials
    // and /api/game/** is public. If this wiring regresses, the reaper reaps
    // players who are still playing — so it is pinned at the HTTP boundary.

    @Test
    void get_stampsLivenessForTheCallingMember() {
        stubGet("alice");
        when(service.heartbeat(any(), any())).thenReturn(Mono.empty());

        web.mutateWith(SecuritySliceSupport.user("alice"))
                .get().uri("/api/sessions/" + SID)
                .exchange()
                .expectStatus().isOk();

        verify(service).heartbeat(eq(SID), eq("alice"));
    }

    @Test
    void get_stampsForTheCallerNotTheCreator() {
        // A guest polling the roster must refresh THEIR seat, not the host's.
        stubGet("alice");
        when(service.heartbeat(any(), any())).thenReturn(Mono.empty());

        web.mutateWith(SecuritySliceSupport.user("bob"))
                .get().uri("/api/sessions/" + SID)
                .exchange()
                .expectStatus().isOk();

        verify(service).heartbeat(eq(SID), eq("bob"));
    }

    @Test
    void get_anonymous_is401_andStampsNothing() {
        stubGet("alice");

        web.get().uri("/api/sessions/" + SID)
                .exchange()
                .expectStatus().isUnauthorized();

        verify(service, never()).heartbeat(any(), any());
    }

    // ── leave : the unload flag that carries the superseded-tab guard ─────────

    @Test
    void leave_defaultsToAnExplicitPlayerLeave() {
        // No query param ⇒ the player asked. Never dropped, whatever the
        // takeover state — the "leave" control only exists in the tab they are
        // actually looking at.
        when(service.leave(any(), any(), org.mockito.ArgumentMatchers.anyBoolean()))
                .thenReturn(Mono.empty());

        web.mutateWith(SecuritySliceSupport.user("bob"))
                .mutateWith(csrf())
                .delete().uri("/api/sessions/" + SID + "/me")
                .exchange()
                .expectStatus().isOk();

        verify(service).leave(eq(SID), eq("bob"), eq(false));
    }

    @Test
    void leave_threadsTheUnloadHintThroughToTheService() {
        // The browser's pagehide leg. The flag is what lets the BFF apply its
        // superseded-tab backstop, so losing it in the binding would silently
        // turn a frozen old tab's eventual close into an eviction of the new
        // tab's player.
        when(service.leave(any(), any(), org.mockito.ArgumentMatchers.anyBoolean()))
                .thenReturn(Mono.empty());

        web.mutateWith(SecuritySliceSupport.user("bob"))
                .mutateWith(csrf())
                .delete().uri("/api/sessions/" + SID + "/me?unload=true")
                .exchange()
                .expectStatus().isOk();

        verify(service).leave(eq(SID), eq("bob"), eq(true));
    }

    @Test
    void leave_anonymous_is401() {
        web.mutateWith(csrf())
                .delete().uri("/api/sessions/" + SID + "/me?unload=true")
                .exchange()
                .expectStatus().isUnauthorized();

        verify(service, never()).leave(any(), any(), org.mockito.ArgumentMatchers.anyBoolean());
    }
}
