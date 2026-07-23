package games.hugmesh.vnbuilder.repository;

import games.hugmesh.vnbuilder.entity.VnAct;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import reactor.core.publisher.Flux;

import java.util.UUID;

public interface VnActRepository extends ReactiveCrudRepository<VnAct, UUID> {
    Flux<VnAct> findByProjectIdOrderBySortOrderAscNameAsc(UUID projectId);
}
