package games.hugmesh.bff.service;

import games.hugmesh.bff.model.PublisherGameScope;
import games.hugmesh.bff.model.UserRelationship;
import games.hugmesh.bff.repository.PublisherGameScopeRepository;
import games.hugmesh.bff.repository.UserRelationshipRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Specs for {@link UserRelationshipService} — the friends graph + blocklist
 * orchestration that {@code GameSessionService.listOpen} relies on for the
 * lobby visibility filter.
 *
 * <p>The most error-prone branches are the auto-accept on requestFriend
 * (mutual-pending → both sides become friend without a second click) and
 * the publisher-immunity carve-out on block (a malicious client posting
 * raw payloads cannot block a moderator / publisher), so these tests
 * focus there.
 */
class UserRelationshipServiceTest {

    private UserRelationshipRepository repo;
    private PublisherGameScopeRepository publisherScopes;
    private UserRelationshipService service;

    @BeforeEach
    void setUp() {
        repo            = mock(UserRelationshipRepository.class);
        publisherScopes = mock(PublisherGameScopeRepository.class);
        service         = new UserRelationshipService(repo, publisherScopes);

        // Default: no reverse edge, no existing edge, save echoes input,
        // target is not a publisher. Tests opt-in to other branches.
        lenient().when(repo.findEdge(anyString(), anyString())).thenReturn(Mono.empty());
        lenient().when(repo.save(any())).thenAnswer(inv -> Mono.just(inv.getArgument(0)));
        lenient().when(repo.delete(any())).thenReturn(Mono.empty());
        lenient().when(publisherScopes.findActiveByCasdoor(anyString())).thenReturn(Flux.empty());
    }

    private static UserRelationship edge(String user, String other, String rel) {
        UserRelationship r = new UserRelationship();
        r.setUserCasdoorId(user);
        r.setOtherCasdoorId(other);
        r.setRelationship(rel);
        return r;
    }

    // ── requestFriend ────────────────────────────────────────────────────

    @Test
    void requestFriend_rejectsSelfReferentialPair() {
        StepVerifier.create(service.requestFriend("u1", "u1"))
                .expectErrorSatisfies(e -> {
                    assertThat(e).isInstanceOf(ResponseStatusException.class);
                    assertThat(((ResponseStatusException) e).getStatusCode().value()).isEqualTo(400);
                })
                .verify();
    }

    @Test
    void requestFriend_rejectsBlankPrincipal() {
        StepVerifier.create(service.requestFriend("", "u2"))
                .expectError(ResponseStatusException.class)
                .verify();
    }

    @Test
    void requestFriend_createsPendingEdgeWhenNoReverse() {
        StepVerifier.create(service.requestFriend("u1", "u2"))
                .assertNext(r -> {
                    assertThat(r.getRelationship()).isEqualTo(UserRelationship.FRIEND_REQUEST);
                    assertThat(r.getUserCasdoorId()).isEqualTo("u1");
                    assertThat(r.getOtherCasdoorId()).isEqualTo("u2");
                })
                .verifyComplete();
        // Single forward edge written, no reverse mutation.
        verify(repo, times(1)).save(any());
    }

    @Test
    void requestFriend_autoAcceptsWhenReverseRequestExists() {
        UserRelationship reverse = edge("u2", "u1", UserRelationship.FRIEND_REQUEST);
        when(repo.findEdge("u2", "u1")).thenReturn(Mono.just(reverse));
        // The forward-edge lookup (u1→u2) is what `upsertEdge` consults
        // *inside* the auto-accept branch; default empty Mono fires the
        // create path so the chained save returns a fresh row.
        when(repo.findEdge("u1", "u2")).thenReturn(Mono.empty());

        StepVerifier.create(service.requestFriend("u1", "u2"))
                .assertNext(r -> {
                    assertThat(r.getRelationship()).isEqualTo(UserRelationship.FRIEND);
                    assertThat(r.getUserCasdoorId()).isEqualTo("u1");
                })
                .verifyComplete();
        // Both directions persisted: reverse flipped to friend + forward created as friend.
        assertThat(reverse.getRelationship()).isEqualTo(UserRelationship.FRIEND);
        verify(repo, times(2)).save(any());
    }

    @Test
    void requestFriend_blockedReverseShortCircuitsTo403() {
        UserRelationship reverse = edge("u2", "u1", UserRelationship.BLOCKED);
        when(repo.findEdge("u2", "u1")).thenReturn(Mono.just(reverse));
        StepVerifier.create(service.requestFriend("u1", "u2"))
                .expectErrorSatisfies(e -> {
                    assertThat(e).isInstanceOf(ResponseStatusException.class);
                    assertThat(((ResponseStatusException) e).getStatusCode().value()).isEqualTo(403);
                })
                .verify();
    }

    // ── acceptFriend ─────────────────────────────────────────────────────

    @Test
    void acceptFriend_404WhenNoPending() {
        when(repo.findEdge("u2", "u1")).thenReturn(Mono.empty());
        StepVerifier.create(service.acceptFriend("u1", "u2"))
                .expectErrorSatisfies(e -> {
                    assertThat(((ResponseStatusException) e).getStatusCode().value()).isEqualTo(404);
                })
                .verify();
    }

    @Test
    void acceptFriend_409WhenReverseIsAlreadyFriend() {
        when(repo.findEdge("u2", "u1"))
                .thenReturn(Mono.just(edge("u2", "u1", UserRelationship.FRIEND)));
        StepVerifier.create(service.acceptFriend("u1", "u2"))
                .expectErrorSatisfies(e -> {
                    assertThat(((ResponseStatusException) e).getStatusCode().value()).isEqualTo(409);
                })
                .verify();
    }

    @Test
    void acceptFriend_flipsBothDirectionsToFriend() {
        UserRelationship reverse = edge("u2", "u1", UserRelationship.FRIEND_REQUEST);
        when(repo.findEdge("u2", "u1")).thenReturn(Mono.just(reverse));
        when(repo.findEdge("u1", "u2")).thenReturn(Mono.empty());

        StepVerifier.create(service.acceptFriend("u1", "u2"))
                .assertNext(r -> assertThat(r.getRelationship()).isEqualTo(UserRelationship.FRIEND))
                .verifyComplete();
        assertThat(reverse.getRelationship()).isEqualTo(UserRelationship.FRIEND);
    }

    // ── block / unblock ──────────────────────────────────────────────────

    @Test
    void block_rejectsSelf() {
        StepVerifier.create(service.block("u1", "u1"))
                .expectError(ResponseStatusException.class)
                .verify();
    }

    @Test
    void block_publisherIsForbidden() {
        // Make `other` show up as an active publisher — block() should 403.
        PublisherGameScope scope = new PublisherGameScope();
        scope.setCasdoorId("u2");
        scope.setGameSlug("three-wizards");
        when(publisherScopes.findActiveByCasdoor("u2")).thenReturn(Flux.just(scope));

        StepVerifier.create(service.block("u1", "u2"))
                .expectErrorSatisfies(e -> {
                    assertThat(((ResponseStatusException) e).getStatusCode().value()).isEqualTo(403);
                })
                .verify();
    }

    @Test
    void block_removesAnyExistingFriendEdgesFirst() {
        // u1→u2 friend edge exists; block should call delete on it AND
        // delete the reverse edge before writing the blocked row.
        UserRelationship forward = edge("u1", "u2", UserRelationship.FRIEND);
        UserRelationship reverse = edge("u2", "u1", UserRelationship.FRIEND);
        when(repo.findEdge("u1", "u2")).thenReturn(Mono.just(forward), Mono.empty());
        when(repo.findEdge("u2", "u1")).thenReturn(Mono.just(reverse));

        StepVerifier.create(service.block("u1", "u2"))
                .assertNext(r -> assertThat(r.getRelationship()).isEqualTo(UserRelationship.BLOCKED))
                .verifyComplete();
        verify(repo).delete(forward);
        verify(repo).delete(reverse);
    }

    @Test
    void unblock_isNoopWhenEdgeIsFriendNotBlocked() {
        // unblock() filters to relationship='blocked'; a friend edge
        // here must not be deleted (silently no-op).
        when(repo.findEdge("u1", "u2"))
                .thenReturn(Mono.just(edge("u1", "u2", UserRelationship.FRIEND)));
        StepVerifier.create(service.unblock("u1", "u2"))
                .verifyComplete();
        verify(repo, times(0)).delete(any(UserRelationship.class));
    }

    // ── query helpers ────────────────────────────────────────────────────

    @Test
    void friendsOf_collectsOtherCasdoorIdsIntoSet() {
        when(repo.findFriendsOf("u1")).thenReturn(Flux.just(
                edge("u1", "u2", UserRelationship.FRIEND),
                edge("u1", "u3", UserRelationship.FRIEND)));

        StepVerifier.create(service.friendsOf("u1"))
                .assertNext(set -> assertThat(set).containsExactlyInAnyOrder("u2", "u3"))
                .verifyComplete();
    }

    @Test
    void blocklistFor_mergesOutgoingAndIncomingBlocks() {
        when(repo.findBlocksBy("u1")).thenReturn(Flux.just(edge("u1", "u2", UserRelationship.BLOCKED)));
        when(repo.findBlocksAgainst("u1")).thenReturn(Flux.just(edge("u3", "u1", UserRelationship.BLOCKED)));

        StepVerifier.create(service.blocklistFor("u1"))
                .assertNext(set -> assertThat(set).containsExactlyInAnyOrder("u2", "u3"))
                .verifyComplete();
    }
}
