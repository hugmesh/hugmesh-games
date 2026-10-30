package games.hugmesh.bff.controller;

import games.hugmesh.bff.model.AdminAction;
import games.hugmesh.bff.service.AdminActionService;
import games.hugmesh.bff.service.SessionService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;

/**
 * Moderator endpoints for kick / ban / unban + the player-facing
 * "what's my ban status" endpoint.
 */
@Tag(name = "Admin actions",
     description = "Moderator-driven kick / ban / unban surface. Writes append-only rows to admin_actions and broadcasts a live event so the targeted player's tabs close their game window immediately.")
@RestController
@RequestMapping("/api")
public class AdminActionController {

    private final AdminActionService actions;
    private final SessionService     sessions;

    public AdminActionController(AdminActionService actions,
                                 SessionService sessions) {
        this.actions  = actions;
        this.sessions = sessions;
    }

    /** Body for kick/ban/unban — both fields optional. */
    public record ActionRequest(String message, Instant expiresAt) {}

    @Operation(summary = "Kick a player from the active session",
               description = "One-shot — the player can rejoin. Closes their game window via the live admin-actions SSE.")
    @PreAuthorize("hasRole('GAMES_ADMIN')")
    @PostMapping("/admin/actions/kick/{casdoorId}")
    public Mono<AdminAction> kick(@AuthenticationPrincipal Jwt jwt,
                                  @PathVariable String casdoorId,
                                  @RequestBody(required = false) ActionRequest req) {
        String message = req == null ? null : req.message();
        return actions.kick(casdoorId, jwt.getSubject(), message)
                .flatMap(a -> sessions.disconnect(casdoorId).thenReturn(a));
    }

    @Operation(summary = "Ban a player",
               description = "Persistent until unbanned (or expiresAt for temp bans). The login flow redirects banned users to /banned; live sessions are torn down via SSE.")
    @PreAuthorize("hasRole('GAMES_ADMIN')")
    @PostMapping("/admin/actions/ban/{casdoorId}")
    public Mono<AdminAction> ban(@AuthenticationPrincipal Jwt jwt,
                                 @PathVariable String casdoorId,
                                 @RequestBody(required = false) ActionRequest req) {
        String message = req == null ? null : req.message();
        Instant exp    = req == null ? null : req.expiresAt();
        return actions.ban(casdoorId, jwt.getSubject(), message, exp)
                .flatMap(a -> sessions.disconnect(casdoorId).thenReturn(a));
    }

    @Operation(summary = "Lift a ban",
               description = "Records an `unban` row that supersedes the prior ban. The player can log in again.")
    @PreAuthorize("hasRole('GAMES_ADMIN')")
    @PostMapping("/admin/actions/unban/{casdoorId}")
    public Mono<AdminAction> unban(@AuthenticationPrincipal Jwt jwt,
                                   @PathVariable String casdoorId,
                                   @RequestBody(required = false) ActionRequest req) {
        String message = req == null ? null : req.message();
        return actions.unban(casdoorId, jwt.getSubject(), message);
    }

    @Operation(summary = "List action history for a player",
               description = "Newest first. Used by the admin UI's player-detail panel.")
    @PreAuthorize("hasRole('GAMES_ADMIN')")
    @GetMapping("/admin/actions/{casdoorId}")
    public Flux<AdminAction> history(@PathVariable String casdoorId) {
        return actions.historyFor(casdoorId);
    }

    @Operation(summary = "Live admin-actions feed (SSE)",
               description = "Used by the host shell to detect a kick/ban for the current user across tabs. Filtered client-side on target_casdoor_id == JWT.sub.")
    @PreAuthorize("isAuthenticated()")
    @GetMapping(path = "/me/admin-actions/stream",
                produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<AdminAction>> stream(@AuthenticationPrincipal Jwt jwt) {
        String me = jwt.getSubject();
        Flux<ServerSentEvent<AdminAction>> events = actions.stream()
                .filter(a -> me.equals(a.getTargetCasdoorId()))
                .map(a -> ServerSentEvent.<AdminAction>builder()
                        .event("admin-action")
                        .data(a)
                        .build());
        // Keepalive so EventSource.onopen fires within the connect
        // round-trip even before any moderator action lands.
        Flux<ServerSentEvent<AdminAction>> ping = Flux.interval(Duration.ofSeconds(15))
                .map(t -> ServerSentEvent.<AdminAction>builder()
                        .event("keepalive")
                        .build());
        return Flux.merge(events, ping);
    }

    @Operation(summary = "Resolve current ban / kick state for the calling user",
               description = "Returns `{banned: boolean, action?: AdminAction}`. The login flow + auth guard hit this to decide whether to redirect to /banned.")
    @PreAuthorize("isAuthenticated()")
    @GetMapping("/me/ban-status")
    public Mono<Map<String, Object>> banStatus(@AuthenticationPrincipal Jwt jwt) {
        String me = jwt.getSubject();
        return actions.isBanned(me).flatMap(banned ->
            actions.latestFor(me)
                    .map(a -> Map.<String, Object>of("banned", banned, "action", a))
                    .switchIfEmpty(Mono.just(Map.<String, Object>of("banned", false))));
    }
}
