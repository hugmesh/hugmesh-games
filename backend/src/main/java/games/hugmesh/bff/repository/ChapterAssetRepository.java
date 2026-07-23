package games.hugmesh.bff.repository;

import games.hugmesh.bff.model.ChapterAsset;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Reactive R2DBC repository for {@code chapter_assets} rows in Postgres.
 *
 * <p>A {@link ChapterAsset} is a named binary or URL asset attached to a
 * chapter — for example, a background image, audio file, or supplemental
 * resource the Godot WASM bundle references at runtime. Assets are
 * keyed by a human-readable {@code label} within the scope of their chapter.
 *
 * <p>The primary query patterns are: list all assets for a chapter (used
 * when building the scene config response) and fetch a single asset by
 * chapter + label (used when a bundle requests a specific named resource).
 */
public interface ChapterAssetRepository extends ReactiveCrudRepository<ChapterAsset, Long> {

    // -------------------------------------------------------------------------
    // Chapter-scoped lookups
    // -------------------------------------------------------------------------

    /**
     * Returns all assets belonging to the given chapter.
     *
     * @param chapterId the surrogate id of the parent chapter
     * @return a {@code Flux} streaming every asset row for that chapter;
     *         empty if the chapter has no associated assets
     */
    Flux<ChapterAsset> findByChapterId(Long chapterId);

    /**
     * Returns a single asset identified by its chapter and label.
     *
     * <p>The {@code (chapter_id, label)} pair is unique — a label names
     * exactly one asset within a chapter's asset set.
     *
     * @param chapterId the surrogate id of the parent chapter
     * @param label     the human-readable asset label (e.g. {@code "background"})
     * @return a {@code Mono} emitting the matching asset row, or empty if
     *         no asset with that label exists for the chapter
     */
    Mono<ChapterAsset> findByChapterIdAndLabel(Long chapterId, String label);
}
