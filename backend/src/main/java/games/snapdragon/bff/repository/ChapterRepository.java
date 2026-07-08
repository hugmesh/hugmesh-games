package games.snapdragon.bff.repository;

import games.snapdragon.bff.model.Chapter;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Reactive R2DBC repository for {@code chapters} rows in Postgres.
 *
 * <p>Each {@link Chapter} represents one compiled Godot 4 WASM bundle — a
 * playable stage within a game (formerly called a "level" before the PR #20
 * rename). Chapters belong to a {@code game} and are ordered within it by
 * {@code sort_order}. The {@code slug} column is the human-readable
 * URL-safe identifier used in the asset path
 * ({@code /api/assets/levels/<slug>/index.html}).
 *
 * <p>The primary read patterns are: load a chapter by slug (for the scene
 * and asset endpoints), list all chapters in display order (for the lobby
 * chapter chooser), and list all chapters for a specific game in display
 * order (for per-game navigation).
 */
public interface ChapterRepository extends ReactiveCrudRepository<Chapter, Long> {

    // -------------------------------------------------------------------------
    // Single-chapter lookups
    // -------------------------------------------------------------------------

    /**
     * Returns the chapter with the given URL-safe slug.
     *
     * @param slug the unique slug identifying the chapter (e.g. {@code "cave"})
     * @return a {@code Mono} emitting the matching chapter, or empty if none
     *         with that slug exists
     */
    Mono<Chapter> findBySlug(String slug);

    // -------------------------------------------------------------------------
    // Ordered chapter lists
    // -------------------------------------------------------------------------

    /**
     * Returns every chapter across all games ordered by {@code sort_order}
     * ascending.  Used by admin tooling that needs a flat sorted view of
     * the full chapter catalogue.
     *
     * @return a {@code Flux} streaming all chapter rows in ascending
     *         {@code sort_order}; empty if no chapters are registered
     */
    Flux<Chapter> findAllByOrderBySortOrderAsc();

    /**
     * Returns all chapters belonging to the given game, ordered by
     * {@code sort_order} ascending.  Used by the lobby chapter-chooser to
     * present playable stages in their intended sequence.
     *
     * @param gameId the surrogate id of the parent game
     * @return a {@code Flux} streaming that game's chapter rows in ascending
     *         {@code sort_order}; empty if the game has no chapters
     */
    Flux<Chapter> findByGameIdOrderBySortOrderAsc(Long gameId);
}
