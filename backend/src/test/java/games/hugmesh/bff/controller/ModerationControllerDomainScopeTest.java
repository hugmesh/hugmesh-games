package games.hugmesh.bff.controller;

import games.hugmesh.bff.controller.ModerationController.KickPlayerRequest;
import games.hugmesh.bff.controller.ModerationController.StopSessionRequest;
import games.hugmesh.bff.model.Game;
import games.hugmesh.bff.model.GameSession;
import games.hugmesh.bff.repository.GameRepository;
import games.hugmesh.bff.repository.GameSessionRepository;
import games.hugmesh.bff.repository.ModGameScopeRepository;
import games.hugmesh.bff.security.DomainRegistry;
import games.hugmesh.bff.service.DomainScopeService;
import games.hugmesh.bff.service.ModerationService;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Controller-level proof that {@link ModerationController} enforces caller-domain ==
 * the SESSION's game-domain. A brand-X admin/mod must not stop or kick on a brand-Y
 * session; SUPERADMIN bypasses; single-tenant is inert.
 *
 * <p>The moderation service is a recording subclass (Java-25 can't mock the concrete
 * {@code ModerationService}); the DENY path asserts 403 AND that the service was never
 * reached, the ALLOW path asserts it WAS reached — proving the brand gate fires before
 * the action. The session→game_slug→domain resolution runs through a REAL
 * {@link DomainScopeService} over mock repos.
 */
class ModerationControllerDomainScopeTest {

    private static final String ISS_X = "https://casdoor.brandx.example";
    private static final String ISS_Y = "https://casdoor.brandy.example";

    private GameRepository gameRepo;
    private GameSessionRepository sessionRepo;
    private RecordingModeration moderation;

    /** Records whether stop/kick were reached, without a real service (or a concrete mock). */
    private static final class RecordingModeration extends ModerationService {
        final AtomicInteger stopCalls = new AtomicInteger();
        final AtomicInteger kickCalls = new AtomicInteger();
        RecordingModeration() { super(null, null, null, null, null); }
        @Override public Mono<Void> stopSession(String modCasdoorId, String sessionId, String reason) {
            stopCalls.incrementAndGet();
            return Mono.empty();
        }
        @Override public Mono<Void> kickPlayer(String modCasdoorId, String sessionId,
                                               String targetCasdoorId, String reason) {
            kickCalls.incrementAndGet();
            return Mono.empty();
        }
    }

    private ModerationController controllerFor(DomainRegistry registry) {
        gameRepo = mock(GameRepository.class);
        sessionRepo = mock(GameSessionRepository.class);
        moderation = new RecordingModeration();
        DomainScopeService scope = new DomainScopeService(gameRepo, sessionRepo);
        return new ModerationController(moderation, mock(ModGameScopeRepository.class), scope, registry);
    }

    private static DomainRegistry multiBrand() {
        return new DomainRegistry("", ISS_X + "=brandx," + ISS_Y + "=brandy");
    }

    /** A session id whose game is {@code gameSlug}, whose game has {@code domain}. */
    private void stubSession(String sessionId, String gameSlug, String domain) {
        GameSession s = new GameSession();
        s.setId(sessionId);
        s.setGameSlug(gameSlug);
        when(sessionRepo.findById(eq(sessionId))).thenReturn(Mono.just(s));
        Game g = new Game();
        g.setDomain(domain);
        when(gameRepo.findBySlug(eq(gameSlug))).thenReturn(Mono.just(g));
    }

    private static Jwt admin(String issuer) {
        return jwt(issuer, "admin-x", List.of("admin"));
    }

    private static Jwt superadmin(String issuer) {
        return jwt(issuer, "root", List.of("admin", "superadmin"));
    }

    private static Jwt jwt(String issuer, String sub, List<String> roles) {
        return Jwt.withTokenValue("t").header("alg", "RS256")
                .issuer(issuer).subject(sub)
                .issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(300))
                .claim("roles", roles).build();
    }

    private static void assertForbidden(org.reactivestreams.Publisher<?> p) {
        StepVerifier.create(p)
                .expectErrorSatisfies(err -> {
                    assertThat(err).isInstanceOf(ResponseStatusException.class);
                    assertThat(((ResponseStatusException) err).getStatusCode().value()).isEqualTo(403);
                })
                .verify();
    }

    // ===================================================================
    // CROSS-USER — a brand-X admin on a brand-X vs brand-Y session
    // ===================================================================
    @Nested
    class CrossUserIsolation {

        @Test
        void stopDeniesForeignBrandSessionBeforeActing() {
            ModerationController c = controllerFor(multiBrand());
            stubSession("s-y", "gy", "brandy");
            assertForbidden(c.stopSession(admin(ISS_X), "s-y", new StopSessionRequest("spam")));
            assertThat(moderation.stopCalls.get()).as("gate fired before the action").isZero();
        }

        @Test
        void stopAllowsOwnBrandSession() {
            ModerationController c = controllerFor(multiBrand());
            stubSession("s-x", "gx", "brandx");
            StepVerifier.create(c.stopSession(admin(ISS_X), "s-x", new StopSessionRequest("spam")))
                    .verifyComplete();
            assertThat(moderation.stopCalls.get()).isEqualTo(1);
        }

        @Test
        void kickDeniesForeignBrandSession() {
            ModerationController c = controllerFor(multiBrand());
            stubSession("s-y", "gy", "brandy");
            assertForbidden(c.kickPlayer(admin(ISS_X), "s-y", new KickPlayerRequest("target", "cheating")));
            assertThat(moderation.kickCalls.get()).isZero();
        }
    }

    // ===================================================================
    // MULTIUSER-SITUATIONAL — SUPERADMIN cross-domain bypass (no session lookup)
    // ===================================================================
    @Nested
    class MultiUserSituational {

        @Test
        void superadminActsOnAnyBrandsSession() {
            ModerationController c = controllerFor(multiBrand());
            // session/ game NOT stubbed — superadmin bypass must not resolve them.
            StepVerifier.create(c.stopSession(superadmin(ISS_X), "s-any", new StopSessionRequest("x")))
                    .verifyComplete();
            assertThat(moderation.stopCalls.get()).isEqualTo(1);
        }
    }

    // ===================================================================
    // SINGLE-TENANT — default registry + 'default' game: gate inert
    // ===================================================================
    @Nested
    class SingleTenant {

        @Test
        void defaultSessionAllowsEveryMod() {
            ModerationController c = controllerFor(new DomainRegistry("", ""));
            stubSession("s", "g", "default");
            StepVerifier.create(c.stopSession(admin(ISS_X), "s", new StopSessionRequest("x")))
                    .verifyComplete();
            assertThat(moderation.stopCalls.get()).isEqualTo(1);
        }
    }
}
