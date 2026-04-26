package games.snapdragon.bff.repository;

import games.snapdragon.bff.model.Item;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;

public interface ItemRepository extends ReactiveCrudRepository<Item, Long> {}
