package games.hugmesh.vnbuilder.repository;

import games.hugmesh.vnbuilder.entity.VnBackground;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import reactor.core.publisher.Flux;

import java.util.UUID;

public interface VnBackgroundRepository extends ReactiveCrudRepository<VnBackground, UUID> {
    Flux<VnBackground> findByProjectIdOrderByNameAsc(UUID projectId);
}
