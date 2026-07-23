package games.hugmesh.bff.config;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.ReactiveSecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Specs for {@link DomainGuardFilter} — the BFF-side, per-instance DOMAIN hard-deny
 * that mirrors the hugmesh-gateway domain check as compose-network defence-in-depth.
 *
 * <p>The filter is the first thing in the BFF that READS the {@code DOMAIN_<x>}
 * authority the {@code domain-foundation} converter stamps. These specs pin the four
 * outcomes — pass (match / superadmin / anonymous), deny (foreign brand / legacy
 * default at a branded instance / fail-closed null) — plus the load-bearing
 * single-tenant no-op.
 *
 * <p>Delineated along the project's test taxonomy:
 * <ul>
 *   <li><b>USER-SITUATIONAL</b> — one principal at a configured instance: match,
 *       mismatch, legacy-default, anonymous.</li>
 *   <li><b>MULTIUSER-SITUATIONAL</b> — superadmin's cross-domain bypass alongside an
 *       ordinary principal.</li>
 *   <li><b>CROSS-USER (isolation, ALLOW+DENY)</b> — at ONE instance, the in-brand
 *       token is served AND the foreign-brand token is refused, in the same test.</li>
 *   <li><b>FAIL-CLOSED</b> — zero / &gt;1 DOMAIN_ authorities (the injection
 *       signature) deny.</li>
 *   <li><b>CONCURRENT</b> — the stateless filter routes mixed-brand traffic with no
 *       cross-talk under load.</li>
 * </ul>
 */
class DomainGuardFilterTest {

    // ------------------------------------------------------------------ helpers

    /** Records whether the downstream chain ran (i.e. the request was ALLOWED). */
    private static final class RecordingChain implements WebFilterChain {
        final AtomicBoolean called = new AtomicBoolean(false);
        @Override
        public Mono<Void> filter(ServerWebExchange exchange) {
            called.set(true);
            return Mono.empty();
        }
    }

    /** Outcome of running the filter once. */
    private record Outcome(boolean allowed, HttpStatus status, String deniedHeader) {}

    private static Jwt jwt(String subject) {
        return Jwt.withTokenValue("token")
                .header("alg", "RS256")
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(300))
                .subject(subject)
                .claim("sub", subject)
                .build();
    }

    /** Authenticated JWT principal carrying the given Spring authorities. */
    private static Authentication auth(String subject, GrantedAuthority... authorities) {
        return new JwtAuthenticationToken(jwt(subject), List.of(authorities));
    }

    private static GrantedAuthority domain(String d) { return new SimpleGrantedAuthority("DOMAIN_" + d); }
    private static final GrantedAuthority ROLE_USER       = new SimpleGrantedAuthority("ROLE_USER");
    private static final GrantedAuthority ROLE_SUPERADMIN = new SimpleGrantedAuthority("ROLE_SUPERADMIN");

    /** Run the filter for an authenticated principal (seeds the reactive context). */
    private static Outcome run(String instanceDomain, Authentication principal) {
        DomainGuardFilter filter = new DomainGuardFilter(instanceDomain);
        RecordingChain chain = new RecordingChain();
        MockServerWebExchange exchange =
                MockServerWebExchange.from(MockServerHttpRequest.get("/api/games").build());
        Mono<Void> result = filter.filter(exchange, chain);
        if (principal != null) {
            result = result.contextWrite(ReactiveSecurityContextHolder.withAuthentication(principal));
        }
        result.block();
        return new Outcome(
                chain.called.get(),
                exchange.getResponse().getStatusCode() == null
                        ? null
                        : HttpStatus.valueOf(exchange.getResponse().getStatusCode().value()),
                exchange.getResponse().getHeaders().getFirst("X-Domain-Denied"));
    }

    /** Run with NO security context — the anonymous / public-endpoint path. */
    private static Outcome runAnonymous(String instanceDomain) {
        return run(instanceDomain, null);
    }

    // ===================================================================
    // DISABLED — single-tenant default: a complete no-op
    // ===================================================================
    @Nested
    class Disabled {

        @Test
        void unsetInstanceDomainPassesEveryTokenThroughIncludingForeignBrand() {
            // The default deployment shape. Even a "foreign" DOMAIN_ is irrelevant —
            // the guard never engages, so the request path is unchanged.
            for (String unset : List.of("", "   ")) {
                assertThat(run(unset, auth("u", ROLE_USER, domain("brandy"))).allowed()).isTrue();
                assertThat(runAnonymous(unset).allowed()).isTrue();
            }
        }

        @Test
        void unsetInstanceDomainNeverWritesADenyStatus() {
            Outcome o = run("", auth("u", ROLE_USER, domain("brandy")));
            assertThat(o.status()).isNull();           // chain ran; nothing set the response
            assertThat(o.deniedHeader()).isNull();
        }
    }

    // ===================================================================
    // USER-SITUATIONAL — one principal at a configured brandx instance
    // ===================================================================
    @Nested
    class UserSituational {

        @Test
        void inBrandTokenIsAllowed() {
            assertThat(run("brandx", auth("u", ROLE_USER, domain("brandx"))).allowed()).isTrue();
        }

        @Test
        void foreignBrandTokenIs403WithHeader() {
            Outcome o = run("brandx", auth("u", ROLE_USER, domain("brandy")));
            assertThat(o.allowed()).isFalse();
            assertThat(o.status()).isEqualTo(HttpStatus.FORBIDDEN);
            assertThat(o.deniedHeader()).isEqualTo("brandx");
        }

        @Test
        void legacyDefaultTokenIsDeniedAtABrandedInstance() {
            // A token from the legacy single-issuer Casdoor (DOMAIN_default) presented
            // to an instance that has opted into a real brand must not be served.
            Outcome o = run("brandx", auth("u", ROLE_USER, domain("default")));
            assertThat(o.allowed()).isFalse();
            assertThat(o.status()).isEqualTo(HttpStatus.FORBIDDEN);
        }

        @Test
        void anonymousRequestIsAllowedSoPublicEndpointsStayPublic() {
            Outcome o = runAnonymous("brandx");
            assertThat(o.allowed()).isTrue();
            assertThat(o.status()).isNull();
        }

        @Test
        void instanceDomainConfigIsSanitizedToMatchStampedAuthority() {
            // APP_TENANCY_DOMAIN="BrandX " must still match the converter's lowercase
            // DOMAIN_brandx stamp.
            assertThat(run("BrandX ", auth("u", ROLE_USER, domain("brandx"))).allowed()).isTrue();
        }
    }

    // ===================================================================
    // MULTIUSER-SITUATIONAL — superadmin cross-domain bypass
    // ===================================================================
    @Nested
    class MultiUserSituational {

        @Test
        void superadminOfAnotherBrandStillReachesThisInstance() {
            // SUPERADMIN is the infra control plane — the ONLY cross-domain bypass.
            Outcome o = run("brandx", auth("root", ROLE_USER, ROLE_SUPERADMIN, domain("brandy")));
            assertThat(o.allowed()).isTrue();
        }

        @Test
        void superadminBypassesEvenWithNoDomainAuthority() {
            // The bypass is checked BEFORE the (fail-closed) domain read, so a
            // superadmin token missing a DOMAIN_ still passes.
            assertThat(run("brandx", auth("root", ROLE_USER, ROLE_SUPERADMIN)).allowed()).isTrue();
        }

        @Test
        void ordinaryPrincipalAlongsideSuperadminStillScoped() {
            // Same instance: the superadmin is served, the foreign ordinary user is not.
            assertThat(run("brandx", auth("root", ROLE_SUPERADMIN, domain("brandy"))).allowed()).isTrue();
            assertThat(run("brandx", auth("u", ROLE_USER, domain("brandy"))).allowed()).isFalse();
        }
    }

    // ===================================================================
    // CROSS-USER — isolation contract (ALLOW + DENY together)
    // ===================================================================
    @Nested
    class CrossUserIsolation {

        @Test
        void oneInstanceServesItsBrandAndRefusesTheOther() {
            String instance = "brandx";
            Outcome inBrand  = run(instance, auth("alice", ROLE_USER, domain("brandx")));
            Outcome foreign  = run(instance, auth("mallory", ROLE_USER, domain("brandy")));

            assertThat(inBrand.allowed()).isTrue();
            assertThat(foreign.allowed()).isFalse();
            assertThat(foreign.status()).isEqualTo(HttpStatus.FORBIDDEN);
        }

        @Test
        void defaultInstanceMatchesDefaultPrincipalButRefusesABrand() {
            // Explicitly naming the instance "default" is single-tenant-by-name: the
            // legacy default principal matches, a branded token does not.
            assertThat(run("default", auth("u", ROLE_USER, domain("default"))).allowed()).isTrue();
            assertThat(run("default", auth("u", ROLE_USER, domain("brandx"))).allowed()).isFalse();
        }

        @Test
        void domainMatchesContractIsPinnedDirectly() {
            assertThat(DomainGuardFilter.domainMatches("brandx", "brandx")).isTrue();
            assertThat(DomainGuardFilter.domainMatches("brandy", "brandx")).isFalse();
            assertThat(DomainGuardFilter.domainMatches("default", "default")).isTrue();
            assertThat(DomainGuardFilter.domainMatches("default", "brandx")).isFalse();
            assertThat(DomainGuardFilter.domainMatches(null, "brandx")).isFalse();   // fail-closed
            assertThat(DomainGuardFilter.domainMatches(null, "default")).isFalse();  // fail-closed
        }
    }

    // ===================================================================
    // FAIL-CLOSED — anomalous DOMAIN_ authority sets deny
    // ===================================================================
    @Nested
    class FailClosed {

        @Test
        void zeroDomainAuthoritiesIsDenied() {
            // An authenticated token with no DOMAIN_ at all (pre-converter / hand-built)
            // can't prove a tenant — Roles.domainOf returns null, the guard denies.
            Outcome o = run("brandx", auth("u", ROLE_USER));
            assertThat(o.allowed()).isFalse();
            assertThat(o.status()).isEqualTo(HttpStatus.FORBIDDEN);
        }

        @Test
        void moreThanOneDomainAuthorityIsAnInjectionSignatureAndDenied() {
            // Two DOMAIN_ authorities = a forged permissions:["DOMAIN_brandx"] alongside
            // the genuine issuer DOMAIN_brandy. Roles.domainOf fails closed to null even
            // when one of them happens to be this instance's brand — never pick a match.
            Outcome o = run("brandx", auth("mallory", ROLE_USER, domain("brandx"), domain("brandy")));
            assertThat(o.allowed()).isFalse();
            assertThat(o.status()).isEqualTo(HttpStatus.FORBIDDEN);
        }
    }

    // ===================================================================
    // CONCURRENT — stateless routing, no cross-talk
    // ===================================================================
    @Nested
    class Concurrent {

        @Test
        void mixedBrandTrafficIsRoutedWithoutCrossTalk() throws Exception {
            int threads = 16;
            int iterations = 500;
            ExecutorService pool = Executors.newFixedThreadPool(threads);
            CountDownLatch start = new CountDownLatch(1);
            AtomicInteger wrong = new AtomicInteger();

            for (int t = 0; t < threads; t++) {
                final boolean inBrand = (t % 2 == 0);
                pool.submit(() -> {
                    try {
                        start.await();
                        for (int i = 0; i < iterations; i++) {
                            String brand = inBrand ? "brandx" : "brandy";
                            boolean allowed = run("brandx", auth("u", ROLE_USER, domain(brand))).allowed();
                            if (allowed != inBrand) {
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
}
