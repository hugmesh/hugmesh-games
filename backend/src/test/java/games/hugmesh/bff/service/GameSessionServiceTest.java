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
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
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

    /**
     * Heartbeat write-suppression window the service under test is built with.
     * Kept explicit (rather than leaning on the 15 s production default) so the
     * rate-limiter specs can reason about it.
     */
    private static final long HEARTBEAT_MIN_INTERVAL_MS = 15_000L;

    private GameSessionRepository sessions;
    private SessionMemberRepository members;
    private RelationshipQuery relationships;
    private GameStateService gameState;
    private GameSessionService service;

    @BeforeEach
    void setUp() {
        sessions      = mock(GameSessionRepository.class);
        members       = mock(SessionMemberRepository.class);
        relationships = mock(RelationshipQuery.class);
        // PR #58 added a GameStateService dependency on
        // GameSessionService so the pause / resume / host-drop service
        // methods can emit SessionControlEvent onto the SSE sinks.
        // The pause paths are exercised by the liveness specs at the bottom of
        // this class; a mock GameStateService captures the emitted
        // SessionControlEvents without standing up any SSE plumbing.
        gameState     = mock(GameStateService.class);
        service       = new GameSessionService(sessions, members, relationships,
                gameState, java.util.Optional.empty(), java.util.Optional.empty(),
                HEARTBEAT_MIN_INTERVAL_MS);

        // Generic save echoes — most tests only care about the resulting
        // DTO shape; if a test cares about the row's id it sets a more
        // specific expectation.
        lenient().when(sessions.save(any())).thenAnswer(inv -> Mono.just(inv.getArgument(0)));
        lenient().when(members.save(any())).thenAnswer(inv -> Mono.just(inv.getArgument(0)));
        lenient().when(members.findActiveBySession(anyString())).thenReturn(Flux.empty());
        lenient().when(members.countBySessionIdAndLeftAtIsNull(anyString())).thenReturn(Mono.just(0L));
        lenient().when(relationships.friendsOf(anyString())).thenReturn(Mono.just(java.util.Set.of()));
        lenient().when(relationships.blocklistFor(anyString())).thenReturn(Mono.just(java.util.Set.of()));

        // Liveness writes (migration 059). Defaults say "the row was there and
        // I stamped it"; the concurrency specs override with 0 to model a row
        // somebody else already ended.
        lenient().when(members.touchLastSeen(anyString(), anyString(), any())).thenReturn(Mono.just(1));
        lenient().when(members.touchAllActive(anyString(), any())).thenReturn(Mono.just(1));
        lenient().when(members.stampLeftIfActive(any(), any())).thenReturn(Mono.just(1));
        lenient().when(members.stampAllActiveLeft(anyString(), any())).thenReturn(Mono.just(1));
        lenient().when(members.findStaleActive(any())).thenReturn(Flux.empty());
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
        GameSession s = sample();                          // creator is "alice"
        when(sessions.findById(s.getId())).thenReturn(Mono.just(s));
        // A NON-creator: the creator is pinned to CREATOR_SLOT and cannot bump
        // its own slot (see the creator-slot specs below).
        SessionMember existing = newMember(s.getId(), "bob", 2L, 1, false);
        when(members.findActive(s.getId(), "bob")).thenReturn(Mono.just(existing));

        StepVerifier.create(service.join(s.getId(), "bob", 2L, 2))  // bumping slot
                .assertNext(dto -> {
                    assertThat(dto.id()).isEqualTo(s.getId());
                })
                .verifyComplete();
        assertThat(existing.getSlotIndex()).isEqualTo(2);  // mutated
    }

    // ── creator-slot pin ────────────────────────────────────────────────────
    // Host-authoritative Godot titles elect their in-game host from the
    // client-visible slot_index (shadows-of-the-net: `_is_host = slot_index ==
    // 0`) while this service grants authority on session_members.is_creator —
    // InputEventController's Gate 0b rejects kind:'world' from anyone else.
    // slotIndex used to be a nullable CLIENT-supplied field on both create and
    // join, so the two predicates could name different members: a session
    // created without one left slot 0 free for the first guest, who then
    // believed itself host, drove the room's seat/stage/media arbitration, and
    // had every one of its authoritative broadcasts 403'd into an
    // SD_SESSION_EXPIRED reconnect storm — while the real creator, on slot 1,
    // never broadcast at all, so nothing in the room ever synced.

    @Test
    void createPinsTheCreatorToSlotZeroWhateverTheClientAsked() {
        StepVerifier.create(service.create("alice", "three-wizards", "Run", 4, "public", 1L, 7))
                .assertNext(dto -> assertThat(dto.id()).isNotBlank())
                .verifyComplete();

        org.mockito.ArgumentCaptor<SessionMember> saved =
                org.mockito.ArgumentCaptor.forClass(SessionMember.class);
        org.mockito.Mockito.verify(members).save(saved.capture());
        assertThat(saved.getValue().isCreator()).isTrue();
        assertThat(saved.getValue().getSlotIndex())
                .as("creator is seated at CREATOR_SLOT, not the requested slot 7")
                .isEqualTo(GameSessionService.CREATOR_SLOT);
    }

    @Test
    void createPinsTheCreatorToSlotZeroWhenNoSlotWasRequested() {
        StepVerifier.create(service.create("alice", "three-wizards", "Run", 4, "public", 1L, null))
                .assertNext(dto -> assertThat(dto.id()).isNotBlank())
                .verifyComplete();

        org.mockito.ArgumentCaptor<SessionMember> saved =
                org.mockito.ArgumentCaptor.forClass(SessionMember.class);
        org.mockito.Mockito.verify(members).save(saved.capture());
        assertThat(saved.getValue().getSlotIndex())
                .as("a null slotIndex must not leave slot 0 free for a guest")
                .isEqualTo(GameSessionService.CREATOR_SLOT);
    }

    @Test
    void joinRefusesTheCreatorSlotToANonCreator() {
        GameSession s = sample();                          // creator is "alice"
        when(sessions.findById(s.getId())).thenReturn(Mono.just(s));
        lenient().when(members.findActive(s.getId(), "bob")).thenReturn(Mono.empty());

        StepVerifier.create(service.join(s.getId(), "bob", 2L, GameSessionService.CREATOR_SLOT))
                .expectErrorSatisfies(err -> {
                    assertThat(err).isInstanceOf(ResponseStatusException.class);
                    assertThat(((ResponseStatusException) err).getStatusCode().value()).isEqualTo(409);
                    assertThat(err.getMessage()).contains("reserved for the session creator");
                })
                .verify();
        org.mockito.Mockito.verify(members, org.mockito.Mockito.never()).save(any());
    }

    @Test
    void joinLetsANonCreatorTakeAnyOtherSlot() {
        GameSession s = sample();
        when(sessions.findById(s.getId())).thenReturn(Mono.just(s));
        when(members.findActive(s.getId(), "bob")).thenReturn(Mono.empty());
        when(members.countBySessionIdAndLeftAtIsNull(s.getId())).thenReturn(Mono.just(1L));

        StepVerifier.create(service.join(s.getId(), "bob", 2L, 2))
                .assertNext(dto -> assertThat(dto.id()).isEqualTo(s.getId()))
                .verifyComplete();

        org.mockito.ArgumentCaptor<SessionMember> saved =
                org.mockito.ArgumentCaptor.forClass(SessionMember.class);
        org.mockito.Mockito.verify(members).save(saved.capture());
        assertThat(saved.getValue().getSlotIndex()).isEqualTo(2);
        assertThat(saved.getValue().isCreator()).isFalse();
    }

    @Test
    void joinPutsTheCreatorBackOnTheCreatorSlot() {
        GameSession s = sample();                          // creator is "alice"
        when(sessions.findById(s.getId())).thenReturn(Mono.just(s));
        SessionMember existing = newMember(s.getId(), "alice", 1L,
                GameSessionService.CREATOR_SLOT, true);
        when(members.findActive(s.getId(), "alice")).thenReturn(Mono.just(existing));

        // The creator re-launches with a stale/hand-edited slot in the URL.
        StepVerifier.create(service.join(s.getId(), "alice", 1L, 5))
                .assertNext(dto -> assertThat(dto.id()).isEqualTo(s.getId()))
                .verifyComplete();
        assertThat(existing.getSlotIndex())
                .as("the creator can never leave the host slot")
                .isEqualTo(GameSessionService.CREATOR_SLOT);
    }

    @Test
    void joinReSeatsTheCreatorEvenWhenNoSlotWasRequested() {
        GameSession s = sample();
        when(sessions.findById(s.getId())).thenReturn(Mono.just(s));
        // A legacy row created before the pin existed: creator on no slot.
        SessionMember existing = newMember(s.getId(), "alice", 1L, null, true);
        when(members.findActive(s.getId(), "alice")).thenReturn(Mono.just(existing));

        StepVerifier.create(service.join(s.getId(), "alice", 1L, null))
                .assertNext(dto -> assertThat(dto.id()).isEqualTo(s.getId()))
                .verifyComplete();
        assertThat(existing.getSlotIndex())
                .as("rejoining repairs a legacy creator row onto the host slot")
                .isEqualTo(GameSessionService.CREATOR_SLOT);
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

        // The departure is stamped by the guarded UPDATE (left_at IS NULL in
        // the predicate), not by mutating the loaded entity — that is what
        // stops a reaper on another pod from double-firing the close paths for
        // one departure. Assert the write, not the in-memory field.
        verify(members).stampLeftIfActive(eq(mine.getId()), any());
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

    // -------------------------------------------------------------------------
    // Member liveness — heartbeat, reaper, and the guards that keep it from
    // evicting players who are still there (migration 059).
    //
    // The negative cases carry the weight here. A reaper that releases dead
    // seats is easy; one that never releases a live seat is the hard part, and
    // every guard below has a matching spec so reverting the guard turns a test
    // red rather than silently kicking players out of running games.
    // -------------------------------------------------------------------------

    @Test
    void reaperReleasesTheSeatOfAMemberWhoseSignalWentQuiet() {
        GameSession s = sample();
        SessionMember ghost = staleMember(s.getId(), "bob", false);
        when(members.findStaleActive(any())).thenReturn(Flux.just(ghost));
        when(sessions.findById(s.getId())).thenReturn(Mono.just(s));
        // Somebody else is still playing, so the room itself survives.
        when(members.countBySessionIdAndLeftAtIsNull(s.getId())).thenReturn(Mono.just(1L));

        StepVerifier.create(service.reapStaleMembers(cutoff()))
                .expectNext(1L)
                .verifyComplete();

        verify(members).stampLeftIfActive(eq(ghost.getId()), any());
        assertThat(s.getClosedAt()).as("room stays open while a live player remains").isNull();
    }

    @Test
    void reaperSparesEveryMemberOfAPausedSession() {
        // The pinned trap: SESSION_PAUSED sets tree.paused = true in the Godot
        // room, which halts session_bridge._process and with it the 5 s roster
        // poll. EVERY member of a paused session is therefore stale by
        // construction, and reaping them would delete the room the instant
        // somebody hit pause. SessionMemberRepository.findStaleActive excludes
        // paused sessions in SQL; this pins the second, code-level check that
        // covers a session paused AFTER the candidate list was built.
        GameSession s = sample();
        s.setPausedAt(Instant.now());
        SessionMember stillHere = staleMember(s.getId(), "bob", false);
        when(members.findStaleActive(any())).thenReturn(Flux.just(stillHere));
        when(sessions.findById(s.getId())).thenReturn(Mono.just(s));

        StepVerifier.create(service.reapStaleMembers(cutoff()))
                .expectNext(0L)
                .verifyComplete();

        verify(members, never()).stampLeftIfActive(any(), any());
        assertThat(s.getClosedAt()).isNull();
    }

    @Test
    void reaperSparesAMemberWhoseSessionClosedAfterTheCandidateListWasBuilt() {
        GameSession s = sample();
        s.setClosedAt(Instant.now());
        SessionMember ghost = staleMember(s.getId(), "bob", false);
        when(members.findStaleActive(any())).thenReturn(Flux.just(ghost));
        when(sessions.findById(s.getId())).thenReturn(Mono.just(s));

        StepVerifier.create(service.reapStaleMembers(cutoff()))
                .expectNext(0L)
                .verifyComplete();

        verify(members, never()).stampLeftIfActive(any(), any());
    }

    @Test
    void reaperSparesAMemberMidTabTakeover() {
        // The player is moving between two of their own tabs: the old one is
        // frozen and the new one may still be compiling WASM, so neither is
        // polling. Reaping here would evict somebody who is actively loading
        // the game.
        GameSession s = sample();
        SessionMember switching = staleMember(s.getId(), "bob", false);
        when(members.findStaleActive(any())).thenReturn(Flux.just(switching));
        when(sessions.findById(s.getId())).thenReturn(Mono.just(s));

        StepVerifier.create(service.pausePlayer(s.getId(), "bob", "tab-takeover", "new-tab-uid"))
                .verifyComplete();

        StepVerifier.create(service.reapStaleMembers(cutoff()))
                .expectNext(0L)
                .verifyComplete();

        verify(members, never()).stampLeftIfActive(any(), any());
    }

    @Test
    void takeoverShelterExpiresSoANewTabThatNeverLoadsCannotPinTheSeat() {
        // The shelter is bounded by the same staleness window as the heartbeat.
        // A cutoff NEWER than the pause record means the takeover itself has
        // gone stale — the "new tab" never finished loading — and the seat is
        // collectable again.
        GameSession s = sample();
        SessionMember abandoned = staleMember(s.getId(), "bob", false);
        when(members.findStaleActive(any())).thenReturn(Flux.just(abandoned));
        when(sessions.findById(s.getId())).thenReturn(Mono.just(s));
        when(members.countBySessionIdAndLeftAtIsNull(s.getId())).thenReturn(Mono.just(1L));

        StepVerifier.create(service.pausePlayer(s.getId(), "bob", "tab-takeover", "new-tab-uid"))
                .verifyComplete();

        StepVerifier.create(service.reapStaleMembers(Instant.now().plusSeconds(60)))
                .expectNext(1L)
                .verifyComplete();

        verify(members).stampLeftIfActive(eq(abandoned.getId()), any());
    }

    @Test
    void reaperDoesNotDoubleStampARowAConcurrentLeaveAlreadyEnded() {
        // Between the candidate SELECT and the UPDATE the player reconnected
        // and left cleanly (or a mod kicked them). The guarded UPDATE matches
        // zero rows, and the reaper must NOT go on to re-audit the departure or
        // re-fire the close path.
        GameSession s = sample();
        SessionMember raced = staleMember(s.getId(), "bob", false);
        when(members.findStaleActive(any())).thenReturn(Flux.just(raced));
        when(sessions.findById(s.getId())).thenReturn(Mono.just(s));
        when(members.stampLeftIfActive(eq(raced.getId()), any())).thenReturn(Mono.just(0));
        // The concurrent leave emptied the room; if the reaper ignored the zero
        // row count it would close the session a second time from here.
        when(members.countBySessionIdAndLeftAtIsNull(s.getId())).thenReturn(Mono.just(0L));

        StepVerifier.create(service.reapStaleMembers(cutoff()))
                .expectNext(0L)
                .verifyComplete();

        verify(gameState, never()).emitSessionControl(any());
        assertThat(s.getClosedAt()).isNull();
    }

    @Test
    void reaperClosesTheWholeRoomWhenTheCreatorGoesStale() {
        GameSession s = sample();
        SessionMember host = staleMember(s.getId(), "alice", true);
        when(members.findStaleActive(any())).thenReturn(Flux.just(host));
        when(sessions.findById(s.getId())).thenReturn(Mono.just(s));

        StepVerifier.create(service.reapStaleMembers(cutoff()))
                .expectNext(1L)
                .verifyComplete();

        assertThat(s.getClosedAt()).as("host drop closes the room").isNotNull();
        var ev = org.mockito.ArgumentCaptor.forClass(games.hugmesh.bff.model.SessionControlEvent.class);
        verify(gameState).emitSessionControl(ev.capture());
        assertThat(ev.getValue().action())
                .isEqualTo(games.hugmesh.bff.model.SessionControlEvent.Action.SESSION_CLOSED);
        assertThat(ev.getValue().reason()).isEqualTo("host-disconnected");
        // Every stranded guest's seat is released by the close.
        verify(members).stampAllActiveLeft(eq(s.getId()), any());
    }

    @Test
    void reaperNeverBareStampsTheCreatorsOwnRow() {
        // Gate 0b resolves is_creator through findActiveOwnerInSession, whose
        // predicate is left_at IS NULL. Stamping the creator while the room is
        // still open 403s the host's own controls into an SD_SESSION_EXPIRED
        // loop and 403s its kind:'world' broadcasts into a stand-down, leaving
        // a hostless room that never closes — strictly worse than the ghost
        // seat. The creator must be collected by closing the room, never by a
        // row-scoped stamp that could outlive a failed close.
        GameSession s = sample();
        SessionMember host = staleMember(s.getId(), "alice", true);
        when(members.findStaleActive(any())).thenReturn(Flux.just(host));
        when(sessions.findById(s.getId())).thenReturn(Mono.just(s));

        StepVerifier.create(service.reapStaleMembers(cutoff()))
                .expectNext(1L)
                .verifyComplete();

        verify(members, never()).stampLeftIfActive(eq(host.getId()), any());
    }

    @Test
    void reaperCountsOnlyTheSeatsItActuallyReleased() {
        GameSession live   = sample();
        GameSession paused = sample("sess-paused");
        paused.setPausedAt(Instant.now());
        SessionMember ghost    = staleMember(live.getId(), "bob", false);
        SessionMember sheltered = staleMember(paused.getId(), "carol", false);
        when(members.findStaleActive(any())).thenReturn(Flux.just(ghost, sheltered));
        when(sessions.findById(live.getId())).thenReturn(Mono.just(live));
        when(sessions.findById(paused.getId())).thenReturn(Mono.just(paused));
        when(members.countBySessionIdAndLeftAtIsNull(live.getId())).thenReturn(Mono.just(1L));

        StepVerifier.create(service.reapStaleMembers(cutoff()))
                .expectNext(1L)
                .verifyComplete();
    }

    // --- unload-leave: the superseded-tab guard --------------------------------

    @Test
    void unloadLeaveFromASupersededTabDoesNotEvictTheNewTab() {
        // SessionControlService.onTakeoverMessage freezes the OLD tab but
        // leaves it open, so its eventual pagehide still fires. Honouring that
        // unload-leave would stamp left_at on the membership row the NEW tab is
        // playing under — evicting a player who is sitting right there.
        GameSession s = sample();
        SessionMember mine = newMember(s.getId(), "bob", 1L, 1, false);
        lenient().when(members.findActive(s.getId(), "bob")).thenReturn(Mono.just(mine));

        StepVerifier.create(service.pausePlayer(s.getId(), "bob", "tab-takeover", "new-tab-uid"))
                .verifyComplete();

        StepVerifier.create(service.leave(s.getId(), "bob", true))
                .verifyComplete();

        verify(members, never()).stampLeftIfActive(any(), any());
        assertThat(s.getClosedAt()).isNull();
    }

    @Test
    void explicitLeaveIsHonouredEvenMidTakeover() {
        // The guard is scoped to unload-fired leaves. A player who clicks
        // "leave" means it, and that control only exists in the tab they are
        // actually looking at.
        GameSession s = sample();
        SessionMember mine = newMember(s.getId(), "bob", 1L, 1, false);
        when(members.findActive(s.getId(), "bob")).thenReturn(Mono.just(mine));
        when(members.countBySessionIdAndLeftAtIsNull(s.getId())).thenReturn(Mono.just(1L));

        StepVerifier.create(service.pausePlayer(s.getId(), "bob", "tab-takeover", "new-tab-uid"))
                .verifyComplete();

        StepVerifier.create(service.leave(s.getId(), "bob", false))
                .verifyComplete();

        verify(members).stampLeftIfActive(eq(mine.getId()), any());
    }

    @Test
    void unloadLeaveIsHonouredWhenNoTakeoverIsPending() {
        // The ordinary closed-tab case — this is the leg that releases the seat
        // in milliseconds instead of waiting out the reaper's timeout.
        GameSession s = sample();
        SessionMember mine = newMember(s.getId(), "bob", 1L, 1, false);
        when(members.findActive(s.getId(), "bob")).thenReturn(Mono.just(mine));
        when(members.countBySessionIdAndLeftAtIsNull(s.getId())).thenReturn(Mono.just(1L));

        StepVerifier.create(service.leave(s.getId(), "bob", true))
                .verifyComplete();

        verify(members).stampLeftIfActive(eq(mine.getId()), any());
    }

    @Test
    void unloadLeaveIsHonouredOnceTheTakeoverResolves() {
        // resumePlayer drops the shelter and the BFF is out of guards: both tabs
        // authenticate as the same subject with the same JWT, so a leave arriving
        // now is indistinguishable from the surviving tab's own. Honouring it is
        // the only defensible server-side answer — refusing forever would mean a
        // player who took over once could never release their seat by closing
        // the tab again, and would wait out the reaper every single time.
        //
        // This is therefore NOT the fix for the tab-takeover hazard, and reading
        // it as one is the trap. The old tab may sit frozen-but-open for hours
        // before its pagehide fires, and by then this path would evict the
        // player the new tab is playing as. The real guard is the client-side
        // latch (SessionControlService.superseded, raised on the tab that
        // RECEIVES a takeover-claim — BroadcastChannel does not deliver to the
        // sender, so a receiver is by definition the old tab, and it is released
        // again only when that tab itself claims a takeover).
        //
        // A latched tab sends NEITHER exit: not this unload leave, and not the
        // explicit DELETE behind the Close button. Both matter. A takeover
        // freezes the old tab's iframe but leaves its toolbar live, so the
        // Close button is reachable on a frozen tab — and the DELETE it fires
        // carries no `unload` flag, which the guard below deliberately honours.
        // Suppressing only the unload leg would have left the eviction fully
        // open through a button the player can see. The server guard covers
        // only the narrow window the latch cannot: an old tab closed while the
        // new one is still compiling WASM. Both client legs are pinned in
        // frontend/src/app/services/session-leave-on-unload.service.spec.ts and
        // frontend/src/app/home/home.component.spec.ts.
        GameSession s = sample();
        SessionMember mine = newMember(s.getId(), "bob", 1L, 1, false);
        when(members.findActive(s.getId(), "bob")).thenReturn(Mono.just(mine));
        when(members.countBySessionIdAndLeftAtIsNull(s.getId())).thenReturn(Mono.just(1L));

        StepVerifier.create(service.pausePlayer(s.getId(), "bob", "tab-takeover", "uid")).verifyComplete();
        StepVerifier.create(service.resumePlayer(s.getId(), "bob")).verifyComplete();
        StepVerifier.create(service.leave(s.getId(), "bob", true)).verifyComplete();

        verify(members).stampLeftIfActive(eq(mine.getId()), any());
    }

    @Test
    void leaveDoesNotFireTheClosePathsWhenTheReaperAlreadyEndedTheMembership() {
        GameSession s = sample();
        SessionMember mine = newMember(s.getId(), "alice", 1L, 0, true);
        when(members.findActive(s.getId(), "alice")).thenReturn(Mono.just(mine));
        when(members.stampLeftIfActive(eq(mine.getId()), any())).thenReturn(Mono.just(0));
        when(members.countBySessionIdAndLeftAtIsNull(s.getId())).thenReturn(Mono.just(1L));

        StepVerifier.create(service.leave(s.getId(), "alice"))
                .verifyComplete();

        // The creator leaving normally closes the room; a losing race must not.
        verify(gameState, never()).emitSessionControl(any());
    }

    // --- resume must re-stamp BEFORE the room becomes reapable -----------------

    @Test
    void resumeSessionRestampsLivenessBeforeClearingThePause() {
        // Ordering is the whole point: paused_at IS NULL is what puts a room
        // back in findStaleActive's candidate set, and every member of a room
        // that has been paused for longer than the timeout is stale. Clearing
        // the pause first leaves a window in which a sweep reaps the room that
        // was just resumed.
        GameSession s = sample();
        s.setPausedAt(Instant.now().minusSeconds(600));
        s.setPauseReason("manual");
        when(sessions.findById(s.getId())).thenReturn(Mono.just(s));

        StepVerifier.create(service.resumeSession(s.getId(), "alice"))
                .expectNextCount(1)
                .verifyComplete();

        var inOrder = org.mockito.Mockito.inOrder(members, sessions);
        inOrder.verify(members).touchAllActive(eq(s.getId()), any());
        inOrder.verify(sessions).save(any());
        assertThat(s.getPausedAt()).isNull();
    }

    @Test
    void resumePlayerRestampsLivenessForTheReturningTab() {
        GameSession s = sample();
        StepVerifier.create(service.resumePlayer(s.getId(), "bob"))
                .verifyComplete();
        verify(members).touchLastSeen(eq(s.getId()), eq("bob"), any());
    }

    @Test
    void pausePlayerStampsLivenessBecauseATakeoverIsItselfProofOfLife() {
        GameSession s = sample();
        StepVerifier.create(service.pausePlayer(s.getId(), "bob", "tab-takeover", "uid"))
                .verifyComplete();
        verify(members).touchLastSeen(eq(s.getId()), eq("bob"), any());
    }

    // --- heartbeat -------------------------------------------------------------

    @Test
    void heartbeatIsRateLimitedToOneWritePerMemberPerInterval() {
        // authorizeControl runs on every single control write; an UPDATE per
        // input event would put the liveness bookkeeping on the game's hot path
        // for no extra safety.
        StepVerifier.create(service.heartbeat("sess-1", "bob")).verifyComplete();
        StepVerifier.create(service.heartbeat("sess-1", "bob")).verifyComplete();
        StepVerifier.create(service.heartbeat("sess-1", "bob")).verifyComplete();

        verify(members, org.mockito.Mockito.times(1)).touchLastSeen(eq("sess-1"), eq("bob"), any());
    }

    @Test
    void heartbeatRateLimitIsPerMemberNotGlobal() {
        StepVerifier.create(service.heartbeat("sess-1", "bob")).verifyComplete();
        StepVerifier.create(service.heartbeat("sess-1", "carol")).verifyComplete();

        verify(members).touchLastSeen(eq("sess-1"), eq("bob"), any());
        verify(members).touchLastSeen(eq("sess-1"), eq("carol"), any());
    }

    @Test
    void heartbeatIsANoOpWithoutBothIdentifiers() {
        StepVerifier.create(service.heartbeat(null, "bob")).verifyComplete();
        StepVerifier.create(service.heartbeat("sess-1", null)).verifyComplete();
        StepVerifier.create(service.heartbeat("sess-1", "  ")).verifyComplete();

        verify(members, never()).touchLastSeen(anyString(), anyString(), any());
    }

    @Test
    void aFailedHeartbeatWriteIsRetriedOnTheNextSignalRatherThanWaitingOutTheInterval() {
        when(members.touchLastSeen(eq("sess-1"), eq("bob"), any()))
                .thenReturn(Mono.error(new IllegalStateException("connection reset")))
                .thenReturn(Mono.just(1));

        StepVerifier.create(service.heartbeat("sess-1", "bob")).verifyComplete();
        StepVerifier.create(service.heartbeat("sess-1", "bob")).verifyComplete();

        verify(members, org.mockito.Mockito.times(2)).touchLastSeen(eq("sess-1"), eq("bob"), any());
    }

    @Test
    void authorizeControlRecordsALivenessSignal() {
        // The one chokepoint every game's hot path crosses — and the only
        // liveness signal the 16 non-Godot titles produce from gameplay alone.
        SessionMember m = newMember("sess-A", "alice", 1L, 0, true);
        when(members.findActiveOwnerInSession("sess-A", 1L)).thenReturn(Mono.just(m));

        StepVerifier.create(service.authorizeControl("alice", 1L, "sess-A"))
                .expectNextCount(1)
                .verifyComplete();

        verify(members).touchLastSeen(eq("sess-A"), eq("alice"), any());
    }

    @Test
    void aFailedHeartbeatNeverFailsTheControlItRodeInOn() {
        // A heartbeat is bookkeeping. Propagating its error would 403 a
        // legitimate control write and bounce the player into the
        // SD_SESSION_EXPIRED recovery loop over a bookkeeping blip.
        SessionMember m = newMember("sess-A", "alice", 1L, 0, true);
        when(members.findActiveOwnerInSession("sess-A", 1L)).thenReturn(Mono.just(m));
        when(members.touchLastSeen(anyString(), anyString(), any()))
                .thenReturn(Mono.error(new IllegalStateException("db down")));

        StepVerifier.create(service.authorizeControl("alice", 1L, "sess-A"))
                .assertNext(r -> assertThat(r.getCasdoorId()).isEqualTo("alice"))
                .verifyComplete();
    }

    @Test
    void joinSeedsLastSeenAtSoAColdWasmBootIsNeverAReapCandidate() {
        // A cold image takes ~30 s to compile WASM — longer than a heartbeat
        // interval — before the client produces its first signal.
        GameSession s = sample();
        when(sessions.findById(s.getId())).thenReturn(Mono.just(s));
        when(members.findActive(s.getId(), "bob")).thenReturn(Mono.empty());
        when(members.countBySessionIdAndLeftAtIsNull(s.getId())).thenReturn(Mono.just(1L));

        StepVerifier.create(service.join(s.getId(), "bob", 2L, 1))
                .expectNextCount(1)
                .verifyComplete();

        var saved = org.mockito.ArgumentCaptor.forClass(SessionMember.class);
        verify(members).save(saved.capture());
        assertThat(saved.getValue().getLastSeenAt()).isNotNull();
    }

    // --- closing a session must release its seats ------------------------------

    @Test
    void forceCloseReleasesEveryActiveSeat() {
        // Pre-existing ghost source: close() stamped game_sessions.closed_at
        // alone, leaving every member row left_at IS NULL forever. Those
        // orphans still satisfied findActiveOwnerOfCharacter and still read as
        // "in a session" to presence and history.
        GameSession s = sample();
        when(sessions.findById(s.getId())).thenReturn(Mono.just(s));

        StepVerifier.create(service.close(s.getId()))
                .verifyComplete();

        verify(members).stampAllActiveLeft(eq(s.getId()), any());
    }

    @Test
    void hostDropReleasesEveryStrandedGuestsSeat() {
        GameSession s = sample();
        when(sessions.findById(s.getId())).thenReturn(Mono.just(s));

        StepVerifier.create(service.closeOnHostDrop(s.getId()))
                .verifyComplete();

        verify(members).stampAllActiveLeft(eq(s.getId()), any());
    }

    // --- the reaper touches nothing it wasn't handed ---------------------------

    @Test
    void aPlayerWhoIsStillPollingIsNeverASweepCandidate() {
        // findStaleActive is the ONLY source of candidates: there is no second
        // scan, no "while I'm here" pass over the rest of the room. So a member
        // whose signal is fresh — and who therefore fails the query's
        // COALESCE(last_seen_at, joined_at) < :cutoff predicate — cannot be
        // reached by the sweep at all, and a sweep that finds nobody writes
        // nothing rather than falling back to some broader set.
        GameSession s = sample();
        when(members.findStaleActive(any())).thenReturn(Flux.empty());
        lenient().when(sessions.findById(s.getId())).thenReturn(Mono.just(s));

        StepVerifier.create(service.reapStaleMembers(cutoff()))
                .expectNext(0L)
                .verifyComplete();

        verify(members, never()).stampLeftIfActive(any(), any());
        verify(members, never()).stampAllActiveLeft(anyString(), any());
        verify(gameState, never()).emitSessionControl(any());
    }

    @Test
    void theSweepAsksTheQueryForExactlyTheCutoffItWasGiven() {
        // Widening the window in the service would silently reap live players
        // whatever the reaper's configured timeout says.
        Instant cutoff = Instant.now().minusSeconds(90);

        StepVerifier.create(service.reapStaleMembers(cutoff))
                .expectNext(0L)
                .verifyComplete();

        verify(members).findStaleActive(eq(cutoff));
    }

    @Test
    void aCandidateWhoseSessionRowVanishedIsLeftAlone() {
        // findById empty — the room was hard-deleted between the select and the
        // reap. Stamping the member anyway would strand a row referencing a
        // session nothing can close.
        GameSession s = sample();
        SessionMember orphan = staleMember(s.getId(), "bob", false);
        when(members.findStaleActive(any())).thenReturn(Flux.just(orphan));
        when(sessions.findById(s.getId())).thenReturn(Mono.empty());

        StepVerifier.create(service.reapStaleMembers(cutoff()))
                .expectNext(0L)
                .verifyComplete();

        verify(members, never()).stampLeftIfActive(any(), any());
    }

    // --- heartbeat suppression must not outlive the seat -----------------------

    @Test
    void aReleasedSeatDropsItsSuppressionSoARejoinStampsImmediately() {
        // The rate limiter is keyed by (session, member). If leaving did not
        // clear the stamp, a player who left and rejoined inside one interval
        // would have their first heartbeat suppressed — and with it the only
        // thing standing between them and the next sweep.
        GameSession s = sample();
        SessionMember mine = newMember(s.getId(), "bob", 1L, 1, false);
        when(members.findActive(s.getId(), "bob")).thenReturn(Mono.just(mine));
        when(members.countBySessionIdAndLeftAtIsNull(s.getId())).thenReturn(Mono.just(1L));

        StepVerifier.create(service.heartbeat(s.getId(), "bob")).verifyComplete();
        StepVerifier.create(service.leave(s.getId(), "bob")).verifyComplete();
        StepVerifier.create(service.heartbeat(s.getId(), "bob")).verifyComplete();

        verify(members, org.mockito.Mockito.times(2))
                .touchLastSeen(eq(s.getId()), eq("bob"), any());
    }

    @Test
    void reapingASeatDropsItsSuppressionTheSameWay() {
        GameSession s = sample();
        SessionMember ghost = staleMember(s.getId(), "bob", false);
        when(members.findStaleActive(any())).thenReturn(Flux.just(ghost));
        when(sessions.findById(s.getId())).thenReturn(Mono.just(s));
        when(members.countBySessionIdAndLeftAtIsNull(s.getId())).thenReturn(Mono.just(1L));

        StepVerifier.create(service.heartbeat(s.getId(), "bob")).verifyComplete();
        StepVerifier.create(service.reapStaleMembers(cutoff())).expectNext(1L).verifyComplete();
        StepVerifier.create(service.heartbeat(s.getId(), "bob")).verifyComplete();

        verify(members, org.mockito.Mockito.times(2))
                .touchLastSeen(eq(s.getId()), eq("bob"), any());
    }

    @Test
    void closingASessionForgetsItsTakeoverSheltersSoTheyCannotOutliveTheRoom() {
        // The shelter map is keyed by session id and session ids are minted, so
        // a leaked entry is not a correctness bug — it is a leak. It is also the
        // map hasPendingTakeover reads, and an entry for a dead session would
        // keep dropping unload-leaves for a member who has nowhere to be.
        GameSession s = sample();
        when(sessions.findById(s.getId())).thenReturn(Mono.just(s));
        SessionMember mine = newMember(s.getId(), "bob", 1L, 1, false);
        lenient().when(members.findActive(s.getId(), "bob")).thenReturn(Mono.just(mine));
        when(members.countBySessionIdAndLeftAtIsNull(s.getId())).thenReturn(Mono.just(1L));

        StepVerifier.create(service.pausePlayer(s.getId(), "bob", "tab-takeover", "uid")).verifyComplete();
        StepVerifier.create(service.close(s.getId())).verifyComplete();

        // With the shelter gone the unload-leave is honoured again.
        StepVerifier.create(service.leave(s.getId(), "bob", true)).verifyComplete();
        verify(members).stampLeftIfActive(eq(mine.getId()), any());
    }

    // --- reaping the creator: the close is what decides, not the intent ------
    //
    // reapCreator collects the host by closing the whole room, and
    // closeOnHostDrop is idempotent by filtering on `closed_at IS NULL`. That
    // means it can legitimately do NOTHING — a concurrent force-close or
    // auto-empty close got there first. Announcing the reap before knowing
    // which happened emitted a `session.left` audit row for a departure this
    // sweep did not perform and inflated the operator-facing count.

    /**
     * The race: {@code reapOne} re-reads the session and sees it OPEN, so the
     * creator goes down the host-drop path — and by the time
     * {@code closeOnHostDrop} does its own read, a concurrent force-close has
     * landed and its {@code closed_at IS NULL} filter short-circuits the whole
     * body. Two consecutive stubs model exactly that interleaving.
     */
    private SessionMember creatorClosedOutFromUnderTheReaper() {
        GameSession open = sample();
        GameSession closedByRace = sample(open.getId());
        closedByRace.setClosedAt(Instant.now());
        SessionMember host = staleMember(open.getId(), "alice", true);
        when(members.findStaleActive(any())).thenReturn(Flux.just(host));
        when(sessions.findById(open.getId()))
                .thenReturn(Mono.just(open), Mono.just(closedByRace));
        return host;
    }

    @Test
    void reapingACreatorTheRaceAlreadyCollectedCountsNothing() {
        SessionMember host = creatorClosedOutFromUnderTheReaper();
        // The winning close stamped every active seat, this one included.
        when(members.stampLeftIfActive(eq(host.getId()), any())).thenReturn(Mono.just(0));

        StepVerifier.create(service.reapStaleMembers(cutoff()))
                .expectNext(0L)
                .verifyComplete();

        verify(gameState, never()).emitSessionControl(any());
    }

    @Test
    void reapingACreatorTheRaceAlreadyCollectedStillRecoversAnUnstampedRow() {
        // The orphan class this changeset exists to eliminate. findStaleActive
        // requires `s.closed_at IS NULL`, so a member row left active inside an
        // already-CLOSED session is invisible to every future sweep — this is
        // the last chance anything has to release it. Gate 0b's objection to
        // stamping a creator does not apply once the room is closed: there are
        // no controls left to 403.
        SessionMember host = creatorClosedOutFromUnderTheReaper();
        when(members.stampLeftIfActive(eq(host.getId()), any())).thenReturn(Mono.just(1));

        StepVerifier.create(service.reapStaleMembers(cutoff()))
                .expectNext(1L)
                .verifyComplete();

        verify(members).stampLeftIfActive(eq(host.getId()), any());
        // Still no second SESSION_CLOSED — the room was closed by somebody else.
        verify(gameState, never()).emitSessionControl(any());
    }

    @Test
    void reapingACreatorOfARoomThatVanishedCountsNothing() {
        // findById empty: the session row was deleted between the candidate
        // select and the reap. Nothing to close, nothing to claim, nothing to
        // report.
        GameSession s = sample();
        SessionMember host = staleMember(s.getId(), "alice", true);
        when(members.findStaleActive(any())).thenReturn(Flux.just(host));
        when(sessions.findById(s.getId())).thenReturn(Mono.empty());

        StepVerifier.create(service.reapStaleMembers(cutoff()))
                .expectNext(0L)
                .verifyComplete();

        verify(members, never()).stampLeftIfActive(eq(host.getId()), any());
    }

    /** A cutoff far enough in the past that {@link #staleMember} rows sit behind it. */
    private static Instant cutoff() {
        return Instant.now().minusSeconds(90);
    }

    /**
     * A member whose last liveness signal predates {@link #cutoff()} — i.e. the
     * shape {@code findStaleActive} would hand the reaper.
     */
    private static SessionMember staleMember(String sid, String cid, boolean creator) {
        SessionMember m = newMember(sid, cid, 1L, creator ? 0 : 1, creator);
        m.setLastSeenAt(Instant.now().minusSeconds(600));
        return m;
    }

    private static GameSession sample() {
        return sample("00000000-0000-0000-0000-000000000001");
    }

    private static GameSession sample(String id) {
        GameSession s = new GameSession();
        s.setId(id);
        s.setName("Demo");
        s.setGameSlug("three-wizards");
        s.setCreatorCasdoorId("alice");
        s.setMaxPlayers(3);
        s.setCreatedAt(Instant.now());
        return s;
    }

    /**
     * Hands out the {@code session_members.id} surrogate keys the fixtures
     * below pretend Postgres assigned. Distinct ids matter for the liveness
     * specs, which verify the reaper's row-scoped UPDATE targeted exactly the
     * member it meant to.
     */
    private static final java.util.concurrent.atomic.AtomicLong MEMBER_IDS =
            new java.util.concurrent.atomic.AtomicLong(1);

    private static SessionMember newMember(String sid, String cid, Long charId, Integer slot, boolean creator) {
        SessionMember m = new SessionMember();
        m.setId(MEMBER_IDS.getAndIncrement());
        m.setSessionId(sid);
        m.setCasdoorId(cid);
        m.setCharacterId(charId);
        m.setSlotIndex(slot);
        m.setCreator(creator);
        m.setJoinedAt(Instant.now());
        // Fresh by default — a spec that wants a ghost sets an old stamp.
        m.setLastSeenAt(Instant.now());
        return m;
    }
}
