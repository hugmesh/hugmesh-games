package games.snapdragon.bff.controller;

import games.snapdragon.bff.model.WasmConsoleLine;
import games.snapdragon.bff.service.GameStateService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;

import java.time.Instant;

/**
 * Diagnostics ingest from the Godot WASM iframe.
 *
 * <p>Today the only endpoint is a console-line forwarder: the
 * wasm-fragment Godot bundle wraps {@code console.error} /
 * {@code console.warn} / {@code window.onerror} /
 * {@code window.unhandledrejection} and posts captured payloads to
 * its parent window via {@code postMessage}. The host catches them
 * and POSTs here; the BFF stamps a server timestamp and fans out on
 * the {@code wasm-console} SSE channel.
 *
 * <p>Future channels (wasm-perf, wasm-frame-times, etc.) will land
 * under the same tag.
 */
@Tag(name = "Diagnostics",
     description = "Forwarded WASM console + future telemetry channels.")
@RestController
@RequestMapping("/api/diagnostics")
public class DiagnosticsController {

    private final GameStateService state;

    public DiagnosticsController(GameStateService state) {
        this.state = state;
    }

    @Operation(summary = "Forward one WASM console line to the SSE wasm-console channel.")
    @PostMapping("/wasm-console")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public Mono<WasmConsoleLine> wasmConsole(@RequestBody WasmConsoleLine body,
                                             @AuthenticationPrincipal Jwt jwt) {
        // Anonymous WASM-console posts are accepted because a crash
        // mid-page-bootstrap can fire before the JWT is in
        // sessionStorage; we still want those lines.
        WasmConsoleLine stamped = body.withTimestamp(Instant.now());
        state.emitWasmConsole(stamped);
        return Mono.just(stamped);
    }
}
