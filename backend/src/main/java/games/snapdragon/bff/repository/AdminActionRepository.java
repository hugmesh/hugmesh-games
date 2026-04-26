package games.snapdragon.bff.repository;

import games.snapdragon.bff.model.AdminAction;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

public interface AdminActionRepository extends ReactiveCrudRepository<AdminAction, Long> {

    /**
     * Newest action first. The frontend's banned page reads index 0 to
     * resolve "are you banned" and "what message did the admin leave?"
     * in one call.
     */
    Flux<AdminAction> findByTargetCasdoorIdOrderByCreatedAtDesc(String targetCasdoorId);

    /** Just the latest. */
    Mono<AdminAction> findFirstByTargetCasdoorIdOrderByCreatedAtDesc(String targetCasdoorId);
}
