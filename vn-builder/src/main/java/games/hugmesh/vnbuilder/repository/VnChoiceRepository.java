package games.hugmesh.vnbuilder.repository;

import games.hugmesh.vnbuilder.entity.VnChoice;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import reactor.core.publisher.Flux;

import java.util.UUID;

public interface VnChoiceRepository extends ReactiveCrudRepository<VnChoice, UUID> {
    Flux<VnChoice> findByLineIdOrderBySortOrderAsc(UUID lineId);
}
