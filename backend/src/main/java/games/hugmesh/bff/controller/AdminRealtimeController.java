package games.hugmesh.bff.controller;

import games.hugmesh.bff.security.DomainRegistry;
import games.hugmesh.bff.security.Roles;
import games.hugmesh.bff.service.DomainScopeService;
import games.hugmesh.bff.service.realtime.AuthoritativeTickService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

/**
 * Admin-only server-authoritative match operations (docs/realtime-server-
 * authoritative-design.md, P2). The {@code /api/admin/**} matcher in
 * {@code SecurityConfig} already requires ROLE_GAMES_ADMIN; the class-level
 * {@code @PreAuthorize} repeats it for defence in depth (the
 * {@code RoleAdminController} convention).
 */
@Tag(name = "Admin realtime",
     description = "Admin-only realtime match operations: manually recover a persisted match from the Cassandra truth onto this pod.")
@RestController
@RequestMapping("/api/admin/realtime")
@PreAuthorize("hasRole('GAMES_ADMIN')")
public class AdminRealtimeController {

    private final AuthoritativeTickService ticks;
    private final DomainScopeService domainScope;
    private final DomainRegistry domainRegistry;

    public AdminRealtimeController(AuthoritativeTickService ticks,
                                   DomainScopeService domainScope,
                                   DomainRegistry domainRegistry) {
        this.ticks          = ticks;
        this.domainScope    = domainScope;
        this.domainRegistry = domainRegistry;
    }

    @Operation(
        summary = "Manually recover a persisted match onto this pod",
        description = """
            Restores the latest Cassandra keyframe, replays the logged
            commands past it, and resumes the authoritative loop — the
            manual arm of the cross-pod recovery that `submitInput` /
            `snapshotStream` trigger automatically. 409 when a match is
            already running for the session; 404 when there is no durable
            match row.
            """)
    @PostMapping("/{sessionId}/recover")
    public Mono<Void> recover(@AuthenticationPrincipal Jwt jwt,
                              @PathVariable String sessionId) {
        return requireSessionDomain(sessionId, jwt)
                .then(Mono.defer(() -> ticks.recoverMatch(sessionId)));
    }

    /** Brand-scope gate keyed on the SESSION's game — same call shape as
     *  {@code RealtimeController.requireSessionDomain}. */
    private Mono<Void> requireSessionDomain(String sessionId, Jwt jwt) {
        return domainScope.requireSessionDomain(sessionId, Roles.domainOf(jwt, domainRegistry), Roles.isSuperadmin(jwt));
    }
}
