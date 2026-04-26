package games.snapdragon.bff.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;

/**
 * Reactive event bus backed by PostgreSQL pg_notify.
 * Publishes to an in-process Sinks for immediate SSE delivery
 * and calls pg_notify for cross-instance durability.
 */
@Service
public class PgNotificationService {

    private static final Logger log = LoggerFactory.getLogger(PgNotificationService.class);

    private final DatabaseClient db;
    private final Sinks.Many<String> sink = Sinks.many().multicast().onBackpressureBuffer(256);

    public PgNotificationService(DatabaseClient db) {
        this.db = db;
    }

    public Mono<Void> publish(String channel, String payload) {
        sink.tryEmitNext(payload);
        return db.sql("SELECT pg_notify(:channel, :payload)")
                .bind("channel", (Object) channel)
                .bind("payload", (Object) payload)
                .fetch()
                .one()
                .doOnError(e -> log.warn("pg_notify failed: {}", e.getMessage()))
                .onErrorComplete()
                .then();
    }

    public Flux<String> events() {
        return sink.asFlux();
    }
}
