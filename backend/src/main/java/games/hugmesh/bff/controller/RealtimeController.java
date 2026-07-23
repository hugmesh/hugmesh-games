package games.hugmesh.bff.controller;

import games.hugmesh.bff.model.SessionMember;
import games.hugmesh.bff.repository.GameSessionRepository;
import games.hugmesh.bff.repository.SessionMemberRepository;
import games.hugmesh.bff.security.DomainRegistry;
import games.hugmesh.bff.security.Roles;
import games.hugmesh.bff.service.DomainScopeService;
import games.hugmesh.bff.service.realtime.AuthoritativeTickService;
import games.hugmesh.bff.service.realtime.RealtimeTruthStore;
import games.hugmesh.bff.service.realtime.SimCommand;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Server-authoritative real-time match endpoints (docs/realtime-server-
 * authoritative-design.md, P2). Start an authoritative match on a
 * multiplayer session, submit inputs into the lag buffer, subscribe to
 * the authoritative snapshot SSE stream, and read the persisted command
 * log (the deterministic replay feed).
 *
 * <p>Every endpoint is brand-scope gated on the SESSION's game via
 * {@link DomainScopeService#requireSessionDomain} (the
 * {@code ModerationController} shape), wrapped in {@code Mono.defer} /
 * {@code Flux.defer} so a denied request short-circuits with no side work.
 * The read paths (snapshot stream + command log) additionally require the
 * caller to be an ACTIVE session member — or an admin/superadmin (the
 * {@code /api/admin/sessions/{id}/stream} observation precedent) — because
 * the brand gate alone is a no-op on a single-tenant deploy. The whole
 * {@code /api/realtime/**} surface is in {@code SecurityConfig}'s
 * authenticated matcher set, so an anonymous caller never reaches here.
 */
@Tag(name = "Realtime matches",
     description = "Server-authoritative tick loop per session: start a match, submit inputs (lag-buffered), stream authoritative snapshots over SSE, read the command-log replay feed.")
@RestController
@RequestMapping("/api/realtime")
public class RealtimeController {

    private final AuthoritativeTickService ticks;
    private final GameSessionRepository sessions;
    private final SessionMemberRepository members;
    private final DomainScopeService domainScope;
    private final DomainRegistry domainRegistry;
    private final ObjectProvider<RealtimeTruthStore> truthStore;

    public RealtimeController(AuthoritativeTickService ticks,
                              GameSessionRepository sessions,
                              SessionMemberRepository members,
                              DomainScopeService domainScope,
                              DomainRegistry domainRegistry,
                              ObjectProvider<RealtimeTruthStore> truthStore) {
        this.ticks          = ticks;
        this.sessions       = sessions;
        this.members        = members;
        this.domainScope    = domainScope;
        this.domainRegistry = domainRegistry;
        this.truthStore     = truthStore;
    }

    /** Brand-scope gate keyed on the SESSION's game — same call shape as
     *  {@code ModerationController.requireSessionDomain}. */
    private Mono<Void> requireSessionDomain(String sessionId, Jwt jwt) {
        return domainScope.requireSessionDomain(sessionId, Roles.domainOf(jwt, domainRegistry), Roles.isSuperadmin(jwt));
    }

    /** Match-visibility gate for the read paths: the snapshot stream and the
     *  command log expose the FULL authoritative match state, so beyond the
     *  brand gate the caller must be an ACTIVE member of the session — or an
     *  admin/superadmin (the {@code /api/admin/sessions/{id}/stream}
     *  observation precedent). Without this, any same-domain user who learned
     *  a sessionId could watch another match's truth — the inverse of the
     *  {@code SessionIsolationTest} contract. Fail-closed on a missing
     *  subject. */
    private Mono<Void> requireMatchVisibility(String sessionId, Jwt jwt) {
        if (Roles.isAdmin(jwt) || Roles.isSuperadmin(jwt)) {
            return Mono.empty();
        }
        String caller = Roles.casdoorIdOf(jwt);
        if (caller == null) {
            return Mono.error(new ResponseStatusException(
                    HttpStatus.FORBIDDEN, "not a member of this session"));
        }
        return members.findActiveBySession(sessionId)
                .any(m -> caller.equals(m.getCasdoorId()))
                .flatMap(isMember -> isMember
                        ? Mono.empty()
                        : Mono.error(new ResponseStatusException(
                                HttpStatus.FORBIDDEN, "not a member of this session")));
    }

    public record StartMatchRequest(String kind, Long seed, String configJson) {}
    public record InputRequest(String payloadJson) {}

    @Operation(
        summary = "Start the authoritative match for a session",
        description = """
            Only the session CREATOR may start (compared against the JWT
            subject, the same check as session close). The match roster is
            the session's active members. Fail-closed: a second start for
            the same session is 409.
            """)
    @PostMapping("/{sessionId}/start")
    public Mono<Void> start(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable String sessionId,
            @RequestBody StartMatchRequest req) {
        if (req == null || req.kind() == null || req.kind().isBlank()) {
            return Mono.error(new ResponseStatusException(HttpStatus.BAD_REQUEST, "kind required"));
        }
        return requireSessionDomain(sessionId, jwt)
                .then(Mono.defer(() -> sessions.findById(sessionId)
                        .switchIfEmpty(Mono.error(new ResponseStatusException(
                                HttpStatus.NOT_FOUND, "session not found")))
                        .flatMap(s -> {
                            if (!jwt.getSubject().equals(s.getCreatorCasdoorId())) {
                                return Mono.error(new ResponseStatusException(
                                        HttpStatus.FORBIDDEN, "only the creator may start the match"));
                            }
                            return members.findActiveBySession(sessionId)
                                    .map(SessionMember::getCasdoorId)
                                    .collectList()
                                    .flatMap(playerIds -> ticks.startMatch(
                                            sessionId, s.getGameSlug(), req.kind(),
                                            req.seed() != null ? req.seed() : 0L,
                                            playerIds, req.configJson()));
                        })));
    }

    @Operation(
        summary = "Submit one input into the match's lag buffer",
        description = """
            The player id is the caller's casdoor subject — a member can
            only ever submit as themselves. Returns the authoritative tick
            the input is scheduled to execute at (submission tick +
            input-delay; never the past).
            """)
    @PostMapping("/{sessionId}/input")
    public Mono<Map<String, Object>> input(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable String sessionId,
            @RequestBody InputRequest req) {
        return requireSessionDomain(sessionId, jwt)
                .then(Mono.defer(() -> ticks.submitInput(
                        sessionId, jwt.getSubject(), req == null ? null : req.payloadJson())))
                .map(tick -> Map.of("scheduledTick", tick));
    }

    @Operation(
        summary = "Authoritative snapshot SSE stream",
        description = """
            `text/event-stream` of the match's authoritative snapshot JSON
            (event `snapshot`), one per tick, plus a 15s keepalive comment.
            The stream completes when the match reaches its terminal state.
            Only ACTIVE session members (or admins/superadmins) may observe.
            Clients render SERVER truth and reconcile their prediction
            against it.
            """)
    @GetMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<Object>> stream(
            @AuthenticationPrincipal Jwt jwt,
            @Parameter(description = "The session whose match to observe.")
            @RequestParam("sessionId") String sessionId) {
        AtomicLong sseId = new AtomicLong(0);
        // The keepalive interval is infinite by construction, and Flux.merge
        // completes only when ALL sources complete — so the keepalive must be
        // bounded by the snapshot stream's termination (the match's terminal
        // state) or the merged SSE would hold the socket + the per-subscriber
        // 15s interval open forever after the match ends.
        Sinks.Empty<Void> snapshotsDone = Sinks.empty();
        Flux<ServerSentEvent<Object>> snapshots = ticks.snapshotStream(sessionId)
                .doOnTerminate(snapshotsDone::tryEmitEmpty)
                .map(json -> ServerSentEvent.builder()
                        .id(String.valueOf(sseId.incrementAndGet()))
                        .event("snapshot")
                        .data(json)
                        .build());
        // First keepalive at t=0 so response headers flush immediately —
        // same rationale as GameStreamController.
        Flux<ServerSentEvent<Object>> keepalive = Flux.interval(Duration.ZERO, Duration.ofSeconds(15))
                .map(i -> ServerSentEvent.builder().comment("keepalive").build())
                .takeUntilOther(snapshotsDone.asMono());
        return requireSessionDomain(sessionId, jwt)
                .then(requireMatchVisibility(sessionId, jwt))
                .thenMany(Flux.defer(() -> Flux.merge(snapshots, keepalive)));
    }

    @Operation(
        summary = "The persisted command log (replay feed)",
        description = """
            Commands from the Cassandra truth store with `forTick >
            afterTick`, in deterministic (forTick, seq) order — replaying
            them through the same sim kind reproduces the match bit-exactly.
            Only ACTIVE session members (or admins/superadmins) may read.
            Empty when the truth store is absent (Cassandra-less deploys).
            """)
    @GetMapping("/{sessionId}/log")
    public Flux<SimCommand> log(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable String sessionId,
            @RequestParam(value = "afterTick", defaultValue = "-1") long afterTick) {
        return requireSessionDomain(sessionId, jwt)
                .then(requireMatchVisibility(sessionId, jwt))
                .thenMany(Flux.defer(() -> {
                    RealtimeTruthStore store = truthStore.getIfAvailable();
                    return store == null ? Flux.empty() : store.commandsAfter(sessionId, afterTick);
                }));
    }
}
