package games.hugmesh.bff.repository;

import games.hugmesh.bff.model.ChapterPrerequisite;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import reactor.core.publisher.Flux;

/**
 * Reactive R2DBC repository for {@code chapter_prerequisites} rows in Postgres.
 *
 * <p>A {@link ChapterPrerequisite} links a chapter to another chapter that
 * must be completed before the linked chapter becomes accessible. The
 * unlock / gating logic in the chapter service loads prerequisite rows
 * for a given chapter to determine whether the player's progress satisfies
 * all required completions.
 */
public interface ChapterPrerequisiteRepository extends ReactiveCrudRepository<ChapterPrerequisite, Long> {

    // -------------------------------------------------------------------------
    // Chapter-scoped lookups
    // -------------------------------------------------------------------------

    /**
     * Returns all prerequisite entries for a given chapter — i.e. every
     * other chapter that must be completed before {@code chapterId} unlocks.
     *
     * @param chapterId the surrogate id of the chapter whose prerequisites
     *                  are to be loaded
     * @return a {@code Flux} streaming every prerequisite row for that chapter;
     *         empty if the chapter has no prerequisites (always accessible)
     */
    Flux<ChapterPrerequisite> findByChapterId(Long chapterId);
}
