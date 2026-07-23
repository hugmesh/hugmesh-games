package games.hugmesh.bff.repository;

import games.hugmesh.bff.model.realtime.RealtimeMatch;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;

public interface RealtimeMatchRepository extends ReactiveCrudRepository<RealtimeMatch, String> {
}
