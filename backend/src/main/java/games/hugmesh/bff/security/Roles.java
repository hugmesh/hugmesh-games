package games.hugmesh.bff.security;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Single seam onto the principal's roles and brand/domain scope.
 *
 * <h2>Responsibility</h2>
 *
 * <p>Most access control should hang off Spring's
 * {@code @PreAuthorize("hasRole('ADMIN')")} annotations, which operate on
 * the converted Spring authority list. Some paths are data-driven though —
 * {@code GameAccessRepository} intersects the caller's roles with
 * {@code game_access} scope rows to decide which games appear on the home
 * grid. Those call sites need role names as plain strings, and this utility
 * is the only place that reads them.
 *
 * <h2>Two role-read paths</h2>
 *
 * <p>Two overloads exist so the caller doesn't have to plumb whichever
 * argument it has on hand:
 *
 * <ul>
 *   <li>{@link #fromJwt(Jwt)} — used by controllers that inject
 *       {@code @AuthenticationPrincipal Jwt}. Reads the {@code roles} claim
 *       and canonicalises each name — one leading {@code ROLE_} stripped,
 *       lowercased — the same shape {@code CasdoorAuthorities} re-prefixes
 *       (returned lowercase for the {@code game_access} scope
 *       intersection).</li>
 *   <li>{@link #fromAuthentication(Authentication)} — for code paths that
 *       hold an {@code Authentication} but not the raw JWT. Reads from
 *       {@code getAuthorities()} and strips the {@code ROLE_} prefix that
 *       Spring's converter added.</li>
 * </ul>
 *
 * <h2>Domain-scoping helpers</h2>
 *
 * <p>The second section (below the section divider) mirrors the gateway's
 * {@code systems.hugmesh.gateway.security.Roles}. These helpers answer
 * "which brand/domain is this principal scoped to?" (derived from the
 * cryptographically-validated token issuer, never a user-settable claim)
 * and enforce a fail-closed contract consistent with the gateway's own
 * {@code GatewayRouteAccessFilter}.
 *
 * <h2>Role hierarchy (for reference)</h2>
 *
 * <pre>
 *  ROLE_USER &lt; ROLE_MOD &lt; ROLE_ADMIN &lt; ROLE_SUPERADMIN
 * </pre>
 *
 * @see DomainRegistry
 * @see games.hugmesh.bff.config.CasdoorAuthorities
 * @see games.hugmesh.bff.config.DomainGuardFilter
 */
public final class Roles {

    private Roles() { /* static utility */ }

    // -------------------------------------------------------------------------
    // JWT role extraction
    // -------------------------------------------------------------------------

    /**
     * Returns the canonical lowercase role names carried by the JWT's
     * {@code roles} claim. Returns an empty list for an anonymous (null)
     * principal, which lets callers fall through to the {@code scope='all'}
     * catalog filter without branching.
     *
     * <p>Casdoor's default token format mints {@code roles} as a list of objects
     * ({@code [{name: "admin", owner: ...}, ...]}); a custom JWT template can also
     * produce a flat list of strings. Both shapes are accepted — {@code Map} items
     * contribute their {@code name} field, plain strings contribute themselves.
     * Without the {@code Map} handler an admin user's JWT comes through with no
     * roles and {@link #isAdmin(Jwt)} returns false (the legacy {@code isAdmin}
     * boolean fallback only fires inside
     * {@code SecurityConfig.casdoorAuthoritiesConverter}, not here).
     *
     * <p><b>Canonical form.</b> Each name has ONE leading {@code ROLE_} stripped
     * (any case) before lowercasing — the same canonicalisation
     * {@code CasdoorAuthorities.canonicalRoleName} applies before it re-prefixes
     * the Spring authority. The games realm ({@code threewizards}) seeds bare
     * names ({@code admin}); the systems Casdoor a mesh-mounted BFF validates
     * against seeds {@code ROLE_}-prefixed ones ({@code ROLE_ADMIN},
     * {@code ROLE_GAMES_ADMIN}). Both read back as {@code admin} /
     * {@code games_admin} here, so {@link #isAdmin(Jwt)}, {@link #isSuperadmin(Jwt)}
     * and {@link #has(Jwt, String)} answer the same for either realm. Without the
     * strip a mesh admin read as {@code role_admin} and every raw-JWT admin gate
     * (session pause/resume, character/item admin override, stream/realtime
     * bypass) refused them while the {@code hasRole('ADMIN')} gates admitted them.
     *
     * @param jwt the validated Casdoor JWT; {@code null} yields an empty list
     * @return immutable canonical lowercase role-name list, never {@code null}
     */
    public static List<String> fromJwt(Jwt jwt) {
        if (jwt == null) return List.of();
        Object claim = jwt.getClaim("roles");
        if (!(claim instanceof List<?> list)) return List.of();
        return list.stream()
                .filter(r -> r != null)
                .map(Roles::roleNameOf)
                .map(Roles::canonical)
                .filter(s -> !s.isEmpty())
                .distinct()
                .toList();
    }

    /**
     * Canonical lowercase form of a raw role name: trimmed, ONE leading
     * {@code ROLE_} (any case) stripped, then lowercased. {@code "ROLE_ADMIN"},
     * {@code " Admin "} and {@code "admin"} all canonicalise to {@code "admin"};
     * {@code "ROLE_ROLE_ADMIN"} canonicalises to {@code "role_admin"} (exactly
     * one prefix is stripped, mirroring {@code CasdoorAuthorities}). A null or
     * blank input yields {@code ""} so callers can filter it.
     *
     * @param raw a role name as found in the JWT claim, a {@code game_access}
     *            row, or a caller-supplied predicate argument
     * @return the canonical lowercase name, or {@code ""} when blank
     */
    public static String canonical(String raw) {
        if (raw == null) return "";
        String s = raw.trim();
        if (s.length() >= 5 && s.regionMatches(true, 0, "ROLE_", 0, 5)) {
            s = s.substring(5).trim();
        }
        return s.toLowerCase();
    }

    /**
     * Extracts a role name from either a plain {@code String} or a Casdoor-format
     * {@code {name: "..."}} object. Returns {@code ""} for any other shape so the
     * caller can safely filter blanks.
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

    /**
     * Same shape as {@link #fromJwt(Jwt)}, sourced from a Spring
     * {@link Authentication}. The authorities carry a {@code ROLE_} prefix that
     * Spring Security's JWT converter added; this method strips it so the returned
     * names are in the same lowercase, prefix-free form as {@link #fromJwt(Jwt)}.
     *
     * @param auth the Spring Security authentication; {@code null} yields an
     *             empty list
     * @return immutable lowercase role-name list (prefix-stripped), never
     *         {@code null}
     */
    public static List<String> fromAuthentication(Authentication auth) {
        if (auth == null) return List.of();
        Collection<? extends GrantedAuthority> authorities = auth.getAuthorities();
        if (authorities == null || authorities.isEmpty()) return List.of();
        return authorities.stream()
                .map(GrantedAuthority::getAuthority)
                .filter(a -> a != null)
                .map(a -> a.startsWith("ROLE_") ? a.substring(5) : a)
                .map(String::toLowerCase)
                .distinct()
                .toList();
    }

    /**
     * Canonical name of the platform's DERIVED games-admin role
     * ({@code games AND (admin OR mod)}, materialised by the generator seed /
     * the games-admin-sync Job as {@code ROLE_GAMES_ADMIN} on the systems
     * Casdoor). Kept in step with {@code CasdoorAuthorities.GAMES_ADMIN_ROLE}.
     */
    static final String GAMES_ADMIN = "games_admin";

    /**
     * Returns {@code true} when the caller is an admin of the games service,
     * derived from the raw JWT claim: the {@code admin} role in either realm
     * spelling ({@code admin} / {@code ROLE_ADMIN}), OR the platform's derived
     * {@code games_admin} role ({@code ROLE_GAMES_ADMIN} = games AND (admin OR
     * mod)). Mirrors EXACTLY the set of principals {@code CasdoorAuthorities}
     * grants the {@code ROLE_ADMIN} authority, so this predicate and Spring's
     * {@code hasRole('ADMIN')} admit the same callers — a games+mod holder who
     * passes the {@code /api/admin/**} matcher is also an admin at every raw-JWT
     * gate (session pause/resume, character/item admin override, stream and
     * realtime bypass). Deliberately NOT widened to {@code superadmin}: the
     * call sites that admit superadmins test {@link #isSuperadmin(Jwt)} alongside.
     *
     * @param jwt the validated Casdoor JWT; {@code null} returns {@code false}
     * @return {@code true} iff the JWT's {@code roles} claim canonicalises to
     *         {@code admin} or {@code games_admin}
     */
    public static boolean isAdmin(Jwt jwt) {
        List<String> roles = fromJwt(jwt);
        return roles.contains("admin") || roles.contains(GAMES_ADMIN);
    }

    /**
     * Returns {@code true} when the caller holds the given role name. The
     * argument is canonicalised the same way the claim is ({@link #canonical}),
     * so {@code has(jwt, "ROLE_GAMES")}, {@code has(jwt, "GAMES")} and
     * {@code has(jwt, "games")} are one question.
     *
     * @param jwt  the validated Casdoor JWT; {@code null} returns {@code false}
     * @param role the role name to test, any case, with or without a
     *             {@code ROLE_} prefix (e.g. {@code "ADMIN"} or {@code "mod"})
     * @return {@code true} iff the JWT carries the given role
     */
    public static boolean has(Jwt jwt, String role) {
        String want = canonical(role);
        return !want.isEmpty() && fromJwt(jwt).contains(want);
    }

    /**
     * Returns the caller's role names as an immutable {@link Set}, handy when
     * the call site needs membership tests without iterating a list.
     *
     * @param jwt the validated Casdoor JWT; {@code null} returns an empty set
     * @return immutable lowercase role-name set, never {@code null}
     */
    public static Set<String> setFromJwt(Jwt jwt) {
        return Set.copyOf(fromJwt(jwt));
    }

    /**
     * Returns the Casdoor subject UUID from the JWT's {@code sub} claim — the
     * identifier used by the revocation list, per-user scope tables, and ban
     * records. Returns {@code null} for an anonymous principal so callers can
     * short-circuit instead of branching on an empty string.
     *
     * @param jwt the validated Casdoor JWT; may be {@code null}
     * @return the Casdoor user UUID string, or {@code null} for a null JWT
     */
    public static String casdoorIdOf(Jwt jwt) {
        return jwt == null ? null : jwt.getSubject();
    }

    /**
     * Casdoor username (the JWT {@code name} claim). The subject is a user
     * UUID that Casdoor re-mints on every fresh DB boot, so rows seeded by
     * Liquibase (e.g. {@code publisher_game_scopes}) key on the username —
     * the only identifier stable across boots — while rows written at
     * runtime key on the subject. Ownership checks against seedable tables
     * must therefore accept either form (the rpg/vn-builder
     * {@code Roles.ownerMatches} idiom).
     *
     * @param jwt the validated Casdoor JWT; may be {@code null}
     * @return the Casdoor username, or {@code null} for a null JWT / absent claim
     */
    public static String usernameOf(Jwt jwt) {
        return jwt == null ? null : jwt.getClaimAsString("name");
    }

    // -------------------------------------------------------------------------
    // Domain-scoping helpers — aligned with hugmesh-gateway
    // {@code systems.hugmesh.gateway.security.Roles}. The games realm is
    // its own Casdoor; these answer "which infra/super capabilities does this
    // principal hold" and "which brand/domain is it scoped to" (the latter
    // derived from the cryptographically-VALIDATED token issuer, never a
    // user-settable claim). The games BFF currently has no per-domain
    // request-scoped data, so no @PreAuthorize/query DOMAIN predicate is
    // wired; these helpers are the canonical, ready-to-use seam for when it
    // gains brand-scoped data, and they enforce the same fail-closed contract
    // the gateway does.
    // -------------------------------------------------------------------------

    /**
     * Returns {@code true} iff the principal carries the infra super-role
     * {@code SUPERADMIN}. SUPERADMIN is the ONLY role exempt from per-domain
     * scoping — it reaches every brand's data (the infra control plane). Both
     * hyphen and underscore spellings of the Casdoor role name match, matching
     * the gateway's own tolerance for aliased spellings.
     *
     * @param jwt the validated Casdoor JWT; {@code null} returns {@code false}
     * @return {@code true} iff the JWT carries any recognised SUPERADMIN alias
     */
    public static boolean isSuperadmin(Jwt jwt) {
        return has(jwt, "superadmin") || has(jwt, "super-admin") || has(jwt, "super_admin");
    }

    /**
     * {@code SUPERADMIN} read off an {@link Authentication}'s authorities — the
     * form used by filter-layer code that holds only Spring authorities, not the
     * raw JWT. Tests for the {@code ROLE_SUPERADMIN} authority the
     * {@code CasdoorAuthorities} converter emits from the JWT {@code roles} claim.
     *
     * @param auth the Spring Security authentication; {@code null} returns
     *             {@code false}
     * @return {@code true} iff the authentication carries {@code ROLE_SUPERADMIN}
     */
    public static boolean isSuperadmin(Authentication auth) {
        if (auth == null) return false;
        for (GrantedAuthority a : auth.getAuthorities()) {
            if ("ROLE_SUPERADMIN".equals(a.getAuthority())) return true;
        }
        return false;
    }

    /**
     * Returns {@code true} iff the principal may build mesh-workload images.
     * This is a separate per-domain capability, NOT an implicit admin default.
     * Both hyphen and underscore spellings match.
     *
     * @param jwt the validated Casdoor JWT; {@code null} returns {@code false}
     * @return {@code true} iff the JWT carries any recognised image-builder alias
     */
    public static boolean isImageBuilder(Jwt jwt) {
        return has(jwt, "image-builder") || has(jwt, "image_builder");
    }

    /**
     * Returns the brand/domain this principal is scoped to, derived from the
     * token ISSUER (per-brand Casdoor => distinct {@code iss}) through the SAME
     * {@link DomainRegistry} the {@code CasdoorAuthorities} converter uses.
     *
     * <p>Returns {@code "default"} for the single-issuer fallback so single-issuer
     * clusters keep one domain, and defensively when {@code jwt} or
     * {@code registry} is null. A {@code null} from the registry (unknown issuer
     * in multi-brand mode) is surfaced as-is so the caller can fail closed.
     *
     * @param jwt      the validated Casdoor JWT; {@code null} returns
     *                 {@link DomainRegistry#DEFAULT_DOMAIN}
     * @param registry the issuer-to-domain registry; {@code null} returns
     *                 {@link DomainRegistry#DEFAULT_DOMAIN}
     * @return the resolved domain string, or {@code null} for an untrusted issuer
     *         in multi-brand mode
     */
    public static String domainOf(Jwt jwt, DomainRegistry registry) {
        if (jwt == null || registry == null) return DomainRegistry.DEFAULT_DOMAIN;
        return registry.domainOf(jwt.getIssuer() == null ? null : jwt.getIssuer().toString());
    }

    /**
     * Returns the brand/domain carried on the already-converted authorities
     * ({@code DOMAIN_<x>}) — the form a caller holding only Spring authorities
     * (no registry) can read.
     *
     * <p><b>Fail-closed on an ambiguous authority set.</b> The canonical converter
     * ({@code CasdoorAuthorities}) stamps EXACTLY ONE issuer-derived
     * {@code DOMAIN_<x>}, so any other count is anomalous:
     * <ul>
     *   <li>ZERO {@code DOMAIN_} authorities (a hand-built / pre-converter token)
     *       — the tenant cannot be proven; returns {@code null}.</li>
     *   <li>MORE THAN ONE {@code DOMAIN_} authority — an injection signature
     *       (e.g. a forged {@code permissions:["DOMAIN_other"]} alongside the
     *       genuine issuer {@code DOMAIN_brandx}); never pick the first; returns
     *       {@code null}.</li>
     * </ul>
     * Returning {@code null} makes a domain-match predicate treat it as a
     * non-match (deny). Belt-and-suspenders: the converter's reserved-prefix guard
     * already prevents the injected {@code DOMAIN_} from ever appearing — this
     * overload simply refuses to be a spoofable side channel. Prefer
     * {@link #domainOf(Jwt, DomainRegistry)} (issuer-keyed) where a {@link Jwt}
     * is in hand.
     *
     * @param authorities the principal's Spring Security authority collection;
     *                    {@code null} returns {@code null} (fail-closed)
     * @return the domain string extracted from the single {@code DOMAIN_<x>}
     *         authority, or {@code null} when the count is zero or more than one
     */
    public static String domainOf(Collection<? extends GrantedAuthority> authorities) {
        if (authorities == null) return null;                       // fail-closed
        String found = null;
        for (GrantedAuthority a : authorities) {
            String s = a.getAuthority();
            if (s != null && s.startsWith("DOMAIN_")) {
                if (found != null) return null;                     // >1 DOMAIN_ -> injection, fail-closed
                found = s.substring("DOMAIN_".length());
            }
        }
        return found;                                               // null when ZERO DOMAIN_ -> fail-closed
    }

    /**
     * Domain read off an {@link Authentication}'s authorities (the principal type
     * reactive controllers and services hold). Same fail-closed contract as
     * {@link #domainOf(Collection)}.
     *
     * @param auth the Spring Security authentication; {@code null} returns
     *             {@code null} (fail-closed)
     * @return the domain string, or {@code null} when unprovable
     */
    public static String domainOf(Authentication auth) {
        return auth == null ? null : domainOf(auth.getAuthorities());
    }

    /**
     * Returns every role name carried by the JWT, canonicalised (one leading
     * {@code ROLE_} stripped) and upper-cased — the gateway's {@code Roles.fromJwt}
     * shape. Distinct from {@link #fromJwt(Jwt)}
     * which returns lowercase names for the {@code game_access} scope intersection;
     * this helper exists for code that wants the upper-cased,
     * {@code ROLE_}-prefix-stripped form the gateway uses.
     *
     * @param jwt the validated Casdoor JWT; {@code null} returns an empty list
     * @return mutable list of upper-cased role names, never {@code null}
     */
    public static List<String> upperRolesFromJwt(Jwt jwt) {
        List<String> out = new ArrayList<>();
        if (jwt == null) return out;
        Object claim = jwt.getClaim("roles");
        if (claim instanceof Collection<?> list) {
            for (Object item : list) {
                String name = canonical(roleNameOf(item));
                if (!name.isEmpty()) out.add(name.toUpperCase());
            }
        }
        return out;
    }

    // -------------------------------------------------------------------------
    // Row-level domain predicate
    // -------------------------------------------------------------------------

    /**
     * Row-level brand-scope predicate — decides whether a caller in
     * {@code callerDomain} may act on a row owned by {@code rowDomain}.
     * Copied (logic) from hugmesh-gateway
     * {@code GatewayRouteAccessService.domainApplies}, with the gateway's
     * SUPERADMIN short-circuit folded in so every call site enforces the
     * contract identically.
     *
     * <p>The contract, in order:
     * <ol>
     *   <li><b>SUPERADMIN sees all.</b> {@code isSuperadmin == true} implies
     *       {@code true} unconditionally — the infra control plane reaches every
     *       brand's data.</li>
     *   <li><b>Unscoped row ⇒ global.</b> A {@code rowDomain} that is
     *       {@code null}, blank, or {@code "default"} (case-insensitive, trimmed)
     *       applies to ANY caller. This is the back-compat path: every pre-merger
     *       {@code games} row backfills to {@code "default"}, so single-tenant
     *       clusters are unaffected.</li>
     *   <li><b>Scoped row ⇒ exact brand match.</b> Otherwise the caller's domain
     *       must equal the row's (case-insensitive, both trimmed). A {@code null}
     *       {@code callerDomain} against a scoped row FAILS CLOSED (deny) —
     *       matching the gateway, where a scoped rule never matches a null target.
     *       {@code null} is the deny signal {@link #domainOf(Authentication)}
     *       already emits for a token with zero or &gt;1 {@code DOMAIN_} authorities
     *       (the injection signature), so an unprovable tenant cannot act on a
     *       scoped row.</li>
     * </ol>
     *
     * <p>Note the deliberate case-INSENSITIVITY here (vs the case-SENSITIVE
     * principal-vs-instance match in
     * {@code DomainGuardFilter.domainMatches}): a ROW domain may be hand-seeded
     * or uploaded, so it is normalised; the filter's inputs are both
     * registry-canonical.
     *
     * @param rowDomain    the {@code domain} column value of the target data row;
     *                     {@code null} / blank / {@code "default"} means global
     * @param callerDomain the caller's brand/domain from {@link #domainOf};
     *                     {@code null} fails closed against any scoped row
     * @param isSuperadmin {@code true} iff the caller holds the SUPERADMIN role
     * @return {@code true} iff this caller may act on the given row
     */
    public static boolean domainApplies(String rowDomain, String callerDomain, boolean isSuperadmin) {
        if (isSuperadmin) {
            return true;                                                  // cross-domain infra plane
        }
        if (rowDomain == null || rowDomain.isBlank()
                || DomainRegistry.DEFAULT_DOMAIN.equalsIgnoreCase(rowDomain.trim())) {
            return true;                                                  // UNSCOPED row -> any brand
        }
        return callerDomain != null
                && rowDomain.trim().equalsIgnoreCase(callerDomain.trim()); // SCOPED -> exact, null fails closed
    }
}
