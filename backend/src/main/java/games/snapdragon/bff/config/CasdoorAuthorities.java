package games.snapdragon.bff.config;

import games.snapdragon.bff.security.DomainRegistry;
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
 * snapdragon-gateway ({@code systems.snapdragon.gateway.config.CasdoorAuthorities})
 * — keeping one converter avoids the half-migration pitfall where one path
 * silently drops the {@code DOMAIN_<x>} authority.
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
 * Every authenticated principal floors to {@code ROLE_USER} and is stamped with
 * exactly one issuer-derived {@code DOMAIN_<x>} authority.
 *
 * <h2>Note for the games BFF</h2>
 *
 * <p>{@code SecurityConfig.casdoorAuthoritiesConverter()} wraps this converter
 * with the {@code UserDirectoryService.cacheLogin} side-effect (the
 * username→casdoorId cache warm-up the relationship endpoints depend on). The
 * AUTHORITY output is identical to this class — the wrapper only adds the cache
 * hook, it never alters the emitted authorities.
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
     * Builds a reactive {@link Converter} bound to the given {@link DomainRegistry}
     * (used to resolve the issuer → {@code DOMAIN_<x>} authority). Both the
     * single-issuer and multi-issuer decoder paths call this factory so they share
     * identical authority output.
     *
     * @param domainRegistry the issuer-to-domain registry; must not be {@code null}
     * @return a converter from {@link Jwt} to {@link Flux}&lt;{@link GrantedAuthority}&gt;
     */
    static Converter<Jwt, Flux<GrantedAuthority>> converter(DomainRegistry domainRegistry) {
        return jwt -> Flux.fromIterable(authorities(jwt, domainRegistry));
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
     *       plain string or from a {@code {name: "..."}} object).</li>
     *   <li>{@code permissions} claim entries → verbatim, after the
     *       reserved-prefix guard drops any injection attempt.</li>
     *   <li>{@code isAdmin} legacy boolean → {@code ROLE_ADMIN} when
     *       {@code true}.</li>
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
     * @return mutable authority list (safe to wrap in {@link Flux#fromIterable})
     */
    static List<GrantedAuthority> authorities(Jwt jwt, DomainRegistry domainRegistry) {
        List<GrantedAuthority> authorities = new ArrayList<>();
        // Every authenticated principal gets ROLE_USER as the floor authority.
        authorities.add(new SimpleGrantedAuthority("ROLE_USER")); // floor — UNCHANGED

        // Map the roles claim (strings or {name} objects) to ROLE_<UPPER>.
        Object rolesClaim = jwt.getClaim("roles");
        if (rolesClaim instanceof Collection<?> list) {
            for (Object r : list) {
                String name = roleNameOf(r);
                if (!name.isBlank()) {
                    // Always ROLE_-prefixed, so a roles entry can NEVER inject a DOMAIN_
                    // authority: "DOMAIN_x" -> "ROLE_DOMAIN_X" (inert, non-tenant). See
                    // RESERVED_PREFIXES.
                    authorities.add(new SimpleGrantedAuthority(
                            "ROLE_" + name.trim().toUpperCase()));
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
