package games.hugmesh.rpgbuilder.repository;

import games.hugmesh.rpgbuilder.entity.RpgAvatarConfig;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import reactor.core.publisher.Mono;

import java.util.UUID;

public interface RpgAvatarConfigRepository extends ReactiveCrudRepository<RpgAvatarConfig, UUID> {
    Mono<RpgAvatarConfig> findByProjectId(UUID projectId);
}
