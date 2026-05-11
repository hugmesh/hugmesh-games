package games.snapdragon.rpgbuilder.repository;

import games.snapdragon.rpgbuilder.entity.BuildStatus;
import games.snapdragon.rpgbuilder.entity.RpgBuildJob;
import org.springframework.data.r2dbc.repository.Query;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.UUID;

public interface RpgBuildJobRepository extends ReactiveCrudRepository<RpgBuildJob, UUID> {
    Flux<RpgBuildJob> findByProjectIdOrderByCreatedAtDesc(UUID projectId);
    Flux<RpgBuildJob> findByStatus(BuildStatus status);

    /**
     * Atomic claim — flips the oldest QUEUED row to RENDERING and returns
     * its row in one round-trip. Lets the worker scheduler call this from
     * multiple instances without racing duplicate executions.
     */
    @Query("UPDATE rpg_build_jobs SET status = 'RENDERING', started_at = now() WHERE id = (" +
            "  SELECT id FROM rpg_build_jobs WHERE status = 'QUEUED' ORDER BY created_at ASC LIMIT 1 FOR UPDATE SKIP LOCKED" +
            ") RETURNING *")
    Mono<RpgBuildJob> claimNextQueued();
}
