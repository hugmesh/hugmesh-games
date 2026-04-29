package games.snapdragon.bff.service;

import games.snapdragon.bff.model.ModGameScope;
import games.snapdragon.bff.model.PublisherGameScope;
import games.snapdragon.bff.repository.ModGameScopeRepository;
import games.snapdragon.bff.repository.PublisherGameScopeRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;

import java.time.Instant;

/**
 * Publisher capabilities scoped per-game.
 *
 * <p>Casdoor's {@code publisher} role grants the capability; the rows
 * in {@code publisher_game_scopes} say *which games* the publisher
 * may upload bundles for and grant moderators on. Cross-game grants
 * require {@code admin}.
 *
 * <p>Two action verbs:
 * <ul>
 *   <li>{@code requireOwns} — gates a publisher action on a specific
 *       game slug. Used by the upload endpoint to refuse bundles for
 *       games the publisher doesn't own.</li>
 *   <li>{@code grantMod} — adds a mod_game_scopes row tying a target
 *       casdoor user to a specific game the publisher owns. Idempotent
 *       on the active-row partial unique index.</li>
 * </ul>
 */
@Service
public class PublisherService {

    private static final Logger log = LoggerFactory.getLogger(PublisherService.class);

    private final PublisherGameScopeRepository publisherScopes;
    private final ModGameScopeRepository       modScopes;

    public PublisherService(PublisherGameScopeRepository publisherScopes,
                            ModGameScopeRepository       modScopes) {
        this.publisherScopes = publisherScopes;
        this.modScopes       = modScopes;
    }

    /**
     * Authorize: publisher must own {@code gameSlug}. Used by the
     * publisher game-upload endpoint to reject bundles whose slug
     * isn't in the publisher's scope. Admins bypass this via
     * {@code @PreAuthorize("hasRole('ADMIN')")} at the controller
     * level.
     */
    public Mono<Void> requireOwns(String publisherCasdoorId, String gameSlug) {
        return publisherScopes.findActive(publisherCasdoorId, gameSlug)
                .switchIfEmpty(Mono.error(new ResponseStatusException(
                        HttpStatus.FORBIDDEN,
                        "publisher does not own game " + gameSlug)))
                .then();
    }

    /**
     * Grant the {@code mod} role for {@code gameSlug} to the target
     * casdoor user. Caller must be a publisher who owns the game (or
     * an admin, gated upstream). Idempotent: re-granting an existing
     * active scope is a no-op.
     */
    public Mono<ModGameScope> grantMod(
            String publisherCasdoorId,
            String targetCasdoorId,
            String gameSlug) {
        return requireOwns(publisherCasdoorId, gameSlug)
                .then(modScopes.findActive(targetCasdoorId, gameSlug))
                .switchIfEmpty(Mono.defer(() -> {
                    ModGameScope row = new ModGameScope();
                    row.setCasdoorId(targetCasdoorId);
                    row.setGameSlug(gameSlug);
                    row.setGrantedByCasdoorId(publisherCasdoorId);
                    row.setGrantedAt(Instant.now());
                    log.info("Publisher {} granted mod role to {} for game {}",
                            publisherCasdoorId, targetCasdoorId, gameSlug);
                    return modScopes.save(row);
                }));
    }

    /**
     * Revoke a previously-granted mod scope. Soft-delete via
     * {@code revoked_at}. The publisher must still own {@code gameSlug}.
     */
    public Mono<Void> revokeMod(
            String publisherCasdoorId,
            String targetCasdoorId,
            String gameSlug) {
        return requireOwns(publisherCasdoorId, gameSlug)
                .then(modScopes.findActive(targetCasdoorId, gameSlug))
                .switchIfEmpty(Mono.error(new ResponseStatusException(HttpStatus.NOT_FOUND, "no active mod scope to revoke")))
                .flatMap(row -> {
                    row.setRevokedAt(Instant.now());
                    row.setRevokedByCasdoorId(publisherCasdoorId);
                    return modScopes.save(row).then();
                });
    }

    /**
     * Helper for the admin role grants UI: write a publisher scope row
     * directly. Admin-only path; the controller {@code @PreAuthorize}s
     * before calling.
     */
    public Mono<PublisherGameScope> adminGrantPublisher(
            String adminCasdoorId,
            String targetCasdoorId,
            String gameSlug) {
        return publisherScopes.findActive(targetCasdoorId, gameSlug)
                .switchIfEmpty(Mono.defer(() -> {
                    PublisherGameScope row = new PublisherGameScope();
                    row.setCasdoorId(targetCasdoorId);
                    row.setGameSlug(gameSlug);
                    row.setGrantedByCasdoorId(adminCasdoorId);
                    row.setGrantedAt(Instant.now());
                    log.info("Admin {} granted publisher scope to {} for game {}",
                            adminCasdoorId, targetCasdoorId, gameSlug);
                    return publisherScopes.save(row);
                }));
    }

    public Mono<Void> adminRevokePublisher(
            String adminCasdoorId,
            String targetCasdoorId,
            String gameSlug) {
        return publisherScopes.findActive(targetCasdoorId, gameSlug)
                .switchIfEmpty(Mono.error(new ResponseStatusException(HttpStatus.NOT_FOUND, "no active publisher scope to revoke")))
                .flatMap(row -> {
                    row.setRevokedAt(Instant.now());
                    row.setRevokedByCasdoorId(adminCasdoorId);
                    return publisherScopes.save(row).then();
                });
    }
}
