package games.snapdragon.vnbuilder.security;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

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

    public static String casdoorIdOf(Authentication auth) {
        if (auth instanceof JwtAuthenticationToken jat) return jat.getToken().getSubject();
        return null;
    }

    /** Casdoor username (JWT `name` claim). Used as a fixture-stable
     *  owner alias; see rpg-builder Roles.ownerMatches for the rationale. */
    public static String usernameOf(Authentication auth) {
        if (auth instanceof JwtAuthenticationToken jat) {
            return jat.getToken().getClaimAsString("name");
        }
        return null;
    }

    /** Project owner accepts either the JWT subject (Casdoor user id)
     *  or the JWT name (username). Dev fixtures seed the username so
     *  ownership survives Casdoor re-minting UUIDs on a fresh DB. */
    public static boolean ownerMatches(Authentication auth, String ownerCasdoorId) {
        if (ownerCasdoorId == null || auth == null) return false;
        String sub = casdoorIdOf(auth);
        String name = usernameOf(auth);
        return ownerCasdoorId.equals(sub) || ownerCasdoorId.equals(name);
    }

    /** Composite principal identifier — see rpg-builder Roles.principalOf. */
    public static String principalOf(Authentication auth) {
        String sub = casdoorIdOf(auth);
        String name = usernameOf(auth);
        return (sub == null ? "" : sub) + "|" + (name == null ? "" : name);
    }

    /** Extract the casdoor sub from a composite principal. */
    public static String subFromPrincipal(String principal) {
        if (principal == null) return null;
        int bar = principal.indexOf('|');
        return bar < 0 ? principal : principal.substring(0, bar);
    }

    /** True when the stored owner matches either half of a composite principal. */
    public static boolean principalMatches(String principal, String storedOwner) {
        if (principal == null || storedOwner == null) return false;
        for (String part : principal.split("\\|", -1)) {
            if (!part.isEmpty() && part.equals(storedOwner)) return true;
        }
        return false;
    }

    public static String bearerOf(Authentication auth) {
        if (auth instanceof JwtAuthenticationToken jat) return jat.getToken().getTokenValue();
        return null;
    }
}
