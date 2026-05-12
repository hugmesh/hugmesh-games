package games.snapdragon.bff.audit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.netty.DisposableServer;
import reactor.netty.http.server.HttpServer;
import reactor.test.StepVerifier;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link PlayerStateRecoveryService}. Uses a reactor-
 * netty {@link DisposableServer} as an in-process HTTP stand-in for
 * ClickHouse so the test doesn't need a running broker + warehouse —
 * just verifies the SQL we send, the URL params we encode, and how
 * we parse the JSONEachRow response.
 *
 * <p>Reactor Netty is already on the classpath via WebFlux; no extra
 * test-only dependency is needed (which is why this file uses it
 * rather than okhttp's MockWebServer or Spring's MockWebServerExtension).
 */
class PlayerStateRecoveryServiceTest {

    private DisposableServer server;
    private PlayerStateRecoveryService recovery;
    private final AtomicReference<String> lastBody = new AtomicReference<>();
    private final AtomicReference<String> lastQuery = new AtomicReference<>();
    private final AtomicReference<Function<String, String>> responder = new AtomicReference<>();

    @BeforeEach
    void setUp() {
        // Stand up an in-process HTTP server on an ephemeral port. The
        // handler captures the request body + query string and returns
        // whatever the per-test `responder` function produces.
        server = HttpServer.create()
                .port(0)
                .handle((req, res) -> {
                    lastQuery.set(req.uri());
                    return req.receive().aggregate().asString(StandardCharsets.UTF_8)
                            .defaultIfEmpty("")
                            .flatMap(body -> {
                                lastBody.set(body);
                                Function<String, String> r = responder.get();
                                String response = r == null ? "" : r.apply(body);
                                if (response.startsWith("__HTTP_500__")) {
                                    return res.status(500)
                                            .sendString(reactor.core.publisher.Mono.just("server error"))
                                            .then();
                                }
                                return res.header("Content-Type", "application/json")
                                        .sendString(reactor.core.publisher.Mono.just(response))
                                        .then();
                            });
                })
                .bindNow();

        WebClient client = WebClient.builder()
                .baseUrl("http://localhost:" + server.port())
                .defaultHeader(HttpHeaders.AUTHORIZATION,
                        "Basic " + Base64.getEncoder().encodeToString("default:".getBytes()))
                .defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.TEXT_PLAIN_VALUE)
                .build();
        recovery = new PlayerStateRecoveryService(client, "snapdragon.player_state_current");
    }

    @AfterEach
    void tearDown() {
        if (server != null) server.disposeNow();
    }

    @Test
    void recoverParsesPositionFromJSONEachRow() {
        responder.set(body -> """
                {"payload_json":"{\\"characterId\\":\\"1\\",\\"x\\":2.5,\\"y\\":0.0,\\"z\\":-3.0,\\"rotationY\\":1.5}"}
                """);

        StepVerifier.create(recovery.recover("three-wizards", "sess-A", 1L))
                .assertNext(rec -> {
                    assertThat(rec.posX()).isEqualTo(2.5);
                    assertThat(rec.posY()).isEqualTo(0.0);
                    assertThat(rec.posZ()).isEqualTo(-3.0);
                    assertThat(rec.rotationY()).isEqualTo(1.5);
                })
                .verifyComplete();

        assertThat(lastQuery.get()).contains("param_gameSlug=three-wizards");
        assertThat(lastQuery.get()).contains("param_sessionId=sess-A");
        assertThat(lastQuery.get()).contains("param_characterId=1");
        assertThat(lastBody.get()).contains("FROM snapdragon.player_state_current FINAL");
        assertThat(lastBody.get()).contains("channel = 'movement'");
    }

    @Test
    void recoverReturnsEmptyOnNoRow() {
        responder.set(body -> "");

        StepVerifier.create(recovery.recover("three-wizards", "sess-Q", 42L))
                .verifyComplete();
    }

    @Test
    void recoverReturnsEmptyOnHttp500() {
        responder.set(body -> "__HTTP_500__");

        StepVerifier.create(recovery.recover("three-wizards", "sess-Q", 42L))
                .verifyComplete();
    }

    @Test
    void recoverReturnsEmptyOnBlankInputs() {
        // No HTTP call should be made when caller can't disambiguate
        // which row to fetch — defensive check at the entry. The
        // responder is intentionally left unset; if it were called,
        // `responder.get()` would be null but the handler returns
        // "" in that case, which would still verify-complete; we
        // assert query==null instead to prove no request fired.
        lastQuery.set(null);
        StepVerifier.create(recovery.recover(null, "sess-A", 1L)).verifyComplete();
        StepVerifier.create(recovery.recover("g", "", 1L)).verifyComplete();
        assertThat(lastQuery.get()).isNull();
    }

    @Test
    void recoverReturnsEmptyWhenPayloadMissesPositionFields() {
        responder.set(body -> """
                {"payload_json":"{\\"characterId\\":\\"1\\",\\"action\\":\\"JUMP\\"}"}
                """);

        StepVerifier.create(recovery.recover("three-wizards", "sess-A", 1L))
                .verifyComplete();
    }
}
