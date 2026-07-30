package games.hugmesh.rpgbuilder.repository;

import games.hugmesh.rpgbuilder.entity.RpgExit;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.UUID;

public interface RpgExitRepository extends ReactiveCrudRepository<RpgExit, UUID> {
    Flux<RpgExit> findByProjectId(UUID projectId);
    Flux<RpgExit> findByFromRoomId(UUID fromRoomId);
    /** Exits pointing INTO a room. `to_room_id` deliberately has no DB
     *  cascade, so callers must clear these before deleting the room. */
    Mono<Void> deleteByToRoomId(UUID toRoomId);
    /** Every exit touching any of the given rooms from either end —
     *  used by building delete to clear stair exits AND user-created
     *  exits into/out of interior rooms in one shot. */
    Mono<Void> deleteByFromRoomIdInOrToRoomIdIn(java.util.Collection<UUID> fromRoomIds,
                                                java.util.Collection<UUID> toRoomIds);
}
