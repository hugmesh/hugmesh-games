package games.snapdragon.bff.service;

import games.snapdragon.bff.repository.GameRepository;
import games.snapdragon.bff.security.Roles;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;

/**
 * Per-game brand-scope gate — the catalog-data analog of
 * {@link PublisherService#requireOwns} / {@code ModerationService.requireScope},
 * for the multi-tenancy (snapdragon.systems) merger.
 *
 * <p>Where {@code requireOwns}/{@code requireScope} ask "is this caller scoped to this
 * game by an explicit grant row?", this asks "does this caller's BRAND own this game?"
 * — i.e. is the caller acting within their own tenant. It 403s a brand-X admin
 * reaching for a brand-Y game, exactly as the snapdragon-gateway hard-denies a
 * cross-brand request at the edge; this is the BFF-side, per-game defence-in-depth for
 * a single BFF that serves more than one brand from one database.
 *
 * <p><b>Inert in single-tenant by construction.</b> Every pre-merger {@code games} row
 * backfills to {@code domain = "default"} (migration 043), and the row-domain predicate
 * {@link Roles#domainApplies(String, String, boolean)} treats {@code "default"} (and
 * {@code null}/blank) as UNSCOPED — visible to any caller. So with no per-brand data
 * this gate always passes. No config flag is needed: the data, not a property, makes it
 * a no-op (and crucially it still fires in a multi-issuer-shared-BFF deployment where
 * {@code app.tenancy.domain} is unset but games carry real brands).
 *
 * <p><b>Caller plumbing follows the codebase idiom:</b> the CONTROLLER resolves the
 * caller's brand from {@code @AuthenticationPrincipal Jwt} via
 * {@code Roles.domainOf(jwt, domainRegistry)} + {@code Roles.isSuperadmin(jwt)} and
 * passes them in as primitives. Services never read the reactive security context
 * (only the {@code DomainGuardFilter} WebFilter does).
 */
@Service
public class DomainScopeService {

    private final GameRepository games;

    public DomainScopeService(GameRepository games) {
        this.games = games;
    }

    /**
     * Authorize: the caller's brand must OWN the game identified by {@code gameSlug}
     * (or the game must be unscoped/global, or the caller is SUPERADMIN). Completes
     * empty on allow; errors {@code 403 FORBIDDEN} on a cross-brand mismatch.
     *
     * <p>If no game has that slug this completes empty (no domain to check) so the
     * downstream handler raises its own {@code 404} — the gate refuses access, it does
     * not invent a not-found. {@code .then()}-chain it BEFORE the side effect, the same
     * placement as {@code requireScope}/{@code requireOwns}.
     *
     * @param gameSlug    the target game's slug (the addressed resource)
     * @param callerDomain the caller's issuer-derived brand ({@code Roles.domainOf});
     *                     {@code null} (unprovable tenant) fails closed against a scoped game
     * @param isSuperadmin whether the caller holds the cross-domain SUPERADMIN role
     */
    public Mono<Void> requireGameDomain(String gameSlug, String callerDomain, boolean isSuperadmin) {
        if (isSuperadmin) {
            return Mono.empty();                                  // cross-domain bypass, no lookup needed
        }
        return games.findBySlug(gameSlug)
                .flatMap(game -> Roles.domainApplies(game.getDomain(), callerDomain, false)
                        ? Mono.<Void>empty()
                        : Mono.<Void>error(new ResponseStatusException(
                                HttpStatus.FORBIDDEN,
                                "caller domain '" + callerDomain + "' may not act on game '"
                                        + gameSlug + "' (domain '" + game.getDomain() + "')")))
                // Unknown slug: no domain to enforce — let the caller's own 404 surface.
                .switchIfEmpty(Mono.empty());
    }
}
