package games.snapdragon.bff.repository;

import games.snapdragon.bff.model.GameTimeState;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;

public interface GameTimeRepository extends ReactiveCrudRepository<GameTimeState, Long> {}
