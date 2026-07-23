package games.hugmesh.bff.repository;

import games.hugmesh.bff.model.GameTimeState;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;

/**
 * Reactive R2DBC repository for {@code game_time_states} rows in Postgres.
 *
 * <p>A {@link GameTimeState} records the in-world time state for a game
 * session — used by the game engine to persist day/night cycle position or
 * other time-based world state across session pauses and reconnects.
 *
 * <p>All standard CRUD operations are available via the inherited
 * {@link ReactiveCrudRepository} methods. No custom derived query methods
 * are needed; callers look up the time state by session id using
 * {@code findById}.
 */
public interface GameTimeRepository extends ReactiveCrudRepository<GameTimeState, Long> {}
