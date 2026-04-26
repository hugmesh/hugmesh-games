package games.snapdragon.bff.model;

import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Table;
import java.time.Instant;

@Table("player_sessions")
public class PlayerSession {

    @Id
    private Long id;
    private String sessionToken;
    private String casdoorId;
    private Long characterId;
    private boolean isController;
    private Instant connectedAt;
    private Instant lastPing;
    private Instant disconnectedAt;

    public Long getId() { return id; }
    public String getSessionToken() { return sessionToken; }
    public String getCasdoorId() { return casdoorId; }
    public Long getCharacterId() { return characterId; }
    public boolean isController() { return isController; }
    public Instant getConnectedAt() { return connectedAt; }
    public Instant getLastPing() { return lastPing; }
    public Instant getDisconnectedAt() { return disconnectedAt; }

    public void setSessionToken(String sessionToken) { this.sessionToken = sessionToken; }
    public void setCasdoorId(String casdoorId) { this.casdoorId = casdoorId; }
    public void setCharacterId(Long characterId) { this.characterId = characterId; }
    public void setController(boolean controller) { isController = controller; }
    public void setConnectedAt(Instant connectedAt) { this.connectedAt = connectedAt; }
    public void setLastPing(Instant lastPing) { this.lastPing = lastPing; }
    public void setDisconnectedAt(Instant disconnectedAt) { this.disconnectedAt = disconnectedAt; }
}
