package com.threewizards.model;

import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Table;

import java.time.Instant;

/**
 * Per-game access policy row (table: {@code game_access}). One game has 1..N
 * rows; the union grants visibility. See migration 019 for the schema.
 *
 * <p>Boundary: {@code scope_value} stores Casdoor role names / user ids as
 * plain strings. The role catalog stays in Casdoor; this table is metadata
 * about which Casdoor identity gets to see which game.
 */
@Table("game_access")
public class GameAccess {

    @Id
    private Long id;
    private Long gameId;
    /** {@code 'all' | 'role' | 'user'} */
    private String scope;
    /** Role name when {@code scope='role'}; Casdoor user id when {@code scope='user'}; null when {@code scope='all'}. */
    private String scopeValue;
    private String grantedBy;
    private Instant grantedAt;

    public Long getId() { return id; }
    public Long getGameId() { return gameId; }
    public String getScope() { return scope; }
    public String getScopeValue() { return scopeValue; }
    public String getGrantedBy() { return grantedBy; }
    public Instant getGrantedAt() { return grantedAt; }

    public void setGameId(Long gameId) { this.gameId = gameId; }
    public void setScope(String scope) { this.scope = scope; }
    public void setScopeValue(String scopeValue) { this.scopeValue = scopeValue; }
    public void setGrantedBy(String grantedBy) { this.grantedBy = grantedBy; }
}
