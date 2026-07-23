package games.hugmesh.vnbuilder.repository;

import games.hugmesh.vnbuilder.entity.BuildStatus;
import games.hugmesh.vnbuilder.entity.VnBuildJob;
import org.springframework.data.r2dbc.repository.Query;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.UUID;

public interface VnBuildJobRepository extends ReactiveCrudRepository<VnBuildJob, UUID> {
    Flux<VnBuildJob> findByProjectIdOrderByCreatedAtDesc(UUID projectId);
    Flux<VnBuildJob> findByStatus(BuildStatus status);

    @Query("UPDATE vn_build_jobs SET status = 'RENDERING', started_at = now() WHERE id = (" +
            "  SELECT id FROM vn_build_jobs WHERE status = 'QUEUED' ORDER BY created_at ASC LIMIT 1 FOR UPDATE SKIP LOCKED" +
            ") RETURNING *")
    Mono<VnBuildJob> claimNextQueued();
}
