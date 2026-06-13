package games.snapdragon.bff.controller;

import games.snapdragon.bff.model.ModGameScope;
import games.snapdragon.bff.repository.ModGameScopeRepository;
import games.snapdragon.bff.security.DomainRegistry;
import games.snapdragon.bff.security.Roles;
import games.snapdragon.bff.service.DomainScopeService;
import games.snapdragon.bff.service.ModerationService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Per-game moderation endpoints.
 *
 * <p>Casdoor's {@code mod} role is required (or {@code admin}, who can
 * always moderate any game). The per-game scope is enforced by
 * {@link ModerationService#requireScope}, which checks
 * {@code mod_game_scopes} before any side effect.
 *
 * <p>Distinct from {@code /api/admin/actions/*} (which any admin can
 * call across every game) and from {@code /api/sessions/{id}}'s
 * close action (creator-only).
 */
@Tag(name = "Moderation",
     description = "Per-game moderator actions: stop a session, kick a player. ROLE_MOD or ROLE_ADMIN required, plus a mod_game_scopes row for the targeted session's game.")
@RestController
@RequestMapping("/api/mod")
@PreAuthorize("hasAnyRole('MOD','ADMIN')")
public class ModerationController {

    private final ModerationService moderation;
    private final ModGameScopeRepository scopes;
    private final DomainScopeService domainScope;
    private final DomainRegistry domainRegistry;

    public ModerationController(ModerationService moderation, ModGameScopeRepository scopes,
                                DomainScopeService domainScope, DomainRegistry domainRegistry) {
        this.moderation = moderation;
        this.scopes     = scopes;
        this.domainScope    = domainScope;
        this.domainRegistry = domainRegistry;
    }

    /** Brand-scope gate keyed on the SESSION's game: a brand-X admin/mod may not act on
     *  a brand-Y session (SUPERADMIN exempt; inert single-tenant). The session→game_slug
     *  resolution lives in {@link DomainScopeService#requireSessionDomain}. */
    private Mono<Void> requireSessionDomain(String sessionId, Jwt jwt) {
        return domainScope.requireSessionDomain(sessionId, Roles.domainOf(jwt, domainRegistry), Roles.isSuperadmin(jwt));
    }

    public record StopSessionRequest(String reason) {}
    public record KickPlayerRequest(String targetCasdoorId, String reason) {}

    @Operation(
        summary = "List the games this caller may moderate",
        description = """
            Returns the active mod_game_scopes rows for the JWT subject.
            Empty list = no scope; the caller still has the role but
            can't act on any session yet.
            """)
    @GetMapping("/scopes")
    public Flux<ModGameScope> myScopes(@AuthenticationPrincipal Jwt jwt) {
        return scopes.findActiveByCasdoor(jwt.getSubject());
    }

    @Operation(
        summary = "Stop (force-close) a session",
        description = """
            Authorizes the caller as a mod for the session's game_slug,
            then sets `closed_at = NOW()` on the session. Audited in
            `admin_actions` with `[stop-session sessionId=...]` in the
            message column.
            """)
    @PostMapping("/sessions/{id}/stop")
    public Mono<Void> stopSession(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable String id,
            @RequestBody(required = false) StopSessionRequest req) {
        return requireSessionDomain(id, jwt)
                .then(Mono.defer(() ->
                        moderation.stopSession(jwt.getSubject(), id, req == null ? null : req.reason())));
    }

    @Operation(
        summary = "Kick a single player from a session",
        description = """
            Stamps `left_at` on the target's session_members row. If they
            were the last active member, the session auto-closes. Audited
            in `admin_actions`.
            """)
    @PostMapping("/sessions/{id}/kick")
    public Mono<Void> kickPlayer(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable String id,
            @RequestBody KickPlayerRequest req) {
        return requireSessionDomain(id, jwt)
                .then(Mono.defer(() ->
                        moderation.kickPlayer(jwt.getSubject(), id, req.targetCasdoorId(), req.reason())));
    }
}
