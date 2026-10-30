package games.hugmesh.bff.security;

import org.springframework.security.oauth2.jwt.Jwt;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * The one answer to "is this caller an admin of the games service?".
 *
 * <h2>Why a policy and not a role name</h2>
 *
 * <p>The BFF trusts one or both of two Casdoors, and the same role name means
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
 * the realm {@code admin} role, and {@code mesh-games-admin} (the games-realm
 * role hugmesh-actions' GM sync owns), count only on a token from the games
 * realm. A role name in a token says nothing about which realm it came from, so
 * the realm is read from the token's signature-bound {@code iss}, never from a
 * claim like {@code owner}.
 *
 * <h2>Which tokens are games-realm tokens</h2>
 *
 * <ul>
 *   <li><b>{@code app.security.games-realm-issuers} set:</b> exactly the tokens
 *       whose {@code iss} is listed. This is the only safe shape for a BFF that
 *       trusts both Casdoors, so {@link #forDeployment} refuses to start a
 *       multi-issuer BFF without it.</li>
 *   <li><b>Unset, one issuer:</b> {@code casdoor.organization} decides for every
 *       token, as before: the games realm when it is {@code threewizards}, a
 *       systems realm otherwise (fail closed). Only one Casdoor can sign here, so
 *       the deployment-wide answer is also the per-token answer.</li>
 * </ul>
 *
 * <p>The legacy Casdoor {@code isAdmin} boolean never counts in either
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

    /** Canonical name of the games-realm role the GM sync owns (hugmesh-actions
     *  {@code GM_SYNC_GAMES_ROLE}). Only that sync writes it, so unlike
     *  {@code admin} it never needs a person to be granted it by hand. Counts on a
     *  games-realm token only: a mesh token cannot earn games admin by naming it. */
    static final String MESH_GAMES_ADMIN_ROLE = "mesh-games-admin";

    private final boolean realmAdminIsGamesAdmin;

    /** Non-empty: exactly these issuers are the games realm, whatever
     *  {@link #realmAdminIsGamesAdmin} says. Empty: that flag decides for every
     *  token (one issuer, see the class doc). */
    private final Set<String> gamesRealmIssuers;

    public GamesAdminPolicy(boolean realmAdminIsGamesAdmin) {
        this(realmAdminIsGamesAdmin, Set.of());
    }

    private GamesAdminPolicy(boolean realmAdminIsGamesAdmin, Set<String> gamesRealmIssuers) {
        this.realmAdminIsGamesAdmin = realmAdminIsGamesAdmin;
        this.gamesRealmIssuers = gamesRealmIssuers;
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
     * <p>The same goes for a BFF that trusts several issuers without saying which
     * one is the games realm: answering from {@code casdoor.organization} there
     * would make every platform admin of the other Casdoor a games admin, or strip
     * the games realm's own admin. A listed issuer that is not trusted is refused
     * too, because it can never match and would quietly lock the realm's admins out.
     *
     * @param casdoorOrganization    {@code casdoor.organization}
     * @param gamesRealmOrganization {@code app.security.games-realm-organization}
     * @param gamesRealmIssuersCsv   {@code app.security.games-realm-issuers}
     * @param trustedIssuers         every issuer this BFF accepts (empty when it
     *                               only knows a jwk-set-uri)
     * @throws IllegalStateException on any of the misconfigurations above
     */
    public static GamesAdminPolicy forDeployment(String casdoorOrganization,
                                                 String gamesRealmOrganization,
                                                 String gamesRealmIssuersCsv,
                                                 Collection<String> trustedIssuers) {
        if (casdoorOrganization == null || casdoorOrganization.isBlank()) {
            throw new IllegalStateException(
                    "casdoor.organization (CASDOOR_ORGANIZATION) is not set. It decides who is a "
                    + "games admin: set it to the Casdoor organization this BFF trusts ("
                    + GAMES_REALM_ORGANIZATION + " for the games realm, the systems org on a mesh).");
        }
        Set<String> listed = parseIssuers(gamesRealmIssuersCsv);
        boolean multiIssuer = trustedIssuers != null && trustedIssuers.size() > 1;
        if (multiIssuer && listed.isEmpty()) {
            throw new IllegalStateException(
                    "This BFF trusts more than one issuer (APP_TENANCY_ISSUERS) but "
                    + "app.security.games-realm-issuers (APP_SECURITY_GAMES_REALM_ISSUERS) is not set. "
                    + "It decides whose `admin` role is a games admin: list the games realm's issuer.");
        }
        if (trustedIssuers != null && !trustedIssuers.isEmpty()) {
            List<String> untrusted = new ArrayList<>(listed);
            untrusted.removeAll(trustedIssuers);
            if (!untrusted.isEmpty()) {
                throw new IllegalStateException(
                        "app.security.games-realm-issuers names issuers this BFF does not trust: "
                        + untrusted + ". Add them to APP_TENANCY_ISSUERS or fix the spelling.");
            }
        }
        if (!listed.isEmpty()) {
            return new GamesAdminPolicy(true, listed);
        }
        return forOrganization(casdoorOrganization, gamesRealmOrganization);
    }

    /** One BFF, two realms: exactly {@code issuers} are the games realm. */
    public static GamesAdminPolicy forIssuers(Collection<String> issuers) {
        Set<String> set = new LinkedHashSet<>();
        for (String i : issuers) {
            String t = normalize(i);
            if (!t.isEmpty()) set.add(t);
        }
        if (set.isEmpty()) {
            throw new IllegalArgumentException("at least one games-realm issuer is required");
        }
        return new GamesAdminPolicy(true, set);
    }

    private static Set<String> parseIssuers(String csv) {
        Set<String> out = new LinkedHashSet<>();
        if (csv == null) return out;
        for (String part : csv.split(",")) {
            String t = normalize(part);
            if (!t.isEmpty()) out.add(t);
        }
        return out;
    }

    /** Same canonical form {@link DomainRegistry} keys issuers on. */
    private static String normalize(String issuer) {
        if (issuer == null) return "";
        String t = issuer.trim();
        return t.endsWith("/") ? t.substring(0, t.length() - 1) : t;
    }

    /** The games realm: {@code admin} is the games admin. */
    public static GamesAdminPolicy gamesRealm() {
        return new GamesAdminPolicy(true);
    }

    /** A systems realm: only the derived {@code games_admin} role counts. */
    public static GamesAdminPolicy systemsRealm() {
        return new GamesAdminPolicy(false);
    }

    /** Whether this BFF has a games realm at all, i.e. some token's own
     *  {@code admin} role can be a games admin. Per-token answer:
     *  {@link #isGamesRealm(Jwt)}. */
    public boolean realmAdminIsGamesAdmin() {
        return realmAdminIsGamesAdmin;
    }

    /**
     * Whether this token came from the games realm. Role names mean different
     * things per realm (see the class doc), so everything that reads one asks here.
     *
     * @param jwt the validated Casdoor JWT; {@code null} returns {@code false}
     * @return {@code true} iff its {@code iss} is the games realm
     */
    public boolean isGamesRealm(Jwt jwt) {
        return jwt != null && isGamesRealmIssuer(jwt.getClaimAsString("iss"));
    }

    /** Same as {@link #isGamesRealm(Jwt)} for an issuer string. */
    public boolean isGamesRealmIssuer(String issuer) {
        if (gamesRealmIssuers.isEmpty()) return realmAdminIsGamesAdmin;
        return gamesRealmIssuers.contains(normalize(issuer));
    }

    /**
     * @param jwt the validated Casdoor JWT; {@code null} returns {@code false}
     * @return {@code true} iff the caller is an admin of the games service
     */
    public boolean isGamesAdmin(Jwt jwt) {
        if (jwt == null) return false;
        List<String> roles = Roles.fromJwt(jwt);
        if (roles.contains(GAMES_ADMIN_ROLE)) return true;
        if (!isGamesRealm(jwt)) return false;
        return roles.contains("admin") || roles.contains(MESH_GAMES_ADMIN_ROLE);
    }

    /**
     * The caller's username for matching rows that key a person on a name
     * ({@code publisher_game_scopes} rows Liquibase seeds): only a games-realm
     * caller has one. Those seeds name games-realm accounts, and names are not
     * unique across realms, so mesh {@code alice} must not inherit what games
     * {@code alice} was granted. Otherwise {@code null}, which every name-matching
     * caller already reads as "match on the subject alone".
     *
     * @param jwt the validated Casdoor JWT; may be {@code null}
     * @return the username, or {@code null} for a null JWT, an absent claim, or a
     *         token from another realm
     */
    public String seededUsernameOf(Jwt jwt) {
        return isGamesRealm(jwt) ? Roles.usernameOf(jwt) : null;
    }

    @Override
    public String toString() {
        return "GamesAdminPolicy[realmAdminIsGamesAdmin=" + realmAdminIsGamesAdmin
                + ", gamesRealmIssuers=" + gamesRealmIssuers + "]";
    }
}
