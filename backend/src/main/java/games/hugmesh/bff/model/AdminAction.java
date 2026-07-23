package games.hugmesh.bff.model;

import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Table;

import java.time.Instant;

/**
 * One row in {@code admin_actions}. Append-only — kick/ban/unban
 * history for moderator decisions. The latest row for a given
 * target_casdoor_id determines the player's effective state.
 *
 * <p>{@link Action} is open-coded as a string so the entity stays
 * compatible with R2DBC's row mapping (no enum codec on the path).
 * The DB CHECK constraint pins the value set.
 *
 * @see games.hugmesh.bff.repository.AdminActionRepository
 */
@Table("admin_actions")
public class AdminAction {

    /** Closed action vocabulary — mirrors the chk_admin_actions_action
     *  constraint added by Liquibase 029-2. */
    public static final String KICK  = "kick";
    public static final String BAN   = "ban";
    public static final String UNBAN = "unban";

    // -------------------------------------------------------------------------
    // Persistent fields
    // -------------------------------------------------------------------------

    /** Auto-generated surrogate primary key for the {@code admin_actions} row. */
    @Id
    private Long id;

    /** Casdoor subject of the player this action targets (the affected user). */
    private String targetCasdoorId;

    /** Casdoor subject of the mod or admin who performed the action. */
    private String actorCasdoorId;

    /**
     * The moderation verb applied — one of {@link #KICK}, {@link #BAN}, or
     * {@link #UNBAN}. Constrained at the DB level via {@code chk_admin_actions_action}.
     */
    private String action;

    /**
     * Optional human-readable reason the actor provided when taking the action.
     * Shown to the target player and visible in the admin audit log.
     */
    private String message;

    /**
     * Optional expiry timestamp for temporary bans. {@code null} means the action
     * has no automatic expiry (permanent until an explicit UNBAN row is appended).
     */
    private Instant expiresAt;

    /** Wall-clock time at which this row was inserted. Never updated — this table is append-only. */
    private Instant createdAt;

    // -------------------------------------------------------------------------
    // Accessors
    // -------------------------------------------------------------------------

    public Long getId() { return id; }
    public String getTargetCasdoorId() { return targetCasdoorId; }
    public String getActorCasdoorId() { return actorCasdoorId; }
    public String getAction() { return action; }
    public String getMessage() { return message; }
    public Instant getExpiresAt() { return expiresAt; }
    public Instant getCreatedAt() { return createdAt; }

    public void setId(Long id) { this.id = id; }
    public void setTargetCasdoorId(String v) { this.targetCasdoorId = v; }
    public void setActorCasdoorId(String v) { this.actorCasdoorId = v; }
    public void setAction(String v) { this.action = v; }
    public void setMessage(String v) { this.message = v; }
    public void setExpiresAt(Instant v) { this.expiresAt = v; }
    public void setCreatedAt(Instant v) { this.createdAt = v; }
}
