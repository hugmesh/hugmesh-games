package games.hugmesh.bff.security;

import org.springframework.security.oauth2.jwt.Jwt;

import java.util.List;

/**
 * The one answer to "is this caller an admin of the games service?".
 *
 * <h2>Why a policy and not a role name</h2>
 *
 * <p>The BFF runs against one of two Casdoors, and the same role name means
 * different things in each:
 *
 * <ul>
 *   <li><b>Games realm</b> (org {@code threewizards}: the standalone compose
 *       stack and the cluster's {@code casdoor-games}). It is the games
 *       service's OWN identity provider, so its {@code admin} role IS the
 *       games admin. The realm has no {@code games_admin} role at all.</li>
 *   <li><b>Systems realm</b> (the platform Casdoor a mesh-mounted BFF trusts,
 *       e.g. org {@code hugmesh}). There {@code ROLE_ADMIN} is the PLATFORM
 *       admin, and the games admin is the derived {@code ROLE_GAMES_ADMIN}
 *       (= ROLE_GAMES AND (ROLE_ADMIN OR ROLE_MOD), materialised by the
 *       generator's games-admin-sync / derive-games-admin.sh). A bare
 *       ROLE_ADMIN is NOT a games admin: hugmesh-generator
 *       {@code tests/e2e/specs/rbac-roles.ts} (e2e-adminonly,
 *       {@code expectGamesAdmin: false}) and
 *       {@code specs/cluster/manifests/route-access/initdb/20-schema.sql}
 *       (the single {@code /games/api/admin/**} row keys on ROLE_GAMES_ADMIN
 *       only). The gateway forwards ROLE_ADMIN and ROLE_SUPERADMIN past its
 *       route-access table, so this BFF is the only place that rule holds.</li>
 * </ul>
 *
 * <p>So: the derived {@code games_admin} role is a games admin in either realm;
 * the realm {@code admin} role counts only when {@code casdoor.organization} is
 * the games realm. Any other organization is treated as a systems realm (fail
 * closed). The legacy Casdoor {@code isAdmin} boolean never counts in either
 * realm: the raw-JWT session pause/resume gate already refused it alone
 * (MultiplayerSessionControllerHttpAuthzTest
 * pause_legacyIsAdminClaimAlone_isNotTreatedAsAdmin), and the games-admin
 * authority now follows that same rule. Only the {@code roles} claim is read,
 * never {@code permissions}.
 *
 * <p>SUPERADMIN alone is NOT a games admin (rbac-roles.ts e2e-superadmin,
 * roles {@code ['ROLE_SUPERADMIN']}, {@code expectGamesAdmin: false}: "its OWN
 * tier — it does NOT carry ROLE_ADMIN"). A superadmin who also holds the games
 * admin role keeps the cross-brand exemption the domain gates give it.
 *
 * <p>The authorities converter emits {@link #AUTHORITY} exactly when
 * {@link #isGamesAdmin(Jwt)} is true, so {@code hasRole('GAMES_ADMIN')} on the
 * filter chain / {@code @PreAuthorize} and the raw-JWT checks in the
 * controllers (character/item override, stream/realtime bypass, session
 * pause/resume, upload scope, scoped session logs) admit the same callers.
 */
public final class GamesAdminPolicy {

    /** The Spring authority every games-admin gate requires. */
    public static final String AUTHORITY = "ROLE_GAMES_ADMIN";

    /** Role name for {@code hasRole(...)} / {@code hasAnyRole(...)}. */
    public static final String ROLE = "GAMES_ADMIN";

    /** Casdoor organization of the games realm (application.yml default,
     *  the cluster's casdoor-games, casdoor/init_data.json.tpl). */
    public static final String GAMES_REALM_ORGANIZATION = "threewizards";

    /** Canonical (see {@link Roles#canonical}) name of the derived role. */
    static final String GAMES_ADMIN_ROLE = "games_admin";

    private final boolean realmAdminIsGamesAdmin;

    public GamesAdminPolicy(boolean realmAdminIsGamesAdmin) {
        this.realmAdminIsGamesAdmin = realmAdminIsGamesAdmin;
    }

    /**
     * Policy for a BFF configured with {@code casdoor.organization =
     * casdoorOrganization}. Realm admins count only when that organization is
     * the games realm ({@code gamesRealmOrganization}, case-insensitive);
     * blank or any other value is a systems realm.
     */
    public static GamesAdminPolicy forOrganization(String casdoorOrganization,
                                                   String gamesRealmOrganization) {
        String org = casdoorOrganization == null ? "" : casdoorOrganization.trim();
        String realm = gamesRealmOrganization == null ? "" : gamesRealmOrganization.trim();
        return new GamesAdminPolicy(!org.isEmpty() && org.equalsIgnoreCase(realm));
    }

    /**
     * The policy a running BFF uses. Unlike {@link #forOrganization}, a blank
     * {@code casdoor.organization} is not "some other realm" here but a
     * misconfiguration: refuse to start rather than guess. (Guessing the games
     * realm, the old application.yml default, re-admitted every bare platform
     * admin on a mesh that forgot to set CASDOOR_ORGANIZATION.)
     *
     * @throws IllegalStateException when {@code casdoorOrganization} is blank
     */
    public static GamesAdminPolicy forDeployment(String casdoorOrganization,
                                                 String gamesRealmOrganization) {
        if (casdoorOrganization == null || casdoorOrganization.isBlank()) {
            throw new IllegalStateException(
                    "casdoor.organization (CASDOOR_ORGANIZATION) is not set. It decides who is a "
                    + "games admin: set it to the Casdoor organization this BFF trusts ("
                    + GAMES_REALM_ORGANIZATION + " for the games realm, the systems org on a mesh).");
        }
        return forOrganization(casdoorOrganization, gamesRealmOrganization);
    }

    /** The games realm: {@code admin} is the games admin. */
    public static GamesAdminPolicy gamesRealm() {
        return new GamesAdminPolicy(true);
    }

    /** A systems realm: only the derived {@code games_admin} role counts. */
    public static GamesAdminPolicy systemsRealm() {
        return new GamesAdminPolicy(false);
    }

    /** Whether the realm's own {@code admin} role is a games admin. */
    public boolean realmAdminIsGamesAdmin() {
        return realmAdminIsGamesAdmin;
    }

    /**
     * @param jwt the validated Casdoor JWT; {@code null} returns {@code false}
     * @return {@code true} iff the caller is an admin of the games service
     */
    public boolean isGamesAdmin(Jwt jwt) {
        if (jwt == null) return false;
        List<String> roles = Roles.fromJwt(jwt);
        if (roles.contains(GAMES_ADMIN_ROLE)) return true;
        if (!realmAdminIsGamesAdmin) return false;
        return roles.contains("admin");
    }

    @Override
    public String toString() {
        return "GamesAdminPolicy[realmAdminIsGamesAdmin=" + realmAdminIsGamesAdmin + "]";
    }
}
