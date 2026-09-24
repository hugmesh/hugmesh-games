package games.hugmesh.bff.controller;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import games.hugmesh.bff.model.InputEvent;
import games.hugmesh.bff.model.SessionMember;
import games.hugmesh.bff.repository.InputEventRepository;
import games.hugmesh.bff.repository.InputEventRepository.InputEventRow;
import games.hugmesh.bff.repository.InputEventTypeRepository;
import games.hugmesh.bff.repository.InputGateLookup;
import games.hugmesh.bff.service.GameSessionService;
import games.hugmesh.bff.service.GameStateService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.Map;

/**
 * Generalised input + audio event ingestion.
 *
 * <p>Single endpoint, discriminated payload. Every gamepad button,
 * mouse motion, key press, touch gesture, audio cue, world event, or
 * legacy JRPG control command flows through the same path.
 *
 * <h2>Validation</h2>
 *
 * Every emit passes these gates, in this order, before it is persisted or
 * fanned out (hugmesh-games#13, decisions 103a = C, 103b = A, 103c = A):
 * <ol start="0">
 *   <li><b>Gate 0 — authorization.</b> A session-scoped event must come from
 *       the active member who owns {@code characterId}; a session-scoped
 *       {@code kind:'world'} must come from the session creator. 403.</li>
 *   <li><b>Gate 1 — vocabulary.</b> The {@code (kind, type)} pair must exist in
 *       {@code input_event_types}. 400.</li>
 *   <li><b>Gate 2 — feature flags, two lookups in the same {@code
 *       games.features} object.</b>
 *       <ol type="a">
 *         <li>Every event must pass its KIND's flag: {@code
 *             games.features.<kind>} must be JSON {@code true}. An ABSENT flag
 *             is refused, exactly like {@code false} — there is no implicit
 *             allow. Changeset 063 seeded {@code world} and {@code control}
 *             (the two relay kinds 026-1 had no flag for) true on every game
 *             and into the column default, so this refuses nothing a shipped
 *             title sends.</li>
 *         <li>Then a short, explicit {@code (kind, type) → flag} table,
 *             {@link #SUBSYSTEM_FLAGS}, gates individual events on a JRPG
 *             subsystem flag as well. Today it has one row: {@code
 *             control:cast → instant_loadout}, so a chrome-less demo with
 *             {@code instant_loadout: false} cannot have a JRPG client smuggle
 *             in {@code control:cast} while its other control events
 *             pass. ({@code instant_loadout} is the key 028-7 renamed from
 *             {@code spell_loadout}; 063 finished that rename in the rows and
 *             the default.)</li>
 *       </ol>
 *       Refused → 405.</li>
 *   <li><b>Gate 3 — per-game opt-in, present but OFF.</b> When {@code
 *       app.input.gate3-enabled} is true, the {@code (gameSlug, kind, type)}
 *       triple must be an enabled {@code game_event_support} row; 405
 *       otherwise. It defaults to false and must stay false until every
 *       shipped title's rows are seeded (decision 103c = A): on origin/main
 *       only three-wizards and dodecahedron have input rows, eleven titles
 *       have relay rows only, and several have none, so turning it on today
 *       would silently refuse real input.</li>
 * </ol>
 *
 * <p>Gates 2 and 3 read through {@link InputGateLookup}, which caches both
 * per slug for a few seconds — see its Javadoc for the invalidation story.
 * docs/wiki/architecture/04-data-model.md ("Generalised input event surface")
 * states the same rule; change both together.
 *
 * <p>Tag {@code Input events} keeps this surface separate from the
 * JRPG-specific {@code Controls} surface in the OpenAPI spec.
 */
@Tag(name = "Input events",
     description = "Generalised event ingestion (gamepad / mouse / keyboard / touch / audio / world / control).")
@RestController
@RequestMapping("/api/input")
public class InputEventController {

    /**
     * Gate 2b: individual events that ALSO need a subsystem flag, keyed
     * {@code "kind:type"}. Short and explicit on purpose — a row here is a
     * product statement that the event belongs to that subsystem. Every event
     * still has to pass its kind's flag first (gate 2a).
     */
    static final Map<String, String> SUBSYSTEM_FLAGS = Map.of(
            "control:cast", "instant_loadout");

    private final InputEventRepository events;
    private final InputEventTypeRepository types;
    private final InputGateLookup gates;
    private final GameStateService state;
    private final GameSessionService sessions;
    private final ObjectMapper json;
    private final boolean gate3Enabled;

    public InputEventController(InputEventRepository events,
                                InputEventTypeRepository types,
                                InputGateLookup gates,
                                GameStateService state,
                                GameSessionService sessions,
                                ObjectMapper json,
                                @Value("${app.input.gate3-enabled:false}") boolean gate3Enabled) {
        this.events       = events;
        this.types        = types;
        this.gates        = gates;
        this.state        = state;
        this.sessions     = sessions;
        this.json         = json;
        this.gate3Enabled = gate3Enabled;
    }

    @Operation(
        summary = "Submit an input event.",
        description = """
            One endpoint, discriminated payload. The (kind, type) pair
            picks a schema. The BFF rejects with 400 if the (kind, type)
            is unknown, and with 405 if the game's games.features flag
            for the event kind is false or absent, or (for events such
            as control:cast) the subsystem flag the event belongs to is.
            Otherwise it persists into input_events and fans out on the
            SSE input + audio channels.

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
        //
        // Gate 0b (authority-spoofing defence): a session-scoped kind:'world'
        // event is the AUTHORITATIVE relay — it carries the host's canonical
        // engine state (e.g. chess FEN) that every guest re-applies verbatim. Only
        // the session CREATOR runs the authoritative wasm engine, so only the
        // creator may post kind:'world'. Without this gate any ordinary member
        // (a guest) could POST a forged kind:'world' payload and rewrite every
        // other player's world — a full authority spoof. We KEEP the SessionMember
        // authorizeControl returns (not .then() it away) precisely so isCreator()
        // is available here. Non-world kinds are unaffected; global (no-session)
        // posts have no creator concept and stay unchanged.
        Mono<Void> authz = (body.sessionId() != null && !body.sessionId().isBlank())
                ? sessions.authorizeControl(jwt.getSubject(), characterId, body.sessionId())
                        .flatMap(member -> "world".equals(body.kind()) && !member.isCreator()
                                ? Mono.<SessionMember>error(new ResponseStatusException(HttpStatus.FORBIDDEN,
                                        "only the session creator may submit authoritative world events"))
                                : Mono.just(member))
                        .then()
                : Mono.empty();

        // Gate 1: (kind, type) must be a known reference row.
        return authz.then(types.findByKindAndType(body.kind(), body.type())
                .switchIfEmpty(Mono.error(new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "unknown event (kind, type): " + body.kind() + ", " + body.type())))
                .flatMap(t -> checkFeatureFlags(body.gameSlug(), body.kind(), body.type()))
                .then(Mono.defer(() -> checkEventSupport(body.gameSlug(), body.kind(), body.type())))
                .then(Mono.defer(() -> {
                    InputEvent ev = new InputEvent(
                            null, body.gameSlug(), characterId,
                            body.sessionId(), jwt.getSubject(),
                            body.kind(), body.type(),
                            body.payload(), null);
                    // fromCallable wraps JsonProcessingException as onError automatically;
                    // onErrorMap then translates it to the 400 response the caller expects,
                    // keeping the error as a publisher signal rather than an in-lambda throw.
                    return Mono.fromCallable(() -> json.writeValueAsString(
                                    ev.payload() == null ? java.util.Map.of() : ev.payload()))
                            .onErrorMap(JsonProcessingException.class, e -> new ResponseStatusException(
                                    HttpStatus.BAD_REQUEST,
                                    "payload is not JSON-serialisable: " + e.getMessage()))
                            .flatMap(payloadJson -> events.insert(ev, payloadJson)
                                    .doOnNext(state::emitInput));
                })));
    }

    /**
     * Gate 2 (decision 103a = C). 2a: {@code features.<kind>} must be JSON
     * true — absent is refused. 2b: if {@code (kind, type)} is in
     * {@link #SUBSYSTEM_FLAGS}, that flag must be true as well.
     */
    private Mono<Void> checkFeatureFlags(String slug, String kind, String type) {
        // defaultIfEmpty: an EMPTY signal must read as "no flags" (refused),
        // never as "nothing to check" — otherwise the .then() chain in
        // submit() would carry straight on to persistence (fail-open).
        return gates.features(slug).defaultIfEmpty(Map.of()).flatMap(flags -> {
            if (!Boolean.TRUE.equals(flags.get(kind))) {
                return Mono.error(refused("game '" + slug + "' does not enable event kind '"
                        + kind + "' (games.features." + kind + " is false or absent)"));
            }
            String subsystem = SUBSYSTEM_FLAGS.get(kind + ":" + type);
            if (subsystem != null && !Boolean.TRUE.equals(flags.get(subsystem))) {
                return Mono.error(refused("game '" + slug + "' does not enable " + kind + ":" + type
                        + " (games.features." + subsystem + " is false or absent)"));
            }
            return Mono.<Void>empty();
        });
    }

    /**
     * Gate 3 (decision 103c = A): present but OFF by default. With
     * {@code app.input.gate3-enabled=true} the triple must be an enabled
     * {@code game_event_support} row. Do not enable it until every shipped
     * title's rows are seeded — see the class Javadoc.
     */
    private Mono<Void> checkEventSupport(String slug, String kind, String type) {
        if (!gate3Enabled) {
            return Mono.empty();
        }
        return gates.enabledEvents(slug).defaultIfEmpty(java.util.Set.of())
                .flatMap(enabled -> enabled.contains(kind + ":" + type)
                ? Mono.<Void>empty()
                : Mono.error(refused("game '" + slug + "' has no enabled game_event_support row for "
                        + kind + ":" + type)));
    }

    private static ResponseStatusException refused(String reason) {
        return new ResponseStatusException(HttpStatus.METHOD_NOT_ALLOWED, reason);
    }

    @Operation(summary = "Recent events for a character (newest first).")
    @GetMapping("/character/{characterId}")
    public Flux<InputEventRow> recentForCharacter(@PathVariable Long characterId,
                                                  @RequestParam(defaultValue = "100") int limit,
                                                  @AuthenticationPrincipal Jwt jwt) {
        // 401 on no auth, then prove the caller currently OWNS characterId.
        // Without this gate an unauthenticated / unrelated caller could read
        // ANY character's recent input history — an IDOR that de-anonymises the
        // owning Casdoor subject and leaks event payloads. authorizeControl
        // (sessionId=null ⇒ findActiveOwnerOfCharacter) is the same ownership
        // check submit() runs; it 403s a caller who does not own the character.
        if (jwt == null) {
            return Flux.error(new ResponseStatusException(HttpStatus.UNAUTHORIZED));
        }
        // Flux.defer so the history query is only ASSEMBLED once the ownership gate
        // passes — a denied request short-circuits without touching the repository.
        return sessions.authorizeControl(jwt.getSubject(), characterId, null)
                .thenMany(Flux.defer(() ->
                        events.findRecentByCharacterId(characterId, Math.min(Math.max(limit, 1), 1000))));
    }

    @Operation(summary = "Recent events for a session (newest first).")
    @GetMapping("/session/{sessionId}")
    public Flux<InputEventRow> recentForSession(@PathVariable String sessionId,
                                                @RequestParam(defaultValue = "100") int limit,
                                                @AuthenticationPrincipal Jwt jwt) {
        // 401 on no auth, then prove the caller is an ACTIVE member of the
        // session before exposing its event history. Without this gate any
        // caller who learned a sessionId could read every member's input
        // payloads (cross-session IDOR). activeMembership is empty for a
        // non-member ⇒ 403. Mirrors submit()'s session-scoped authz.
        if (jwt == null) {
            return Flux.error(new ResponseStatusException(HttpStatus.UNAUTHORIZED));
        }
        // Flux.defer so the history query is only ASSEMBLED once membership is proven.
        return sessions.activeMembership(sessionId, jwt.getSubject())
                .switchIfEmpty(Mono.error(new ResponseStatusException(
                        HttpStatus.FORBIDDEN, "not an active member of this session")))
                .thenMany(Flux.defer(() ->
                        events.findRecentBySessionId(sessionId, Math.min(Math.max(limit, 1), 1000))));
    }
}
