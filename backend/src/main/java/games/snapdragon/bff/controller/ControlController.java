package games.snapdragon.bff.controller;

import games.snapdragon.bff.model.ControlEvent;
import games.snapdragon.bff.service.GameSessionService;
import games.snapdragon.bff.service.GameStateService;
import games.snapdragon.bff.service.InstantService;
import games.snapdragon.bff.service.SessionService;
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

@Tag(name = "Controls",
     description = "Submit player input. Multiplayer: when X-Session-Id is supplied, every active session_member may write controls for the character they own. Legacy single-session path retained for non-multiplayer clients.")
@RestController
@RequestMapping("/api/controls")
public class ControlController {

    private final GameStateService gameStateService;
    private final SessionService sessionService;
    private final GameSessionService gameSessionService;
    private final InstantService instantService;

    public ControlController(GameStateService gameStateService,
                             SessionService sessionService,
                             GameSessionService gameSessionService,
                             InstantService instantService) {
        this.gameStateService   = gameStateService;
        this.sessionService     = sessionService;
        this.gameSessionService = gameSessionService;
        this.instantService     = instantService;
    }

    @Operation(
        summary = "Submit a control event",
        description = """
            Two authorization modes — pick by whether `X-Session-Id` is present.

            **Multiplayer mode** (X-Session-Id supplied). Every active
            `session_members` row may concurrently POST controls for the
            character they own; the JWT subject must match the row's
            `casdoor_id`. The control fans out on the per-session SSE sinks
            so subscribers of `/api/game/stream?sessionId=X` see only their
            session's updates. Returns *403* if the caller is not the
            session_member who owns `characterId`.

            **Legacy single-session mode** (no X-Session-Id). The caller
            must hold the global controller role checked by
            `SessionService.isController(casdoorId)`. Three-wizards
            cave/field single-player retains this path.

            ```
            ──request + X-Session-Id?──►
                          │
                yes ──► gameSessionService.authorizeControl(jwt.sub, characterId)
                          │ ok    │ no
                          ▼       ▼
                applyControl    403
                (event, cid, sid)
                          │
                no  ──► sessionService.isController(jwt.sub)
                          │ true  │ false
                          ▼       ▼
                applyControl    403
                (event, cid)
            ```

            **Body**

            | field | type | example |
            |---|---|---|
            | `action` | string | `MOVE_FORWARD`, `JUMP`, `CAST_INSTANT_1` |
            | `pressed` | boolean | `true` = key-down, `false` = key-up |
            | `timestamp` | long | client epoch-ms |
            | `chapterId` | long? | optional — explicit chapter the move applies to |
            """)
    @PostMapping("/{characterId}")
    public Mono<ResponseEntity<Void>> receiveControl(
            @PathVariable Long characterId,
            @RequestBody ControlEvent event,
            @AuthenticationPrincipal Jwt jwt,
            @Parameter(description = "Multiplayer session id. When present, authz uses session_members ownership instead of the global single-session controller role.")
            @RequestHeader(value = "X-Session-Id", required = false) String sessionIdHeader,
            @Parameter(description = "Legacy fallback — Casdoor user subject. Prefer Authorization: Bearer <jwt>; this header is only honoured when no JWT is present.")
            @RequestHeader(value = "X-Casdoor-Id", required = false) String casdoorIdHeader) {

        String casdoorId = jwt != null ? jwt.getSubject()
                : (casdoorIdHeader != null && !casdoorIdHeader.isBlank() ? casdoorIdHeader : null);

        // Multiplayer mode: authorize via session_members.
        if (sessionIdHeader != null && !sessionIdHeader.isBlank()) {
            if (casdoorId == null) {
                return Mono.error(new ResponseStatusException(HttpStatus.UNAUTHORIZED, "JWT required for multiplayer controls"));
            }
            String sid = sessionIdHeader;
            return gameSessionService.authorizeControl(casdoorId, characterId, sid)
                    .flatMap(member -> dispatch(event, characterId, sid));
        }

        // Legacy single-session mode.
        if (casdoorId != null && !sessionService.isController(casdoorId)) {
            return Mono.error(new ResponseStatusException(HttpStatus.FORBIDDEN, "Not the current controller"));
        }
        return dispatch(event, characterId, null);
    }

    private Mono<ResponseEntity<Void>> dispatch(ControlEvent event, Long characterId, String sessionIdOpt) {
        String action = event.action();
        // Spell cast: drain MP first; only emit the movement event if the cast
        // was affordable. Out-of-MP casts return 204 with no SSE side effects.
        if (event.pressed() && action != null && action.startsWith("CAST_INSTANT_")) {
            int slot;
            try { slot = Integer.parseInt(action.substring("CAST_INSTANT_".length())); }
            catch (NumberFormatException nfe) { slot = -1; }
            if (slot >= 1 && slot <= 5) {
                final int finalSlot = slot;
                return instantService.castInstant(characterId, finalSlot)
                        .doOnNext(success -> {
                            if (success) gameStateService.applyControl(event, characterId, sessionIdOpt);
                        })
                        .thenReturn(ResponseEntity.<Void>noContent().build());
            }
        }
        gameStateService.applyControl(event, characterId, sessionIdOpt);
        return Mono.just(ResponseEntity.<Void>noContent().build());
    }
}
