package games.snapdragon.bff.repository;

import games.snapdragon.bff.model.SessionLogEntry;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Repository;
import reactor.core.publisher.Flux;

import java.time.Instant;

@Repository
public class SessionLogRepositoryImpl implements SessionLogRepository {

    private static final String SQL_BY_CASDOOR = """
            SELECT gs.id              AS session_id,
                   gs.name            AS session_name,
                   gs.game_slug       AS game_slug,
                   gs.visibility      AS visibility,
                   gs.creator_casdoor_id AS creator_casdoor_id,
                   sm.casdoor_id      AS member_casdoor_id,
                   sm.character_id    AS character_id,
                   sm.slot_index      AS slot_index,
                   sm.is_creator      AS is_creator,
                   sm.joined_at       AS joined_at,
                   sm.left_at         AS left_at,
                   gs.closed_at       AS session_closed_at
              FROM session_members sm
              JOIN game_sessions   gs ON gs.id = sm.session_id
             WHERE sm.casdoor_id = :casdoor
             ORDER BY sm.joined_at DESC
             LIMIT :limit
            """;

    private static final String SQL_BY_CASDOOR_AND_GAME = """
            SELECT gs.id              AS session_id,
                   gs.name            AS session_name,
                   gs.game_slug       AS game_slug,
                   gs.visibility      AS visibility,
                   gs.creator_casdoor_id AS creator_casdoor_id,
                   sm.casdoor_id      AS member_casdoor_id,
                   sm.character_id    AS character_id,
                   sm.slot_index      AS slot_index,
                   sm.is_creator      AS is_creator,
                   sm.joined_at       AS joined_at,
                   sm.left_at         AS left_at,
                   gs.closed_at       AS session_closed_at
              FROM session_members sm
              JOIN game_sessions   gs ON gs.id = sm.session_id
             WHERE sm.casdoor_id = :casdoor
               AND gs.game_slug  = :gameSlug
             ORDER BY sm.joined_at DESC
             LIMIT :limit
            """;

    private final DatabaseClient db;

    public SessionLogRepositoryImpl(DatabaseClient db) {
        this.db = db;
    }

    @Override
    public Flux<SessionLogEntry> findByCasdoor(String casdoorId, int limit) {
        return db.sql(SQL_BY_CASDOOR)
                .bind("casdoor", casdoorId)
                .bind("limit", clampLimit(limit))
                .map(SessionLogRepositoryImpl::map)
                .all();
    }

    @Override
    public Flux<SessionLogEntry> findByCasdoorAndGameSlug(String casdoorId, String gameSlug, int limit) {
        return db.sql(SQL_BY_CASDOOR_AND_GAME)
                .bind("casdoor", casdoorId)
                .bind("gameSlug", gameSlug)
                .bind("limit", clampLimit(limit))
                .map(SessionLogRepositoryImpl::map)
                .all();
    }

    private static int clampLimit(int limit) {
        if (limit < 1) return 25;
        return Math.min(limit, 500);
    }

    private static SessionLogEntry map(io.r2dbc.spi.Row row, io.r2dbc.spi.RowMetadata meta) {
        Boolean creator = row.get("is_creator", Boolean.class);
        Long charId = row.get("character_id", Long.class);
        Integer slot = row.get("slot_index", Integer.class);
        return new SessionLogEntry(
                row.get("session_id", String.class),
                row.get("session_name", String.class),
                row.get("game_slug", String.class),
                row.get("visibility", String.class),
                row.get("creator_casdoor_id", String.class),
                row.get("member_casdoor_id", String.class),
                charId,
                slot,
                Boolean.TRUE.equals(creator),
                row.get("joined_at", Instant.class),
                row.get("left_at", Instant.class),
                row.get("session_closed_at", Instant.class)
        );
    }
}
