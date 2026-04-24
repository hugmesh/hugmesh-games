package com.threewizards.controller;

import com.threewizards.model.ControlEvent;
import com.threewizards.service.GameStateService;
import com.threewizards.service.SessionService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;

@Tag(name = "Controls", description = "Submit player input. Only the session holding the controller role has its events applied.")
@RestController
@RequestMapping("/api/controls")
public class ControlController {

    private final GameStateService gameStateService;
    private final SessionService sessionService;

    public ControlController(GameStateService gameStateService, SessionService sessionService) {
        this.gameStateService = gameStateService;
        this.sessionService   = sessionService;
    }

    @Operation(
        summary = "Submit a control event",
        description = """
            Accepts a movement or action event. If `X-Casdoor-Id` is present,
            the session layer checks whether the caller holds `is_controller = true`.
            Non-controllers receive `403 Forbidden`. Godot sends this header automatically
            from the JWT subject claim.

            ```
            ──request──► sessionService.isController(casdoorId)
                               │
                     ┌─────────┴──────────────┐
                     │ is controller          │ not controller
                     ▼                        ▼
              gameStateService            ──✗  403 Forbidden
              .applyControl(event)
                     │
              Sinks.emitNext(newState)
              (broadcasts to all SSE subscribers)
                     │
              ────────────────────────●─|  204 No Content
            ```

            **Body**

            | field | type | example |
            |---|---|---|
            | `action` | string | `MOVE_FORWARD`, `JUMP`, `CAST_SPELL_1` |
            | `pressed` | boolean | `true` = key-down, `false` = key-up |
            | `timestamp` | long | client epoch-ms |
            """)
    @PostMapping("/{characterId}")
    public Mono<ResponseEntity<Void>> receiveControl(
            @PathVariable Long characterId,
            @RequestBody ControlEvent event,
            @Parameter(description = "Casdoor user subject — omit to skip controller check (dev only)")
            @RequestHeader(value = "X-Casdoor-Id", required = false) String casdoorId) {

        if (casdoorId != null && !casdoorId.isBlank() && !sessionService.isController(casdoorId)) {
            return Mono.error(new ResponseStatusException(HttpStatus.FORBIDDEN, "Not the current controller"));
        }

        gameStateService.applyControl(event, characterId);
        return Mono.just(ResponseEntity.<Void>noContent().build());
    }
}
