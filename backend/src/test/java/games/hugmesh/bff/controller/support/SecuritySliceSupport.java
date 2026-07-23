package games.hugmesh.bff.controller.support;

import games.hugmesh.bff.security.DomainRegistry;
import games.hugmesh.bff.service.UserDirectoryService;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.test.web.reactive.server.SecurityMockServerConfigurers;

import java.time.Instant;
import java.util.List;
import java.util.function.Consumer;

import static org.springframework.security.test.web.reactive.server.SecurityMockServerConfigurers.mockJwt;

/**
 * Web-layer security-slice support for the games BFF, mirroring the
 * {@code TestIssuer}/{@code SecuritySliceSupport} idiom established in
 * hugmesh-gateway PR #34 for multi-issuer security-slice tests.
 *
 * <p>Why a slice and not a plain POJO controller test: the existing
 * controller tests {@code new} the controller and call methods directly, which
 * proves the service-layer/method-internal ownership gates but NEVER exercises
 * the Spring Security filter chain — the {@code authorizeExchange} matchers
 * ({@code /api/admin/**} → {@code hasRole('ADMIN')}, {@code authenticated()})
 * and the {@code @PreAuthorize} method security. This wave targets exactly that
 * HTTP/filter boundary: 401 unauthenticated, 403 wrong-role / cross-domain,
 * 200/expected for the authorized caller.
 *
 * <p>Usage: annotate a {@code @WebFluxTest(controllers = X.class)} with
 * {@code @Import({SecurityConfig.class, SecuritySliceSupport.TenantBeans.class})}
 * and drive {@code WebTestClient} with the {@link #user}, {@link #admin},
 * {@link #superadmin}, {@link #mod} mutators. Authorities are set EXACTLY as the
 * production {@code CasdoorAuthorities} converter would emit them (ROLE_USER
 * floor + ROLE_&lt;UPPER&gt; per role + the issuer-derived DOMAIN_&lt;x&gt;),
 * and the backing {@link Jwt} carries the matching {@code sub} + {@code iss} so
 * a controller that derives its domain from {@code jwt.getIssuer()} (never a
 * request param/body) sees the genuinely-validated issuer.
 *
 * <p>{@code mockJwt()} installs the authentication directly into the security
 * context, so the {@code oauth2ResourceServer} JWK decoder is bypassed — no
 * Casdoor / JWK server is needed and the test stays a pure controller/filter
 * authz matrix (the DB / decoder are out of scope here, as the mission notes
 * Testcontainers may not provision on this host).
 */
public final class SecuritySliceSupport {

    private SecuritySliceSupport() {}

    /** Two issuers the multi-brand {@link DomainRegistry} maps to brand domains. */
    public static final String ISS_X = "https://casdoor.brandx.example";
    public static final String ISS_Y = "https://casdoor.brandy.example";
    public static final String BRAND_X = "brandx";
    public static final String BRAND_Y = "brandy";

    /**
     * Beans {@code @WebFluxTest} does not contribute but {@code SecurityConfig}
     * needs: the {@link DomainRegistry} (a {@code @Component}, outside the web
     * slice) and the lazy {@link UserDirectoryService} provider the authorities
     * converter wraps. The registry is multi-brand so the same slice can prove
     * both single-tenant ('default' issuer → inert gate) and cross-brand DENY.
     */
    @TestConfiguration(proxyBeanMethods = false)
    public static class TenantBeans {

        @Bean
        DomainRegistry domainRegistry(
                @Value("${app.tenancy.issuers:}") String issuersCsv) {
            return new DomainRegistry("", issuersCsv);
        }

        /** Null-returning provider — the converter's cache warm-up is a no-op
         *  best-effort hook, never a security control, so a null directory is fine. */
        @Bean
        ObjectProvider<UserDirectoryService> userDirectoryProvider() {
            return new NullProvider<>();
        }
    }

    // ── JWT mutators (authorities mirror CasdoorAuthorities output) ───────────

    /** Authenticated player: ROLE_USER floor only, default domain. */
    public static SecurityMockServerConfigurers.JwtMutator user(String sub) {
        return jwtFor(sub, defaultIssuer(), List.of(), List.of("ROLE_USER", "DOMAIN_default"));
    }

    /** Authenticated player on a specific brand issuer. */
    public static SecurityMockServerConfigurers.JwtMutator userOn(String sub, String issuer, String domain) {
        return jwtFor(sub, issuer, List.of(), List.of("ROLE_USER", "DOMAIN_" + domain));
    }

    /** MOD: ROLE_USER + ROLE_MOD, default domain (NOT an admin/superadmin). */
    public static SecurityMockServerConfigurers.JwtMutator mod(String sub) {
        return jwtFor(sub, defaultIssuer(), List.of("mod"),
                List.of("ROLE_USER", "ROLE_MOD", "DOMAIN_default"));
    }

    /** PUBLISHER: ROLE_USER + ROLE_PUBLISHER, default domain. */
    public static SecurityMockServerConfigurers.JwtMutator publisher(String sub) {
        return jwtFor(sub, defaultIssuer(), List.of("publisher"),
                List.of("ROLE_USER", "ROLE_PUBLISHER", "DOMAIN_default"));
    }

    /** ADMIN on the default issuer. */
    public static SecurityMockServerConfigurers.JwtMutator admin(String sub) {
        return adminOn(sub, defaultIssuer(), "default");
    }

    /** ADMIN on a specific brand issuer (cross-domain matrix). */
    public static SecurityMockServerConfigurers.JwtMutator adminOn(String sub, String issuer, String domain) {
        return jwtFor(sub, issuer, List.of("admin"),
                List.of("ROLE_USER", "ROLE_ADMIN", "DOMAIN_" + domain));
    }

    /** SUPERADMIN on a specific brand issuer — cross-domain bypass. */
    public static SecurityMockServerConfigurers.JwtMutator superadminOn(String sub, String issuer, String domain) {
        return jwtFor(sub, issuer, List.of("admin", "superadmin"),
                List.of("ROLE_USER", "ROLE_ADMIN", "ROLE_SUPERADMIN", "DOMAIN_" + domain));
    }

    /** GAME-MASTER (platform admin) on the default issuer. */
    public static SecurityMockServerConfigurers.JwtMutator gameMaster(String sub) {
        return jwtFor(sub, defaultIssuer(), List.of("game-master"),
                List.of("ROLE_USER", "ROLE_GAME-MASTER", "DOMAIN_default"));
    }

    private static String defaultIssuer() {
        // Single-tenant: any issuer the multi-brand registry doesn't know maps to
        // 'default'. We use a stable non-brand issuer so default-domain callers
        // genuinely resolve to "default" through the registry.
        return "https://casdoor.hugmesh.games";
    }

    private static SecurityMockServerConfigurers.JwtMutator jwtFor(
            String sub, String issuer, List<String> roleNames, List<String> authorities) {
        Consumer<Jwt.Builder> claims = b -> b
                .subject(sub)
                .issuer(issuer)
                .header("alg", "RS256")
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(300))
                .claim("name", sub)
                .claim("roles", roleNames);
        List<org.springframework.security.core.GrantedAuthority> granted = authorities.stream()
                .map(a -> (org.springframework.security.core.GrantedAuthority)
                        new org.springframework.security.core.authority.SimpleGrantedAuthority(a))
                .toList();
        return mockJwt()
                .jwt(claims)
                .authorities(granted);
    }

    /** ObjectProvider that always yields null/absent — adequate for the
     *  converter's best-effort cache hook (never a security control). */
    private static final class NullProvider<T> implements ObjectProvider<T> {
        @Override public T getObject(Object... args) { return null; }
        @Override public T getObject() { return null; }
        @Override public T getIfAvailable() { return null; }
        @Override public T getIfUnique() { return null; }
    }
}
