package games.hugmesh.bff.controller;

import games.hugmesh.bff.model.WasmConsoleLine;
import games.hugmesh.bff.service.DebugModeService;
import games.hugmesh.bff.service.GameStateService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;

import java.time.Instant;

/**
 * Diagnostics ingest from the Godot WASM iframe.
 *
 * <p>The console-line forwarder accepts captured browser-side
 * {@code console.error} / {@code console.warn} / {@code window.onerror}
 * lines and fans them out on the {@code wasm-console} SSE channel.
 *
 * <p><strong>Authorization:</strong> two gates — both must pass.
 * <ol>
 *   <li>The JWT must carry {@code ROLE_GAMES_ADMIN}. Non-admin players
 *       receive *403* and never get their iframe console captured.</li>
 *   <li>{@link DebugModeService#isWasmConsoleEnabled()} must be true.
 *       The default is OFF; an admin flips it on via
 *       {@code PUT /api/admin/debug-mode/wasm-console} only while
 *       triaging an iframe issue, then off again.</li>
 * </ol>
 *
 * <p>This is deliberately conservative: a busy production stack with
 * always-on console capture would balloon the audit surface and pull
 * potentially-sensitive iframe state through the SSE fan-out. The
 * admin-debug-mode gate keeps the surface closed by default.
 */
@Tag(name = "Diagnostics",
     description = "Forwarded WASM console lines. Admin-only and gated on the wasm-console debug-mode toggle.")
@RestController
@RequestMapping("/api/diagnostics")
@PreAuthorize("hasRole('GAMES_ADMIN')")
public class DiagnosticsController {

    private final GameStateService state;
    private final DebugModeService debugMode;

    public DiagnosticsController(GameStateService state, DebugModeService debugMode) {
        this.state     = state;
        this.debugMode = debugMode;
    }

    @Operation(
        summary = "Forward one WASM console line to the SSE wasm-console channel.",
        description = """
            Requires `ROLE_GAMES_ADMIN`. Additionally requires
            `wasm_console_debug_enabled = true` (admin flips at
            `PUT /api/admin/debug-mode/wasm-console`). Both gates must
            pass; otherwise *403*.
            """)
    @PostMapping("/wasm-console")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public Mono<WasmConsoleLine> wasmConsole(
            @RequestBody WasmConsoleLine body,
            @AuthenticationPrincipal Jwt jwt) {
        if (!debugMode.isWasmConsoleEnabled()) {
            return Mono.error(new ResponseStatusException(
                    HttpStatus.FORBIDDEN,
                    "wasm-console debug mode is disabled; an admin must enable it at /api/admin/debug-mode/wasm-console"));
        }
        WasmConsoleLine stamped = body.withTimestamp(Instant.now());
        state.emitWasmConsole(stamped);
        return Mono.just(stamped);
    }
}
