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
 * <p><b>FINDING (documented, not fixed — see PR body):</b> {@code
 * /api/relationships/**} is NOT in {@code SecurityConfig.PLAYER_PATHS}, so it
 * falls through to {@code anyExchange().permitAll()} — the friend-graph +
 * blocklist endpoints are reachable UNAUTHENTICATED, and the controller's
 * {@code me(jwt)} substitutes the literal string {@code "anonymous"} for a null
 * principal rather than rejecting (no 401). The {@link #blocks_anonymous_reachesControllerAsAnonymous}
 * test PINS this real (insecure) behavior so the eventual fix flips it
 * deliberately; it is NOT an assertion that the behavior is correct.
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

    // ── FINDING: anonymous reaches the controller as the literal "anonymous" ──
    //
    // This PINS the current (insecure) reachability so the eventual security
    // fix — adding /api/relationships/** to PLAYER_PATHS.authenticated() OR
    // making me(null) reject — flips this test deliberately. The endpoint is
    // NOT 401 today; it transacts as a phantom "anonymous" user.

    @Test
    void blocks_anonymous_reachesControllerAsAnonymous() {
        lenient().when(directory.displayNameFor(any())).thenReturn("name");
        when(service.blockList(eq("anonymous"))).thenReturn(Flux.empty());

        web.get().uri("/api/relationships/blocks")
                .exchange()
                .expectStatus().isOk();

        // The bug surface: an unauthenticated request keyed the blocklist read
        // to the literal "anonymous" string instead of being rejected (401).
        verify(service).blockList(eq("anonymous"));
    }

    @Test
    void friends_anonymous_reachesControllerAsAnonymous() {
        lenient().when(directory.displayNameFor(any())).thenReturn("name");
        when(service.friendList(eq("anonymous"))).thenReturn(Flux.empty());

        web.get().uri("/api/relationships/friends")
                .exchange()
                .expectStatus().isOk();
        verify(service).friendList(eq("anonymous"));
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
