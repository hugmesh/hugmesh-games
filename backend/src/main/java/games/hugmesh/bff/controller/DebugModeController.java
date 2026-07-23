package games.hugmesh.bff.controller;

import games.hugmesh.bff.service.DebugModeService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

import java.util.Map;

/**
 * Admin-only debug-mode toggles. Today there's one knob:
 * {@code wasm_console_debug_enabled}, which gates the WASM iframe's
 * console-error ingest into the BFF's diagnostics pipeline. Default OFF;
 * admins flip on briefly while triaging an iframe issue.
 *
 * <p>Players never see this endpoint — even reads require {@code ROLE_ADMIN}.
 */
@Tag(name = "Admin debug mode",
     description = "Admin-only runtime toggles for diagnostics ingest. Default OFF; admins enable briefly while triaging.")
@RestController
@RequestMapping("/api/admin/debug-mode")
@PreAuthorize("hasRole('ADMIN')")
public class DebugModeController {

    private final DebugModeService service;

    public DebugModeController(DebugModeService service) {
        this.service = service;
    }

    public record DebugModeStatus(boolean wasmConsoleEnabled) {}
    public record SetWasmConsoleRequest(boolean enabled) {}

    @Operation(summary = "Read current debug-mode flags")
    @GetMapping
    public Mono<DebugModeStatus> get() {
        return Mono.just(new DebugModeStatus(service.isWasmConsoleEnabled()));
    }

    @Operation(
        summary = "Toggle WASM console capture",
        description = """
            Flip the wasm-console diagnostics gate. When `enabled=false`
            (the default), every POST to `/api/diagnostics/wasm-console`
            is refused with *403* and no captured browser-side errors
            are persisted or fanned out on the SSE channel.
            """)
    @PutMapping("/wasm-console")
    public Mono<Map<String, Object>> setWasmConsole(
            @AuthenticationPrincipal Jwt jwt,
            @RequestBody SetWasmConsoleRequest req) {
        return service.setWasmConsoleEnabled(req.enabled(), jwt.getSubject())
                .map(enabled -> Map.<String, Object>of("wasmConsoleEnabled", enabled));
    }
}
