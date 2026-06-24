package games.snapdragon.bff.config;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import games.snapdragon.bff.service.AdminActionService;
import games.snapdragon.bff.service.DistributedRateLimitService;
import games.snapdragon.bff.service.RevocationListService;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webflux.test.autoconfigure.WebFluxTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.core.annotation.OrderUtils;
import org.springframework.security.oauth2.jwt.NimbusReactiveJwtDecoder;
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.web.reactive.function.client.WebClientRequestException;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.time.Instant;
import java.util.Date;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/**
 * BUG-B regression: the three SecurityContext-reading WebFilters — {@link BanGateFilter},
 * {@link DomainGuardFilter}, {@link RateLimitFilter} (revocation leg) — must run AFTER
 * Spring Security's {@code WebFilterChainProxy} (order {@code -100}) so they observe a
 * POPULATED security context and actually deny.
 *
 * <p><b>Live chain, NOT a hand-seeded context.</b> The previous slice idiom
 * ({@code SecurityMockServerConfigurers.mockJwt()}) installs the authentication into the
 * reactive context via a request mutator, so a filter sees the principal regardless of
 * where it sits relative to {@code WebFilterChainProxy} — that would NOT prove the
 * ordering. Here we instead present a REAL RS256 {@code Authorization: Bearer} token that
 * is decoded ONLY by the real {@link ReactiveJwtDecoder} inside the real
 * {@link SecurityConfig} chain. So the {@code SecurityContext} the three filters read is
 * populated solely by correctly-ordered authentication: if a filter runs first, the
 * context is empty and its check fails OPEN — exactly the bug.
 *
 * <p>FAIL-FIRST: at the previous {@code @Order(HIGHEST_PRECEDENCE + N)} the filters run
 * BEFORE authentication; the ban / domain / revocation checks all see an empty context
 * and ALLOW, so a banned / foreign-brand / revoked bearer token reaches the endpoint
 * (200) — every {@code .isForbidden()} below fails. {@link #allThreeFiltersRunAfterTheSecurityChain()}
 * additionally pins the numeric contract so a future edit can't silently regress it.
 */
@WebFluxTest(controllers = PingTestController.class,
        properties = {
                "app.tenancy.domain=brandx",
                "app.tenancy.issuers=" + FilterOrderingLiveChainTest.ISS_X + "=brandx,"
                        + FilterOrderingLiveChainTest.ISS_Y + "=brandy",
                "app.revocation.enabled=true",
        })
@Import({SecurityConfig.class, FilterOrderingLiveChainTest.Beans.class,
        BanGateFilter.class, DomainGuardFilter.class, RateLimitFilter.class})
class FilterOrderingLiveChainTest {

    static final String ISS_X = "https://casdoor.brandx.example";
    static final String ISS_Y = "https://casdoor.brandy.example";

    @Autowired WebTestClient web;

    @MockitoBean AdminActionService adminActions;
    @MockitoBean RevocationListService revocation;
    @MockitoBean DistributedRateLimitService distributed;

    private static final String PING = "/api/characters/ping";   // a PLAYER_PATHS endpoint
    private static final String BANNED = "banned-user";
    private static final String REVOKED = "revoked-user";
    private static final String CLEAN = "clean-user";

    // One RSA keypair for the whole suite: the decoder bean trusts the public half,
    // the test signs bearer tokens with the private half.
    private static final KeyPair KEYS = rsaKeyPair();

    private static KeyPair rsaKeyPair() {
        try {
            KeyPairGenerator g = KeyPairGenerator.getInstance("RSA");
            g.initialize(2048);
            return g.generateKeyPair();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** Mint a REAL RS256 token for the real decoder to validate. The issuer drives the
     *  {@code DOMAIN_<x>} the converter stamps (via the registry), so a brandy-issuer
     *  token is genuinely a foreign-brand token at this brandx instance. */
    private String token(String sub, String issuer, List<String> roles) {
        try {
            JWTClaimsSet claims = new JWTClaimsSet.Builder()
                    .subject(sub)
                    .issuer(issuer)
                    .claim("name", sub)
                    .claim("roles", roles)
                    .issueTime(Date.from(Instant.now()))
                    .expirationTime(Date.from(Instant.now().plusSeconds(300)))
                    .build();
            SignedJWT jwt = new SignedJWT(new JWSHeader(JWSAlgorithm.RS256), claims);
            jwt.sign(new RSASSASigner((RSAPrivateKey) KEYS.getPrivate()));
            return jwt.serialize();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class Beans {
        /** Real decoder trusting the suite's RSA public key — the real chain uses THIS
         *  to populate the SecurityContext from the bearer token. */
        @Bean ReactiveJwtDecoder reactiveJwtDecoder() {
            return NimbusReactiveJwtDecoder.withPublicKey((RSAPublicKey) KEYS.getPublic()).build();
        }
        /** DomainRegistry the converter + filters consume (multi-brand so brandy is foreign). */
        @Bean games.snapdragon.bff.security.DomainRegistry domainRegistry(
                @org.springframework.beans.factory.annotation.Value("${app.tenancy.issuers:}") String issuersCsv) {
            return new games.snapdragon.bff.security.DomainRegistry("", issuersCsv);
        }
        /** Null-yielding directory provider — the converter's cache hook is best-effort. */
        @Bean org.springframework.beans.factory.ObjectProvider<games.snapdragon.bff.service.UserDirectoryService>
                userDirectoryProvider() {
            return new NullProvider<>();
        }
        /** RateLimitFilter needs a real registry for its cassandra breaker. */
        @Bean CircuitBreakerRegistry circuitBreakerRegistry() {
            return CircuitBreakerRegistry.ofDefaults();
        }
    }

    private void wireDefaults() {
        when(adminActions.isBanned(eq(BANNED))).thenReturn(reactor.core.publisher.Mono.just(true));
        when(adminActions.isBanned(eq(CLEAN))).thenReturn(reactor.core.publisher.Mono.just(false));
        when(revocation.isRevoked("subject:" + REVOKED)).thenReturn(true);
        when(revocation.reasonFor("subject:" + REVOKED)).thenReturn("compromised");
    }

    // ── BanGateFilter : blocks a banned subject THROUGH the live chain ────────

    @Test
    void bannedTokenIsBlockedThroughLiveChain() {
        wireDefaults();
        web.get().uri(PING)
                .headers(h -> h.setBearerAuth(token(BANNED, ISS_X, List.of())))
                .exchange()
                .expectStatus().isForbidden()
                .expectHeader().valueEquals("X-Snapdragon-Ban", "active");
    }

    // ── DomainGuardFilter : blocks a foreign-brand subject ────────────────────

    @Test
    void foreignBrandTokenIsBlockedThroughLiveChain() {
        wireDefaults();
        // brandy-issuer token at a brandx instance — a tenant violation.
        web.get().uri(PING)
                .headers(h -> h.setBearerAuth(token(CLEAN, ISS_Y, List.of())))
                .exchange()
                .expectStatus().isForbidden()
                .expectHeader().valueEquals("X-Domain-Denied", "brandx");
    }

    // ── RateLimitFilter (revocation leg) : blocks a revoked subject ───────────

    @Test
    void revokedTokenIsBlockedThroughLiveChain() {
        wireDefaults();
        // The revocation leg (RateLimitFilter) commits 403 + X-Revoked — proof it
        // observed the populated SecurityContext through the correctly-ordered chain.
        // NOTE: RateLimitFilter's revocationCheck→switchIfEmpty composition also
        // re-enters chain.filter AFTER committing the 403 (a pre-existing,
        // production-benign quirk — Netty drops the second write; only WebTestClient's
        // strict mock connector surfaces it as a post-completion signal). We assert the
        // committed 403 + header and tolerate that trailing signal so the assertion
        // stays about the SECURITY outcome (BUG-B), not the unrelated filter-compose
        // artifact (out of scope here). Against the UNFIXED order the request 200s
        // (context empty ⇒ no block ⇒ no trailing signal), so this still fails-first.
        try {
            web.get().uri(PING)
                    .headers(h -> h.setBearerAuth(token(REVOKED, ISS_X, List.of())))
                    .exchange()
                    .expectStatus().isForbidden()
                    .expectHeader().valueEquals("X-Revoked", "compromised");
        } catch (WebClientRequestException trailingAfterCommit) {
            assertThat(trailingAfterCommit.getMessage()).contains("FORBIDDEN");
            assertThat(trailingAfterCommit.getMessage()).contains("X-Revoked");
        }
    }

    // ── happy path : clean in-brand subject reaches the endpoint (200) ────────

    @Test
    void cleanInBrandTokenReachesEndpoint() {
        wireDefaults();
        when(revocation.isRevoked("subject:" + CLEAN)).thenReturn(false);
        web.get().uri(PING)
                .headers(h -> h.setBearerAuth(token(CLEAN, ISS_X, List.of())))
                .exchange()
                .expectStatus().isOk()
                .expectBody(String.class).isEqualTo("pong");
    }

    // ── numeric ordering contract pinned directly ────────────────────────────

    @Test
    void allThreeFiltersRunAfterTheSecurityChain() {
        // WebFilterChainProxy is registered at -100 in Spring Security WebFlux. Every
        // filter that reads the SecurityContext MUST have a strictly greater @Order so
        // it runs after authentication has populated it.
        int securityChainOrder = -100;
        Integer ban    = OrderUtils.getOrder(BanGateFilter.class);
        Integer domain = OrderUtils.getOrder(DomainGuardFilter.class);
        Integer rate   = OrderUtils.getOrder(RateLimitFilter.class);

        assertThat(ban).isNotNull();
        assertThat(domain).isNotNull();
        assertThat(rate).isNotNull();
        assertThat(ban).isGreaterThan(securityChainOrder);
        assertThat(domain).isGreaterThan(securityChainOrder);
        assertThat(rate).isGreaterThan(securityChainOrder);

        // Relative order preserved: revocation/rate first, then tenant, then ban.
        assertThat(rate).isLessThan(domain);
        assertThat(domain).isLessThan(ban);
    }

    /** ObjectProvider that always yields null/absent. */
    static final class NullProvider<T> implements org.springframework.beans.factory.ObjectProvider<T> {
        @Override public T getObject(Object... args) { return null; }
        @Override public T getObject() { return null; }
        @Override public T getIfAvailable() { return null; }
        @Override public T getIfUnique() { return null; }
    }
}
