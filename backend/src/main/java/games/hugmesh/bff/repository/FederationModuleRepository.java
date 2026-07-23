package games.hugmesh.bff.repository;

import games.hugmesh.bff.model.FederationModule;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Reactive R2DBC repository for {@code federation_modules} rows in Postgres.
 *
 * <p>Each {@link FederationModule} describes one Angular micro-frontend
 * remote in the native-federation topology. The Angular host shell reads
 * the set of enabled modules at bootstrap and uses their
 * {@code remote_entry_url} + {@code exposed_module} fields to wire up
 * {@code initFederation(...)}, so new remotes appear without redeploying
 * the host.
 *
 * <p>Rows with {@code kind='wasm-game'} are web-native game entries:
 * the host home component reads them off the manifest to decide whether
 * to launch via the wasm-fragment remote rather than a per-game Angular
 * remote. All other rows are standard Angular module-federation remotes.
 *
 * <p>This repository powers the games BFF manifest read path
 * ({@code GET /api/federation/manifest}). Only enabled rows appear in the
 * manifest; admin tooling may read disabled rows via the inherited
 * {@code findAll()} / {@code findById()} methods.
 */
public interface FederationModuleRepository extends ReactiveCrudRepository<FederationModule, Long> {

    // -------------------------------------------------------------------------
    // Manifest read path
    // -------------------------------------------------------------------------

    /**
     * Returns all enabled federation module rows, ordered by {@code kind}
     * ascending then {@code slug} ascending.
     *
     * <p>Used by the manifest endpoint to build the full list of active
     * remotes that the Angular host shell should register at boot.
     *
     * @return a {@code Flux} streaming every row where {@code enabled = true},
     *         in kind/slug order; empty if no modules are enabled
     */
    Flux<FederationModule> findAllByEnabledTrueOrderByKindAscSlugAsc();

    // -------------------------------------------------------------------------
    // Filtered lookups
    // -------------------------------------------------------------------------

    /**
     * Returns all enabled federation modules of a given kind.
     *
     * <p>Useful for filtering the registered remotes by their deployment type,
     * for example retrieving only {@code kind='wasm-game'} entries when building
     * the web-native game index.
     *
     * @param kind the module kind to filter on (e.g. {@code "wasm-game"})
     * @return a {@code Flux} streaming every enabled row for that kind;
     *         empty if no enabled modules of that kind are registered
     */
    Flux<FederationModule> findByKindAndEnabledTrue(String kind);

    /**
     * Returns the federation module with the given URL-safe slug.
     *
     * <p>Used by the admin registry endpoints to load, update, or disable
     * a specific module by its identifying slug.
     *
     * @param slug the unique slug identifying the module (e.g. {@code "admin-shell"})
     * @return a {@code Mono} emitting the matching module row, or empty if
     *         no module with that slug is registered
     */
    Mono<FederationModule> findBySlug(String slug);
}
