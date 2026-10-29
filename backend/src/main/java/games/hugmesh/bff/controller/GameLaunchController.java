package games.hugmesh.bff.controller;

import games.hugmesh.bff.model.GameLaunch;
import games.hugmesh.bff.repository.GameAccessRepository;
import games.hugmesh.bff.repository.GameLaunchRepository;
import games.hugmesh.bff.repository.GameRepository;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;

import games.hugmesh.bff.security.DomainRegistry;
import games.hugmesh.bff.security.Roles;
import games.hugmesh.bff.service.DomainScopeService;
import games.hugmesh.bff.util.Crypto;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

/**
 * Cryptographically-secure launch URLs.
 *
 * <p>The federated remote calls {@code POST /api/games/{slug}/launch}
 * before mounting the WASM bundle. The BFF:
 * <ol>
 *   <li>verifies the JWT identifies an authenticated player,</li>
 *   <li>checks the principal's roles + user id against {@code game_access}
 *       so a user can't side-step the admin's per-game policy,</li>
 *   <li>mints 256 bits of entropy, base64url-encoded → a 43-char uid,</li>
 *   <li>persists a {@link GameLaunch} row with a 15-minute TTL,</li>
 *   <li>returns {@code {uid, expiresAt}} to the client.</li>
 * </ol>
 *
 * <p>The remote then loads its bundle from
 * {@code /api/assets/levels/{slug}/{uid}/index.{wasm,pck,html,js}}.
 * {@code AssetController} validates the uid against active launches before
 * serving any byte (gated on a feature flag while the federated client is
 * still rolling out — see {@code app.assets.require-launch-uid}).
 */
@Tag(name = "Game launches", description = "Mint a per-session URL-safe uid that gates the WASM bundle path.")
@RestController
@RequestMapping("/api/games")
public class GameLaunchController {

    // Launch UIDs flow through `Crypto.mintUid()` so every URL-bound
    // identifier in the BFF lands as base64url(SHA3-256(entropy))
    // backed by the SP800-90A DRBG. The local SecureRandom field
    // is gone — the Crypto utility owns randomness so a future
    // FIPS / hardware-RNG swap happens in one place.
    private static final Duration LAUNCH_TTL = Duration.ofMinutes(15);

    private final GameRepository games;
    private final GameAccessRepository access;
    private final GameLaunchRepository launches;
    private final DomainScopeService domainScope;
    private final DomainRegistry domainRegistry;

    public GameLaunchController(GameRepository games,
                                GameAccessRepository access,
                                GameLaunchRepository launches,
                                DomainScopeService domainScope,
                                DomainRegistry domainRegistry) {
        this.games = games;
        this.access = access;
        this.launches = launches;
        this.domainScope    = domainScope;
        this.domainRegistry = domainRegistry;
    }

    /** Brand-scope gate: a brand-X caller may not launch/revoke a brand-Y game
     *  (SUPERADMIN exempt; inert single-tenant). Mirrors GameAccessController. */
    private Mono<Void> requireGameDomain(String slug, Jwt jwt) {
        return domainScope.requireGameDomain(slug, Roles.domainOf(jwt, domainRegistry), Roles.isSuperadmin(jwt));
    }

    public record LaunchResponse(String uid, Instant expiresAt, String gameSlug) {}

    @Operation(
        summary = "Launch a game session",
        description = """
            Mints a 256-bit URL-safe uid the federated remote uses as the
            path component when loading the WASM bundle. The BFF persists
            the launch + TTL; the AssetController validates incoming
            asset requests against this table.

            Auth: the principal's roles + user id must match a row in
            `game_access` for this game (else 403). The catalog filter
            already hides forbidden games from the home grid, but this
            endpoint enforces the check independently — defence in depth.
            """)
    @PostMapping("/{slug}/launch")
    Mono<LaunchResponse> launch(@PathVariable String slug,
                                @AuthenticationPrincipal Jwt jwt) {
        if (jwt == null) {
            return Mono.error(new ResponseStatusException(HttpStatus.UNAUTHORIZED,
                    "launch requires an authenticated principal"));
        }
        String userId = jwt.getSubject();
        String[] roles = Roles.fromJwt(jwt).toArray(String[]::new);

        // Brand gate first: a cross-brand caller is refused before any per-user
        // game_access visibility logic runs (mirrors GameAccessController).
        return requireGameDomain(slug, jwt).then(games.findBySlug(slug)
                .switchIfEmpty(Mono.error(new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "unknown game slug: " + slug)))
                .flatMap(g -> isVisible(g.getId(), roles, userId)
                        .flatMap(visible -> {
                            if (!Boolean.TRUE.equals(visible)) {
                                return Mono.error(new ResponseStatusException(HttpStatus.FORBIDDEN,
                                        "game not available to this principal"));
                            }
                            GameLaunch row = new GameLaunch();
                            row.setUid(mintUid());
                            row.setGameSlug(slug);
                            row.setCasdoorUserId(userId);
                            row.setExpiresAt(Instant.now().plus(LAUNCH_TTL));
                            // Pin the active version dir so a hot upload
                            // doesn't swap WASM under this session's feet.
                            row.setVersionDir(g.getCurrentVersionDir());
                            return launches.save(row)
                                    .map(saved -> new LaunchResponse(
                                            saved.getUid(),
                                            saved.getExpiresAt(),
                                            saved.getGameSlug()));
                        })));
    }

    @Operation(summary = "Terminate an active launch (logout, navigate-away).")
    @DeleteMapping("/launches/{uid}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    Mono<Void> terminate(@PathVariable String uid,
                         @AuthenticationPrincipal Jwt jwt) {
        if (jwt == null) {
            return Mono.error(new ResponseStatusException(HttpStatus.UNAUTHORIZED));
        }
        String userId = jwt.getSubject();
        return launches.findByUid(uid)
                .switchIfEmpty(Mono.error(new ResponseStatusException(HttpStatus.NOT_FOUND)))
                .flatMap(row -> {
                    // A user can only terminate their own launches; admins go
                    // through /api/admin/launches/{uid} below.
                    if (!Objects.equals(row.getCasdoorUserId(), userId)) {
                        return Mono.error(new ResponseStatusException(HttpStatus.FORBIDDEN));
                    }
                    if (row.getTerminatedAt() != null) {
                        return Mono.empty();
                    }
                    row.setTerminatedAt(Instant.now());
                    return launches.save(row).then();
                });
    }

    @Operation(
        summary = "List all active launches (admin only).",
        description = "Active = terminated_at IS NULL. The dashboard's 'kick' button calls revoke below.")
    @GetMapping("/launches")
    @org.springframework.security.access.prepost.PreAuthorize("hasRole('GAMES_ADMIN')")
    reactor.core.publisher.Flux<games.hugmesh.bff.model.GameLaunch> activeLaunches(
            @AuthenticationPrincipal Jwt jwt) {
        // Brand-scope the dashboard: a brand-X admin sees only their brand's (+ global)
        // launches; SUPERADMIN sees all; single-tenant unchanged. Each launch's brand is
        // its game's domain (resolved by slug). Decision 026.
        reactor.core.publisher.Flux<games.hugmesh.bff.model.GameLaunch> active =
                launches.findByTerminatedAtIsNullOrderByStartedAtDesc();
        if (Roles.isSuperadmin(jwt)) {
            return active;                        // cross-domain bypass — no per-launch lookup
        }
        String callerDomain = Roles.domainOf(jwt, domainRegistry);
        return active.filterWhen(launch -> games.findBySlug(launch.getGameSlug())
                .map(g -> Roles.domainApplies(g.getDomain(), callerDomain, false))
                .defaultIfEmpty(true));           // unknown slug → no domain to filter, keep
    }

    @Operation(
        summary = "Force-revoke ANY launch (admin only).",
        description = """
            Lets an admin terminate a launch the player owns — useful for kicking
            stuck sessions, locking out a compromised token, or reclaiming a
            launch slot after a crash. Differs from `DELETE /api/games/launches/{uid}`
            (the player-side terminate) only in that the ownership check is skipped.
            """)
    @DeleteMapping("/admin/launches/{uid}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @org.springframework.security.access.prepost.PreAuthorize("hasRole('GAMES_ADMIN')")
    Mono<Void> adminRevoke(@PathVariable String uid,
                           @AuthenticationPrincipal Jwt jwt) {
        return launches.findByUid(uid)
                .switchIfEmpty(Mono.error(new ResponseStatusException(HttpStatus.NOT_FOUND)))
                // Brand gate on the launch's own game (resolved off the loaded row, no
                // extra lookup): a brand-X admin may not revoke a brand-Y game's launch.
                .flatMap(row -> requireGameDomain(row.getGameSlug(), jwt).then(Mono.defer(() -> {
                    if (row.getTerminatedAt() != null) {
                        return Mono.empty();
                    }
                    row.setTerminatedAt(Instant.now());
                    return launches.save(row).then();
                })));
    }

    private static String mintUid() {
        return Crypto.mintUid();
    }

    private Mono<Boolean> isVisible(Long gameId, String[] roles, String userId) {
        return access.findVisibleGameIdsForPrincipal(roles, userId)
                .any(id -> Objects.equals(id, gameId));
    }

}
