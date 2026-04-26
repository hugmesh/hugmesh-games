package games.snapdragon.bff.repository;

import games.snapdragon.bff.model.InputEventType;
import org.springframework.data.r2dbc.repository.Query;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Reference reads against {@code input_event_types}. Used by the
 * input-event ingestion path on every emit to validate that
 * {@code (kind, type)} is a known pair before inserting an
 * {@code input_events} row.
 *
 * <p>The query path is hot — every keystroke / pad-axis-tick / mouse
 * move comes through it — so we keep it cheap: an indexed PK lookup
 * on the composite primary key. The set of known types is small
 * (tens of rows), so callers may also fully load the table at boot
 * and do the lookup in-process for an even tighter loop.
 */
public interface InputEventTypeRepository extends ReactiveCrudRepository<InputEventType, String> {

    @Query("SELECT kind, type, description, " +
           "       example_payload::text AS example_payload, " +
           "       payload_schema::text AS payload_schema " +
           "  FROM input_event_types " +
           " WHERE kind = :kind AND type = :type")
    Mono<InputEventType> findByKindAndType(String kind, String type);

    @Query("SELECT kind, type, description, " +
           "       example_payload::text AS example_payload, " +
           "       payload_schema::text AS payload_schema " +
           "  FROM input_event_types " +
           " ORDER BY kind, type")
    Flux<InputEventType> findAllOrdered();
}
