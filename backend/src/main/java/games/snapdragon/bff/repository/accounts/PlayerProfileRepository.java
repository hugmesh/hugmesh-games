package games.snapdragon.bff.repository.accounts;

import games.snapdragon.bff.model.accounts.PlayerProfile;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;

/** Reactive R2DBC repo for {@code accounts.players}. PK is the
 *  Casdoor user id (text) — one row per user. */
public interface PlayerProfileRepository
        extends ReactiveCrudRepository<PlayerProfile, String> {
}
