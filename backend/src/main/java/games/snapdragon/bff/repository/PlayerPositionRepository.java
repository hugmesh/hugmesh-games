package games.snapdragon.bff.repository;

import games.snapdragon.bff.model.PlayerPosition;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

public interface PlayerPositionRepository extends ReactiveCrudRepository<PlayerPosition, Long> {

    /**
     * Authoritative durable lookup. Liquibase 027 added the
     * uq_position_character_level constraint so this returns at most
     * one row.
     */
    Mono<PlayerPosition> findByCharacterIdAndChapterId(Long characterId, Long chapterId);

    /**
     * All persisted positions for one character — used on session
     * connect to hydrate every level the player has touched, not just
     * the one they're currently viewing.
     */
    Flux<PlayerPosition> findAllByCharacterId(Long characterId);

    /**
     * All persisted positions for one level — used by emitWorldState
     * to broadcast the full set of characters present in a level when
     * a session member connects (cross-pod cold-start).
     */
    Flux<PlayerPosition> findAllByChapterId(Long chapterId);
}
