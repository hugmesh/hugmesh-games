package games.hugmesh.vnbuilder.repository;

import games.hugmesh.vnbuilder.entity.VnProject;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.UUID;

public interface VnProjectRepository extends ReactiveCrudRepository<VnProject, UUID> {
    Flux<VnProject> findByOwnerCasdoorIdOrderByUpdatedAtDesc(String ownerCasdoorId);
    Mono<VnProject> findBySlug(String slug);
}
