package games.snapdragon.bff.repository.accounts;

import games.snapdragon.bff.model.accounts.ProducerProfile;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import reactor.core.publisher.Mono;

/** Reactive R2DBC repo for {@code accounts.producers}. PK is the
 *  Casdoor user id; one row per producer. The {@code contact_email}
 *  index supports a future "dispute routing by email" lookup. */
public interface ProducerProfileRepository
        extends ReactiveCrudRepository<ProducerProfile, String> {

    Mono<ProducerProfile> findByContactEmail(String contactEmail);
}
