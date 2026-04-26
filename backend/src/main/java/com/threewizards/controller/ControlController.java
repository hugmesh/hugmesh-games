package com.threewizards.controller;

import com.threewizards.model.ControlEvent;
import com.threewizards.service.GameStateService;
import com.threewizards.service.SessionService;
import com.threewizards.service.SpellService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;

@Tag(name = "Controls", description = "Submit player input. Only the session holding the controller role has its events applied.")
@RestController
@RequestMapping("/api/controls")
public class ControlController {

    private final GameStateService gameStateService;
    private final SessionService sessionService;
    private final SpellService spellService;

    public ControlController(GameStateService gameStateService,
                             SessionService sessionService,
                             SpellService spellService) {
        this.gameStateService = gameStateService;
        this.sessionService   = sessionService;
        this.spellService     = spellService;
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
            @AuthenticationPrincipal Jwt jwt,
            @Parameter(description = "Legacy fallback — Casdoor user subject. Prefer Authorization: Bearer <jwt>; this header is only honoured when no JWT is present.")
            @RequestHeader(value = "X-Casdoor-Id", required = false) String casdoorIdHeader) {

        // JWT.sub is the authoritative caller identity (signed, can't be
        // spoofed). The X-Casdoor-Id header is the legacy fallback for
        // the Godot fetch path (game_bridge.gd's _twMovementQueue → POST)
        // which doesn't yet propagate Authorization. Once that bridge
        // sends Authorization too, this fallback retires.
        String casdoorId = jwt != null ? jwt.getSubject()
                : (casdoorIdHeader != null && !casdoorIdHeader.isBlank() ? casdoorIdHeader : null);

        if (casdoorId != null && !sessionService.isController(casdoorId)) {
            return Mono.error(new ResponseStatusException(HttpStatus.FORBIDDEN, "Not the current controller"));
        }

        // Spell cast: drain MP first; only emit the movement event if the cast
        // was affordable. Out-of-MP casts return 204 with no SSE side effects.
        String action = event.action();
        if (event.pressed() && action != null && action.startsWith("CAST_SPELL_")) {
            int slot;
            try { slot = Integer.parseInt(action.substring("CAST_SPELL_".length())); }
            catch (NumberFormatException nfe) { slot = -1; }
            if (slot >= 1 && slot <= 5) {
                final int finalSlot = slot;
                return spellService.castSpell(characterId, finalSlot)
                        .doOnNext(success -> {
                            if (success) gameStateService.applyControl(event, characterId);
                        })
                        .thenReturn(ResponseEntity.<Void>noContent().build());
            }
        }

        gameStateService.applyControl(event, characterId);
        return Mono.just(ResponseEntity.<Void>noContent().build());
    }
}
