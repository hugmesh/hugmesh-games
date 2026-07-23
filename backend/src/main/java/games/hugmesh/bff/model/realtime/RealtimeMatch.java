package games.hugmesh.bff.model.realtime;

import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.Transient;
import org.springframework.data.domain.Persistable;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

import java.time.Instant;

/**
 * Durable record of one server-authoritative real-time match
 * (docs/realtime-server-authoritative-design.md, P2). Cassandra holds
 * the LIVE truth (keyframes + command log via
 * {@code RealtimeTruthStore}); this Postgres row is the durable match
 * metadata + final result. One match per session — the session id IS
 * the match id.
 *
 * <p>Implements {@link Persistable} because the id is application-assigned
 * (the session id): without it, R2DBC's {@code save()} can't tell a
 * pre-id'd row is new and issues an UPDATE affecting zero rows (the
 * {@code GameSession} / {@code PlayerProfile} footgun). The start path
 * calls {@code markNew()}; the terminal update re-loads the row so the
 * flag stays false and {@code save()} UPDATEs.
 */
@Table("realtime_matches")
public class RealtimeMatch implements Persistable<String> {

    @Id
    @Column("session_id")
    private String sessionId;
    @Column("game_slug")
    private String gameSlug;
    @Column("kind")
    private String kind;
    @Column("seed")
    private long seed;
    @Column("tick_hz")
    private int tickHz;
    @Column("started_at")
    private Instant startedAt;
    @Column("ended_at")
    private Instant endedAt;
    @Column("result_json")
    private String resultJson;

    @Transient
    private boolean newRecord;

    public String  getSessionId() { return sessionId; }
    public String  getGameSlug() { return gameSlug; }
    public String  getKind() { return kind; }
    public long    getSeed() { return seed; }
    public int     getTickHz() { return tickHz; }
    public Instant getStartedAt() { return startedAt; }
    public Instant getEndedAt() { return endedAt; }
    public String  getResultJson() { return resultJson; }

    public void setSessionId(String s) { this.sessionId = s; }
    public void setGameSlug(String s) { this.gameSlug = s; }
    public void setKind(String s) { this.kind = s; }
    public void setSeed(long n) { this.seed = n; }
    public void setTickHz(int n) { this.tickHz = n; }
    public void setStartedAt(Instant t) { this.startedAt = t; }
    public void setEndedAt(Instant t) { this.endedAt = t; }
    public void setResultJson(String s) { this.resultJson = s; }

    public RealtimeMatch markNew() { this.newRecord = true; return this; }

    @Override public String getId() { return sessionId; }
    @Override public boolean isNew() { return newRecord; }
}
