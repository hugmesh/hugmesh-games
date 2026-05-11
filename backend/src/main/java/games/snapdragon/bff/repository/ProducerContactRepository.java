package games.snapdragon.bff.repository;

import games.snapdragon.bff.model.ProducerContact;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Producer / mod application intake (Liquibase 031). Append-only on
 * the create side; status mutation happens via admin review.
 */
public interface ProducerContactRepository
        extends ReactiveCrudRepository<ProducerContact, Long> {

    /** All submissions from a user, newest first. */
    Flux<ProducerContact> findByCasdoorUserIdOrderBySubmittedAtDesc(String casdoorUserId);

    /** Most-recent pending submission for the user — the canonical
     *  "are you in the queue" check the registration form uses to
     *  decide whether to disable the submit button. */
    Mono<ProducerContact> findFirstByCasdoorUserIdAndStatusOrderBySubmittedAtDesc(
            String casdoorUserId, String status);

    /** Admin queue: every PENDING submission across the org, newest
     *  first so an admin works through the backlog in arrival order. */
    Flux<ProducerContact> findByStatusOrderBySubmittedAtAsc(String status);
}
