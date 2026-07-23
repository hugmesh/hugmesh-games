package games.hugmesh.bff.model;

import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

import java.time.Instant;

/**
 * One row per (session, player) pair — a player's membership in a multiplayer
 * session. Append-only on join; {@code leftAt} stamps a row when the player
 * leaves so the membership history survives.
 *
 * <p>{@code slotIndex} is the slot that player owns inside the session
 * (slot 0/1/2 → wizard 1/2/3 in three-wizards). Two unique partial indexes
 * enforce that an active member holds at most one slot, and a slot is held
 * by at most one active member — see Liquibase 030-2.
 *
 * <p>{@code characterId} is the character a player is driving in this
 * session. May be null briefly between joining and slot-claim.
 */
@Table("session_members")
public class SessionMember {

    @Id
    private Long id;
    @Column("session_id")
    private String sessionId;
    @Column("casdoor_id")
    private String casdoorId;
    @Column("character_id")
    private Long characterId;
    @Column("slot_index")
    private Integer slotIndex;
    @Column("is_creator")
    private boolean creator;
    @Column("joined_at")
    private Instant joinedAt;
    @Column("left_at")
    private Instant leftAt;

    public Long    getId() { return id; }
    public String  getSessionId() { return sessionId; }
    public String  getCasdoorId() { return casdoorId; }
    public Long    getCharacterId() { return characterId; }
    public Integer getSlotIndex() { return slotIndex; }
    public boolean isCreator() { return creator; }
    public Instant getJoinedAt() { return joinedAt; }
    public Instant getLeftAt() { return leftAt; }

    public void setId(Long id) { this.id = id; }
    public void setSessionId(String sessionId) { this.sessionId = sessionId; }
    public void setCasdoorId(String casdoorId) { this.casdoorId = casdoorId; }
    public void setCharacterId(Long characterId) { this.characterId = characterId; }
    public void setSlotIndex(Integer slotIndex) { this.slotIndex = slotIndex; }
    public void setCreator(boolean creator) { this.creator = creator; }
    public void setJoinedAt(Instant joinedAt) { this.joinedAt = joinedAt; }
    public void setLeftAt(Instant leftAt) { this.leftAt = leftAt; }
}
