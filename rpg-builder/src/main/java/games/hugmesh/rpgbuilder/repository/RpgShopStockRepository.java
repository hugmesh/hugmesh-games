package games.hugmesh.rpgbuilder.repository;

import games.hugmesh.rpgbuilder.entity.RpgShopStock;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.UUID;

public interface RpgShopStockRepository extends ReactiveCrudRepository<RpgShopStock, UUID> {
    Flux<RpgShopStock> findByBuildingId(UUID buildingId);
    Flux<RpgShopStock> findByProjectId(UUID projectId);
    Mono<Void> deleteByBuildingId(UUID buildingId);
}
