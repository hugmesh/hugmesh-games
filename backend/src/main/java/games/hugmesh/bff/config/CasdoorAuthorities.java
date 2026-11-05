package games.hugmesh.bff.config;

import games.hugmesh.bff.security.DomainRegistry;
import games.hugmesh.bff.security.GamesAdminPolicy;
import games.hugmesh.bff.service.UserDirectoryService;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.convert.converter.Converter;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import reactor.core.publisher.Flux;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * Single source of truth for the games-BFF's Casdoor JWT → Spring authorities
 * mapping, ensuring that the single-issuer ({@link SecurityConfig}) and the
 * optional multi-issuer ({@link TenancyResourceServerConfig}) decoder paths
 * emit BYTE-IDENTICAL authorities. Copied verbatim (logic) from
 * hugmesh-gateway ({@code systems.hugmesh.gateway.config.CasdoorAuthorities})
 * — keeping one converter avoids the half-migration pitfall where one path
 * silently drops the {@code DOMAIN_<x>} authority. Re-synced with the
 * gateway's role-name canonicalization fix (hugmesh-gateway#74) — see
 * {@link #canonicalRoleName}.
 *
 * <h2>Authority mapping — three claim sources</h2>
 *
 * <ol>
 *   <li>{@code roles} array (strings OR objects with a {@code name} field —
 *       Casdoor's default token shape) → {@code ROLE_<UPPER>}.</li>
 *   <li>{@code permissions} array → verbatim (non-prefixed) authorities, EXCEPT
 *       any entry whose value starts with a RESERVED prefix ({@code ROLE_} /
 *       {@code DOMAIN_}) is dropped — those authorities come ONLY from controlled
 *       logic, never from a user-influenced permission string (authority-injection
 *       defense; see {@code RESERVED_PREFIXES}).</li>
 *   <li>{@code isAdmin} boolean (legacy) → {@code ROLE_ADMIN}.</li>
 * </ol>
 *
 * Plus {@code ROLE_GAMES_ADMIN} exactly when {@link GamesAdminPolicy} says the
 * caller is a games admin (the derived role in either realm; the realm's own
 * {@code admin} only on the games realm). This one authority is the games-admin
 * gate; {@code ROLE_ADMIN} alone opens nothing in this BFF.
 *
 * <h2>Roles are read per realm</h2>
 *
 * <p>The games realm names its capabilities {@code mod}, {@code publisher} and
 * {@code producer}. On the systems realm a bare {@code ROLE_MOD} is a PLATFORM
 * moderator, nothing to do with games, so a systems token only earns those games
 * capabilities from the games-namespaced {@code ROLE_GAMES_MOD},
 * {@code ROLE_GAMES_PUBLISHER} and {@code ROLE_GAMES_PRODUCER}, and the bare names
 * are dropped (see {@link #SYSTEMS_REALM_GAMES_ROLES}). The realm comes from the
 * token's {@code iss} through {@link GamesAdminPolicy#isGamesRealm}.
 *
 * <p>Raw-claim readers stay raw: {@code Roles.fromJwt} feeds the
 * {@code game_access} scope='role' intersection, which only ever widens what a
 * caller can SEE in the catalog. Those values are canonical names matched in both
 * realms, so {@code play-<slug>} has to be seeded in both Casdoors for a game gated
 * on it to appear for both kinds of player.
 *
 * Every authenticated principal floors to {@code ROLE_USER} and is stamped with
 * exactly one issuer-derived {@code DOMAIN_<x>} authority.
 *
 * <h2>Note for the games BFF</h2>
 *
 * <p>Both decoder paths use the {@code UserDirectoryService.cacheLogin} overload
 * of {@link #converter}, which wraps the mapping with the (realm, username) →
 * casdoorId cache warm-up the relationship endpoints depend on. It only adds the
 * cache hook, it never alters the emitted authorities.
 *
 * @see SecurityConfig
 * @see TenancyResourceServerConfig
 * @see DomainRegistry
 */
final class CasdoorAuthorities {

    private CasdoorAuthorities() {}

    // -------------------------------------------------------------------------
    // Reserved-prefix guard
    // -------------------------------------------------------------------------

    /**
     * Authority prefixes RESERVED for the converter's own controlled logic and
     * therefore NEVER allowed to originate from a user-influenced claim string:
     * <ul>
     *   <li>{@code ROLE_} — emitted ONLY from the {@code roles} claim (each role
     *       name upper-cased and prefixed here) and the legacy {@code isAdmin}
     *       boolean. A verbatim {@code permissions} entry must never become a
     *       {@code ROLE_*} authority.</li>
     *   <li>{@code DOMAIN_} — emitted ONLY from the cryptographically-validated
     *       issuer (exactly one, added last). A verbatim {@code permissions} entry
     *       must never forge a {@code DOMAIN_*} tenant scope; a {@code roles}
     *       entry cannot either (it always gains the {@code ROLE_} prefix first,
     *       so {@code "DOMAIN_x" -> "ROLE_DOMAIN_X"}, which is an inert
     *       non-tenant authority).</li>
     * </ul>
     * SECURITY: without this filter a brand-X token carrying
     * {@code permissions:["DOMAIN_other"]} or {@code permissions:["ROLE_SUPERADMIN"]}
     * would inject a forged tenant scope / super-role and escalate cross-domain.
     */
    private static final String[] RESERVED_PREFIXES = {"ROLE_", "DOMAIN_"};

    /**
     * Returns {@code true} iff the given authority string starts with a reserved
     * prefix (case-insensitive). Used to drop injection attempts from the
     * {@code permissions} claim.
     *
     * @param authority a single permissions-claim string
     * @return {@code true} iff the string must be dropped
     */
    private static boolean isReserved(String authority) {
        String upper = authority.trim().toUpperCase();
        for (String prefix : RESERVED_PREFIXES) {
            if (upper.startsWith(prefix)) return true;
        }
        return false;
    }

    // -------------------------------------------------------------------------
    // Public factory
    // -------------------------------------------------------------------------

    /**
     * Canonicalize a raw Casdoor role NAME to the bare, upper-cased role token
     * this converter re-prefixes with the single Spring {@code ROLE_}.
     *
     * <p>Ported from {@code systems.hugmesh.gateway.security.Roles#canonicalRoleName}
     * (added by hugmesh-gateway#74, "canonicalize Casdoor role names so
     * ROLE_-prefixed seeds don't double-prefix") — that fix's own doc comment
     * calls out this exact games realm as the "bare role name" convention it
     * was written to stay compatible with, alongside the systems realm's
     * {@code ROLE_}-prefixed seed shape. The {@code threewizards} Casdoor org
     * currently only ever seeds bare names ({@code admin}/{@code mod}/…), so
     * this has no behavioral effect today — it closes a latent gap so a future
     * {@code ROLE_}-prefixed role name here doesn't silently double-prefix to
     * {@code ROLE_ROLE_ADMIN} and drop out of every {@code hasRole()} check,
     * the same bug class #74 fixed on the gateway side.
     */
    private static String canonicalRoleName(String raw) {
        if (raw == null) return "";
        String s = raw.trim();
        if (s.length() >= 5 && s.regionMatches(true, 0, "ROLE_", 0, 5)) {
            s = s.substring(5).trim();
        }
        return s.toUpperCase();
    }

    /**
     * Games capabilities that collide with a systems-realm role name, as the
     * canonical name the games realm uses → the games-namespaced systems role that
     * grants it. See the class doc.
     */
    private static final Map<String, String> SYSTEMS_REALM_GAMES_ROLES = Map.of(
            "MOD", "GAMES_MOD",
            "PUBLISHER", "GAMES_PUBLISHER",
            "PRODUCER", "GAMES_PRODUCER");

    /** Per-realm reading of one canonical role name; empty means no authority. */
    private static List<String> authoritiesForRole(String name, boolean gamesRealm) {
        if (gamesRealm) return List.of("ROLE_" + name);
        if (SYSTEMS_REALM_GAMES_ROLES.containsKey(name)) return List.of();
        for (Map.Entry<String, String> e : SYSTEMS_REALM_GAMES_ROLES.entrySet()) {
            if (e.getValue().equals(name)) return List.of("ROLE_" + name, "ROLE_" + e.getKey());
        }
        return List.of("ROLE_" + name);
    }

    /**
     * Builds a reactive {@link Converter} bound to the given {@link DomainRegistry}
     * (used to resolve the issuer → {@code DOMAIN_<x>} authority). Both the
     * single-issuer and multi-issuer decoder paths call this factory so they share
     * identical authority output.
     *
     * @param domainRegistry the issuer-to-domain registry; must not be {@code null}
     * @return a converter from {@link Jwt} to {@link Flux}&lt;{@link GrantedAuthority}&gt;
     */
    static Converter<Jwt, Flux<GrantedAuthority>> converter(DomainRegistry domainRegistry,
                                                           GamesAdminPolicy gamesAdmin) {
        return jwt -> Flux.fromIterable(authorities(jwt, domainRegistry, gamesAdmin));
    }

    /**
     * {@link #converter(DomainRegistry, GamesAdminPolicy)} plus the
     * {@code cacheLogin} side-effect, so the single-issuer and multi-issuer paths
     * warm the same directory cache. Populates the (realm, username) → casdoorId
     * cache on every authenticated request: without this, the first
     * /api/relationships/blocks/add { other: "player2" } call misses the cache and
     * falls back to a Casdoor get-user round-trip that can transiently 404,
     * silently no-opping the block. The authorities are unaffected.
     *
     * @param directories lazy provider, so the security chain has no bootstrap-time
     *                    dependency on the directory; may yield {@code null}
     */
    static Converter<Jwt, Flux<GrantedAuthority>> converter(DomainRegistry domainRegistry,
                                                           GamesAdminPolicy gamesAdmin,
                                                           ObjectProvider<UserDirectoryService> directories) {
        Converter<Jwt, Flux<GrantedAuthority>> delegate = converter(domainRegistry, gamesAdmin);
        return jwt -> {
            UserDirectoryService directory = directories.getIfAvailable();
            if (directory != null) {
                directory.cacheLogin(jwt.getClaimAsString("iss"),
                        jwt.getClaimAsString("name"), jwt.getSubject());
            }
            return delegate.convert(jwt);
        };
    }

    // -------------------------------------------------------------------------
    // Authority computation
    // -------------------------------------------------------------------------

    /**
     * Computes the full authority list for the given JWT. Exposed as a
     * package-visible static method so unit tests can verify the mapping without
     * going through the reactive converter.
     *
     * <p>Processing order (each step adds to the mutable list):
     * <ol>
     *   <li>{@code ROLE_USER} floor — always added first.</li>
     *   <li>{@code roles} claim entries → {@code ROLE_<UPPER>} (either from a
     *       plain string or from a {@code {name: "..."}} object), read per realm
     *       (see the class doc).</li>
     *   <li>{@code permissions} claim entries → verbatim, after the
     *       reserved-prefix guard drops any injection attempt.</li>
     *   <li>{@code isAdmin} legacy boolean → {@code ROLE_ADMIN} when
     *       {@code true}.</li>
     *   <li>{@link GamesAdminPolicy#AUTHORITY} ({@code ROLE_GAMES_ADMIN}) when
     *       {@link GamesAdminPolicy#isGamesAdmin} says the caller is a games
     *       admin, and never otherwise. Every games-admin gate in this BFF keys
     *       on it; {@code ROLE_ADMIN} on its own opens nothing here.</li>
     *   <li>Exactly one issuer-derived {@code DOMAIN_<x>} — keyed on the
     *       cryptographically-validated {@code iss} claim, never a user-settable
     *       value. A null from the registry (unknown issuer in multi-brand mode)
     *       falls back to {@code DOMAIN_default} to avoid emitting
     *       {@code "DOMAIN_null"}.</li>
     * </ol>
     *
     * @param jwt            the validated Casdoor JWT; must not be {@code null}
     * @param domainRegistry the issuer-to-domain registry; must not be
     *                       {@code null}
     * @param gamesAdmin     which callers are games admins on this deployment
     *                       (realm-aware, see {@link GamesAdminPolicy})
     * @return mutable authority list (safe to wrap in {@link Flux#fromIterable})
     */
    static List<GrantedAuthority> authorities(Jwt jwt, DomainRegistry domainRegistry,
                                              GamesAdminPolicy gamesAdmin) {
        List<GrantedAuthority> authorities = new ArrayList<>();
        // Every authenticated principal gets ROLE_USER as the floor authority.
        authorities.add(new SimpleGrantedAuthority("ROLE_USER")); // floor — UNCHANGED

        // Map the roles claim (strings or {name} objects) to ROLE_<UPPER>, per realm.
        boolean gamesRealm = gamesAdmin.isGamesRealm(jwt);
        Object rolesClaim = jwt.getClaim("roles");
        if (rolesClaim instanceof Collection<?> list) {
            for (Object r : list) {
                // Canonicalize FIRST (strip one leading ROLE_ so a Casdoor role named
                // "ROLE_ADMIN" doesn't double-prefix to ROLE_ROLE_ADMIN and miss every
                // hasRole()/route-access check), THEN re-add the single Spring ROLE_.
                // Still always ROLE_-prefixed, so a roles entry can NEVER inject a
                // DOMAIN_ authority: "DOMAIN_x" -> "ROLE_DOMAIN_X" (inert, non-tenant).
                // See RESERVED_PREFIXES + canonicalRoleName.
                String name = canonicalRoleName(roleNameOf(r));
                if (!name.isBlank()) {
                    for (String authority : authoritiesForRole(name, gamesRealm)) {
                        authorities.add(new SimpleGrantedAuthority(authority));
                    }
                }
            }
        }

        // Map the permissions claim verbatim, dropping any reserved-prefix injection.
        Object permsClaim = jwt.getClaim("permissions");
        if (permsClaim instanceof Collection<?> list) {
            for (Object p : list) {
                if (p == null) continue;
                String perm = p.toString().trim();
                if (perm.isEmpty()) continue;
                // RESERVED-PREFIX GUARD (authority-injection defense). A verbatim
                // permission string MUST NOT carry the ROLE_ or DOMAIN_ prefix — those
                // authorities come ONLY from the converter's controlled logic
                // (roles-claim -> ROLE_<UPPER>; validated issuer -> DOMAIN_<x>).
                // Skipping such entries stops a brand-X token carrying
                // permissions:["DOMAIN_other"] (forged tenant scope) or
                // permissions:["ROLE_SUPERADMIN"] (forged super-role) from escalating
                // cross-domain. See RESERVED_PREFIXES.
                if (isReserved(perm)) continue;
                authorities.add(new SimpleGrantedAuthority(perm));
            }
        }

        // Legacy isAdmin boolean — emits ROLE_ADMIN for users created before
        // explicit role assignment was available in the Casdoor admin console.
        if (Boolean.TRUE.equals(jwt.getClaim("isAdmin"))) {
            authorities.add(new SimpleGrantedAuthority("ROLE_ADMIN"));
        }

        // Games admin. A GAMES_ADMIN entry in the roles claim already produced
        // ROLE_GAMES_ADMIN above; the policy is the single decision (it also
        // admits the games realm's own admin role), so drop that
        // copy and emit the authority exactly when the policy says so. Until
        // 2026-10 the derived role was ALSO promoted to ROLE_ADMIN so the old
        // hasRole('ADMIN') gates admitted it; that made ROLE_ADMIN the gate, and
        // a bare platform admin (e2e-adminonly) reached every games admin
        // endpoint. Permissions can never reach this: the reserved-prefix guard
        // drops a permissions:["ROLE_GAMES_ADMIN"], and the policy reads the
        // roles claim only.
        authorities.removeIf(a -> GamesAdminPolicy.AUTHORITY.equals(a.getAuthority()));
        if (gamesAdmin.isGamesAdmin(jwt)) {
            authorities.add(new SimpleGrantedAuthority(GamesAdminPolicy.AUTHORITY));
        }

        // Domain authority — keyed on the cryptographically-validated ISSUER (per-brand
        // Casdoor => distinct iss). NEVER a user-settable claim. DOMAIN_<x> is a
        // NON-role authority (no ROLE_ prefix) so it can never satisfy a
        // hasRole()/route-access role check by accident. A null from the registry
        // (unknown issuer) defensively maps to "default" rather than emitting
        // "DOMAIN_null".
        String issuer = jwt.getIssuer() == null ? null : jwt.getIssuer().toString();
        String domain = domainRegistry.domainOf(issuer);
        if (domain == null) domain = DomainRegistry.DEFAULT_DOMAIN;
        authorities.add(new SimpleGrantedAuthority("DOMAIN_" + domain));

        return authorities;
    }

    // -------------------------------------------------------------------------
    // Claim-extraction helper
    // -------------------------------------------------------------------------

    /**
     * Extracts the role name from either a plain {@code String} or a Casdoor-format
     * {@code {name: "..."}} object. Returns {@code ""} for any other shape so the
     * caller can safely filter blank results.
     *
     * @param item a single element from the JWT {@code roles} claim array
     * @return the role name string, or {@code ""} when the shape is unrecognised
     */
    private static String roleNameOf(Object item) {
        if (item instanceof String s) return s;
        if (item instanceof Map<?, ?> m) {
            Object name = m.get("name");
            if (name instanceof String s && !s.isBlank()) return s;
        }
        return "";
    }
}
