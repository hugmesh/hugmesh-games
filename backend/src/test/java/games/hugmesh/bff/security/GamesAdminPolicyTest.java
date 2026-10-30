package games.hugmesh.bff.security;

import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.Jwt;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link GamesAdminPolicy}: who is a games admin, per realm.
 *
 * <p>The personas are hugmesh-generator {@code tests/e2e/specs/rbac-roles.ts}
 * (e2e-gamesadmin, e2e-gamesonly, e2e-adminonly, e2e-user, e2e-superadmin)
 * on the systems realm, and the games realm's own seeds
 * ({@code casdoor/init_data.json.tpl}: admin, mod, publisher, player).
 */
class GamesAdminPolicyTest {

    private static final GamesAdminPolicy MESH = GamesAdminPolicy.systemsRealm();
    private static final GamesAdminPolicy REALM = GamesAdminPolicy.gamesRealm();

    private static Jwt jwt(Map<String, Object> claims) {
        Map<String, Object> all = new HashMap<>(claims);
        all.putIfAbsent("sub", "user-id");
        return new Jwt("tok", Instant.now(), Instant.now().plusSeconds(60),
                Map.of("alg", "RS256"), all);
    }

    private static Jwt roles(Object... names) {
        return jwt(Map.of("roles", List.of(names)));
    }

    // ── systems realm (mesh-mounted BFF, CASDOOR_ORGANIZATION=hugmesh) ────────

    @Test
    void meshGamesAdminPersonaIsAGamesAdmin() {
        assertThat(MESH.isGamesAdmin(roles("ROLE_GAMES", "ROLE_ADMIN", "ROLE_GAMES_ADMIN", "ROLE_USER"))).isTrue();
        // the live session spelling, and the Casdoor {name} object shape
        assertThat(MESH.isGamesAdmin(roles("admin", "games", "games_admin", "user"))).isTrue();
        assertThat(MESH.isGamesAdmin(roles(Map.of("name", "ROLE_GAMES_ADMIN")))).isTrue();
        // games + mod earns the derived role without ROLE_ADMIN
        assertThat(MESH.isGamesAdmin(roles("ROLE_GAMES", "ROLE_MOD", "ROLE_GAMES_ADMIN"))).isTrue();
    }

    @Test
    void meshAdminOnlyPersonaIsNotAGamesAdmin() {
        assertThat(MESH.isGamesAdmin(roles("ROLE_ADMIN", "ROLE_USER"))).isFalse();
        assertThat(MESH.isGamesAdmin(roles("admin", "user"))).isFalse();
        assertThat(MESH.isGamesAdmin(jwt(Map.of("roles", List.of("ROLE_USER"), "isAdmin", true)))).isFalse();
    }

    @Test
    void meshGamesOnlyUserModAndSuperadminAreNotGamesAdmins() {
        assertThat(MESH.isGamesAdmin(roles("ROLE_GAMES", "ROLE_USER"))).isFalse();
        assertThat(MESH.isGamesAdmin(roles("ROLE_USER"))).isFalse();
        assertThat(MESH.isGamesAdmin(roles("ROLE_MOD", "ROLE_GAMES"))).isFalse();
        // e2e-superadmin: its own tier, expectGamesAdmin false
        assertThat(MESH.isGamesAdmin(roles("ROLE_SUPERADMIN"))).isFalse();
    }

    // ── games realm (threewizards) ───────────────────────────────────────────

    @Test
    void gamesRealmAdminRoleIsAGamesAdmin() {
        assertThat(REALM.isGamesAdmin(roles("admin"))).isTrue();
        assertThat(REALM.isGamesAdmin(roles("ADMIN"))).isTrue();
        assertThat(REALM.isGamesAdmin(roles("ROLE_ADMIN"))).isTrue();
        assertThat(REALM.isGamesAdmin(roles("games_admin"))).isTrue();
    }

    @Test
    void legacyIsAdminFlagAloneIsNeverAGamesAdmin() {
        // Same rule the pause/resume gate already had (the flag alone is not a bypass).
        assertThat(REALM.isGamesAdmin(jwt(Map.of("isAdmin", true)))).isFalse();
        assertThat(MESH.isGamesAdmin(jwt(Map.of("isAdmin", true)))).isFalse();
    }

    @Test
    void gamesRealmNonAdminsAreNotGamesAdmins() {
        assertThat(REALM.isGamesAdmin(roles("player", "mod", "publisher", "uploader"))).isFalse();
        assertThat(REALM.isGamesAdmin(roles("superadmin"))).isFalse();
        assertThat(REALM.isGamesAdmin(roles("ROLE_ROLE_ADMIN"))).isFalse();
        assertThat(REALM.isGamesAdmin(null)).isFalse();
        assertThat(MESH.isGamesAdmin(null)).isFalse();
    }

    @Test
    void permissionsClaimNeverCounts() {
        Jwt j = jwt(Map.of("roles", List.of("ROLE_USER"),
                "permissions", List.of("games_admin", "ROLE_GAMES_ADMIN", "admin")));
        assertThat(MESH.isGamesAdmin(j)).isFalse();
        assertThat(REALM.isGamesAdmin(j)).isFalse();
    }

    // ── which realm a configuration is ───────────────────────────────────────

    @Test
    void onlyTheGamesRealmOrganizationCountsRealmAdmins() {
        String realm = GamesAdminPolicy.GAMES_REALM_ORGANIZATION;
        assertThat(GamesAdminPolicy.forOrganization("threewizards", realm).realmAdminIsGamesAdmin()).isTrue();
        assertThat(GamesAdminPolicy.forOrganization(" ThreeWizards ", realm).realmAdminIsGamesAdmin()).isTrue();
        assertThat(GamesAdminPolicy.forOrganization("hugmesh", realm).realmAdminIsGamesAdmin()).isFalse();
        assertThat(GamesAdminPolicy.forOrganization("some-brand", realm).realmAdminIsGamesAdmin()).isFalse();
        // blank / null fail closed
        assertThat(GamesAdminPolicy.forOrganization("", realm).realmAdminIsGamesAdmin()).isFalse();
        assertThat(GamesAdminPolicy.forOrganization(null, realm).realmAdminIsGamesAdmin()).isFalse();
        assertThat(GamesAdminPolicy.forOrganization("", "").realmAdminIsGamesAdmin()).isFalse();
        // a renamed games realm can be named explicitly
        assertThat(GamesAdminPolicy.forOrganization("arcade", "arcade").realmAdminIsGamesAdmin()).isTrue();
    }

    // ── a running BFF refuses to guess the realm ─────────────────────────────

    @Test
    void forDeploymentRefusesABlankOrganization() {
        for (String blank : new String[] {null, "", "   "}) {
            org.assertj.core.api.Assertions.assertThatThrownBy(
                            () -> GamesAdminPolicy.forDeployment(blank, GamesAdminPolicy.GAMES_REALM_ORGANIZATION))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("casdoor.organization");
        }
        assertThat(GamesAdminPolicy.forDeployment("threewizards", "threewizards").realmAdminIsGamesAdmin()).isTrue();
        assertThat(GamesAdminPolicy.forDeployment("hugmesh", "threewizards").realmAdminIsGamesAdmin()).isFalse();
    }
}
