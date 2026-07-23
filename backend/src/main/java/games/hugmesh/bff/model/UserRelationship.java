package games.hugmesh.bff.model;

import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

import java.time.Instant;

/**
 * One row per directional (user, other, relationship) edge.
 *
 * <p>{@code relationship} is one of:
 * <ul>
 *   <li>{@code friend_request} — user has invited other; flips to
 *       {@code friend} on accept (and a mirror row is created the
 *       other direction).</li>
 *   <li>{@code friend} — mutual; two rows exist (a→b and b→a).</li>
 *   <li>{@code blocked} — user has blocked other; one row only
 *       (the target never sees the block, by design).</li>
 * </ul>
 *
 * <p>Unique constraint on (user_casdoor_id, other_casdoor_id) — at most
 * one edge per ordered pair, so a friend/request flip rewrites in place
 * and a block silently overrides any existing friend edge in that
 * direction.
 */
@Table("user_relationships")
public class UserRelationship {

    public static final String FRIEND_REQUEST = "friend_request";
    public static final String FRIEND         = "friend";
    public static final String BLOCKED        = "blocked";

    @Id
    private Long id;

    @Column("user_casdoor_id")
    private String userCasdoorId;

    @Column("other_casdoor_id")
    private String otherCasdoorId;

    @Column("relationship")
    private String relationship;

    @Column("created_at")
    private Instant createdAt;

    @Column("updated_at")
    private Instant updatedAt;

    public Long    getId() { return id; }
    public String  getUserCasdoorId() { return userCasdoorId; }
    public String  getOtherCasdoorId() { return otherCasdoorId; }
    public String  getRelationship() { return relationship; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }

    public void setId(Long id) { this.id = id; }
    public void setUserCasdoorId(String v) { this.userCasdoorId = v; }
    public void setOtherCasdoorId(String v) { this.otherCasdoorId = v; }
    public void setRelationship(String v) { this.relationship = v; }
    public void setCreatedAt(Instant v) { this.createdAt = v; }
    public void setUpdatedAt(Instant v) { this.updatedAt = v; }
}
