package games.hugmesh.bff.model;

import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

import java.time.Instant;

/**
 * Per-game mod scope. Casdoor's `mod` role grants the *capability*;
 * the rows in this table say *which games* the user may moderate.
 *
 * <p>Active scope = {@code revoked_at IS NULL}. Soft-deleted on revoke
 * so the audit trail (granted_by + revoked_by) stays queryable.
 */
@Table("mod_game_scopes")
public class ModGameScope {

    @Id
    private Long id;
    @Column("casdoor_id")
    private String casdoorId;
    @Column("game_slug")
    private String gameSlug;
    @Column("granted_by_casdoor_id")
    private String grantedByCasdoorId;
    @Column("granted_at")
    private Instant grantedAt;
    @Column("revoked_at")
    private Instant revokedAt;
    @Column("revoked_by_casdoor_id")
    private String revokedByCasdoorId;

    public Long    getId() { return id; }
    public String  getCasdoorId() { return casdoorId; }
    public String  getGameSlug() { return gameSlug; }
    public String  getGrantedByCasdoorId() { return grantedByCasdoorId; }
    public Instant getGrantedAt() { return grantedAt; }
    public Instant getRevokedAt() { return revokedAt; }
    public String  getRevokedByCasdoorId() { return revokedByCasdoorId; }

    public void setId(Long id) { this.id = id; }
    public void setCasdoorId(String casdoorId) { this.casdoorId = casdoorId; }
    public void setGameSlug(String gameSlug) { this.gameSlug = gameSlug; }
    public void setGrantedByCasdoorId(String grantedByCasdoorId) { this.grantedByCasdoorId = grantedByCasdoorId; }
    public void setGrantedAt(Instant grantedAt) { this.grantedAt = grantedAt; }
    public void setRevokedAt(Instant revokedAt) { this.revokedAt = revokedAt; }
    public void setRevokedByCasdoorId(String revokedByCasdoorId) { this.revokedByCasdoorId = revokedByCasdoorId; }
}
