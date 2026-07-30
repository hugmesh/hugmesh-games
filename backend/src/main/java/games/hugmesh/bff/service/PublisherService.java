package games.hugmesh.bff.service;

import games.hugmesh.bff.model.ModGameScope;
import games.hugmesh.bff.model.PublisherGameScope;
import games.hugmesh.bff.repository.ModGameScopeRepository;
import games.hugmesh.bff.repository.PublisherGameScopeRepository;
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
     * {@link #requireOwns(String, String)} accepting both principal
     * identifiers. Casdoor re-mints user UUIDs (the JWT subject) on every
     * fresh DB boot, so Liquibase-seeded scope rows key on the *username*
     * (the {@code name} claim) while runtime-granted rows key on the
     * subject — a row matched by either identifier authorizes. Mirrors
     * the rpg/vn-builder {@code Roles.ownerMatches} contract.
     */
    public Mono<Void> requireOwns(String publisherCasdoorId, String username, String gameSlug) {
        Mono<PublisherGameScope> byUsername =
                username == null || username.equals(publisherCasdoorId)
                        ? Mono.empty()
                        : publisherScopes.findActive(username, gameSlug);
        return publisherScopes.findActive(publisherCasdoorId, gameSlug)
                .switchIfEmpty(byUsername)
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
        return grantMod(publisherCasdoorId, null, targetCasdoorId, gameSlug);
    }

    /**
     * {@link #grantMod(String, String, String)} accepting both principal
     * identifiers, so a publisher whose scope row is Liquibase-seeded
     * (keyed on the username) can manage mods too. The audit stamp
     * ({@code granted_by_casdoor_id}) always records the subject.
     */
    public Mono<ModGameScope> grantMod(
            String publisherCasdoorId,
            String username,
            String targetCasdoorId,
            String gameSlug) {
        return requireOwns(publisherCasdoorId, username, gameSlug)
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
        return revokeMod(publisherCasdoorId, null, targetCasdoorId, gameSlug);
    }

    /** Both-identifier variant of {@link #revokeMod(String, String, String)} —
     *  see {@link #grantMod(String, String, String, String)} for why. */
    public Mono<Void> revokeMod(
            String publisherCasdoorId,
            String username,
            String targetCasdoorId,
            String gameSlug) {
        return requireOwns(publisherCasdoorId, username, gameSlug)
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
