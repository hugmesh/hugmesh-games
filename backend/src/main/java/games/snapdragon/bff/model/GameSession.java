package games.snapdragon.bff.model;

import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.Transient;
import org.springframework.data.domain.Persistable;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

import java.time.Instant;

/**
 * A multiplayer session — a named container that one or more players join to
 * share game state.
 *
 * <p>Created by a player from the game-menu picker; persists in Postgres so
 * the session list survives pod restarts. Hot-path control fan-out lives in
 * {@code GameStateService}'s per-session sinks; this row is read on
 * create/list/join/leave only — never on every control event.
 *
 * <p>{@code closedAt = null} marks the session as open. {@code maxPlayers}
 * caps concurrent membership; for slot-based games like three-wizards the
 * cap matches the slot count (three). {@code visibility} is one of
 * {@code public} / {@code friends} / {@code private}: public sessions are
 * always discoverable, {@code friends} sessions are only visible to the
 * creator's friends, and {@code private} sessions are invite-only (joined
 * via direct link / id).
 *
 * <p>This entity implements {@link Persistable} because the id is assigned
 * by the application (UUID), not the database. Without that, R2DBC's
 * generic {@code save()} can't tell whether a pre-id'd row is new and
 * issues an UPDATE that affects zero rows.
 */
@Table("game_sessions")
public class GameSession implements Persistable<String> {

    @Id
    private String id;
    private String name;
    @Column("game_slug")
    private String gameSlug;
    @Column("creator_casdoor_id")
    private String creatorCasdoorId;
    @Column("max_players")
    private int maxPlayers;
    @Column("visibility")
    private String visibility;
    @Column("created_at")
    private Instant createdAt;
    @Column("closed_at")
    private Instant closedAt;

    @Transient
    private boolean newRecord;

    public String  getId() { return id; }
    public String  getName() { return name; }
    public String  getGameSlug() { return gameSlug; }
    public String  getCreatorCasdoorId() { return creatorCasdoorId; }
    public int     getMaxPlayers() { return maxPlayers; }
    public String  getVisibility() { return visibility; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getClosedAt() { return closedAt; }

    public void setId(String id) { this.id = id; }
    public void setName(String name) { this.name = name; }
    public void setGameSlug(String gameSlug) { this.gameSlug = gameSlug; }
    public void setCreatorCasdoorId(String creatorCasdoorId) { this.creatorCasdoorId = creatorCasdoorId; }
    public void setMaxPlayers(int maxPlayers) { this.maxPlayers = maxPlayers; }
    public void setVisibility(String visibility) { this.visibility = visibility; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
    public void setClosedAt(Instant closedAt) { this.closedAt = closedAt; }

    public GameSession markNew() { this.newRecord = true; return this; }

    @Override
    public boolean isNew() { return newRecord; }
}
