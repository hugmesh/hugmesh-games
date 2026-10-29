package games.hugmesh.bff.controller;

import games.hugmesh.bff.security.GamesAdminPolicy;

import games.hugmesh.bff.model.SessionLogEntry;
import games.hugmesh.bff.repository.ModGameScopeRepository;
import games.hugmesh.bff.repository.PublisherGameScopeRepository;
import games.hugmesh.bff.repository.SessionLogRepository;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Per-user session participation history.
 *
 * <p>Three roles:
 * <ul>
 *   <li><strong>player</strong> — {@code GET /me} returns the caller's
 *       own history (any game).</li>
 *   <li><strong>admin</strong> — {@code GET /admin/{casdoorId}} returns
 *       any user's history (any game).</li>
 *   <li><strong>mod / publisher</strong> — {@code GET /scoped/{casdoorId}}
 *       returns history filtered to games the caller has scope for.</li>
 * </ul>
 */
@Tag(name = "Session logs", description = "Session participation history per user.")
@RestController
@RequestMapping("/api/session-logs")
public class SessionLogController {

    private static final int DEFAULT_LIMIT = 50;

    private final SessionLogRepository           logs;
    private final ModGameScopeRepository         modScopes;
    private final PublisherGameScopeRepository   publisherScopes;
    /** Who is a games admin (realm-aware; see GamesAdminPolicy). */
    private final GamesAdminPolicy gamesAdmin;

    public SessionLogController(SessionLogRepository logs,
                                ModGameScopeRepository modScopes,
                                PublisherGameScopeRepository publisherScopes,
                                GamesAdminPolicy gamesAdmin) {
        this.gamesAdmin = gamesAdmin;
        this.logs            = logs;
        this.modScopes       = modScopes;
        this.publisherScopes = publisherScopes;
    }

    @Operation(summary = "Caller's own session participation history")
    @GetMapping("/me")
    public Flux<SessionLogEntry> me(@AuthenticationPrincipal Jwt jwt,
                                    @RequestParam(defaultValue = "50") int limit) {
        if (jwt == null) {
            return Flux.error(new ResponseStatusException(HttpStatus.UNAUTHORIZED));
        }
        return logs.findByCasdoor(jwt.getSubject(), limit > 0 ? limit : DEFAULT_LIMIT);
    }

    @Operation(summary = "Anyone's session participation history (admin only)")
    @PreAuthorize("hasRole('GAMES_ADMIN')")
    @GetMapping("/admin/{casdoorId}")
    public Flux<SessionLogEntry> adminFor(@PathVariable String casdoorId,
                                          @RequestParam(defaultValue = "100") int limit) {
        return logs.findByCasdoor(casdoorId, limit > 0 ? limit : DEFAULT_LIMIT);
    }

    @Operation(summary = "Session history scoped to the caller's mod/publisher games")
    @PreAuthorize("hasAnyRole('MOD','PUBLISHER','GAMES_ADMIN')")
    @GetMapping("/scoped/{casdoorId}")
    public Flux<SessionLogEntry> scopedFor(@AuthenticationPrincipal Jwt jwt,
                                           @PathVariable String casdoorId,
                                           @RequestParam(defaultValue = "100") int limit) {
        if (jwt == null) {
            return Flux.error(new ResponseStatusException(HttpStatus.UNAUTHORIZED));
        }
        String me = jwt.getSubject();
        // A games admin sees everything regardless of scope (GamesAdminPolicy:
        // the same callers the converter grants ROLE_GAMES_ADMIN).
        if (gamesAdmin.isGamesAdmin(jwt)) {
            return logs.findByCasdoor(casdoorId, limit > 0 ? limit : DEFAULT_LIMIT);
        }
        // Otherwise: union of the caller's mod + publisher game-slug scopes.
        return Mono.zip(
                modScopes.findActiveByCasdoor(me)
                        .map(s -> s.getGameSlug())
                        .collect(java.util.stream.Collectors.toSet()),
                publisherScopes.findActiveByCasdoor(me)
                        .map(s -> s.getGameSlug())
                        .collect(java.util.stream.Collectors.toSet()))
                .flatMapMany(t -> {
                    java.util.Set<String> games = new java.util.HashSet<>(t.getT1());
                    games.addAll(t.getT2());
                    if (games.isEmpty()) {
                        return Flux.error(new ResponseStatusException(
                                HttpStatus.FORBIDDEN, "no games in scope"));
                    }
                    return Flux.fromIterable(games)
                            .flatMap(g -> logs.findByCasdoorAndGameSlug(casdoorId, g, limit));
                });
    }
}
