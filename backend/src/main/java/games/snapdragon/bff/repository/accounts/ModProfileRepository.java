package games.snapdragon.bff.repository.accounts;

import games.snapdragon.bff.model.accounts.ModProfile;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import reactor.core.publisher.Flux;

/** Reactive R2DBC repo for {@code accounts.mods}. PK is the Casdoor
 *  user id. The {@code oncall_slot} accessor powers the dispute-
 *  routing pipeline. */
public interface ModProfileRepository
        extends ReactiveCrudRepository<ModProfile, String> {

    /** Every mod assigned to the given on-call slot. Used by the
     *  dispute router to pick a recipient. */
    Flux<ModProfile> findByOncallSlot(String oncallSlot);
}
