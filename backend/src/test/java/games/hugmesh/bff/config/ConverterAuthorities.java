package games.hugmesh.bff.config;

import games.hugmesh.bff.security.DomainRegistry;
import games.hugmesh.bff.security.GamesAdminPolicy;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;

import java.util.List;

/**
 * Test-side door onto the package-private {@link CasdoorAuthorities}, so a
 * security slice in another package can give a mock JWT the authorities the
 * PRODUCTION converter computes for its claims, instead of a hand-written list
 * that can drift from it.
 */
public final class ConverterAuthorities {

    private ConverterAuthorities() {}

    public static List<GrantedAuthority> of(Jwt jwt, DomainRegistry registry, GamesAdminPolicy policy) {
        return CasdoorAuthorities.authorities(jwt, registry, policy);
    }
}
