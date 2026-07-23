package games.hugmesh.bff.security;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Specs for the domain-scoping helpers added to {@link Roles} (the gateway-
 * aligned {@code isSuperadmin} / {@code isImageBuilder} / fail-closed
 * {@code domainOf}). The pre-existing lowercase {@code fromJwt}/{@code isAdmin}
 * helpers stay pinned in {@code RolesTest}; this file pins the NEW seam.
 *
 * <p>Delineated:
 * <ul>
 *   <li><b>USER-SITUATIONAL</b> — a single principal's super/image-builder
 *       capability + its issuer-derived domain.</li>
 *   <li><b>MULTIUSER-SITUATIONAL</b> — two principals resolve to two domains
 *       via the same registry / authority read-back.</li>
 *   <li><b>CROSS-USER (isolation)</b> — fail-closed {@code domainOf}: ZERO or
 *       &gt;1 DOMAIN_ authorities (an injection signature) yields {@code null}
 *       (deny), never a spoofable first-pick.</li>
 *   <li><b>CONCURRENT</b> — the static helpers are thread-safe under load.</li>
 * </ul>
 */
class RolesDomainScopingTest {

    private static final String ISS_X = "https://casdoor.brandx.example";
    private static final String ISS_Y = "https://casdoor.brandy.example";

    private static Jwt jwt(String issuer, Object roles) {
        Jwt.Builder b = Jwt.withTokenValue("token")
                .header("alg", "RS256")
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(300))
                .subject("user-id");
        if (issuer != null) b.issuer(issuer);
        if (roles != null) b.claim("roles", roles);
        return b.build();
    }

    private static Authentication authWith(GrantedAuthority... authorities) {
        return new UsernamePasswordAuthenticationToken("p", "c", List.of(authorities));
    }

    // ===================================================================
    // USER-SITUATIONAL
    // ===================================================================
    @Nested
    class UserSituational {

        @Test
        void isSuperadminMatchesAllSpellingsFromJwt() {
            assertThat(Roles.isSuperadmin(jwt(null, List.of("superadmin")))).isTrue();
            assertThat(Roles.isSuperadmin(jwt(null, List.of("SUPER-ADMIN")))).isTrue();
            assertThat(Roles.isSuperadmin(jwt(null, List.of("Super_Admin")))).isTrue();
            assertThat(Roles.isSuperadmin(jwt(null, List.of("player")))).isFalse();
            assertThat(Roles.isSuperadmin((Jwt) null)).isFalse();
        }

        @Test
        void isSuperadminReadsRoleSuperadminAuthorityOffAuthentication() {
            assertThat(Roles.isSuperadmin(authWith(new SimpleGrantedAuthority("ROLE_SUPERADMIN")))).isTrue();
            assertThat(Roles.isSuperadmin(authWith(new SimpleGrantedAuthority("ROLE_ADMIN")))).isFalse();
            assertThat(Roles.isSuperadmin((Authentication) null)).isFalse();
        }

        @Test
        void isImageBuilderMatchesBothSeparators() {
            assertThat(Roles.isImageBuilder(jwt(null, List.of("image-builder")))).isTrue();
            assertThat(Roles.isImageBuilder(jwt(null, List.of("IMAGE_BUILDER")))).isTrue();
            assertThat(Roles.isImageBuilder(jwt(null, List.of("admin")))).isFalse();
        }

        @Test
        void domainOfJwtUsesIssuerThroughRegistry() {
            DomainRegistry reg = new DomainRegistry("", ISS_X + "=brandx");
            assertThat(Roles.domainOf(jwt(ISS_X, null), reg)).isEqualTo("brandx");
        }

        @Test
        void domainOfJwtFallsBackToDefaultWhenJwtOrRegistryNull() {
            DomainRegistry reg = new DomainRegistry("", "");
            assertThat(Roles.domainOf(null, reg)).isEqualTo("default");
            assertThat(Roles.domainOf(jwt(ISS_X, null), null)).isEqualTo("default");
        }

        @Test
        void upperRolesFromJwtReturnsUpperCasedNames() {
            Jwt jwt = jwt(null, List.of("player", Map.of("name", "mod")));
            assertThat(Roles.upperRolesFromJwt(jwt)).containsExactlyInAnyOrder("PLAYER", "MOD");
            assertThat(Roles.upperRolesFromJwt(null)).isEmpty();
        }
    }

    // ===================================================================
    // MULTIUSER-SITUATIONAL
    // ===================================================================
    @Nested
    class MultiUserSituational {

        @Test
        void twoPrincipalsResolveToTwoDomainsViaRegistry() {
            DomainRegistry reg = new DomainRegistry("", ISS_X + "=brandx," + ISS_Y + "=brandy");
            assertThat(Roles.domainOf(jwt(ISS_X, null), reg)).isEqualTo("brandx");
            assertThat(Roles.domainOf(jwt(ISS_Y, null), reg)).isEqualTo("brandy");
        }

        @Test
        void domainReadBackOffDistinctAuthoritySets() {
            assertThat(Roles.domainOf(authWith(
                    new SimpleGrantedAuthority("ROLE_USER"),
                    new SimpleGrantedAuthority("DOMAIN_brandx")))).isEqualTo("brandx");
            assertThat(Roles.domainOf(authWith(
                    new SimpleGrantedAuthority("ROLE_USER"),
                    new SimpleGrantedAuthority("DOMAIN_brandy")))).isEqualTo("brandy");
        }
    }

    // ===================================================================
    // CROSS-USER — fail-closed domainOf(authorities)
    // ===================================================================
    @Nested
    class CrossUserFailClosed {

        @Test
        void singleDomainAuthorityIsReadBack() {
            assertThat(Roles.domainOf(List.of(
                    new SimpleGrantedAuthority("ROLE_USER"),
                    new SimpleGrantedAuthority("DOMAIN_brandx")))).isEqualTo("brandx");
        }

        @Test
        void zeroDomainAuthoritiesFailsClosedToNull() {
            // A hand-built / pre-converter token without any DOMAIN_ — can't prove
            // a tenant, so deny.
            assertThat(Roles.domainOf(List.of(new SimpleGrantedAuthority("ROLE_USER")))).isNull();
            assertThat(Roles.domainOf((java.util.Collection<GrantedAuthority>) null)).isNull();
            assertThat(Roles.domainOf((Authentication) null)).isNull();
        }

        @Test
        void moreThanOneDomainAuthorityIsAnInjectionSignatureAndFailsClosed() {
            // The converter stamps EXACTLY ONE DOMAIN_. Two is the signature of a
            // forged permissions:["DOMAIN_other"] alongside the genuine issuer
            // DOMAIN_. Never pick the first — fail closed (null => deny).
            assertThat(Roles.domainOf(List.of(
                    new SimpleGrantedAuthority("DOMAIN_brandx"),
                    new SimpleGrantedAuthority("DOMAIN_brandy")))).isNull();
        }

        @Test
        void failClosedThroughAuthenticationOverloadToo() {
            assertThat(Roles.domainOf(authWith(
                    new SimpleGrantedAuthority("DOMAIN_brandx"),
                    new SimpleGrantedAuthority("DOMAIN_other")))).isNull();
        }
    }

    // ===================================================================
    // CONCURRENT
    // ===================================================================
    @Nested
    class Concurrent {

        @Test
        void staticHelpersAreThreadSafeUnderLoad() throws Exception {
            DomainRegistry reg = new DomainRegistry("", ISS_X + "=brandx," + ISS_Y + "=brandy");
            int threads = 16;
            int iterations = 1000;
            ExecutorService pool = Executors.newFixedThreadPool(threads);
            CountDownLatch start = new CountDownLatch(1);
            AtomicInteger mismatches = new AtomicInteger();

            for (int t = 0; t < threads; t++) {
                final boolean even = (t % 2 == 0);
                pool.submit(() -> {
                    try {
                        start.await();
                        for (int i = 0; i < iterations; i++) {
                            String iss = even ? ISS_X : ISS_Y;
                            String expected = even ? "brandx" : "brandy";
                            if (!expected.equals(Roles.domainOf(jwt(iss, List.of("player")), reg))) {
                                mismatches.incrementAndGet();
                            }
                            // fail-closed read-back stays deterministic under contention.
                            if (Roles.domainOf(List.of(
                                    new SimpleGrantedAuthority("DOMAIN_brandx"),
                                    new SimpleGrantedAuthority("DOMAIN_brandy"))) != null) {
                                mismatches.incrementAndGet();
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
            assertThat(mismatches.get()).isZero();
        }
    }

    // ===================================================================
    // domainApplies — row-level brand predicate (mirrors gateway domainApplies)
    // ===================================================================
    @Nested
    class DomainApplies {

        @Test
        void superadminAlwaysApplies() {
            // even a scoped row vs a mismatched caller -> true when superadmin.
            assertThat(Roles.domainApplies("brandx", "brandy", true)).isTrue();
            assertThat(Roles.domainApplies("brandx", null, true)).isTrue();
        }

        @Test
        void unscopedRowAppliesToAnyCaller() {
            for (String unscoped : new String[]{null, "", "   ", "default", "DEFAULT", " Default "}) {
                assertThat(Roles.domainApplies(unscoped, "brandx", false)).isTrue();
                assertThat(Roles.domainApplies(unscoped, null, false)).isTrue();
            }
        }

        @Test
        void scopedRowRequiresExactCaseInsensitiveTrimmedMatch() {
            assertThat(Roles.domainApplies("brandx", "brandx", false)).isTrue();
            assertThat(Roles.domainApplies("BrandX", " brandx ", false)).isTrue();
            assertThat(Roles.domainApplies("brandx", "brandy", false)).isFalse();
        }

        @Test
        void scopedRowAgainstNullCallerFailsClosed() {
            assertThat(Roles.domainApplies("brandx", null, false)).isFalse();
        }
    }
}
