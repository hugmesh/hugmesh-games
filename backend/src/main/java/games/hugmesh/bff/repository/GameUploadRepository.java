package games.hugmesh.bff.repository;

import games.hugmesh.bff.model.GameUpload;
import org.springframework.data.r2dbc.repository.Modifying;
import org.springframework.data.r2dbc.repository.Query;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

public interface GameUploadRepository extends ReactiveCrudRepository<GameUpload, Long> {

    /** History rows for one slug, newest first — drives the rollback UI. */
    Flux<GameUpload> findBySlugOrderByUploadedAtDesc(String slug);

    /** Currently-active upload for a slug (latest non-superseded row). */
    @Query("""
        SELECT * FROM game_uploads
         WHERE slug = :slug AND superseded_at IS NULL
         ORDER BY uploaded_at DESC LIMIT 1
        """)
    Mono<GameUpload> findActiveBySlug(String slug);

    /** Mark every prior upload for this slug as superseded. */
    @Modifying
    @Query("""
        UPDATE game_uploads
           SET superseded_at = NOW()
         WHERE slug = :slug AND superseded_at IS NULL AND id <> :keepId
        """)
    Mono<Integer> markPriorsSuperseded(String slug, Long keepId);
}
