package games.hugmesh.bff.config;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.web.cors.reactive.CorsUtils;
import org.springframework.web.reactive.function.server.RouterFunctions;
import org.springframework.web.reactive.function.server.ServerResponse;
import org.springframework.web.server.adapter.ForwardedHeaderTransformer;

import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.web.reactive.function.server.RequestPredicates.POST;

/**
 * GUI-02: Play answered 403 on every mesh door except https://localhost:8488.
 *
 * <p>The launch POST is same-origin for the browser, but the BFF is addressed as
 * {@code http://hugmesh-games-bff:8080}, so the CORS filter judged
 * {@code Origin: https://hugmesh.net} against the allowlist and refused it.
 * {@link #capturedGatewayHeadersAreSameOrigin()} replays, verbatim, the headers the BFF
 * received from the fe-gateway during a real Play click through {@code make view}
 * (captured with tcpdump in the BFF's network namespace, 2026-11-11). It answers 403
 * without {@link WebConfig.ForwardedSameOriginCorsProcessor}.
 *
 * <p>The rest pin that nothing ELSE got through: a foreign origin, a scheme or port
 * mismatch, a forged or opaque origin and a preflight all still meet the allowlist.
 */
class WebConfigForwardedSameOriginTest {

    private static final String BFF = "http://hugmesh-games-bff:8080";
    private static final String LAUNCH = "/api/games/chess/launch";

    private WebTestClient client;

    @BeforeEach
    void setUp() {
        WebConfig config = new WebConfig();
        ReflectionTestUtils.setField(config, "allowedOriginsCsv", "");
        client = WebTestClient
                .bindToRouterFunction(RouterFunctions.route(POST(LAUNCH), req -> ServerResponse.ok().build()))
                .webFilter(config.corsWebFilter())
                .configureClient()
                .baseUrl(BFF)
                .build();
    }

    /** The fe-gateway's real header set: outer hop first, then its own (http, port 80). */
    private static Consumer<HttpHeaders> gateway(String proto, String host) {
        return h -> {
            h.add("X-Forwarded-Proto", proto + ",http");
            h.add("X-Forwarded-Host", host + "," + host);
            h.add("X-Forwarded-Port", "80");
            h.add("X-Forwarded-Prefix", "/games");
            h.add("X-Forwarded-For", "172.19.0.1,172.19.0.53");
            h.add("Forwarded", "proto=http;host=" + host + ";for=\"172.19.0.53:57444\"");
        };
    }

    private WebTestClient.ResponseSpec launch(String origin, Consumer<HttpHeaders> forwarded) {
        return client.post().uri(LAUNCH)
                .header(HttpHeaders.ORIGIN, origin)
                .headers(forwarded)
                .exchange();
    }

    private static Consumer<HttpHeaders> none() {
        return h -> { };
    }

    @Test
    void capturedGatewayHeadersAreSameOrigin() {
        launch("https://hugmesh.net", gateway("https", "hugmesh.net"))
                .expectStatus().isOk()
                .expectHeader().doesNotExist(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN)
                .expectHeader().valuesMatch(HttpHeaders.VARY,
                        "Origin", "Access-Control-Request-Method", "Access-Control-Request-Headers");
    }

    @Test
    void aRequestWithoutAnOriginThroughTheGatewayIsUntouched() {
        // A browser sends no Origin on a same-origin GET; most calls look like this.
        client.post().uri(LAUNCH).headers(gateway("https", "hugmesh.net")).exchange()
                .expectStatus().isOk();
    }

    @Test
    void lanNameOnANonDefaultPortIsSameOrigin() {
        launch("https://mesh.lan:8488", gateway("https", "mesh.lan:8488")).expectStatus().isOk();
    }

    @Test
    void plainHttpOnionIsSameOrigin() {
        String onion = "abcdefghijklmnopqrstuvwxyzabcdefghijklmnopqrstuvwxyz234567.onion";
        launch("http://" + onion, gateway("http", onion)).expectStatus().isOk();
    }

    @Test
    void hostAndSchemeCompareCaseInsensitively() {
        launch("https://hugmesh.net", gateway("HTTPS", "HugMesh.NET")).expectStatus().isOk();
    }

    @Test
    void explicitDefaultPortMatchesTheBareOrigin() {
        launch("https://hugmesh.net", gateway("https", "hugmesh.net:443")).expectStatus().isOk();
    }

    @Test
    void aForeignOriginBehindTheSameGatewayIsStillRefused() {
        launch("https://evil.example", gateway("https", "hugmesh.net")).expectStatus().isForbidden();
    }

    @Test
    void schemeMismatchIsStillRefused() {
        // Same explicit port on both sides, so only the scheme can tell them apart.
        launch("http://mesh.lan:8488", gateway("https", "mesh.lan:8488")).expectStatus().isForbidden();
    }

    @Test
    void portMismatchIsStillRefused() {
        launch("https://hugmesh.net:8443", gateway("https", "hugmesh.net")).expectStatus().isForbidden();
    }

    @Test
    void onlyTheLeftmostForwardedHostCounts() {
        launch("https://hugmesh.net", h -> {
            h.add("X-Forwarded-Proto", "https");
            h.add("X-Forwarded-Host", "evil.example,hugmesh.net");
        }).expectStatus().isForbidden();
    }

    @Test
    void anOpaqueOriginIsStillRefused() {
        launch("null", gateway("https", "hugmesh.net")).expectStatus().isForbidden();
    }

    @Test
    void aForwardedHostCarryingMoreThanAnAuthorityIsNotTrusted() {
        launch("https://hugmesh.net", h -> {
            h.add("X-Forwarded-Proto", "https");
            h.add("X-Forwarded-Host", "hugmesh.net/x");
        }).expectStatus().isForbidden();
    }

    @Test
    void withoutForwardedHeadersAnOriginOffTheAllowlistIsStillRefused() {
        launch("https://hugmesh.net", none()).expectStatus().isForbidden();
    }

    @Test
    void anAllowlistedOriginStillGetsCorsHeaders() {
        launch("https://localhost:8488", none())
                .expectStatus().isOk()
                .expectHeader().valueEquals(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, "https://localhost:8488")
                .expectHeader().valueEquals(HttpHeaders.ACCESS_CONTROL_ALLOW_CREDENTIALS, "true");
    }

    /**
     * Why the fix is not {@code server.forward-headers-strategy: framework}: Spring's own
     * forwarded-header handling prefers the gateway's {@code Forwarded: proto=http} (and,
     * without it, takes {@code X-Forwarded-Port: 80}), so the rewritten request still
     * reads as cross-origin. If this ever starts failing, that simpler fix may work.
     */
    @Test
    void forwardHeadersStrategyFrameworkWouldNotHaveFixedIt() {
        MockServerHttpRequest request = MockServerHttpRequest.post(BFF + LAUNCH)
                .header(HttpHeaders.ORIGIN, "https://hugmesh.net")
                .headers(httpHeaders(gateway("https", "hugmesh.net")))
                .build();
        ServerHttpRequest rewritten = new ForwardedHeaderTransformer().apply(request);
        assertThat(CorsUtils.isCorsRequest(rewritten)).isTrue();
        assertThat(rewritten.getURI().toString()).startsWith("http://hugmesh.net/");
    }

    private static HttpHeaders httpHeaders(Consumer<HttpHeaders> fill) {
        HttpHeaders h = new HttpHeaders();
        fill.accept(h);
        return h;
    }

    @Test
    void aPreflightIsStillJudgedByTheAllowlist() {
        client.options().uri(LAUNCH)
                .header(HttpHeaders.ORIGIN, "https://hugmesh.net")
                .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "POST")
                .headers(gateway("https", "hugmesh.net"))
                .exchange()
                .expectStatus().isForbidden();
    }
}
