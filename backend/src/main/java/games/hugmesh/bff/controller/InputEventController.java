package games.hugmesh.bff.controller;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import games.hugmesh.bff.model.InputEvent;
import games.hugmesh.bff.model.SessionMember;
import games.hugmesh.bff.repository.InputEventRepository;
import games.hugmesh.bff.repository.InputEventRepository.InputEventRow;
import games.hugmesh.bff.repository.InputEventTypeRepository;
import games.hugmesh.bff.service.GameSessionService;
import games.hugmesh.bff.service.GameStateService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Generalised input + audio event ingestion.
 *
 * <p>Single endpoint, discriminated payload. Every gamepad button,
 * mouse motion, key press, touch gesture, audio cue, world event, or
 * legacy JRPG control command flows through the same path.
 *
 * <h2>Validation</h2>
 *
 * Each emit is designed to go through three gates, of which only the
 * first is currently implemented (see the TODO at the end of {@link
 * #submit}):
 * <ol>
 *   <li><b>Implemented.</b> The {@code (kind, type)} pair must exist
 *       in the {@code input_event_types} reference table. Unknown
 *       pairs → 400.</li>
 *   <li><b>Not yet implemented.</b> The active game's {@code
 *       games.features} JSONB must accept the event kind (a
 *       chrome-less demo with {@code spell_loadout: false} can't have
 *       a JRPG client smuggle in fake control:cast events). Refused
 *       kinds → 405.</li>
 *   <li><b>Not yet implemented.</b> {@code game_event_support} must
 *       list the {@code (game_slug, event_kind, event_type)} triple
 *       as enabled. Per-game opt-in beyond the feature flags. Refused
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
                .flatMap(t -> {
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
                }));
        // TODO(PR #116 follow-up, blocker 2): per-game event-support gate —
        // gates 2 (features.<kind>) and 3 (game_event_support) are STILL not
        // implemented. This is a deliberate defer, not an oversight: three
        // concrete decisions have to be made first, and none of them belongs to
        // whoever next touches this method.
        //
        // 1. WHICH mapping is gate 2? The class Javadoc above gates control:cast
        //    on features.spell_loadout — a (kind, TYPE) → flag mapping. The
        //    canonical architecture doc (docs/wiki/architecture/04-data-model.md,
        //    "Generalised input event surface") defines gate 2 as
        //    features.<kind> — a KIND → flag mapping. Those two rules disagree
        //    about exactly the case the Javadoc uses as its worked example.
        //
        // 2. What does an ABSENT feature key mean? Changeset 026-3 seeds seven
        //    input_event_kinds (gamepad / mouse / keyboard / touch / audio /
        //    world / control); the games.features DEFAULT in 026-1 has keys for
        //    only five of them. `world` and `control` — the two kinds every
        //    host-authoritative relay uses — have no flag at all. Absent ⇒ deny
        //    breaks every multiplayer game; absent ⇒ allow leaves the Javadoc's
        //    own control:cast example ungated. Pick one and seed the column to
        //    match; do not leave it implicit.
        //
        // 3. Gate 3 needs a data pass before it can be turned on, or it is a
        //    silent outage. game_event_support carries gamepad/mouse/keyboard/
        //    touch/audio rows for exactly TWO slugs (three-wizards and
        //    dodecahedron, both from 026-5). Meanwhile GamepadService
        //    (frontend/src/app/services/gamepad.service.ts) mirrors every
        //    gamepad event here as kind:'gamepad' under whatever game is
        //    active — and its gameSlug field DEFAULTS to the literal string
        //    "unknown". So a literal gate 3 would 405 the gamepad mirror for
        //    every title except those two, and the mirror's fetch(...) ends in
        //    .catch(() => {}) — it would fail completely silently. Seeding the
        //    remaining triples is a per-title product call (which events does
        //    this game declare?), not a refactor.
        //
        // Cost note for whoever picks this up: submit() is the hot path (gamepad
        // axis + mouse motion are per-frame), so gates 2 and 3 as written are two
        // extra DB reads per event. They want a cache with a documented
        // invalidation story, not a naive lookup — that is part of the design
        // pass, not an optimisation to bolt on later.
        //
        // Prerequisites that do not exist yet: the Game entity
        // (model/Game.java) does not map the features column at all, nothing in
        // backend/src/main/java reads games.features (RegisterSqlPolicy only
        // names it as an UPDATE-able column), and there is no
        // METHOD_NOT_ALLOWED / 405 anywhere in the service — so the refusal
        // status these gates are specified to return has no precedent here.
        //
        // See the class Javadoc above and README § Known gaps / TODO.
        //
        // (Blocker 3, Kafka cross-pod world-relay parity, shipped in PR #117 —
        // GameStateService.emitInput tees kind:'world' onto the dedicated
        // `world-native` Kafka channel and reemitToSession's "world-native" case
        // re-emits it on the peer pod's worldNative sink. No longer a gap here.)
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
