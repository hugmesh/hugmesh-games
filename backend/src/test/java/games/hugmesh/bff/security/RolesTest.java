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
 *   <li>{@link Roles#isAdmin(Jwt)} is true exactly when the
 *       {@code roles} claim contains {@code admin} (any case).</li>
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

    @Test
    void isAdminTrueWhenRolesContainsAdmin() {
        assertThat(Roles.isAdmin(jwtWith(Map.of("roles", List.of("admin"))))).isTrue();
        assertThat(Roles.isAdmin(jwtWith(Map.of("roles", List.of("ADMIN"))))).isTrue();
        assertThat(Roles.isAdmin(jwtWith(Map.of("roles", List.of("player"))))).isFalse();
        assertThat(Roles.isAdmin(null)).isFalse();
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
