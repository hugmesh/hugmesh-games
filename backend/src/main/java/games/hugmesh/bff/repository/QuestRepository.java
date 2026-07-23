package games.hugmesh.bff.repository;

import games.hugmesh.bff.model.Quest;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;

public interface QuestRepository extends ReactiveCrudRepository<Quest, Long> {}
