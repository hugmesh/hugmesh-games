package games.snapdragon.bff.controller;

import games.snapdragon.bff.config.BanGateFilter;
import games.snapdragon.bff.config.DomainGuardFilter;
import games.snapdragon.bff.config.RateLimitFilter;
import games.snapdragon.bff.config.SecurityConfig;
import games.snapdragon.bff.config.SecurityHeadersFilter;
import games.snapdragon.bff.controller.support.SecuritySliceSupport;
import games.snapdragon.bff.model.UserRelationship;
import games.snapdragon.bff.service.UserDirectoryService;
import games.snapdragon.bff.service.UserRelationshipService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.reactive.WebFluxTest;
import org.springframework.context.annotation.ComponentScan.Filter;
import org.springframework.context.annotation.FilterType;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.reactive.server.SecurityMockServerConfigurers.csrf;

/**
 * HTTP/filter-layer authorization matrix for {@link UserRelationshipController}
 * (mission target #1: relationships/block-list isolation — a user cannot
 * see/act on another user's blocks; cross-user DENY).
 *
 * <p>The caller identity is taken from the JWT subject — clients pass only the
 * {@code other} target in the body. These tests assert at the WebTestClient
 * boundary:
 * <ul>
 *   <li><strong>block-list isolation</strong> — {@code GET /blocks} is keyed to
 *       the JWT subject; user A's call NEVER reads user B's list. There is no
 *       request parameter to name another owner — the subject is the only
 *       key, so an IDOR on the block list is structurally impossible;</li>
 *   <li><strong>block/unblock</strong> always pass the JWT subject as the
 *       acting user to the service, regardless of body content — so A can never
 *       add/remove a block on B's behalf.</li>
 * </ul>
 *
 * <p><b>FIXED (was a documented finding):</b> {@code /api/relationships/**} is
 * now in {@code SecurityConfig.PLAYER_PATHS.authenticated()} (it previously fell
 * through to {@code anyExchange().permitAll()}, letting the friend-graph +
 * blocklist endpoints be reached UNAUTHENTICATED and transact as the literal
 * {@code "anonymous"} subject). An unauthenticated request is now 401 and never
 * reaches the controller — see
 * {@link #blocks_anonymous_is401_neverReachesController} /
 * {@link #friends_anonymous_is401_neverReachesController}.
 */
@WebFluxTest(controllers = UserRelationshipController.class,
        excludeFilters = @Filter(type = FilterType.ASSIGNABLE_TYPE, classes = {
                BanGateFilter.class, DomainGuardFilter.class,
                RateLimitFilter.class, SecurityHeadersFilter.class}))
@Import({SecurityConfig.class, SecuritySliceSupport.TenantBeans.class})
class UserRelationshipControllerHttpAuthzTest {

    @Autowired WebTestClient web;

    @MockitoBean UserRelationshipService service;
    @MockitoBean UserDirectoryService directory;

    private static UserRelationship block(String user, String other) {
        UserRelationship r = new UserRelationship();
        r.setUserCasdoorId(user);
        r.setOtherCasdoorId(other);
        r.setRelationship(UserRelationship.BLOCKED);
        return r;
    }

    // ── FIXED: anonymous is now rejected at the filter chain (401) ────────────
    //
    // (was blocks_anonymous_reachesControllerAsAnonymous /
    // friends_anonymous_reachesControllerAsAnonymous, which PINNED the insecure
    // permitAll fall-through.) /api/relationships/** is now in
    // SecurityConfig.PLAYER_PATHS.authenticated(), so an unauthenticated request
    // is 401 and NEVER reaches the controller — the phantom "anonymous" read is
    // gone.

    @Test
    void blocks_anonymous_is401_neverReachesController() {
        lenient().when(directory.displayNameFor(any())).thenReturn("name");

        web.get().uri("/api/relationships/blocks")
                .exchange()
                .expectStatus().isUnauthorized();

        // The fix: an unauthenticated request is rejected at the filter chain;
        // the service is never consulted for the phantom "anonymous" subject.
        verify(service, never()).blockList(any());
    }

    @Test
    void friends_anonymous_is401_neverReachesController() {
        lenient().when(directory.displayNameFor(any())).thenReturn("name");

        web.get().uri("/api/relationships/friends")
                .exchange()
                .expectStatus().isUnauthorized();
        verify(service, never()).friendList(any());
    }

    // ── block-list isolation: GET /blocks keyed to the JWT subject ────────────

    @Test
    void blocks_scopedToJwtSubject_neverAnotherUsersList() {
        // alice has one block (bob). The list is fetched for "alice" — the
        // subject — and there is no way to request "carol"'s list.
        lenient().when(directory.displayNameFor(any())).thenReturn("name");
        when(service.blockList(eq("alice"))).thenReturn(Flux.just(block("alice", "bob")));

        web.mutateWith(SecuritySliceSupport.user("alice"))
                .get().uri("/api/relationships/blocks")
                .exchange()
                .expectStatus().isOk()
                .expectBody().jsonPath("$.length()").isEqualTo(1);

        verify(service).blockList(eq("alice"));
        // CROSS-USER DENY: carol's block list is never queried for alice's call.
        verify(service, never()).blockList(eq("carol"));
    }

    @Test
    void blocks_differentCaller_getsOwnListOnly() {
        lenient().when(directory.displayNameFor(any())).thenReturn("name");
        when(service.blockList(eq("carol"))).thenReturn(Flux.empty());

        web.mutateWith(SecuritySliceSupport.user("carol"))
                .get().uri("/api/relationships/blocks")
                .exchange()
                .expectStatus().isOk()
                .expectBody().jsonPath("$.length()").isEqualTo(0);

        verify(service).blockList(eq("carol"));
        verify(service, never()).blockList(eq("alice"));
    }

    // ── block/unblock always act AS the JWT subject ───────────────────────────

    @Test
    void blockAdd_actsAsJwtSubjectNotBodyUser() {
        lenient().when(directory.displayNameFor(any())).thenReturn("name");
        when(directory.resolveOrPassthrough(eq("victim"))).thenReturn(Mono.just("victim-id"));
        when(service.block(eq("alice"), eq("victim-id")))
                .thenReturn(Mono.just(block("alice", "victim-id")));

        web.mutateWith(SecuritySliceSupport.user("alice"))
                .mutateWith(csrf())
                .post().uri("/api/relationships/blocks/add")
                .bodyValue(Map.of("other", "victim"))
                .exchange()
                .expectStatus().isOk();

        // The acting user is alice (the subject) — never a smuggled "user" field.
        verify(service).block(eq("alice"), eq("victim-id"));
        verify(service, never()).block(eq("victim-id"), any());
        verify(service, never()).block(eq("carol"), any());
    }

    @Test
    void blockAdd_emptyTarget_is400() {
        web.mutateWith(SecuritySliceSupport.user("alice"))
                .mutateWith(csrf())
                .post().uri("/api/relationships/blocks/add")
                .bodyValue(Map.of("other", ""))
                .exchange()
                .expectStatus().isBadRequest();
        verify(service, never()).block(any(), any());
    }

    @Test
    void unblock_actsAsJwtSubject() {
        when(directory.resolveOrPassthrough(eq("victim"))).thenReturn(Mono.just("victim-id"));
        when(service.unblock(eq("bob"), eq("victim-id"))).thenReturn(Mono.empty());

        web.mutateWith(SecuritySliceSupport.user("bob"))
                .mutateWith(csrf())
                .post().uri("/api/relationships/blocks/remove")
                .bodyValue(Map.of("other", "victim"))
                .exchange()
                .expectStatus().isOk();

        verify(service).unblock(eq("bob"), eq("victim-id"));
        verify(service, never()).unblock(eq("alice"), any());
    }

    @Test
    void friendRequest_actsAsJwtSubject() {
        lenient().when(directory.displayNameFor(any())).thenReturn("name");
        when(directory.resolveOrPassthrough(eq("target"))).thenReturn(Mono.just("target-id"));
        when(service.requestFriend(eq("alice"), eq("target-id")))
                .thenReturn(Mono.just(block("alice", "target-id")));

        web.mutateWith(SecuritySliceSupport.user("alice"))
                .mutateWith(csrf())
                .post().uri("/api/relationships/friends/request")
                .bodyValue(Map.of("other", "target"))
                .exchange()
                .expectStatus().isOk();

        verify(service).requestFriend(eq("alice"), eq("target-id"));
    }
}
