package games.hugmesh.bff.repository;

import io.r2dbc.spi.Row;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Repository;

import java.util.Map;

import games.hugmesh.bff.model.InputEvent;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Append-only writes into {@code input_events} + replay queries.
 *
 * <p>Implemented directly against {@link DatabaseClient} (not
 * {@code ReactiveCrudRepository}) for two reasons:
 *
 * <ol>
 *   <li>Composite FK to {@code input_event_types(kind, type)} —
 *       Spring Data R2DBC's repository derivation doesn't ergonomically
 *       handle composite-key relationships, and the row id we want to
 *       generate is the synthetic bigserial, not the FK pair.</li>
 *   <li>Payload column is {@code jsonb}; the driver's default for
 *       {@code Map<String, Object>} round-trips through {@code String}
 *       which we want to control here so the upload path can
 *       fast-path-reject malformed JSON.</li>
 * </ol>
 *
 * <p>Reads are kept narrow: per-character recent + per-session recent
 * are the two queries the dashboard + replay tooling care about.
 */
@Repository
public class InputEventRepository {

    private final DatabaseClient db;

    public InputEventRepository(DatabaseClient db) {
        this.db = db;
    }

    /**
     * Insert one event. Returns the persisted row with id +
     * occurredAt populated. The caller has already checked that
     * {@code (kind, type)} is registered AND that the game's feature
     * flag accepts the event kind.
     */
    public Mono<InputEvent> insert(InputEvent ev, String payloadJson) {
        return db.sql("""
                INSERT INTO input_events
                    (game_slug, character_id, session_id, casdoor_user_id,
                     event_kind, event_type, payload)
                VALUES
                    (:gameSlug, :characterId, :sessionId, :casdoorUserId,
                     :kind, :type, :payload::jsonb)
                RETURNING id, occurred_at
                """)
                .bind("gameSlug",      ev.gameSlug())
                .bind("characterId",   bindNullable(ev.characterId()))
                .bind("sessionId",     ev.sessionId())
                .bind("casdoorUserId", bindNullable(ev.casdoorUserId()))
                .bind("kind",          ev.kind())
                .bind("type",          ev.type())
                .bind("payload",       payloadJson)
                .map((row, meta) -> new InputEvent(
                        row.get("id", Long.class),
                        ev.gameSlug(),
                        ev.characterId(),
                        ev.sessionId(),
                        ev.casdoorUserId(),
                        ev.kind(),
                        ev.type(),
                        ev.payload(),
                        row.get("occurred_at", java.time.Instant.class)
                ))
                .first();
    }

    /** Recent events for a character — newest first. */
    public Flux<InputEventRow> findRecentByCharacterId(long characterId, int limit) {
        return db.sql("""
                SELECT id, game_slug, character_id, session_id,
                       casdoor_user_id, event_kind, event_type,
                       payload::text AS payload_json, occurred_at
                  FROM input_events
                 WHERE character_id = :characterId
                 ORDER BY occurred_at DESC
                 LIMIT :limit
                """)
                .bind("characterId", characterId)
                .bind("limit",       limit)
                .map(InputEventRepository::map)
                .all();
    }

    /** Recent events for a session — newest first. */
    public Flux<InputEventRow> findRecentBySessionId(String sessionId, int limit) {
        return db.sql("""
                SELECT id, game_slug, character_id, session_id,
                       casdoor_user_id, event_kind, event_type,
                       payload::text AS payload_json, occurred_at
                  FROM input_events
                 WHERE session_id = :sessionId
                 ORDER BY occurred_at DESC
                 LIMIT :limit
                """)
                .bind("sessionId", sessionId)
                .bind("limit",     limit)
                .map(InputEventRepository::map)
                .all();
    }

    private static InputEventRow map(Row row, io.r2dbc.spi.RowMetadata meta) {
        return new InputEventRow(
                row.get("id", Long.class),
                row.get("game_slug", String.class),
                row.get("character_id", Long.class),
                row.get("session_id", String.class),
                row.get("casdoor_user_id", String.class),
                row.get("event_kind", String.class),
                row.get("event_type", String.class),
                row.get("payload_json", String.class),
                row.get("occurred_at", java.time.Instant.class)
        );
    }

    private static Object bindNullable(Object v) {
        return v == null ? io.r2dbc.spi.Parameters.in(io.r2dbc.spi.R2dbcType.VARCHAR) : v;
    }

    /**
     * Read shape for replay queries. Payload is left as a JSON string
     * — controllers Jackson-decode it lazily so a SELECT * doesn't
     * pay the parse cost for fields the caller doesn't render.
     */
    public record InputEventRow(
            Long id,
            String gameSlug,
            Long characterId,
            String sessionId,
            String casdoorUserId,
            String eventKind,
            String eventType,
            String payloadJson,
            java.time.Instant occurredAt
    ) {}
}
