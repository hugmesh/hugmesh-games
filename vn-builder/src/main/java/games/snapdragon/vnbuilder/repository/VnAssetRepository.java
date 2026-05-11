package games.snapdragon.vnbuilder.repository;

import games.snapdragon.vnbuilder.entity.VnAsset;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import reactor.core.publisher.Flux;

import java.util.UUID;

public interface VnAssetRepository extends ReactiveCrudRepository<VnAsset, UUID> {
    Flux<VnAsset> findByProjectIdOrderByUploadedAtDesc(UUID projectId);
}
