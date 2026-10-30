package games.hugmesh.bff.controller;

import games.hugmesh.bff.config.BanGateFilter;
import games.hugmesh.bff.config.DomainGuardFilter;
import games.hugmesh.bff.config.RateLimitFilter;
import games.hugmesh.bff.config.SecurityConfig;
import games.hugmesh.bff.config.SecurityHeadersFilter;
import games.hugmesh.bff.controller.support.RealIssuer;
import games.hugmesh.bff.controller.support.SecuritySliceSupport;
import games.hugmesh.bff.service.DebugModeService;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webflux.test.autoconfigure.WebFluxTest;
import org.springframework.context.annotation.ComponentScan.Filter;
import org.springframework.context.annotation.FilterType;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.reactive.server.WebTestClient;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The standalone single-issuer path with real tokens: its {@code jwk-set-uri} decoder
 * holds the issuer to {@code CASDOOR_ISSUER_URI} when that is set, and a stack that
 * leaves it unset keeps working. The unset case is the one the obvious fix breaks: an
 * empty {@code issuer-uri} property makes Boot demand {@code iss == ""}.
 *
 * <p>An authenticated unmapped path answers 404, so a status of 401 can only come from
 * the decoder.
 */
class SingleIssuerTokenValidationTest {

    private static final String GAMES_ISS = "https://id.games.example";
    private static final RealIssuer GAMES = new RealIssuer(GAMES_ISS);
    private static final RealIssuer ROGUE = new RealIssuer("https://id.rogue.example");

    @WebFluxTest(controllers = DebugModeController.class,
            properties = {"casdoor.endpoint=http://127.0.0.1:9",
                    "casdoor.client-id=test", "casdoor.client-secret=test"},
            excludeFilters = @Filter(type = FilterType.ASSIGNABLE_TYPE, classes = {
                    BanGateFilter.class, DomainGuardFilter.class,
                    RateLimitFilter.class, SecurityHeadersFilter.class}))
    @Import({SecurityConfig.class, SecuritySliceSupport.TenantBeans.class})
    abstract static class Slice {

        @Autowired WebTestClient web;
        @MockitoBean DebugModeService debug;

        @DynamicPropertySource
        static void jwks(DynamicPropertyRegistry r) {
            r.add("spring.security.oauth2.resourceserver.jwt.jwk-set-uri", GAMES::jwksUri);
        }

        int status(String token) {
            return web.get().uri("/api/nothing-here")
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                    .exchange().returnResult(byte[].class).getStatus().value();
        }
    }

    @Nested
    @TestPropertySource(properties = "casdoor.issuer-uri=" + GAMES_ISS)
    class WithAnIssuerSet extends Slice {

        @Test
        void itsOwnTokenIsAccepted() {
            assertThat(status(GAMES.token("g-player", "player"))).isEqualTo(404);
        }

        @Test
        void aForeignIssuerIsRefused_evenWithTheRightKey() {
            assertThat(status(GAMES.tokenClaiming("https://id.mesh.example", "evil", "player"))).isEqualTo(401);
        }

        @Test
        void aForeignKeyIsRefused() {
            assertThat(status(ROGUE.tokenClaiming(GAMES_ISS, "evil", "player"))).isEqualTo(401);
        }
    }

    @Nested
    class WithNoIssuerSet extends Slice {

        @Test
        void aValidTokenIsStillAccepted() {
            assertThat(status(GAMES.token("g-player", "player"))).isEqualTo(404);
        }

        @Test
        void aForeignKeyIsStillRefused() {
            assertThat(status(ROGUE.token("evil", "player"))).isEqualTo(401);
        }
    }
}
