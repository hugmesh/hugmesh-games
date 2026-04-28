package games.snapdragon.bff.service;

import games.snapdragon.bff.model.GameSession;
import games.snapdragon.bff.model.SessionMember;
import games.snapdragon.bff.repository.GameSessionRepository;
import games.snapdragon.bff.repository.SessionMemberRepository;
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
    private UserRelationshipService relationships;
    private GameSessionService service;

    @BeforeEach
    void setUp() {
        sessions      = mock(GameSessionRepository.class);
        members       = mock(SessionMemberRepository.class);
        relationships = mock(UserRelationshipService.class);
        service       = new GameSessionService(sessions, members, relationships);

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
        SessionMember mine = newMember(s.getId(), "alice", 1L, 0, true);
        when(members.findActive(s.getId(), "alice")).thenReturn(Mono.just(mine));
        when(members.countBySessionIdAndLeftAtIsNull(s.getId())).thenReturn(Mono.just(2L));

        StepVerifier.create(service.leave(s.getId(), "alice"))
                .verifyComplete();
        assertThat(s.getClosedAt()).isNull();
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
    void authorizeControlPassesWhenCallerOwnsCharacter() {
        SessionMember alicesMembership = newMember("sess-1", "alice", 7L, 0, true);
        when(members.findActiveOwnerOfCharacter(7L)).thenReturn(Mono.just(alicesMembership));

        StepVerifier.create(service.authorizeControl("alice", 7L))
                .assertNext(m -> assertThat(m.getCasdoorId()).isEqualTo("alice"))
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
