package games.hugmesh.bff.repository;

import games.hugmesh.bff.model.Item;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;

public interface ItemRepository extends ReactiveCrudRepository<Item, Long> {}
