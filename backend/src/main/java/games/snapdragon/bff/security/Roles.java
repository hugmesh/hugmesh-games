package games.snapdragon.bff.security;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;

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

    /** Casdoor user id (JWT subject). Drive-by fix — main references
     *  this from RateLimitFilter but the method was missing. */
    public static String casdoorIdOf(Jwt jwt) {
        return jwt == null ? null : jwt.getSubject();
    }
}
