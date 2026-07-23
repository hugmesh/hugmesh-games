package games.hugmesh.bff.config;

import games.hugmesh.bff.security.DomainRegistry;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Security keystone specs for {@link CasdoorAuthorities} — the games-realm
 * Casdoor JWT -&gt; Spring authorities converter (COPIED VERBATIM logic from
 * hugmesh-gateway). This is the single place that decides which authorities a
 * token carries, so the authority-injection defenses are pinned here.
 *
 * <p>Delineated per the comprehensive-testing standard:
 * <ul>
 *   <li><b>USER-SITUATIONAL</b> — a single principal's authority set per role /
 *       claim shape (ALLOW + the floor + the issuer-derived DOMAIN_).</li>
 *   <li><b>MULTIUSER-SITUATIONAL</b> — two principals from two brand Casdoors
 *       get two distinct DOMAIN_ scopes from the same converter.</li>
 *   <li><b>CROSS-USER (isolation boundary)</b> — the injection regressions: a
 *       brand-X token carrying permissions:["ROLE_SUPERADMIN"] /
 *       permissions:["DOMAIN_other"] is DROPPED, so it can never forge a
 *       super-role or cross-tenant scope to reach another user's brand.</li>
 *   <li><b>CONCURRENT</b> — the converter is stateless; many threads converting
 *       different-brand tokens at once never bleed one brand's DOMAIN_ into
 *       another's authority set.</li>
 * </ul>
 */
class CasdoorAuthoritiesTest {

    private static final String ISS_BRANDX = "https://casdoor.brandx.example";
    private static final String ISS_BRANDY = "https://casdoor.brandy.example";

    /** Multi-brand registry: brandx + brandy are trusted, mapped to distinct domains. */
    private static DomainRegistry multiBrandRegistry() {
        return new DomainRegistry("", ISS_BRANDX + "=brandx," + ISS_BRANDY + "=brandy");
    }

    /** Single-issuer (legacy) registry — no config at all => everyone is DOMAIN_default. */
    private static DomainRegistry singleIssuerRegistry() {
        return new DomainRegistry("", "");
    }

    private static Set<String> authStrings(Jwt jwt, DomainRegistry registry) {
        return CasdoorAuthorities.authorities(jwt, registry).stream()
                .map(GrantedAuthority::getAuthority)
                .collect(Collectors.toSet());
    }

    private static Jwt.Builder baseJwt() {
        return Jwt.withTokenValue("token")
                .header("alg", "RS256")
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(300))
                .subject("user-id");
    }

    // ===================================================================
    // USER-SITUATIONAL — a single principal's authority set
    // ===================================================================
    @Nested
    class UserSituational {

        @Test
        void everyTokenFloorsToRoleUserAndGetsDomainDefaultOnSingleIssuer() {
            Jwt jwt = baseJwt().build();
            assertThat(authStrings(jwt, singleIssuerRegistry()))
                    .containsExactlyInAnyOrder("ROLE_USER", "DOMAIN_default");
        }

        @Test
        void roleClaimStringsBecomeUpperCasedRoleAuthorities() {
            Jwt jwt = baseJwt().claim("roles", List.of("player", "Admin")).build();
            assertThat(authStrings(jwt, singleIssuerRegistry()))
                    .contains("ROLE_USER", "ROLE_PLAYER", "ROLE_ADMIN", "DOMAIN_default");
        }

        @Test
        void roleClaimObjectsWithNameFieldAreHandled() {
            // Casdoor's default token shape: roles is a list of {name:..} objects.
            Jwt jwt = baseJwt()
                    .claim("roles", List.of(Map.of("name", "mod"), Map.of("name", "publisher")))
                    .build();
            assertThat(authStrings(jwt, singleIssuerRegistry()))
                    .contains("ROLE_MOD", "ROLE_PUBLISHER");
        }

        @Test
        void legacyIsAdminBooleanStillGrantsRoleAdmin() {
            Jwt jwt = baseJwt().claim("isAdmin", true).build();
            assertThat(authStrings(jwt, singleIssuerRegistry())).contains("ROLE_ADMIN");
        }

        @Test
        void nonReservedPermissionsPassThroughVerbatim() {
            Jwt jwt = baseJwt().claim("permissions", List.of("upload:games", "moderate:reports")).build();
            assertThat(authStrings(jwt, singleIssuerRegistry()))
                    .contains("upload:games", "moderate:reports");
        }

        @Test
        void roleClaimAlreadyRolePrefixedDoesNotDoublePrefix() {
            // Regression for the bug class hugmesh-gateway#74 fixed: a Casdoor
            // role NAMED "ROLE_ADMIN" (the snapdragon.systems seed convention,
            // as opposed to this realm's own bare "admin" convention) must still
            // canonicalize to the single-prefixed ROLE_ADMIN, not double-prefix
            // to ROLE_ROLE_ADMIN and silently miss every hasRole() check.
            Jwt jwt = baseJwt().claim("roles", List.of("ROLE_ADMIN", "role_mod")).build();
            Set<String> auths = authStrings(jwt, singleIssuerRegistry());
            assertThat(auths).contains("ROLE_ADMIN", "ROLE_MOD");
            assertThat(auths).noneMatch(a -> a.startsWith("ROLE_ROLE_"));
        }

        @Test
        void blankAndNullRoleEntriesAreSkipped() {
            List<Object> roles = new ArrayList<>();
            roles.add("player");
            roles.add("");
            roles.add("   ");
            roles.add(null);
            roles.add(Map.of("name", "")); // blank name object
            Jwt jwt = baseJwt().claim("roles", roles).build();
            assertThat(authStrings(jwt, singleIssuerRegistry()))
                    .containsExactlyInAnyOrder("ROLE_USER", "ROLE_PLAYER", "DOMAIN_default");
        }

        @Test
        void exactlyOneDomainAuthorityIsAlwaysEmitted() {
            Jwt jwt = baseJwt().issuer(ISS_BRANDX).claim("roles", List.of("player")).build();
            long domainCount = CasdoorAuthorities.authorities(jwt, multiBrandRegistry()).stream()
                    .filter(a -> a.getAuthority().startsWith("DOMAIN_"))
                    .count();
            assertThat(domainCount).isEqualTo(1);
        }

        @Test
        void brandXIssuerStampsDomainBrandx() {
            Jwt jwt = baseJwt().issuer(ISS_BRANDX).build();
            assertThat(authStrings(jwt, multiBrandRegistry())).contains("DOMAIN_brandx");
        }
    }

    // ===================================================================
    // MULTIUSER-SITUATIONAL — two principals from two brand Casdoors
    // ===================================================================
    @Nested
    class MultiUserSituational {

        @Test
        void twoBrandTokensGetTwoDistinctDomainScopes() {
            DomainRegistry reg = multiBrandRegistry();
            Jwt brandXUser = baseJwt().issuer(ISS_BRANDX).subject("x-user").build();
            Jwt brandYUser = baseJwt().issuer(ISS_BRANDY).subject("y-user").build();

            assertThat(authStrings(brandXUser, reg)).contains("DOMAIN_brandx").doesNotContain("DOMAIN_brandy");
            assertThat(authStrings(brandYUser, reg)).contains("DOMAIN_brandy").doesNotContain("DOMAIN_brandx");
        }

        @Test
        void unknownIssuerFailsClosedToDomainDefaultNotDomainNull() {
            // An issuer not in the multi-brand registry resolves to null; the
            // converter defensively maps null -> "default" rather than emitting
            // the inert "DOMAIN_null". (Such a token is 401'd upstream by the
            // multi-issuer decoder before reaching here; this is belt-and-braces.)
            Jwt unknown = baseJwt().issuer("https://casdoor.evil.example").build();
            Set<String> auths = authStrings(unknown, multiBrandRegistry());
            assertThat(auths).contains("DOMAIN_default");
            assertThat(auths).noneMatch(a -> a.equals("DOMAIN_null"));
        }
    }

    // ===================================================================
    // CROSS-USER — the authority-injection isolation boundary
    // ===================================================================
    @Nested
    class CrossUserIsolation {

        @Test
        void permissionForgingDomainOtherIsDropped() {
            // INJECTION REGRESSION: a brandx token tries to forge a brandy scope
            // via a permissions entry. It must be DROPPED; only the genuine
            // issuer-derived DOMAIN_brandx survives.
            Jwt forged = baseJwt().issuer(ISS_BRANDX)
                    .claim("permissions", List.of("DOMAIN_other"))
                    .build();
            Set<String> auths = authStrings(forged, multiBrandRegistry());
            assertThat(auths).contains("DOMAIN_brandx");
            assertThat(auths).doesNotContain("DOMAIN_other");
            // exactly one DOMAIN_ authority, and it's the genuine one.
            assertThat(auths.stream().filter(a -> a.startsWith("DOMAIN_")).toList())
                    .containsExactly("DOMAIN_brandx");
        }

        @Test
        void permissionForgingRoleSuperadminIsDropped() {
            // INJECTION REGRESSION: a token tries to forge the cross-domain infra
            // super-role via a permissions entry. It must be DROPPED — ROLE_*
            // authorities come ONLY from the roles claim / legacy isAdmin.
            Jwt forged = baseJwt().issuer(ISS_BRANDX)
                    .claim("permissions", List.of("ROLE_SUPERADMIN"))
                    .build();
            assertThat(authStrings(forged, multiBrandRegistry())).doesNotContain("ROLE_SUPERADMIN");
        }

        @Test
        void reservedPrefixGuardIsCaseInsensitiveAndTrims() {
            // Defense must not be bypassable with casing / whitespace.
            Jwt forged = baseJwt().issuer(ISS_BRANDX)
                    .claim("permissions", List.of("  role_admin ", "domain_brandy", "RoLe_SuperAdmin"))
                    .build();
            Set<String> auths = authStrings(forged, multiBrandRegistry());
            assertThat(auths).noneMatch(a ->
                    a.toUpperCase().startsWith("ROLE_") && !a.equals("ROLE_USER")
                    || a.toUpperCase().startsWith("DOMAIN_") && !a.equals("DOMAIN_brandx"));
        }

        @Test
        void roleClaimNamedDomainBecomesInertRolePrefixedAuthorityNotTenantScope() {
            // A roles entry "DOMAIN_x" gains the ROLE_ prefix first => ROLE_DOMAIN_X,
            // which is an inert non-tenant authority, NOT a DOMAIN_ scope.
            Jwt jwt = baseJwt().issuer(ISS_BRANDX).claim("roles", List.of("DOMAIN_brandy")).build();
            Set<String> auths = authStrings(jwt, multiBrandRegistry());
            assertThat(auths).contains("ROLE_DOMAIN_BRANDY");
            assertThat(auths).doesNotContain("DOMAIN_brandy");
            assertThat(auths.stream().filter(a -> a.startsWith("DOMAIN_")).toList())
                    .containsExactly("DOMAIN_brandx");
        }

        @Test
        void combinedInjectionAttemptYieldsExactlyTheGenuineAuthorities() {
            // Kitchen-sink: forge both a super-role and a cross-tenant scope while
            // legitimately being a brandx player. Only the genuine authorities survive.
            Jwt forged = baseJwt().issuer(ISS_BRANDX)
                    .claim("roles", List.of("player"))
                    .claim("permissions", List.of("ROLE_SUPERADMIN", "DOMAIN_brandy", "upload:games"))
                    .build();
            assertThat(authStrings(forged, multiBrandRegistry()))
                    .containsExactlyInAnyOrder(
                            "ROLE_USER", "ROLE_PLAYER", "upload:games", "DOMAIN_brandx");
        }
    }

    // ===================================================================
    // CONCURRENT — stateless converter under contention
    // ===================================================================
    @Nested
    class Concurrent {

        @Test
        void concurrentConversionsOfDifferentBrandTokensNeverBleedScopes() throws Exception {
            DomainRegistry reg = multiBrandRegistry();
            int threads = 16;
            int iterations = 500;
            ExecutorService pool = Executors.newFixedThreadPool(threads);
            CountDownLatch start = new CountDownLatch(1);
            ConcurrentHashMap<String, Boolean> failures = new ConcurrentHashMap<>();

            for (int t = 0; t < threads; t++) {
                final boolean evenThread = (t % 2 == 0);
                pool.submit(() -> {
                    try {
                        start.await();
                        for (int i = 0; i < iterations; i++) {
                            String iss = evenThread ? ISS_BRANDX : ISS_BRANDY;
                            String expected = evenThread ? "DOMAIN_brandx" : "DOMAIN_brandy";
                            String forbidden = evenThread ? "DOMAIN_brandy" : "DOMAIN_brandx";
                            Jwt jwt = baseJwt().issuer(iss)
                                    .subject("u" + i)
                                    .claim("permissions", List.of("DOMAIN_" + (evenThread ? "brandy" : "brandx")))
                                    .build();
                            Set<String> auths = authStrings(jwt, reg);
                            if (!auths.contains(expected) || auths.contains(forbidden)
                                    || auths.stream().filter(a -> a.startsWith("DOMAIN_")).count() != 1) {
                                failures.put(Thread.currentThread().getName() + "#" + i, true);
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
            assertThat(failures).as("no thread saw a bled / forged / duplicate DOMAIN_ scope").isEmpty();
        }
    }
}
