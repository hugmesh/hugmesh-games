package games.snapdragon.rpgbuilder.repository;

import games.snapdragon.rpgbuilder.entity.RpgProject;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.UUID;

public interface RpgProjectRepository extends ReactiveCrudRepository<RpgProject, UUID> {
    Flux<RpgProject> findByOwnerCasdoorIdOrderByUpdatedAtDesc(String ownerCasdoorId);
    Mono<RpgProject> findBySlug(String slug);
}
