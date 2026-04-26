package games.snapdragon.bff.model;

import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Table;
import java.time.Instant;

@Table("game_time_state")
public class GameTimeState {

    @Id
    private Long id;
    private int dayNumber;
    private int hour;
    private int minute;
    private Instant realStartedAt;
    private Instant lastPersistedAt;

    public Long getId() { return id; }
    public int getDayNumber() { return dayNumber; }
    public int getHour() { return hour; }
    public int getMinute() { return minute; }
    public Instant getRealStartedAt() { return realStartedAt; }
    public Instant getLastPersistedAt() { return lastPersistedAt; }

    public void setDayNumber(int dayNumber) { this.dayNumber = dayNumber; }
    public void setHour(int hour) { this.hour = hour; }
    public void setMinute(int minute) { this.minute = minute; }
    public void setLastPersistedAt(Instant lastPersistedAt) { this.lastPersistedAt = lastPersistedAt; }
}
