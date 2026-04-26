package games.snapdragon.bff.repository;

import games.snapdragon.bff.model.PlayerSession;
import org.springframework.data.r2dbc.repository.Query;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Instant;

public interface PlayerSessionRepository extends ReactiveCrudRepository<PlayerSession, Long> {
    Mono<PlayerSession> findBySessionToken(String sessionToken);

    @Query("SELECT * FROM player_sessions WHERE casdoor_id = :casdoorId AND disconnected_at IS NULL LIMIT 1")
    Mono<PlayerSession> findByCasdoorId(String casdoorId);

    Mono<Void> deleteByCasdoorId(String casdoorId);

    @Query("SELECT * FROM player_sessions WHERE disconnected_at IS NULL")
    Flux<PlayerSession> findAllActive();

    @Query("SELECT * FROM player_sessions WHERE casdoor_id = :casdoorId ORDER BY connected_at DESC LIMIT 50")
    Flux<PlayerSession> findHistoryByCasdoorId(String casdoorId);

    @Query("""
           SELECT DISTINCT ON (ps.casdoor_id) ps.*
           FROM player_sessions ps
           WHERE ps.casdoor_id <> :casdoorId
             AND ps.connected_at < COALESCE(:endTime, NOW())
             AND COALESCE(ps.disconnected_at, NOW()) > :startTime
           """)
    Flux<PlayerSession> findOverlappingSessions(String casdoorId, Instant startTime, Instant endTime);
}
