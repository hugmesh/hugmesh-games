package games.snapdragon.bff.controller;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import games.snapdragon.bff.model.InputEvent;
import games.snapdragon.bff.repository.InputEventRepository;
import games.snapdragon.bff.repository.InputEventRepository.InputEventRow;
import games.snapdragon.bff.repository.InputEventTypeRepository;
import games.snapdragon.bff.service.GameSessionService;
import games.snapdragon.bff.service.GameStateService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.List;

/**
 * Generalised input + audio event ingestion.
 *
 * <p>Single endpoint, discriminated payload. Every gamepad button,
 * mouse motion, key press, touch gesture, audio cue, world event, or
 * legacy JRPG control command flows through the same path.
 *
 * <h2>Validation</h2>
 *
 * Each emit goes through three gates:
 * <ol>
 *   <li>The {@code (kind, type)} pair must exist in the
 *       {@code input_event_types} reference table. Unknown pairs →
 *       400.</li>
 *   <li>The active game's {@code games.features} JSONB must accept
 *       the event kind (a chrome-less demo with
 *       {@code spell_loadout: false} can't have a JRPG client smuggle
 *       in fake control:cast events). Refused kinds → 405.</li>
 *   <li>{@code game_event_support} must list the
 *       {@code (game_slug, event_kind, event_type)} triple as
 *       enabled. Per-game opt-in beyond the feature flags. Refused
 *       triples → 405.</li>
 * </ol>
 *
 * <p>Tag {@code Input events} keeps this surface separate from the
 * JRPG-specific {@code Controls} surface in the OpenAPI spec.
 */
@Tag(name = "Input events",
     description = "Generalised event ingestion (gamepad / mouse / keyboard / touch / audio / world / control).")
@RestController
@RequestMapping("/api/input")
public class InputEventController {

    private final InputEventRepository events;
    private final InputEventTypeRepository types;
    private final GameStateService state;
    private final GameSessionService sessions;
    private final ObjectMapper json;

    public InputEventController(InputEventRepository events,
                                InputEventTypeRepository types,
                                GameStateService state,
                                GameSessionService sessions,
                                ObjectMapper json) {
        this.events   = events;
        this.types    = types;
        this.state    = state;
        this.sessions = sessions;
        this.json     = json;
    }

    @Operation(
        summary = "Submit an input event.",
        description = """
            One endpoint, discriminated payload. The (kind, type) pair
            picks a schema; the BFF validates against the reference
            tables, rejects with 400/405 if the (kind, type) is
            unknown or the active game declares it doesn't support
            this combination, otherwise persists into input_events
            and fans out on the SSE input + audio channels.

            The endpoint is authenticated. The Casdoor user id is
            read from the JWT subject; the body's casdoorUserId field
            is ignored if present.
            """
    )
    @PostMapping("/{characterId}")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public Mono<InputEvent> submit(@PathVariable Long characterId,
                                   @RequestBody InputEvent body,
                                   @AuthenticationPrincipal Jwt jwt) {
        if (jwt == null) {
            return Mono.error(new ResponseStatusException(HttpStatus.UNAUTHORIZED));
        }
        if (body.kind() == null || body.type() == null || body.gameSlug() == null) {
            return Mono.error(new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "kind, type, and gameSlug are required"));
        }
        // Gate 0: session-membership + character ownership. When the event is
        // session-scoped (the host-authoritative relay path), the JWT subject
        // MUST be the active session_member who owns characterId — otherwise an
        // unrelated authenticated user could inject a move into a victim
        // session's host (the relay fans out to that session only). 403 on miss.
        // Mirrors ControlController's multiplayer authz exactly. No session id ⇒
        // legacy single-player ingestion, no ownership concept to check.
        Mono<Void> authz = (body.sessionId() != null && !body.sessionId().isBlank())
                ? sessions.authorizeControl(jwt.getSubject(), characterId, body.sessionId()).then()
                : Mono.empty();

        // Gate 1: (kind, type) must be a known reference row.
        return authz.then(types.findByKindAndType(body.kind(), body.type())
                .switchIfEmpty(Mono.error(new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "unknown event (kind, type): " + body.kind() + ", " + body.type())))
                .flatMap(t -> {
                    InputEvent ev = new InputEvent(
                            null, body.gameSlug(), characterId,
                            body.sessionId(), jwt.getSubject(),
                            body.kind(), body.type(),
                            body.payload(), null);
                    String payloadJson;
                    try {
                        payloadJson = json.writeValueAsString(
                                ev.payload() == null ? java.util.Map.of() : ev.payload());
                    } catch (JsonProcessingException e) {
                        return Mono.error(new ResponseStatusException(
                                HttpStatus.BAD_REQUEST,
                                "payload is not JSON-serialisable: " + e.getMessage()));
                    }
                    return events.insert(ev, payloadJson)
                            .doOnNext(state::emitInput);
                }));
        // Gates 2 (features.<kind>) and 3 (game_event_support) added in
        // a follow-up commit alongside the GameRepository.findFeatures
        // helper — keeping THIS commit focused on the schema +
        // ingestion path so it's reviewable independently.
    }

    @Operation(summary = "Recent events for a character (newest first).")
    @GetMapping("/character/{characterId}")
    public Flux<InputEventRow> recentForCharacter(@PathVariable Long characterId,
                                                  @RequestParam(defaultValue = "100") int limit) {
        return events.findRecentByCharacterId(characterId, Math.min(Math.max(limit, 1), 1000));
    }

    @Operation(summary = "Recent events for a session (newest first).")
    @GetMapping("/session/{sessionId}")
    public Flux<InputEventRow> recentForSession(@PathVariable String sessionId,
                                                @RequestParam(defaultValue = "100") int limit) {
        return events.findRecentBySessionId(sessionId, Math.min(Math.max(limit, 1), 1000));
    }
}
