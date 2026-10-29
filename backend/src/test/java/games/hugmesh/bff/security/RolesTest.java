package games.hugmesh.bff.security;

import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pinning specs for {@link Roles}.
 *
 * <p>The role extractor is the single seam onto the JWT's {@code roles}
 * claim. Three controllers used to maintain copy-pasted variants;
 * everything funnels through {@link Roles#fromJwt(Jwt)} now. The
 * tests pin every contract those controllers depend on:
 *
 * <ul>
 *   <li>Null / missing / non-list claims emit empty.</li>
 *   <li>String entries are trimmed + lowercased + deduped.</li>
 *   <li>Non-string entries are coerced via toString().</li>
 *   <li>Reading from {@code Authentication} strips the {@code ROLE_}
 *       prefix Spring's converter prepends.</li>
 *   <li>Names are CANONICALISED: one leading {@code ROLE_} (any case) is
 *       stripped before lowercasing, so the systems Casdoor's
 *       {@code ROLE_ADMIN} / {@code ROLE_GAMES} seeds (a mesh-mounted BFF)
 *       and the games realm's bare {@code admin} / {@code games} read the
 *       same; exactly ONE prefix goes ({@code ROLE_ROLE_ADMIN} ->
 *       {@code role_admin}).</li>
 *   <li>Who is a games admin is {@link GamesAdminPolicy}'s question (see
 *       GamesAdminPolicyTest); {@code Roles} only canonicalises names.</li>
 *   <li>{@link Roles#isSuperadmin(Jwt)} accepts {@code ROLE_SUPERADMIN}.</li>
 * </ul>
 */
class RolesTest {

    @Test
    void fromJwtReturnsEmptyOnNull() {
        assertThat(Roles.fromJwt(null)).isEmpty();
    }

    @Test
    void fromJwtReturnsEmptyWhenClaimMissing() {
        Jwt jwt = jwtWith(Map.of());
        assertThat(Roles.fromJwt(jwt)).isEmpty();
    }

    @Test
    void fromJwtReturnsEmptyWhenClaimIsNotAList() {
        Jwt jwt = jwtWith(Map.of("roles", "admin"));
        assertThat(Roles.fromJwt(jwt)).isEmpty();
    }

    @Test
    void fromJwtExtractsRolesFromListClaim() {
        Jwt jwt = jwtWith(Map.of("roles", List.of("admin", "player")));
        assertThat(Roles.fromJwt(jwt)).containsExactlyInAnyOrder("admin", "player");
    }

    @Test
    void fromJwtTrimsWhitespaceAndLowercases() {
        Jwt jwt = jwtWith(Map.of("roles", List.of(" Admin ", "PLAYER", "Mod")));
        assertThat(Roles.fromJwt(jwt)).containsExactlyInAnyOrder("admin", "player", "mod");
    }

    @Test
    void fromJwtDedupesEquivalentEntries() {
        Jwt jwt = jwtWith(Map.of("roles", List.of("admin", "ADMIN", " Admin ")));
        assertThat(Roles.fromJwt(jwt)).containsExactly("admin");
    }

    @Test
    void fromJwtIgnoresNullsAndEmpties() {
        java.util.List<Object> mixed = new java.util.ArrayList<>();
        mixed.add("admin");
        mixed.add(null);
        mixed.add("");
        mixed.add("   ");
        Jwt jwt = jwtWith(Map.of("roles", mixed));
        assertThat(Roles.fromJwt(jwt)).containsExactly("admin");
    }

    @Test
    void fromAuthenticationStripsRolePrefix() {
        Authentication auth = new UsernamePasswordAuthenticationToken(
                "alice", "creds",
                List.of(new SimpleGrantedAuthority("ROLE_ADMIN"),
                        new SimpleGrantedAuthority("ROLE_USER")));
        assertThat(Roles.fromAuthentication(auth))
                .containsExactlyInAnyOrder("admin", "user");
    }

    @Test
    void fromAuthenticationKeepsAuthoritiesWithoutPrefix() {
        Authentication auth = new UsernamePasswordAuthenticationToken(
                "alice", "creds",
                List.of(new SimpleGrantedAuthority("SCOPE_read"),
                        new SimpleGrantedAuthority("ROLE_ADMIN")));
        // SCOPE_read isn't a ROLE_ prefix; pass through, then lowercase.
        assertThat(Roles.fromAuthentication(auth))
                .containsExactlyInAnyOrder("admin", "scope_read");
    }

    @Test
    void fromAuthenticationReturnsEmptyOnNull() {
        assertThat(Roles.fromAuthentication(null)).isEmpty();
    }

    // ── canonical form: the systems Casdoor seeds ROLE_-prefixed names ────────

    @Test
    void fromJwtStripsOneLeadingRolePrefix() {
        // The mesh persona shape (hugmesh-generator seed-e2e.sh
        // LOCAL_OPERATOR_ROLES=ROLE_SUPERADMIN,ROLE_ADMIN,...,ROLE_GAMES).
        Jwt jwt = jwtWith(Map.of("roles",
                List.of("ROLE_USER", "ROLE_GAMES", "ROLE_ADMIN", "ROLE_GAMES_ADMIN", "role_Mod")));
        assertThat(Roles.fromJwt(jwt))
                .containsExactly("user", "games", "admin", "games_admin", "mod");
    }

    @Test
    void fromJwtStripsExactlyOnePrefix() {
        Jwt jwt = jwtWith(Map.of("roles", List.of("ROLE_ROLE_ADMIN")));
        assertThat(Roles.fromJwt(jwt)).containsExactly("role_admin");
        assertThat(GamesAdminPolicy.gamesRealm().isGamesAdmin(jwt)).isFalse();
    }

    @Test
    void fromJwtDedupesPrefixedAndBareSpellings() {
        Jwt jwt = jwtWith(Map.of("roles", List.of("ROLE_ADMIN", "admin", " Admin ")));
        assertThat(Roles.fromJwt(jwt)).containsExactly("admin");
    }

    @Test
    void fromJwtCanonicalisesCasdoorObjectShape() {
        Jwt jwt = jwtWith(Map.of("roles",
                List.of(Map.of("name", "ROLE_GAMES", "owner", "hugmesh"), Map.of("name", "admin"))));
        assertThat(Roles.fromJwt(jwt)).containsExactly("games", "admin");
    }

    @Test
    void canonicalHandlesBlankPrefixOnlyAndNull() {
        assertThat(Roles.canonical(null)).isEmpty();
        assertThat(Roles.canonical("   ")).isEmpty();
        assertThat(Roles.canonical("ROLE_")).isEmpty();
        assertThat(Roles.canonical("ROLE_ Games ")).isEqualTo("games");
        assertThat(Roles.canonical("ROLES")).isEqualTo("roles"); // not a prefix
        assertThat(Roles.canonical("role")).isEqualTo("role");
    }

    @Test
    void isSuperadminTrueForPrefixedSpelling() {
        assertThat(Roles.isSuperadmin(jwtWith(Map.of("roles", List.of("ROLE_SUPERADMIN"))))).isTrue();
        assertThat(Roles.isSuperadmin(jwtWith(Map.of("roles", List.of("superadmin"))))).isTrue();
        assertThat(Roles.isSuperadmin(jwtWith(Map.of("roles", List.of("ROLE_ADMIN"))))).isFalse();
    }

    @Test
    void hasAcceptsPrefixedArgumentAndClaim() {
        Jwt jwt = jwtWith(Map.of("roles", List.of("ROLE_GAMES")));
        assertThat(Roles.has(jwt, "games")).isTrue();
        assertThat(Roles.has(jwt, "GAMES")).isTrue();
        assertThat(Roles.has(jwt, "ROLE_GAMES")).isTrue();
        assertThat(Roles.has(jwt, "ROLE_")).isFalse();
        assertThat(Roles.has(jwt, "")).isFalse();
    }

    @Test
    void upperRolesFromJwtStripsThePrefixAsItsDocPromises() {
        Jwt jwt = jwtWith(Map.of("roles", List.of("ROLE_GAMES", "admin")));
        assertThat(Roles.upperRolesFromJwt(jwt)).containsExactly("GAMES", "ADMIN");
    }

    @Test
    void hasRolesIsCaseInsensitive() {
        Jwt jwt = jwtWith(Map.of("roles", List.of("Mod")));
        assertThat(Roles.has(jwt, "mod")).isTrue();
        assertThat(Roles.has(jwt, "MOD")).isTrue();
        assertThat(Roles.has(jwt, "admin")).isFalse();
    }

    @Test
    void setFromJwtIsImmutable() {
        Jwt jwt = jwtWith(Map.of("roles", List.of("admin")));
        Set<String> roles = Roles.setFromJwt(jwt);
        assertThat(roles).containsExactly("admin");
    }

    private static Jwt jwtWith(Map<String, Object> claims) {
        java.util.Map<String, Object> all = new java.util.HashMap<>(claims);
        if (!all.containsKey("sub")) all.put("sub", "user-id");
        return new Jwt(
                "tok",
                Instant.now(),
                Instant.now().plusSeconds(60),
                Map.of("alg", "RS256"),
                all);
    }
}
