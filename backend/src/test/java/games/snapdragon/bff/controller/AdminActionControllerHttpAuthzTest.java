package games.snapdragon.bff.controller;

import games.snapdragon.bff.config.BanGateFilter;
import games.snapdragon.bff.config.DomainGuardFilter;
import games.snapdragon.bff.config.RateLimitFilter;
import games.snapdragon.bff.config.SecurityConfig;
import games.snapdragon.bff.config.SecurityHeadersFilter;
import games.snapdragon.bff.controller.support.SecuritySliceSupport;
import games.snapdragon.bff.model.AdminAction;
import games.snapdragon.bff.service.AdminActionService;
import games.snapdragon.bff.service.SessionService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.reactive.WebFluxTest;
import org.springframework.context.annotation.ComponentScan.Filter;
import org.springframework.context.annotation.FilterType;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.Duration;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.reactive.server.SecurityMockServerConfigurers.csrf;

/**
 * HTTP/filter-layer authorization matrix for {@link AdminActionController}
 * (mission target #4: admin SSE/streaming — owner==subject + role gate).
 *
 * <p>Three policies, all asserted at the WebTestClient boundary:
 * <ul>
 *   <li>kick / ban / unban / history are {@code @PreAuthorize('ROLE_ADMIN')}
 *       AND under {@code /api/admin/**} → a USER/MOD is 403, anonymous 401, and
 *       a denied caller's moderation action NEVER reaches the service;</li>
 *   <li>{@code GET /me/admin-actions/stream} is {@code isAuthenticated()} (401
 *       anonymous) and the SSE feed is filtered to {@code target == JWT.sub}:
 *       a subscriber ONLY sees admin-actions targeting THEMSELVES — they cannot
 *       observe a kick/ban aimed at another user (owner==subject SSE);</li>
 *   <li>{@code GET /me/ban-status} is {@code isAuthenticated()} and resolved for
 *       the JWT subject (no param).</li>
 * </ul>
 */
@WebFluxTest(controllers = AdminActionController.class,
        excludeFilters = @Filter(type = FilterType.ASSIGNABLE_TYPE, classes = {
                BanGateFilter.class, DomainGuardFilter.class,
                RateLimitFilter.class, SecurityHeadersFilter.class}))
@Import({SecurityConfig.class, SecuritySliceSupport.TenantBeans.class})
class AdminActionControllerHttpAuthzTest {

    @Autowired WebTestClient web;

    @MockitoBean AdminActionService actions;
    @MockitoBean SessionService sessions;

    private static AdminAction action(String target, String kind) {
        AdminAction a = new AdminAction();
        a.setTargetCasdoorId(target);
        a.setActorCasdoorId("root");
        a.setAction(kind);
        return a;
    }

    // ── role gate on kick/ban/unban/history ───────────────────────────────────

    @Test
    void ban_anonymous_is401_andNeverActs() {
        web.mutateWith(csrf())
                .post().uri("/api/admin/actions/ban/victim")
                .exchange()
                .expectStatus().isUnauthorized();
        verify(actions, never()).ban(any(), any(), any(), any());
    }

    @Test
    void ban_user_is403_andNeverActs() {
        web.mutateWith(SecuritySliceSupport.user("eve"))
                .mutateWith(csrf())
                .post().uri("/api/admin/actions/ban/victim")
                .exchange()
                .expectStatus().isForbidden();
        verify(actions, never()).ban(any(), any(), any(), any());
        verify(sessions, never()).disconnect(any());
    }

    @Test
    void kick_mod_is403() {
        web.mutateWith(SecuritySliceSupport.mod("m"))
                .mutateWith(csrf())
                .post().uri("/api/admin/actions/kick/victim")
                .exchange()
                .expectStatus().isForbidden();
        verify(actions, never()).kick(any(), any(), any());
    }

    @Test
    void history_user_is403() {
        web.mutateWith(SecuritySliceSupport.user("eve"))
                .get().uri("/api/admin/actions/victim")
                .exchange()
                .expectStatus().isForbidden();
        verify(actions, never()).historyFor(any());
    }

    @Test
    void ban_admin_is200_actorIsJwtSubject() {
        when(actions.ban(eq("victim"), eq("root"), any(), any()))
                .thenReturn(Mono.just(action("victim", AdminAction.BAN)));
        when(sessions.disconnect(eq("victim"))).thenReturn(Mono.empty());

        web.mutateWith(SecuritySliceSupport.admin("root"))
                .mutateWith(csrf())
                .post().uri("/api/admin/actions/ban/victim")
                .exchange()
                .expectStatus().isOk();
        // The actor recorded is the moderator's JWT subject, never a body field.
        verify(actions).ban(eq("victim"), eq("root"), any(), any());
    }

    // ── /me/ban-status : isAuthenticated, scoped to the subject ───────────────

    @Test
    void banStatus_anonymous_is401() {
        web.get().uri("/api/me/ban-status")
                .exchange()
                .expectStatus().isUnauthorized();
        verify(actions, never()).isBanned(any());
    }

    @Test
    void banStatus_resolvedForJwtSubject() {
        when(actions.isBanned(eq("alice"))).thenReturn(Mono.just(false));
        when(actions.latestFor(eq("alice"))).thenReturn(Mono.empty());

        web.mutateWith(SecuritySliceSupport.user("alice"))
                .get().uri("/api/me/ban-status")
                .exchange()
                .expectStatus().isOk()
                .expectBody().jsonPath("$.banned").isEqualTo(false);
        verify(actions).isBanned(eq("alice"));
        verify(actions, never()).isBanned(eq("bob"));
    }

    // ── /me/admin-actions/stream : isAuthenticated + owner==subject filter ─────

    @Test
    void stream_anonymous_is401() {
        web.get().uri("/api/me/admin-actions/stream")
                .accept(MediaType.TEXT_EVENT_STREAM)
                .exchange()
                .expectStatus().isUnauthorized();
        verify(actions, never()).stream();
    }

    @Test
    void stream_onlyDeliversActionsTargetingTheSubject() {
        // The live feed carries actions for MANY users; the controller filters
        // to target == JWT.sub. alice must see only her own ban event, never the
        // kick aimed at bob — proving owner==subject SSE isolation.
        when(actions.stream()).thenReturn(Flux.just(
                action("bob", AdminAction.KICK),     // for someone else — filtered out
                action("alice", AdminAction.BAN),    // for the subject — delivered
                action("carol", AdminAction.BAN)));  // for someone else — filtered out

        Flux<ServerSentEvent<AdminAction>> body = web
                .mutateWith(SecuritySliceSupport.user("alice"))
                .get().uri("/api/me/admin-actions/stream")
                .accept(MediaType.TEXT_EVENT_STREAM)
                .exchange()
                .expectStatus().isOk()
                .returnResult(new ParameterizedTypeReference<ServerSentEvent<AdminAction>>() {})
                .getResponseBody()
                // drop the periodic keepalive pings; keep only admin-action events
                .filter(e -> "admin-action".equals(e.event()))
                .take(1);

        StepVerifier.create(body)
                .assertNext(e -> {
                    org.assertj.core.api.Assertions.assertThat(e.data()).isNotNull();
                    org.assertj.core.api.Assertions.assertThat(e.data().getTargetCasdoorId())
                            .isEqualTo("alice");
                })
                .thenCancel()
                .verify(Duration.ofSeconds(10));
    }
}
