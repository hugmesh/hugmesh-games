package games.snapdragon.bff.security;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Single seam onto the principal's roles.
 *
 * <p>Most access control should hang off Spring's
 * {@code @PreAuthorize("hasRole('ADMIN')")}. Some queries are
 * data-driven though — {@code GameAccessRepository} intersects the
 * caller's roles with {@code game_access} scope rows to decide which
 * games show on the home grid. Those call sites need the role names
 * as a string list, and this utility is the only place that reads
 * them. Two read paths so the caller doesn't have to plumb whichever
 * argument they have on hand:
 *
 * <ul>
 *   <li>{@link #fromJwt(Jwt)} — used by controllers that already
 *       inject {@code @AuthenticationPrincipal Jwt}. Reads the
 *       {@code roles} claim verbatim (lowercase).</li>
 *   <li>{@link #fromAuthentication(Authentication)} — for code paths
 *       that have an {@code Authentication} but not the raw JWT.
 *       Reads from {@code getAuthorities()} and strips the
 *       {@code ROLE_} prefix Spring's converter adds.</li>
 * </ul>
 *
 * <p>Both return lowercase role names matching what
 * {@code GameAccessRepository.findVisibleGameIdsForPrincipal}
 * expects.
 */
public final class Roles {

    private Roles() { /* static utility */ }

    /** Anonymous principals get an empty role list — caller layers
     *  fall through to the {@code scope='all'} catalog filter.
     *
     *  <p>Casdoor's default token format mints {@code roles} as a list
     *  of objects ({@code [{name: "admin", owner: ...}, ...]}); a
     *  custom JWT template can also produce a flat list of strings.
     *  We accept either shape — Map items contribute their {@code name}
     *  field, plain strings contribute themselves. Without the Map
     *  handler an admin user's JWT comes through with no roles and
     *  {@link #isAdmin} returns false (the legacy {@code isAdmin}
     *  boolean fallback only fires inside
     *  {@code SecurityConfig.casdoorAuthoritiesConverter}, not here). */
    public static List<String> fromJwt(Jwt jwt) {
        if (jwt == null) return List.of();
        Object claim = jwt.getClaim("roles");
        if (!(claim instanceof List<?> list)) return List.of();
        return list.stream()
                .filter(r -> r != null)
                .map(Roles::roleNameOf)
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .map(String::toLowerCase)
                .distinct()
                .toList();
    }

    private static String roleNameOf(Object item) {
        if (item instanceof String s) return s;
        if (item instanceof Map<?, ?> m) {
            Object name = m.get("name");
            if (name instanceof String s && !s.isBlank()) return s;
        }
        return "";
    }

    /** Same shape, sourced from Spring's {@code Authentication}. The
     *  authorities have a {@code ROLE_} prefix the SecurityConfig's
     *  jwt converter adds; we strip it. */
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

    /** True when the caller holds {@code admin} (case-insensitive),
     *  derived from the raw JWT claim. Mirrors the predicate Spring's
     *  {@code hasRole('ADMIN')} runs on the converted authority. */
    public static boolean isAdmin(Jwt jwt) {
        return fromJwt(jwt).contains("admin");
    }

    public static boolean has(Jwt jwt, String role) {
        return fromJwt(jwt).contains(role.toLowerCase());
    }

    /** Set form — handy when the caller wants membership tests. */
    public static Set<String> setFromJwt(Jwt jwt) {
        return Set.copyOf(fromJwt(jwt));
    }

    /** Casdoor's {@code sub} claim is the user UUID — same shape the
     *  revocation list keys + per-user scope tables use. Returns
     *  {@code null} for an anonymous principal so callers can short-
     *  circuit instead of branching on an empty string. */
    public static String casdoorIdOf(Jwt jwt) {
        return jwt == null ? null : jwt.getSubject();
    }

    // ---------------------------------------------------------------------
    // Domain-scoping helpers — aligned with the snapdragon-gateway
    // {@code systems.snapdragon.gateway.security.Roles}. The games realm is
    // its own Casdoor; these answer "which infra/super capabilities does this
    // principal hold" and "which brand/domain is it scoped to" (the latter
    // derived from the cryptographically-VALIDATED token issuer, never a
    // user-settable claim). The games BFF currently has no per-domain
    // request-scoped data, so no @PreAuthorize/query DOMAIN predicate is
    // wired; these helpers are the canonical, ready-to-use seam for when it
    // gains brand-scoped data, and they enforce the same fail-closed contract
    // the gateway does.
    // ---------------------------------------------------------------------

    /** True iff the principal carries the infra super-role {@code SUPERADMIN}.
     *  The ONLY role exempt from per-domain scoping — SUPERADMIN reaches every
     *  brand's data (the infra control plane). Both hyphen and underscore
     *  spellings of the Casdoor role name match. */
    public static boolean isSuperadmin(Jwt jwt) {
        return has(jwt, "superadmin") || has(jwt, "super-admin") || has(jwt, "super_admin");
    }

    /** {@code SUPERADMIN} read off an {@link Authentication}'s authorities (the
     *  {@code ROLE_SUPERADMIN} authority the converter emits from the roles
     *  claim) — the form a domain-scoping service uses when it holds only the
     *  Spring authorities. */
    public static boolean isSuperadmin(Authentication auth) {
        if (auth == null) return false;
        for (GrantedAuthority a : auth.getAuthorities()) {
            if ("ROLE_SUPERADMIN".equals(a.getAuthority())) return true;
        }
        return false;
    }

    /** True iff the principal may build mesh-WORKLOAD images. A separate
     *  per-domain capability (NOT an admin default); both hyphen and underscore
     *  spellings match. */
    public static boolean isImageBuilder(Jwt jwt) {
        return has(jwt, "image-builder") || has(jwt, "image_builder");
    }

    /** The brand/domain this principal is scoped to, derived from the token
     *  ISSUER (per-brand Casdoor =&gt; distinct {@code iss}) through the SAME
     *  {@link DomainRegistry} the converter uses. Returns {@code "default"} for
     *  the single-issuer fallback so single-issuer clusters keep one domain,
     *  and (defensively) when {@code jwt}/{@code registry} is null. A
     *  {@code null} from the registry (unknown issuer in multi-brand mode) is
     *  surfaced as-is so the caller can fail closed. */
    public static String domainOf(Jwt jwt, DomainRegistry registry) {
        if (jwt == null || registry == null) return DomainRegistry.DEFAULT_DOMAIN;
        return registry.domainOf(jwt.getIssuer() == null ? null : jwt.getIssuer().toString());
    }

    /** Domain carried on the already-converted authorities ({@code DOMAIN_<x>})
     *  — the form a caller holding only Spring authorities (no registry) can
     *  read.
     *
     *  <p>FAIL-CLOSED on an ambiguous authority set: the canonical converter
     *  ({@code CasdoorAuthorities}) stamps EXACTLY ONE issuer-derived
     *  {@code DOMAIN_<x>}, so any other count is anomalous —
     *  <ul>
     *    <li>ZERO {@code DOMAIN_} authorities (a hand-built / pre-converter
     *        token) — we can't prove a tenant, so return {@code null}.</li>
     *    <li>MORE THAN ONE {@code DOMAIN_} authority — an INJECTION signature
     *        (e.g. a forged {@code permissions:["DOMAIN_other"]} alongside the
     *        genuine issuer {@code DOMAIN_brandx}); never pick the first, return
     *        {@code null}.</li>
     *  </ul>
     *  Returning {@code null} makes a domain-match predicate treat it as a
     *  non-match (deny). Belt-and-suspenders: the converter's reserved-prefix
     *  guard already prevents the injected DOMAIN_ from ever appearing — this
     *  overload simply refuses to be a spoofable side channel. Prefer
     *  {@link #domainOf(Jwt, DomainRegistry)} (issuer-keyed) where a {@link Jwt}
     *  is in hand. */
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

    /** Domain read off an {@link Authentication}'s authorities (the principal
     *  type the reactive controllers/service hold). Same fail-closed contract
     *  as {@link #domainOf(Collection)}. */
    public static String domainOf(Authentication auth) {
        return auth == null ? null : domainOf(auth.getAuthorities());
    }

    /** Every role name carried by the JWT, UPPER-cased + trimmed (the gateway's
     *  {@code Roles.fromJwt} shape). Distinct from {@link #fromJwt(Jwt)} which
     *  returns lowercase names for the {@code game_access} scope intersection;
     *  this overload-free helper exists for code that wants the upper-cased
     *  {@code ROLE_}-prefix-stripped form the gateway uses. */
    public static List<String> upperRolesFromJwt(Jwt jwt) {
        List<String> out = new ArrayList<>();
        if (jwt == null) return out;
        Object claim = jwt.getClaim("roles");
        if (claim instanceof Collection<?> list) {
            for (Object item : list) {
                String name = roleNameOf(item);
                if (!name.isBlank()) out.add(name.trim().toUpperCase());
            }
        }
        return out;
    }

    /**
     * Row-level brand-scope predicate — does a caller in {@code callerDomain} get to
     * act on a row OWNED by {@code rowDomain}? COPIED (logic) from snapdragon-gateway
     * {@code GatewayRouteAccessService.domainApplies}, with the gateway's SUPERADMIN
     * short-circuit folded in so every call site enforces the contract identically.
     *
     * <p>The contract, in order:
     * <ol>
     *   <li><b>SUPERADMIN sees all.</b> {@code isSuperadmin == true} ⇒ {@code true}
     *       unconditionally — the infra control plane reaches every brand's data. The
     *       gateway keeps this as a separate short-circuit BEFORE the row match; we
     *       fold it in so a service caller can't forget it.</li>
     *   <li><b>UNSCOPED row ⇒ global.</b> A {@code rowDomain} that is {@code null},
     *       blank, or {@code "default"} (case-insensitive, trimmed) applies to ANY
     *       caller. This is the back-compat path: every pre-merger {@code games} row
     *       backfills to {@code "default"}, so single-tenant clusters are unaffected.</li>
     *   <li><b>SCOPED row ⇒ exact brand match.</b> Otherwise the caller's domain must
     *       equal the row's (case-insensitive, both trimmed). A {@code null}
     *       {@code callerDomain} against a scoped row FAILS CLOSED (deny) — matching
     *       the gateway, where a scoped rule never matches a null target. {@code null}
     *       is the deny signal {@link #domainOf(Authentication)} already emits for a
     *       token with zero or &gt;1 {@code DOMAIN_} authorities (the injection
     *       signature), so an unprovable tenant cannot act on a scoped row.</li>
     * </ol>
     *
     * <p>Note the deliberate case-INSENSITIVITY here (vs the case-SENSITIVE
     * principal-vs-instance match in {@code DomainGuardFilter.domainMatches}): a ROW
     * domain may be hand-seeded / uploaded, so it is normalised, whereas the filter's
     * inputs are both registry-canonical.
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
