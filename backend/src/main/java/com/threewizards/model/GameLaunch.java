package com.threewizards.model;

import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Table;

import java.time.Instant;

/**
 * One launched game session (table: {@code game_launches}, migration 020).
 *
 * <p>Holds the URL-safe random UID the federated remote uses as a path
 * component when fetching the WASM bundle from
 * {@code /api/assets/levels/{slug}/{uid}/...}. {@code uid} is unique;
 * looking up a launch is keyed on it.
 */
@Table("game_launches")
public class GameLaunch {

    @Id
    private Long id;
    private String uid;
    private String gameSlug;
    private String casdoorUserId;
    private Instant startedAt;
    private Instant expiresAt;
    private Instant terminatedAt;

    public Long getId() { return id; }
    public String getUid() { return uid; }
    public String getGameSlug() { return gameSlug; }
    public String getCasdoorUserId() { return casdoorUserId; }
    public Instant getStartedAt() { return startedAt; }
    public Instant getExpiresAt() { return expiresAt; }
    public Instant getTerminatedAt() { return terminatedAt; }

    public void setUid(String uid) { this.uid = uid; }
    public void setGameSlug(String gameSlug) { this.gameSlug = gameSlug; }
    public void setCasdoorUserId(String casdoorUserId) { this.casdoorUserId = casdoorUserId; }
    public void setExpiresAt(Instant expiresAt) { this.expiresAt = expiresAt; }
    public void setTerminatedAt(Instant terminatedAt) { this.terminatedAt = terminatedAt; }
}
