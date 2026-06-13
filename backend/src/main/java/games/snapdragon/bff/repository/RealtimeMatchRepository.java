package games.snapdragon.bff.repository;

import games.snapdragon.bff.model.realtime.RealtimeMatch;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;

public interface RealtimeMatchRepository extends ReactiveCrudRepository<RealtimeMatch, String> {
}
