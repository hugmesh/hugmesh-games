package games.hugmesh.rpgbuilder.repository;

import games.hugmesh.rpgbuilder.entity.RpgAsset;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import reactor.core.publisher.Flux;

import java.util.UUID;

public interface RpgAssetRepository extends ReactiveCrudRepository<RpgAsset, UUID> {
    Flux<RpgAsset> findByProjectIdOrderByUploadedAtDesc(UUID projectId);

    /** Seed rows awaiting boot-time materialization ({@code seed:*}). */
    Flux<RpgAsset> findByStoragePathStartingWith(String prefix);
}
