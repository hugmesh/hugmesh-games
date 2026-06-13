package games.snapdragon.bff.model.accounts;

import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.Transient;
import org.springframework.data.domain.Persistable;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

import java.time.Instant;

/**
 * One row in {@code accounts.players}. Lazy-populated on the first
 * authenticated request a player makes — the auth filter chain
 * hands the JWT subject to {@code ProfileService.ensurePlayer()} which
 * creates the row if missing.
 *
 * <p>This is INTENTIONALLY thin — it carries the per-role surface
 * (XP, friend/block counts) that's specific to the player role and
 * leaves character / session / quest state to the existing tables.
 * The split is "one row per (user, role)" so a single user with
 * multiple roles has multiple profile rows (one per
 * accounts.{players,producers,mods}). See decisions/020-per-role-
 * schema-split.md.
 */
@Table("accounts.players")
public class PlayerProfile implements Persistable<String> {

    @Id
    @Column("casdoor_user_id")
    private String casdoorUserId;

    @Column("display_name") private String displayName;
    @Column("xp_level")     private int xpLevel;
    @Column("friend_count") private int friendCount;
    @Column("block_count")  private int blockCount;
    @Column("created_at")   private Instant createdAt;
    @Column("updated_at")   private Instant updatedAt;

    public String getCasdoorUserId() { return casdoorUserId; }
    public String getDisplayName() { return displayName; }
    public int getXpLevel() { return xpLevel; }
    public int getFriendCount() { return friendCount; }
    public int getBlockCount() { return blockCount; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }

    public void setCasdoorUserId(String s) { this.casdoorUserId = s; }
    public void setDisplayName(String s) { this.displayName = s; }
    public void setXpLevel(int n) { this.xpLevel = n; }
    public void setFriendCount(int n) { this.friendCount = n; }
    public void setBlockCount(int n) { this.blockCount = n; }
    public void setCreatedAt(Instant t) { this.createdAt = t; }
    public void setUpdatedAt(Instant t) { this.updatedAt = t; }

    // ── Persistable ──────────────────────────────────────────────────
    // The id is application-assigned (the Casdoor subject), so Spring Data
    // R2DBC would otherwise treat a populated @Id as an existing row and
    // issue an UPDATE on save() — affecting 0 rows for a first-time player,
    // which surfaced as a 500 on GET /api/profile/me/player. Mirrors the
    // GameSession pattern: default newRecord=false (loaded rows aren't new);
    // the lazy-create path calls markNew() so save() INSERTs.
    @Transient
    private boolean newRecord;

    @Override public String getId() { return casdoorUserId; }
    @Override public boolean isNew() { return newRecord; }
    public PlayerProfile markNew() { this.newRecord = true; return this; }
}
