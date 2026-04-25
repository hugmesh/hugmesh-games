package com.threewizards.controller;

import com.threewizards.model.GameLaunch;
import com.threewizards.repository.GameAccessRepository;
import com.threewizards.repository.GameLaunchRepository;
import com.threewizards.repository.GameRepository;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;

import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

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

    private static final SecureRandom RNG = new SecureRandom();
    private static final Base64.Encoder URL_ENCODER = Base64.getUrlEncoder().withoutPadding();
    private static final Duration LAUNCH_TTL = Duration.ofMinutes(15);

    private final GameRepository games;
    private final GameAccessRepository access;
    private final GameLaunchRepository launches;

    public GameLaunchController(GameRepository games,
                                GameAccessRepository access,
                                GameLaunchRepository launches) {
        this.games = games;
        this.access = access;
        this.launches = launches;
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
        Set<String> roleSet = extractRoles(jwt);
        String[] roles = roleSet.toArray(String[]::new);

        return games.findBySlug(slug)
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
                            return launches.save(row)
                                    .map(saved -> new LaunchResponse(
                                            saved.getUid(),
                                            saved.getExpiresAt(),
                                            saved.getGameSlug()));
                        }));
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
    @org.springframework.security.access.prepost.PreAuthorize("hasRole('ADMIN')")
    reactor.core.publisher.Flux<com.threewizards.model.GameLaunch> activeLaunches() {
        return launches.findByTerminatedAtIsNullOrderByStartedAtDesc();
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
    @org.springframework.security.access.prepost.PreAuthorize("hasRole('ADMIN')")
    Mono<Void> adminRevoke(@PathVariable String uid) {
        return launches.findByUid(uid)
                .switchIfEmpty(Mono.error(new ResponseStatusException(HttpStatus.NOT_FOUND)))
                .flatMap(row -> {
                    if (row.getTerminatedAt() != null) {
                        return Mono.empty();
                    }
                    row.setTerminatedAt(Instant.now());
                    return launches.save(row).then();
                });
    }

    private static String mintUid() {
        byte[] bytes = new byte[32];
        RNG.nextBytes(bytes);
        return URL_ENCODER.encodeToString(bytes);
    }

    private Mono<Boolean> isVisible(Long gameId, String[] roles, String userId) {
        return access.findVisibleGameIdsForPrincipal(roles, userId)
                .any(id -> Objects.equals(id, gameId));
    }

    private static Set<String> extractRoles(Jwt jwt) {
        Object claim = jwt.getClaim("roles");
        Set<String> out = new HashSet<>();
        if (claim instanceof List<?> list) {
            for (Object r : list) {
                if (r != null) out.add(r.toString());
            }
        }
        return out;
    }
}
