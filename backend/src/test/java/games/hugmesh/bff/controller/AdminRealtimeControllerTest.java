package games.hugmesh.bff.controller;

import games.hugmesh.bff.model.Game;
import games.hugmesh.bff.model.GameSession;
import games.hugmesh.bff.model.SessionMember;
import games.hugmesh.bff.model.realtime.RealtimeMatch;
import games.hugmesh.bff.repository.GameRepository;
import games.hugmesh.bff.repository.GameSessionRepository;
import games.hugmesh.bff.repository.RealtimeMatchRepository;
import games.hugmesh.bff.repository.SessionMemberRepository;
import games.hugmesh.bff.security.DomainRegistry;
import games.hugmesh.bff.service.DomainScopeService;
import games.hugmesh.bff.service.realtime.AuthoritativeTickService;
import games.hugmesh.bff.service.realtime.RealtimeTruthStore;
import games.hugmesh.bff.service.realtime.SimCommand;
import games.hugmesh.bff.service.realtime.SimCore;
import games.hugmesh.bff.service.realtime.SimCoreFactory;
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
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Controller-level contract for {@link AdminRealtimeController}: manual
 * recover happy path, 409 when a match is already running, 404 when no
 * durable match row, and brand-domain DENY before any recovery side work
 * — the {@link RealtimeControllerTest} conventions (real services over
 * mock repos; Java-25 ByteBuddy can't mock the concrete tick service, so
 * the truth store + sim are hand-rolled fakes). The ROLE_ADMIN gate
 * itself is annotation-level ({@code @PreAuthorize} + the
 * {@code /api/admin/**} matcher) and not exercised by direct calls.
 */
class AdminRealtimeControllerTest {

    private static final String ISS_X = "https://casdoor.brandx.example";
    private static final String ISS_Y = "https://casdoor.brandy.example";

    private GameRepository gameRepo;
    private GameSessionRepository sessionRepo;
    private SessionMemberRepository memberRepo;
    private RealtimeMatchRepository matchRepo;
    private ObjectProvider<RealtimeTruthStore> truthStore;
    private StubSimFactory stubFactory;
    private AuthoritativeTickService ticks;

    /** Minimal restorable sim — the recover contract under test is the
     *  controller's, not the replay's (AuthoritativeTickServiceTest owns that). */
    static final class StubSim implements SimCore {
        long tick;
        @Override public long tick() { return tick; }
        @Override public void step(List<SimCommand> cmds) { tick++; }
        @Override public String snapshotJson() { return "{\"tick\":" + tick + "}"; }
        @Override public boolean isTerminal() { return false; }
        @Override public String resultJson() { return null; }
    }

    static final class StubSimFactory implements SimCoreFactory {
        final AtomicInteger restores = new AtomicInteger();
        @Override public String kind() { return "stub"; }
        @Override public SimCore create(long seed, String configJson) { return new StubSim(); }
        @Override public SimCore restore(String snapshotJson) {
            restores.incrementAndGet();
            return new StubSim();
        }
    }

    /** In-memory truth holding one recoverable keyframe per session. */
    static final class SeededTruthStore extends RealtimeTruthStore {
        SeededTruthStore() { super(null, null); }
        @Override public Mono<Void> saveKeyframe(String sid, long tick, String stateJson) {
            return Mono.empty();
        }
        @Override public Mono<Void> appendCommand(String sid, SimCommand cmd) {
            return Mono.empty();
        }
        @Override public Mono<Keyframe> latestKeyframe(String sid) {
            return Mono.just(new Keyframe(sid, 0, "{}"));
        }
        @Override public Flux<SimCommand> commandsAfter(String sid, long tick) {
            return Flux.empty();
        }
    }

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
        lenient().when(truthStore.getIfAvailable()).thenReturn(new SeededTruthStore());
    }

    private AdminRealtimeController controllerFor(DomainRegistry registry) {
        stubFactory = new StubSimFactory();
        ticks = new AuthoritativeTickService(
                List.of(stubFactory), matchRepo, memberRepo, truthStore,
                Optional.empty(), /* tickHz: manual */ 0, 2, 30);
        DomainScopeService scope = new DomainScopeService(gameRepo, sessionRepo);
        return new AdminRealtimeController(ticks, scope, registry);
    }

    private void stubSession(String sessionId, String gameSlug, String domain) {
        GameSession s = new GameSession();
        s.setId(sessionId);
        s.setGameSlug(gameSlug);
        s.setCreatorCasdoorId("alice");
        when(sessionRepo.findById(sessionId)).thenReturn(Mono.just(s));
        Game g = new Game();
        g.setDomain(domain);
        when(gameRepo.findBySlug(gameSlug)).thenReturn(Mono.just(g));
        SessionMember m = new SessionMember();
        m.setSessionId(sessionId);
        m.setCasdoorId("alice");
        m.setJoinedAt(Instant.now());
        when(memberRepo.findActiveBySession(sessionId)).thenReturn(Flux.just(m));
    }

    private void stubMatchRow(String sessionId) {
        when(matchRepo.findById(sessionId)).thenReturn(Mono.fromCallable(() -> {
            RealtimeMatch row = new RealtimeMatch();
            row.setSessionId(sessionId);
            row.setKind("stub");
            row.setGameSlug("g");
            return row;
        }));
    }

    private static Jwt jwt(String issuer, String sub) {
        return Jwt.withTokenValue("t").header("alg", "RS256")
                .issuer(issuer).subject(sub)
                .issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(300))
                .claim("roles", List.of("admin")).build();
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
    // USER-SITUATIONAL — manual recover
    // ===================================================================
    @Nested
    class ManualRecover {

        @Test
        void adminRecoversAPersistedMatch() {
            AdminRealtimeController c = controllerFor(new DomainRegistry("", ""));
            stubSession("s", "g", "default");
            stubMatchRow("s");

            StepVerifier.create(c.recover(jwt(ISS_X, "admin"), "s")).verifyComplete();
            assertThat(stubFactory.restores).hasValue(1);

            // The match is now running on this pod — a second recover is 409.
            assertStatus(c.recover(jwt(ISS_X, "admin"), "s"), 409);
            assertThat(stubFactory.restores).hasValue(1);
        }

        @Test
        void recoverOfAnAlreadyRunningMatchIs409() {
            AdminRealtimeController c = controllerFor(new DomainRegistry("", ""));
            stubSession("s", "g", "default");
            StepVerifier.create(ticks.startMatch("s", "g", "stub", 1L, List.of("p1"), null))
                    .verifyComplete();

            assertStatus(c.recover(jwt(ISS_X, "admin"), "s"), 409);
            assertThat(stubFactory.restores).hasValue(0);
        }

        @Test
        void recoverWithoutAMatchRowIs404() {
            AdminRealtimeController c = controllerFor(new DomainRegistry("", ""));
            stubSession("s", "g", "default");
            // matchRepo.findById defaults to empty — no durable row.
            assertStatus(c.recover(jwt(ISS_X, "admin"), "s"), 404);
            assertThat(stubFactory.restores).hasValue(0);
        }
    }

    // ===================================================================
    // CROSS-USER — brand-domain DENY before any recovery side work
    // ===================================================================
    @Nested
    class DomainScoping {

        private DomainRegistry multiBrand() {
            return new DomainRegistry("", ISS_X + "=brandx," + ISS_Y + "=brandy");
        }

        @Test
        void recoverDeniesForeignBrandSessionBeforeAnySideWork() {
            AdminRealtimeController c = controllerFor(multiBrand());
            stubSession("s-y", "gy", "brandy");
            stubMatchRow("s-y");

            assertStatus(c.recover(jwt(ISS_X, "admin"), "s-y"), 403);
            assertThat(stubFactory.restores).hasValue(0);
            verify(matchRepo, never()).findById(anyString());
        }

        @Test
        void recoverAllowsOwnBrandSession() {
            AdminRealtimeController c = controllerFor(multiBrand());
            stubSession("s-x", "gx", "brandx");
            stubMatchRow("s-x");

            StepVerifier.create(c.recover(jwt(ISS_X, "admin"), "s-x")).verifyComplete();
            assertThat(stubFactory.restores).hasValue(1);
        }
    }
}
