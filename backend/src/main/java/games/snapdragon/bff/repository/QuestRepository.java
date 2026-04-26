package games.snapdragon.bff.repository;

import games.snapdragon.bff.model.Quest;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;

public interface QuestRepository extends ReactiveCrudRepository<Quest, Long> {}
