package games.snapdragon.bff.service;

import games.snapdragon.bff.model.Game;
import games.snapdragon.bff.repository.GameRepository;
import games.snapdragon.bff.repository.GameSessionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Specs for {@link DomainScopeService#requireGameDomain} — the per-game brand gate
 * (the catalog-data analog of {@code requireScope}/{@code requireOwns}). It 403s a
 * brand-X caller reaching for a brand-Y game; SUPERADMIN and unscoped ('default')
 * games pass; an unknown slug passes (no domain to enforce — the downstream 404
 * surfaces instead).
 *
 * <p>Taxonomy (matching {@code DomainGuardFilterTest} / {@code RolesDomainScopingTest}):
 * <ul>
 *   <li><b>SingleTenant</b> — every game is {@code domain='default'} ⇒ the gate is a
 *       no-op, the load-bearing back-compat case.</li>
 *   <li><b>UserSituational</b> — one caller vs match / mismatch / unknown-slug.</li>
 *   <li><b>MultiUserSituational</b> — SUPERADMIN's cross-domain bypass.</li>
 *   <li><b>CrossUserIsolation (ALLOW+DENY)</b> — one game serves its brand and refuses
 *       the other, in one test.</li>
 *   <li><b>FailClosed</b> — a null caller domain (unprovable tenant) denies a scoped
 *       game.</li>
 *   <li><b>Concurrent</b> — the stateless gate routes mixed-brand callers with no
 *       cross-talk.</li>
 * </ul>
 */
class DomainScopeServiceTest {

    private GameRepository games;
    private GameSessionRepository sessions;
    private DomainScopeService service;

    @BeforeEach
    void setup() {
        games = mock(GameRepository.class);
        sessions = mock(GameSessionRepository.class);
        service = new DomainScopeService(games, sessions);
    }

    private Game game(String slug, String domain) {
        Game g = new Game();
        g.setDomain(domain);
        // id/slug not needed by the gate, but set slug for realism.
        try {
            var f = Game.class.getDeclaredField("slug");
            f.setAccessible(true);
            f.set(g, slug);
        } catch (ReflectiveOperationException ignored) { /* slug is cosmetic here */ }
        return g;
    }

    private void stub(String slug, String domain) {
        when(games.findBySlug(eq(slug))).thenReturn(Mono.just(game(slug, domain)));
    }

    /** ALLOW = the gate completes empty. */
    private void assertAllowed(Mono<Void> gate) {
        StepVerifier.create(gate).verifyComplete();
    }

    /** DENY = 403 ResponseStatusException. */
    private void assertForbidden(Mono<Void> gate) {
        StepVerifier.create(gate)
                .expectErrorSatisfies(err -> {
                    assertThat(err).isInstanceOf(ResponseStatusException.class);
                    assertThat(((ResponseStatusException) err).getStatusCode().value()).isEqualTo(403);
                })
                .verify();
    }

    // ===================================================================
    // SINGLE-TENANT — every game is 'default' (global): gate is inert
    // ===================================================================
    @Nested
    class SingleTenant {

        @Test
        void defaultDomainGameIsManageableByAnyCaller() {
            stub("three-wizards", "default");
            // a 'default'-issuer caller, a branded caller, and a null (anonymous-ish)
            // caller all pass against an unscoped game.
            assertAllowed(service.requireGameDomain("three-wizards", "default", false));
            assertAllowed(service.requireGameDomain("three-wizards", "brandx", false));
            assertAllowed(service.requireGameDomain("three-wizards", null, false));
        }

        @Test
        void nullOrBlankRowDomainIsAlsoTreatedAsGlobal() {
            stub("legacy-a", null);
            stub("legacy-b", "   ");
            assertAllowed(service.requireGameDomain("legacy-a", "brandx", false));
            assertAllowed(service.requireGameDomain("legacy-b", "brandx", false));
        }
    }

    // ===================================================================
    // USER-SITUATIONAL — one caller at a branded game
    // ===================================================================
    @Nested
    class UserSituational {

        @Test
        void inBrandCallerIsAllowed() {
            stub("brandx-game", "brandx");
            assertAllowed(service.requireGameDomain("brandx-game", "brandx", false));
        }

        @Test
        void foreignBrandCallerIs403() {
            stub("brandx-game", "brandx");
            assertForbidden(service.requireGameDomain("brandx-game", "brandy", false));
        }

        @Test
        void unknownSlugPassesSoTheDownstream404Surfaces() {
            when(games.findBySlug(eq("ghost"))).thenReturn(Mono.empty());
            // No game => no domain to enforce => gate completes empty (the handler's
            // own findBySlug raises the 404, not this gate).
            assertAllowed(service.requireGameDomain("ghost", "brandy", false));
        }

        @Test
        void brandMatchIsCaseInsensitiveAndTrimmed() {
            stub("brandx-game", "BrandX");
            assertAllowed(service.requireGameDomain("brandx-game", " brandx ", false));
        }
    }

    // ===================================================================
    // MULTIUSER-SITUATIONAL — SUPERADMIN cross-domain bypass
    // ===================================================================
    @Nested
    class MultiUserSituational {

        @Test
        void superadminReachesAnyBrandsGameWithoutLookup() {
            // superadmin short-circuits BEFORE the repo lookup — never stubbed, so a
            // stray query would NPE. Proves the bypass needs no game row.
            assertAllowed(service.requireGameDomain("brandy-game", "brandx", true));
        }

        @Test
        void ordinaryCallerAlongsideSuperadminStillScoped() {
            stub("brandy-game", "brandy");
            assertAllowed(service.requireGameDomain("brandy-game", "brandx", true));   // superadmin
            assertForbidden(service.requireGameDomain("brandy-game", "brandx", false)); // ordinary
        }
    }

    // ===================================================================
    // CROSS-USER — isolation contract (ALLOW + DENY together)
    // ===================================================================
    @Nested
    class CrossUserIsolation {

        @Test
        void oneGameServesItsBrandAndRefusesTheOther() {
            stub("brandx-game", "brandx");
            assertAllowed(service.requireGameDomain("brandx-game", "brandx", false));   // owner brand
            assertForbidden(service.requireGameDomain("brandx-game", "brandy", false));  // foreign brand
        }
    }

    // ===================================================================
    // FAIL-CLOSED — unprovable caller domain denies a scoped game
    // ===================================================================
    @Nested
    class FailClosed {

        @Test
        void nullCallerDomainIsDeniedOnAScopedGame() {
            // Roles.domainOf returns null for a token with 0 or >1 DOMAIN_ authorities
            // (the injection signature). Against a scoped game that must deny.
            stub("brandx-game", "brandx");
            assertForbidden(service.requireGameDomain("brandx-game", null, false));
        }

        @Test
        void nullCallerStillPassesAnUnscopedGame() {
            // A null caller against a 'default' game is allowed — the row is global, so
            // there is nothing to fail closed against.
            stub("open-game", "default");
            assertAllowed(service.requireGameDomain("open-game", null, false));
        }
    }

    // ===================================================================
    // CONCURRENT — stateless gate, no cross-talk
    // ===================================================================
    @Nested
    class Concurrent {

        @Test
        void mixedBrandCallersRoutedWithoutCrossTalk() throws Exception {
            // One branded game; half the threads are in-brand (allow), half foreign (deny).
            lenient().when(games.findBySlug(eq("brandx-game")))
                    .thenReturn(Mono.just(game("brandx-game", "brandx")));

            int threads = 16;
            int iterations = 400;
            ExecutorService pool = Executors.newFixedThreadPool(threads);
            CountDownLatch start = new CountDownLatch(1);
            AtomicInteger wrong = new AtomicInteger();

            for (int t = 0; t < threads; t++) {
                final boolean inBrand = (t % 2 == 0);
                pool.submit(() -> {
                    try {
                        start.await();
                        for (int i = 0; i < iterations; i++) {
                            String caller = inBrand ? "brandx" : "brandy";
                            Boolean denied = service.requireGameDomain("brandx-game", caller, false)
                                    .thenReturn(Boolean.FALSE)
                                    .onErrorReturn(Boolean.TRUE)
                                    .block();
                            // in-brand must NOT be denied; foreign MUST be denied.
                            if (Boolean.TRUE.equals(denied) == inBrand) {
                                wrong.incrementAndGet();
                            }
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                });
            }
            start.countDown();
            pool.shutdown();
            assertThat(pool.awaitTermination(30, TimeUnit.SECONDS)).isTrue();
            assertThat(wrong.get()).isZero();
        }
    }

    // ===================================================================
    // requireSessionDomain — resolves sessionId → game_slug → game.domain
    // ===================================================================
    @Nested
    class SessionDomain {

        private void stubSession(String sessionId, String gameSlug) {
            games.snapdragon.bff.model.GameSession s = new games.snapdragon.bff.model.GameSession();
            s.setId(sessionId);
            s.setGameSlug(gameSlug);
            when(sessions.findById(eq(sessionId))).thenReturn(Mono.just(s));
        }

        @Test
        void inBrandSessionIsAllowedForeignIsDenied() {
            stubSession("s-1", "brandx-game");
            stub("brandx-game", "brandx");
            assertAllowed(service.requireSessionDomain("s-1", "brandx", false));     // owner brand
            assertForbidden(service.requireSessionDomain("s-1", "brandy", false));    // foreign brand
        }

        @Test
        void superadminBypassesWithoutAnySessionLookup() {
            // sessions.findById is NEVER stubbed — a stray lookup would NPE on the mock,
            // proving the bypass short-circuits before resolving the session.
            assertAllowed(service.requireSessionDomain("s-any", "brandx", true));
        }

        @Test
        void unknownSessionPassesSoTheHandler404Surfaces() {
            when(sessions.findById(eq("ghost"))).thenReturn(Mono.empty());
            assertAllowed(service.requireSessionDomain("ghost", "brandy", false));
        }

        @Test
        void nullCallerOnAScopedSessionFailsClosed() {
            stubSession("s-1", "brandx-game");
            stub("brandx-game", "brandx");
            assertForbidden(service.requireSessionDomain("s-1", null, false));
        }

        @Test
        void defaultSessionGameIsGlobal() {
            stubSession("s-2", "open-game");
            stub("open-game", "default");
            assertAllowed(service.requireSessionDomain("s-2", "brandx", false));
        }
    }
}
