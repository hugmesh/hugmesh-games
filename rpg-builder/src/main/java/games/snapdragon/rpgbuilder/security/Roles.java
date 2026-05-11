package games.snapdragon.rpgbuilder.security;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

/**
 * Thin convenience around Spring's authority machinery — mirrors the
 * BFF's {@code games.snapdragon.bff.security.Roles} so callers can use
 * the same idiom on both sides of the wire.
 */
public final class Roles {

    private Roles() {}

    public static boolean has(Authentication auth, String role) {
        if (auth == null) return false;
        String marker = "ROLE_" + role.toUpperCase();
        for (GrantedAuthority a : auth.getAuthorities()) {
            if (marker.equals(a.getAuthority())) return true;
        }
        return false;
    }

    public static boolean isAdmin(Authentication auth) { return has(auth, "ADMIN"); }
    public static boolean isPublisher(Authentication auth) { return has(auth, "PUBLISHER"); }

    public static String casdoorIdOf(Authentication auth) {
        if (auth instanceof JwtAuthenticationToken jat) return jat.getToken().getSubject();
        return null;
    }

    /** Casdoor username (the JWT `name` claim). Used as a stable
     *  secondary identifier for dev fixtures whose `owner_casdoor_id`
     *  is seeded as a username because Casdoor re-mints user UUIDs
     *  on every fresh DB boot. */
    public static String usernameOf(Authentication auth) {
        if (auth instanceof JwtAuthenticationToken jat) {
            return jat.getToken().getClaimAsString("name");
        }
        return null;
    }

    /** True when {@code ownerCasdoorId} matches either the JWT subject
     *  (the casdoor user id) or the JWT name (the casdoor username).
     *  Letting both forms count as ownership lets a Liquibase-seeded
     *  fixture survive a fresh Casdoor boot — the fixture owner is
     *  stored as the username (stable across boots) while a project
     *  created at runtime stores the JWT subject. */
    public static boolean ownerMatches(Authentication auth, String ownerCasdoorId) {
        if (ownerCasdoorId == null || auth == null) return false;
        String sub = casdoorIdOf(auth);
        String name = usernameOf(auth);
        return ownerCasdoorId.equals(sub) || ownerCasdoorId.equals(name);
    }

    /** Composite principal identifier: {@code "<sub>|<username>"}.
     *  Threaded through service calls as a single string so the
     *  existing controller→service signatures (single String owner)
     *  don't need to change. Service helpers
     *  {@link #subFromPrincipal} + {@link #principalMatches} split it
     *  back out. Either half counts as ownership. */
    public static String principalOf(Authentication auth) {
        String sub = casdoorIdOf(auth);
        String name = usernameOf(auth);
        return (sub == null ? "" : sub) + "|" + (name == null ? "" : name);
    }

    /** Extract the casdoor sub from a {@link #principalOf} composite,
     *  for create() paths that stamp the canonical owner_casdoor_id. */
    public static String subFromPrincipal(String principal) {
        if (principal == null) return null;
        int bar = principal.indexOf('|');
        return bar < 0 ? principal : principal.substring(0, bar);
    }

    /** True when the stored owner matches either half of a composite
     *  principal (sub OR username). */
    public static boolean principalMatches(String principal, String storedOwner) {
        if (principal == null || storedOwner == null) return false;
        for (String part : principal.split("\\|", -1)) {
            if (!part.isEmpty() && part.equals(storedOwner)) return true;
        }
        return false;
    }

    public static String bearerOf(Authentication auth) {
        if (auth instanceof JwtAuthenticationToken jat) {
            Jwt jwt = jat.getToken();
            return jwt.getTokenValue();
        }
        return null;
    }
}
