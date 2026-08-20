package games.hugmesh.bff.service;

import games.hugmesh.bff.model.AdminAction;
import games.hugmesh.bff.model.GameSession;
import games.hugmesh.bff.model.ModGameScope;
import games.hugmesh.bff.model.SessionMember;
import games.hugmesh.bff.repository.AdminActionRepository;
import games.hugmesh.bff.repository.GameSessionRepository;
import games.hugmesh.bff.repository.ModGameScopeRepository;
import games.hugmesh.bff.repository.SessionMemberRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;
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
 * Authorization specs for the per-game mod role.
 *
 * <p>The contract is: Casdoor's `mod` role grants the capability;
 * `mod_game_scopes` controls which games. A scoped mod can act; an
 * unscoped mod (or a mod scoped to a different game) is rejected.
 */
class ModerationServiceTest {

    private GameSessionRepository sessions;
    private SessionMemberRepository members;
    private ModGameScopeRepository scopes;
    private AdminActionRepository actions;
    private GameSessionService gameSessionService;
    private ModerationService moderation;

    @BeforeEach
    void setUp() {
        sessions = mock(GameSessionRepository.class);
        members  = mock(SessionMemberRepository.class);
        scopes   = mock(ModGameScopeRepository.class);
        actions  = mock(AdminActionRepository.class);
        // Java-25 + ByteBuddy can't mock the concrete GameSessionService; a no-op
        // subclass is the repo idiom for concrete collaborators (the super ctor only
        // assigns fields, none of which this stub touches).
        gameSessionService = new FakeGameSessionService();
        moderation = new ModerationService(sessions, members, scopes, actions, gameSessionService);

        lenient().when(actions.save(any())).thenAnswer(inv -> Mono.just(inv.getArgument(0)));
    }

    private static final class FakeGameSessionService extends GameSessionService {
        FakeGameSessionService() {
            super(null, null, null, null, java.util.Optional.empty(), java.util.Optional.empty(), 15_000L);
        }
        @Override public Mono<Void> close(String sessionId) { return Mono.empty(); }
        @Override public Mono<Void> leave(String sessionId, String casdoorId) { return Mono.empty(); }
    }

    @Test
    void stopSessionBlocksUnscopedMod() {
        GameSession s = sample("three-wizards");
        when(sessions.findById("s-1")).thenReturn(Mono.just(s));
        when(scopes.findActive("mallory", "three-wizards")).thenReturn(Mono.empty());

        StepVerifier.create(moderation.stopSession("mallory", "s-1", "no reason"))
                .expectErrorSatisfies(err -> {
                    assertThat(err).isInstanceOf(ResponseStatusException.class);
                    assertThat(((ResponseStatusException) err).getStatusCode().value()).isEqualTo(403);
                })
                .verify();
    }

    @Test
    void stopSessionAllowsScopedMod() {
        GameSession s = sample("three-wizards");
        when(sessions.findById("s-1")).thenReturn(Mono.just(s));
        when(scopes.findActive("twiat-mod", "three-wizards"))
                .thenReturn(Mono.just(scopeRow("twiat-mod", "three-wizards")));

        StepVerifier.create(moderation.stopSession("twiat-mod", "s-1", "spam"))
                .verifyComplete();
    }

    @Test
    void stopSessionBlocksWhenScopedToDifferentGame() {
        GameSession s = sample("three-wizards");
        when(sessions.findById("s-1")).thenReturn(Mono.just(s));
        when(scopes.findActive("dodec-mod", "three-wizards")).thenReturn(Mono.empty());

        StepVerifier.create(moderation.stopSession("dodec-mod", "s-1", null))
                .expectErrorSatisfies(err ->
                        assertThat(((ResponseStatusException) err).getStatusCode().value()).isEqualTo(403))
                .verify();
    }

    @Test
    void kickPlayerStampsLeftAt() {
        GameSession s = sample("three-wizards");
        when(sessions.findById("s-1")).thenReturn(Mono.just(s));
        when(scopes.findActive("twiat-mod", "three-wizards"))
                .thenReturn(Mono.just(scopeRow("twiat-mod", "three-wizards")));

        SessionMember target = new SessionMember();
        target.setSessionId("s-1");
        target.setCasdoorId("noisy-player");
        when(members.findActive("s-1", "noisy-player")).thenReturn(Mono.just(target));
        when(members.save(any())).thenAnswer(inv -> Mono.just(inv.getArgument(0)));

        StepVerifier.create(moderation.kickPlayer("twiat-mod", "s-1", "noisy-player", "afk for 30 min"))
                .verifyComplete();

        assertThat(target.getLeftAt()).as("kick stamps left_at").isNotNull();
    }

    @Test
    void kickReturns404WhenTargetNotInSession() {
        GameSession s = sample("three-wizards");
        when(sessions.findById("s-1")).thenReturn(Mono.just(s));
        when(scopes.findActive("twiat-mod", "three-wizards"))
                .thenReturn(Mono.just(scopeRow("twiat-mod", "three-wizards")));
        when(members.findActive("s-1", "ghost")).thenReturn(Mono.empty());

        StepVerifier.create(moderation.kickPlayer("twiat-mod", "s-1", "ghost", null))
                .expectErrorSatisfies(err ->
                        assertThat(((ResponseStatusException) err).getStatusCode().value()).isEqualTo(404))
                .verify();
    }

    private static GameSession sample(String gameSlug) {
        GameSession s = new GameSession();
        s.setId("s-1");
        s.setName("Demo");
        s.setGameSlug(gameSlug);
        s.setCreatorCasdoorId("alice");
        s.setMaxPlayers(3);
        s.setCreatedAt(Instant.now());
        return s;
    }

    private static ModGameScope scopeRow(String casdoorId, String gameSlug) {
        ModGameScope row = new ModGameScope();
        row.setCasdoorId(casdoorId);
        row.setGameSlug(gameSlug);
        row.setGrantedByCasdoorId("system-seed");
        row.setGrantedAt(Instant.now());
        return row;
    }

    @SuppressWarnings("unused")
    private static AdminAction sampleAudit() { return new AdminAction(); }
}
