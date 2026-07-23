package games.hugmesh.bff.service;

import games.hugmesh.bff.model.GameSession;
import games.hugmesh.bff.model.SessionMember;
import games.hugmesh.bff.repository.GameSessionRepository;
import games.hugmesh.bff.repository.SessionMemberRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Unit specs for {@link GameSessionService}.
 *
 * <p>Covers the lifecycle paths the user-facing flows depend on:
 * create / list / join (idempotent + collision) / leave (auto-close on
 * last) / authz on control writes. Postgres is mocked — these are the
 * behavioural specs, not integration tests; the Liquibase migration is
 * exercised by the broader test slice.
 */
class GameSessionServiceTest {

    private GameSessionRepository sessions;
    private SessionMemberRepository members;
    private RelationshipQuery relationships;
    private GameSessionService service;

    @BeforeEach
    void setUp() {
        sessions      = mock(GameSessionRepository.class);
        members       = mock(SessionMemberRepository.class);
        relationships = mock(RelationshipQuery.class);
        // PR #58 added a GameStateService dependency on
        // GameSessionService so the pause / resume / host-drop service
        // methods can emit SessionControlEvent onto the SSE sinks.
        // The unit tests in this class don't exercise pause paths, so
        // a no-op mock suffices — the new methods are pinned in the
        // BDD coverage (e2e/features/admin/oversight-and-pause.feature).
        // NOTE: pre-existing Java-25 limitation — ByteBuddy can't mock the
        // concrete GameStateService, so this whole class currently errors
        // under JDK 25 (unrelated to per-game domain scoping).
        GameStateService gameState = mock(GameStateService.class);
        service       = new GameSessionService(sessions, members, relationships,
                gameState, java.util.Optional.empty(), java.util.Optional.empty());

        // Generic save echoes — most tests only care about the resulting
        // DTO shape; if a test cares about the row's id it sets a more
        // specific expectation.
        lenient().when(sessions.save(any())).thenAnswer(inv -> Mono.just(inv.getArgument(0)));
        lenient().when(members.save(any())).thenAnswer(inv -> Mono.just(inv.getArgument(0)));
        lenient().when(members.findActiveBySession(anyString())).thenReturn(Flux.empty());
        lenient().when(members.countBySessionIdAndLeftAtIsNull(anyString())).thenReturn(Mono.just(0L));
        lenient().when(relationships.friendsOf(anyString())).thenReturn(Mono.just(java.util.Set.of()));
        lenient().when(relationships.blocklistFor(anyString())).thenReturn(Mono.just(java.util.Set.of()));
    }

    @Test
    void createPersistsSessionAndCreatorMember() {
        StepVerifier.create(service.create("alice", "three-wizards", "Tower run", 3, "public", 1L, 0))
                .assertNext(dto -> {
                    assertThat(dto.id()).isNotBlank();
                    assertThat(dto.name()).isEqualTo("Tower run");
                    assertThat(dto.gameSlug()).isEqualTo("three-wizards");
                    assertThat(dto.creatorCasdoorId()).isEqualTo("alice");
                    assertThat(dto.maxPlayers()).isEqualTo(3);
                })
                .verifyComplete();
    }

    @Test
    void createRejectsBlankName() {
        StepVerifier.create(service.create("alice", "three-wizards", "  ", 3, "public", 1L, 0))
                .expectErrorSatisfies(err -> {
                    assertThat(err).isInstanceOf(ResponseStatusException.class);
                    assertThat(err.getMessage()).contains("session name");
                })
                .verify();
    }

    @Test
    void createClampsMaxPlayersToOneSixteen() {
        StepVerifier.create(service.create("alice", "g", "Big", 999, "public", null, null))
                .assertNext(dto -> assertThat(dto.maxPlayers()).isEqualTo(16))
                .verifyComplete();

        StepVerifier.create(service.create("alice", "g", "Tiny", 0, "public", null, null))
                .assertNext(dto -> assertThat(dto.maxPlayers()).isEqualTo(1))
                .verifyComplete();
    }

    @Test
    void joinReturns404WhenSessionMissing() {
        when(sessions.findById("nope")).thenReturn(Mono.empty());

        StepVerifier.create(service.join("nope", "alice", 1L, 0))
                .expectErrorSatisfies(err -> {
                    assertThat(err).isInstanceOf(ResponseStatusException.class);
                    assertThat(((ResponseStatusException) err).getStatusCode().value()).isEqualTo(404);
                })
                .verify();
    }

    @Test
    void joinReturns410WhenSessionClosed() {
        GameSession s = sample();
        s.setClosedAt(Instant.now());
        when(sessions.findById(s.getId())).thenReturn(Mono.just(s));
        lenient().when(members.findActive(s.getId(), "alice")).thenReturn(Mono.empty());

        StepVerifier.create(service.join(s.getId(), "alice", 1L, 0))
                .expectErrorSatisfies(err -> {
                    assertThat(err).isInstanceOf(ResponseStatusException.class);
                    assertThat(((ResponseStatusException) err).getStatusCode().value()).isEqualTo(410);
                })
                .verify();
    }

    @Test
    void joinReturns409WhenSessionFull() {
        GameSession s = sample();
        s.setMaxPlayers(2);
        when(sessions.findById(s.getId())).thenReturn(Mono.just(s));
        when(members.findActive(s.getId(), "carol")).thenReturn(Mono.empty());
        when(members.countBySessionIdAndLeftAtIsNull(s.getId())).thenReturn(Mono.just(2L));

        StepVerifier.create(service.join(s.getId(), "carol", 3L, 2))
                .expectErrorSatisfies(err -> {
                    assertThat(err).isInstanceOf(ResponseStatusException.class);
                    assertThat(((ResponseStatusException) err).getStatusCode().value()).isEqualTo(409);
                })
                .verify();
    }

    @Test
    void joinIsIdempotentForExistingMember() {
        GameSession s = sample();
        when(sessions.findById(s.getId())).thenReturn(Mono.just(s));
        SessionMember existing = newMember(s.getId(), "alice", 1L, 0, true);
        when(members.findActive(s.getId(), "alice")).thenReturn(Mono.just(existing));

        StepVerifier.create(service.join(s.getId(), "alice", 1L, 1))  // bumping slot
                .assertNext(dto -> {
                    assertThat(dto.id()).isEqualTo(s.getId());
                })
                .verifyComplete();
        assertThat(existing.getSlotIndex()).isEqualTo(1);  // mutated
    }

    @Test
    void leaveAutoClosesSessionWhenLastMemberLeaves() {
        GameSession s = sample();
        SessionMember mine = newMember(s.getId(), "alice", 1L, 0, true);
        when(members.findActive(s.getId(), "alice")).thenReturn(Mono.just(mine));
        when(members.countBySessionIdAndLeftAtIsNull(s.getId())).thenReturn(Mono.just(0L));
        when(sessions.findById(s.getId())).thenReturn(Mono.just(s));

        StepVerifier.create(service.leave(s.getId(), "alice"))
                .verifyComplete();

        assertThat(mine.getLeftAt()).isNotNull();
        assertThat(s.getClosedAt()).as("auto-close on last leave").isNotNull();
    }

    @Test
    void leaveDoesNotCloseWhenOthersRemain() {
        GameSession s = sample();
        // Non-creator leaver — PR #58 added a host-drop close path
        // that fires when the leaving member is the session creator
        // (so the test now exercises the case the name describes:
        // someone other than the host walks out and the session
        // stays open).
        SessionMember mine = newMember(s.getId(), "bob", 1L, 0, false);
        when(members.findActive(s.getId(), "bob")).thenReturn(Mono.just(mine));
        when(members.countBySessionIdAndLeftAtIsNull(s.getId())).thenReturn(Mono.just(2L));

        StepVerifier.create(service.leave(s.getId(), "bob"))
                .verifyComplete();
        assertThat(s.getClosedAt()).isNull();
    }

    @Test
    void leaveByCreatorClosesSessionEvenIfOthersRemain() {
        // PR #58: when the host drops, the entire session quits and
        // every other player's iframe collapses via SESSION_CLOSED.
        GameSession s = sample();
        SessionMember mine = newMember(s.getId(), "alice", 1L, 0, true);
        when(members.findActive(s.getId(), "alice")).thenReturn(Mono.just(mine));
        when(sessions.findById(s.getId())).thenReturn(Mono.just(s));
        when(members.countBySessionIdAndLeftAtIsNull(s.getId())).thenReturn(Mono.just(2L));

        StepVerifier.create(service.leave(s.getId(), "alice"))
                .verifyComplete();
        assertThat(s.getClosedAt()).isNotNull();
    }

    @Test
    void authorizeControl403WhenCharacterNotOwnedByCaller() {
        SessionMember bobsMembership = newMember("sess-1", "bob", 7L, 1, false);
        when(members.findActiveOwnerOfCharacter(7L)).thenReturn(Mono.just(bobsMembership));

        StepVerifier.create(service.authorizeControl("alice", 7L))
                .expectErrorSatisfies(err -> {
                    assertThat(err).isInstanceOf(ResponseStatusException.class);
                    assertThat(((ResponseStatusException) err).getStatusCode().value()).isEqualTo(403);
                })
                .verify();
    }

    @Test
    void authorizeControl403WhenNoActiveMemberOwnsCharacter() {
        when(members.findActiveOwnerOfCharacter(99L)).thenReturn(Mono.empty());

        StepVerifier.create(service.authorizeControl("alice", 99L))
                .expectErrorSatisfies(err -> {
                    assertThat(err).isInstanceOf(ResponseStatusException.class);
                    assertThat(((ResponseStatusException) err).getStatusCode().value()).isEqualTo(403);
                })
                .verify();
    }

    @Test
    void listOpenHidesSessionsCreatedByBlockedUsers() {
        // Pinning regression: a blocker calling listOpen must NEVER
        // see a session whose creator is in their blocklist. Without
        // this the e2e block-test test (and the documented intent of
        // decision 015) silently fails — the blocked user's
        // sessions still appear in the lobby.
        GameSession blockedSess = sample();
        blockedSess.setCreatorCasdoorId("bob-uuid");
        GameSession allowedSess = sample();
        allowedSess.setId("00000000-0000-0000-0000-000000000002");
        allowedSess.setCreatorCasdoorId("carol-uuid");

        when(sessions.findOpenByGameSlug("three-wizards"))
                .thenReturn(Flux.just(blockedSess, allowedSess));
        when(relationships.friendsOf("alice-uuid")).thenReturn(Mono.just(java.util.Set.of()));
        when(relationships.blocklistFor("alice-uuid"))
                .thenReturn(Mono.just(java.util.Set.of("bob-uuid")));

        StepVerifier.create(service.listOpen("three-wizards", "alice-uuid").collectList())
                .assertNext(list -> {
                    assertThat(list).hasSize(1);
                    assertThat(list.get(0).creatorCasdoorId()).isEqualTo("carol-uuid");
                })
                .verifyComplete();
    }

    @Test
    void listOpenHidesFriendsOnlySessionsFromStrangers() {
        // friends-tier sessions show up only for the creator's
        // friends (or the creator themselves). Strangers see empty
        // even when the session is open.
        GameSession friendsOnly = sample();
        friendsOnly.setCreatorCasdoorId("creator-uuid");
        friendsOnly.setVisibility("friends");

        when(sessions.findOpenByGameSlug("three-wizards"))
                .thenReturn(Flux.just(friendsOnly));
        // alice is NOT a friend of creator
        when(relationships.friendsOf("alice-uuid")).thenReturn(Mono.just(java.util.Set.of()));
        when(relationships.blocklistFor("alice-uuid")).thenReturn(Mono.just(java.util.Set.of()));

        StepVerifier.create(service.listOpen("three-wizards", "alice-uuid").collectList())
                .assertNext(list -> assertThat(list).isEmpty())
                .verifyComplete();
    }

    @Test
    void listOpenSurfacesFriendsOnlySessionsToFriends() {
        GameSession friendsOnly = sample();
        friendsOnly.setCreatorCasdoorId("creator-uuid");
        friendsOnly.setVisibility("friends");

        when(sessions.findOpenByGameSlug("three-wizards"))
                .thenReturn(Flux.just(friendsOnly));
        // bob IS a friend of the creator
        when(relationships.friendsOf("bob-uuid"))
                .thenReturn(Mono.just(java.util.Set.of("creator-uuid")));
        when(relationships.blocklistFor("bob-uuid")).thenReturn(Mono.just(java.util.Set.of()));

        StepVerifier.create(service.listOpen("three-wizards", "bob-uuid").collectList())
                .assertNext(list -> assertThat(list).hasSize(1))
                .verifyComplete();
    }

    @Test
    void listOpenHidesPrivateSessionsFromEveryoneExceptCreator() {
        GameSession priv = sample();
        priv.setCreatorCasdoorId("creator-uuid");
        priv.setVisibility("private");

        when(sessions.findOpenByGameSlug("three-wizards"))
                .thenReturn(Flux.just(priv));
        when(relationships.friendsOf("alice-uuid")).thenReturn(Mono.just(java.util.Set.of()));
        when(relationships.blocklistFor("alice-uuid")).thenReturn(Mono.just(java.util.Set.of()));
        when(relationships.friendsOf("creator-uuid")).thenReturn(Mono.just(java.util.Set.of()));
        when(relationships.blocklistFor("creator-uuid")).thenReturn(Mono.just(java.util.Set.of()));

        StepVerifier.create(service.listOpen("three-wizards", "alice-uuid").collectList())
                .assertNext(list -> assertThat(list).isEmpty())
                .verifyComplete();
        StepVerifier.create(service.listOpen("three-wizards", "creator-uuid").collectList())
                .assertNext(list -> assertThat(list).hasSize(1))
                .verifyComplete();
    }

    @Test
    void authorizeControlPassesWhenCallerOwnsCharacter() {
        SessionMember alicesMembership = newMember("sess-1", "alice", 7L, 0, true);
        when(members.findActiveOwnerOfCharacter(7L)).thenReturn(Mono.just(alicesMembership));

        StepVerifier.create(service.authorizeControl("alice", 7L))
                .assertNext(m -> assertThat(m.getCasdoorId()).isEqualTo("alice"))
                .verifyComplete();
    }

    @Test
    void authorizeControlScopedFormPicksTheRightSessionRow() {
        // Pinning regression: when two players each own character 1
        // in different sessions, the unscoped lookup races and the
        // loser hits "character not owned by caller". The scoped
        // overload must look up the (session_id, character_id) row
        // so each player's own membership wins.
        SessionMember alicesMembership = newMember("sess-A", "alice", 1L, 0, true);
        SessionMember bobsMembership   = newMember("sess-B", "bob",   1L, 0, true);
        when(members.findActiveOwnerInSession("sess-A", 1L)).thenReturn(Mono.just(alicesMembership));
        when(members.findActiveOwnerInSession("sess-B", 1L)).thenReturn(Mono.just(bobsMembership));

        StepVerifier.create(service.authorizeControl("alice", 1L, "sess-A"))
                .assertNext(m -> assertThat(m.getCasdoorId()).isEqualTo("alice"))
                .verifyComplete();
        StepVerifier.create(service.authorizeControl("bob", 1L, "sess-B"))
                .assertNext(m -> assertThat(m.getCasdoorId()).isEqualTo("bob"))
                .verifyComplete();
    }

    @Test
    void authorizeControlScopedRejectsWhenCallerIsntInThatSession() {
        SessionMember alicesMembership = newMember("sess-A", "alice", 1L, 0, true);
        when(members.findActiveOwnerInSession("sess-A", 1L)).thenReturn(Mono.just(alicesMembership));

        StepVerifier.create(service.authorizeControl("bob", 1L, "sess-A"))
                .expectErrorSatisfies(err -> {
                    assertThat(err).isInstanceOf(ResponseStatusException.class);
                    assertThat(((ResponseStatusException) err).getStatusCode().value()).isEqualTo(403);
                })
                .verify();
    }

    @Test
    void authorizeControlScopedFallsBackToUnscopedWhenSessionIdBlank() {
        SessionMember m = newMember("sess-X", "alice", 1L, 0, true);
        when(members.findActiveOwnerOfCharacter(1L)).thenReturn(Mono.just(m));
        // null + "" both flow through to the unscoped query.
        StepVerifier.create(service.authorizeControl("alice", 1L, null))
                .assertNext(r -> assertThat(r.getCasdoorId()).isEqualTo("alice"))
                .verifyComplete();
        StepVerifier.create(service.authorizeControl("alice", 1L, ""))
                .assertNext(r -> assertThat(r.getCasdoorId()).isEqualTo("alice"))
                .verifyComplete();
    }

    private static GameSession sample() {
        GameSession s = new GameSession();
        s.setId("00000000-0000-0000-0000-000000000001");
        s.setName("Demo");
        s.setGameSlug("three-wizards");
        s.setCreatorCasdoorId("alice");
        s.setMaxPlayers(3);
        s.setCreatedAt(Instant.now());
        return s;
    }

    private static SessionMember newMember(String sid, String cid, Long charId, Integer slot, boolean creator) {
        SessionMember m = new SessionMember();
        m.setSessionId(sid);
        m.setCasdoorId(cid);
        m.setCharacterId(charId);
        m.setSlotIndex(slot);
        m.setCreator(creator);
        m.setJoinedAt(Instant.now());
        return m;
    }
}
