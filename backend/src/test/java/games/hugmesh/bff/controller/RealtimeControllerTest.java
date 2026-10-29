package games.hugmesh.bff.controller;

import games.hugmesh.bff.controller.RealtimeController.InputRequest;
import games.hugmesh.bff.controller.RealtimeController.StartMatchRequest;
import games.hugmesh.bff.model.Game;
import games.hugmesh.bff.model.GameSession;
import games.hugmesh.bff.model.SessionMember;
import games.hugmesh.bff.repository.GameRepository;
import games.hugmesh.bff.repository.GameSessionRepository;
import games.hugmesh.bff.repository.RealtimeMatchRepository;
import games.hugmesh.bff.repository.SessionMemberRepository;
import games.hugmesh.bff.security.DomainRegistry;
import games.hugmesh.bff.service.DomainScopeService;
import games.hugmesh.bff.service.realtime.AuthoritativeTickService;
import games.hugmesh.bff.service.realtime.ReferenceArenaSimFactory;
import games.hugmesh.bff.service.realtime.RealtimeTruthStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Controller-level contract for {@link RealtimeController}: creator-only
 * match start, brand-domain gating BEFORE any side work (the
 * {@code ModerationControllerDomainScopeTest} shape — a real
 * {@link DomainScopeService} over mock repos, a REAL
 * {@link AuthoritativeTickService} with {@code tickHz=0} because Java-25
 * ByteBuddy can't mock the concrete class), and the Cassandra-absent
 * empty command log.
 */
class RealtimeControllerTest {

    private static final String ISS_X = "https://casdoor.brandx.example";
    private static final String ISS_Y = "https://casdoor.brandy.example";

    private GameRepository gameRepo;
    private GameSessionRepository sessionRepo;
    private SessionMemberRepository memberRepo;
    private RealtimeMatchRepository matchRepo;
    private ObjectProvider<RealtimeTruthStore> truthStore;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        gameRepo    = mock(GameRepository.class);
        sessionRepo = mock(GameSessionRepository.class);
        memberRepo  = mock(SessionMemberRepository.class);
        matchRepo   = mock(RealtimeMatchRepository.class);
        truthStore  = mock(ObjectProvider.class);
        lenient().when(matchRepo.save(any())).thenAnswer(inv -> Mono.just(inv.getArgument(0)));
        lenient().when(matchRepo.findById(anyString())).thenReturn(Mono.empty());
        lenient().when(truthStore.getIfAvailable()).thenReturn(null);
    }

    private RealtimeController controllerFor(DomainRegistry registry) {
        AuthoritativeTickService ticks = new AuthoritativeTickService(
                List.of(new ReferenceArenaSimFactory()), matchRepo, memberRepo, truthStore,
                Optional.empty(), /* tickHz: manual */ 0, 2, 30);
        DomainScopeService scope = new DomainScopeService(gameRepo, sessionRepo);
        return new RealtimeController(ticks, sessionRepo, memberRepo, scope, registry, truthStore,
                games.hugmesh.bff.security.GamesAdminPolicy.gamesRealm());
    }

    private void stubSession(String sessionId, String gameSlug, String domain, String creator) {
        GameSession s = new GameSession();
        s.setId(sessionId);
        s.setGameSlug(gameSlug);
        s.setCreatorCasdoorId(creator);
        when(sessionRepo.findById(sessionId)).thenReturn(Mono.just(s));
        Game g = new Game();
        g.setDomain(domain);
        when(gameRepo.findBySlug(gameSlug)).thenReturn(Mono.just(g));
        SessionMember m = new SessionMember();
        m.setSessionId(sessionId);
        m.setCasdoorId(creator);
        m.setJoinedAt(Instant.now());
        when(memberRepo.findActiveBySession(sessionId)).thenReturn(Flux.just(m));
    }

    private static Jwt jwt(String issuer, String sub) {
        return Jwt.withTokenValue("t").header("alg", "RS256")
                .issuer(issuer).subject(sub)
                .issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(300))
                .claim("roles", List.of("player")).build();
    }

    private static void assertStatus(org.reactivestreams.Publisher<?> p, int status) {
        StepVerifier.create(p)
                .expectErrorSatisfies(err -> {
                    assertThat(err).isInstanceOf(ResponseStatusException.class);
                    assertThat(((ResponseStatusException) err).getStatusCode().value()).isEqualTo(status);
                })
                .verify();
    }

    // ===================================================================
    // USER-SITUATIONAL — creator-only start
    // ===================================================================
    @Nested
    class CreatorOnlyStart {

        @Test
        void creatorStartsTheMatch() {
            RealtimeController c = controllerFor(new DomainRegistry("", ""));
            stubSession("s", "g", "default", "alice");
            StepVerifier.create(c.start(jwt(ISS_X, "alice"), "s",
                            new StartMatchRequest("reference-arena", 42L, null)))
                    .verifyComplete();
            verify(matchRepo).save(any());
        }

        @Test
        void nonCreatorIsDeniedBeforeAnyMatchWork() {
            RealtimeController c = controllerFor(new DomainRegistry("", ""));
            stubSession("s", "g", "default", "alice");
            assertStatus(c.start(jwt(ISS_X, "bob"), "s",
                    new StartMatchRequest("reference-arena", 42L, null)), 403);
            verify(matchRepo, never()).save(any());
        }
    }

    // ===================================================================
    // CROSS-USER — brand-domain DENY before side work, ALLOW on own brand
    // ===================================================================
    @Nested
    class DomainScoping {

        private DomainRegistry multiBrand() {
            return new DomainRegistry("", ISS_X + "=brandx," + ISS_Y + "=brandy");
        }

        @Test
        void startDeniesForeignBrandSession() {
            RealtimeController c = controllerFor(multiBrand());
            stubSession("s-y", "gy", "brandy", "alice");
            assertStatus(c.start(jwt(ISS_X, "alice"), "s-y",
                    new StartMatchRequest("reference-arena", 1L, null)), 403);
            verify(matchRepo, never()).save(any());
        }

        @Test
        void inputDeniesForeignBrandSessionBeforeServiceLookup() {
            RealtimeController c = controllerFor(multiBrand());
            stubSession("s-y", "gy", "brandy", "alice");
            assertStatus(c.input(jwt(ISS_X, "alice"), "s-y", new InputRequest("{}")), 403);
        }

        @Test
        void inputAllowsOwnBrandMember() {
            RealtimeController c = controllerFor(multiBrand());
            stubSession("s-x", "gx", "brandx", "alice");
            StepVerifier.create(c.start(jwt(ISS_X, "alice"), "s-x",
                            new StartMatchRequest("reference-arena", 1L, null)))
                    .verifyComplete();
            StepVerifier.create(c.input(jwt(ISS_X, "alice"), "s-x",
                            new InputRequest("{\"op\":\"move\",\"dx\":1,\"dy\":0}")))
                    .assertNext(resp -> assertThat(resp).containsEntry("scheduledTick", 2L))
                    .verifyComplete();
        }
    }

    // ===================================================================
    // Cassandra-absent — the replay feed degrades to empty
    // ===================================================================
    @Nested
    class CommandLog {

        @Test
        void logIsEmptyWhenTruthStoreAbsent() {
            RealtimeController c = controllerFor(new DomainRegistry("", ""));
            stubSession("s", "g", "default", "alice");
            StepVerifier.create(c.log(jwt(ISS_X, "alice"), "s", -1))
                    .verifyComplete();
        }
    }
}
