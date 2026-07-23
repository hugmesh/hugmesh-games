package games.hugmesh.bff.service;

import games.hugmesh.bff.model.GameTimeDto;
import games.hugmesh.bff.model.GameTimeState;
import games.hugmesh.bff.repository.GameTimeRepository;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.DependsOn;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Manages the shared in-game clock and broadcasts time events to connected players.
 *
 * <h2>Responsibility</h2>
 *
 * <p>Maintains a server-authoritative in-game time state (day / hour / minute)
 * that advances at a fixed real-time cadence and is persisted periodically to the
 * {@code game_time} Postgres table. Time events are broadcast over two channels:
 * <ul>
 *   <li>an in-process Reactor {@link Sinks.Many} sink ({@link #timeSink}) consumed
 *       by SSE subscribers via {@link #timeStream()}; and</li>
 *   <li>a PostgreSQL {@code pg_notify} event on the {@code game_events} channel so
 *       other BFF pods see the tick as well.</li>
 * </ul>
 *
 * <h2>In-game time model</h2>
 *
 * <pre>
 *   1 game minute  = 1 real second    (PERSIST_INTERVAL  = 10 ticks → persist every 10 s)
 *   60 game minutes = 1 game hour
 *   24 game hours  = 1 game day
 *   SSE event fired every TIME_STREAM_INTERVAL game minutes (every 5 real seconds)
 * </pre>
 *
 * <p>The in-memory state is held in atomic integers ({@link #day}, {@link #hour},
 * {@link #min}) so the hot-path {@link #advanceTick} callback and the SSE read
 * path ({@link #currentTime}) operate without locking.
 *
 * <h2>Startup sequence</h2>
 *
 * <ol>
 *   <li>{@link #init()} is called by Spring after all dependencies are ready.</li>
 *   <li>It loads the single {@code game_time_state} row (id=1) from Postgres; if
 *       absent, it seeds a fresh row via {@link #seedInitialTime()}.</li>
 *   <li>The atomic counters are initialised from the loaded row.</li>
 *   <li>A {@link Flux#interval(Duration)} fires every real second, calling
 *       {@link #advanceTick} on each emission.</li>
 * </ol>
 *
 * <h2>{@code @DependsOn("liquibase")} rationale</h2>
 *
 * <p>Makes the {@code @PostConstruct} DB lookup wait until Liquibase migrations
 * have committed. In production this is a no-op (Spring Boot's
 * {@code LiquibaseAutoConfiguration} always runs first), but {@code @SpringBootTest}
 * brings beans up in parallel — without the explicit dependency, the R2DBC
 * connection pool opens connections before Liquibase commits and Postgres MVCC
 * pins those connections to a pre-migration snapshot. Same reason
 * {@code GameStateService} carries this annotation.
 *
 * <h2>Caller-role access matrix</h2>
 *
 * <pre>
 *   Player      — reads current time via SSE ({@link #timeStream()})
 *   Publisher   — not applicable
 *   Mod         — not applicable
 *   Admin       — observes via admin session stream (reads {@link #currentTime()})
 *   Superadmin  — same as Admin
 * </pre>
 *
 * @see GameTimeRepository
 * @see PgNotificationService
 */
@Service
@DependsOn("liquibase")
public class GameTimeService {

    private static final Logger log = LoggerFactory.getLogger(GameTimeService.class);

    /** Persist in-game time to Postgres every this many ticks (10 real seconds). */
    private static final int PERSIST_INTERVAL = 10;

    /** Emit a time event on the SSE sink every this many game minutes (5 real seconds). */
    private static final int TIME_STREAM_INTERVAL = 5;

    private final GameTimeRepository timeRepo;
    private final PgNotificationService notify;

    /** Primary key of the single {@code game_time_state} row; set from the DB on init. */
    private final AtomicLong dbId   = new AtomicLong(1);

    /** In-game day counter; initialised from the DB row, incremented on rollover. */
    private final AtomicInteger day  = new AtomicInteger(1);

    /** In-game hour [0, 23]; wraps to 0 on day rollover. */
    private final AtomicInteger hour = new AtomicInteger(6);

    /** In-game minute [0, 59]; wraps to 0 on hour rollover. */
    private final AtomicInteger min  = new AtomicInteger(0);

    /**
     * Multicast sink for in-game time events. Backpressure buffer of 16 is
     * ample — a slow subscriber that can't consume one event per 5 real seconds
     * indicates a broken connection, not a legitimately slow consumer.
     * {@code autoCancel = true} (default): the sink stays alive as long as at
     * least one downstream subscriber is present.
     */
    private final Sinks.Many<GameTimeDto> timeSink =
            Sinks.many().multicast().onBackpressureBuffer(16);

    public GameTimeService(GameTimeRepository timeRepo, PgNotificationService notify) {
        this.timeRepo = timeRepo;
        this.notify   = notify;
    }

    // -------------------------------------------------------------------------
    // Startup initialisation
    // -------------------------------------------------------------------------

    /**
     * Loads or seeds the in-game time state and starts the per-second tick loop.
     *
     * <p>Steps:
     * <ol>
     *   <li>Query {@code game_time} for the row with {@code id = 1}.</li>
     *   <li>If absent, insert a default row via {@link #seedInitialTime}.</li>
     *   <li>Hydrate the atomic counters from the loaded / seeded row and log the
     *       restored game time.</li>
     *   <li>Subscribe a {@link Flux#interval} that fires every real second and
     *       calls {@link #advanceTick} on each emission.</li>
     * </ol>
     */
    @PostConstruct
    public void init() {
        // Load the persisted game-time row; create it if this is a fresh deployment.
        timeRepo.findById(1L)
                .switchIfEmpty(Mono.defer(this::seedInitialTime))
                .doOnNext(state -> {
                    // Restore the atomic counters from the DB row.
                    dbId.set(state.getId());
                    day.set(state.getDayNumber());
                    hour.set(state.getHour());
                    min.set(state.getMinute());
                    log.info("Game time loaded: Day {} {}:{}", state.getDayNumber(),
                            String.format("%02d", state.getHour()),
                            String.format("%02d", state.getMinute()));
                })
                .subscribe();

        // Start the wall-clock tick loop — one game minute per real second.
        Flux.interval(Duration.ofSeconds(1))
                .subscribe(this::advanceTick);
    }

    /**
     * Inserts the default {@code game_time_state} row (day 1, 06:00).
     * Called once on a fresh deployment when no row exists.
     *
     * @return a {@link Mono} emitting the newly saved state
     */
    private Mono<GameTimeState> seedInitialTime() {
        return timeRepo.save(new GameTimeState());
    }

    // -------------------------------------------------------------------------
    // Tick loop
    // -------------------------------------------------------------------------

    /**
     * Called once per real second by the {@link Flux#interval} subscription in
     * {@link #init()}. Advances the in-game minute counter and handles hour and
     * day rollovers. Every {@link #PERSIST_INTERVAL} ticks the current state is
     * persisted asynchronously to Postgres. Every {@link #TIME_STREAM_INTERVAL}
     * game minutes a time event is emitted on the in-process SSE sink and
     * published via {@link PgNotificationService} for cross-pod delivery.
     *
     * @param tick monotonically increasing counter emitted by {@link Flux#interval}
     */
    private void advanceTick(Long tick) {
        // Advance the minute; roll over into the next hour if needed.
        int newMin = min.incrementAndGet();
        if (newMin >= 60) {
            min.set(0);
            int newHour = hour.incrementAndGet();
            // Roll over into the next day when midnight is reached.
            if (newHour >= 24) {
                hour.set(0);
                day.incrementAndGet();
            }
        }

        // Persist the in-game time to Postgres every PERSIST_INTERVAL real seconds
        // so a pod restart resumes close to where it left off.
        if (tick % PERSIST_INTERVAL == 0) {
            persistAsync();
        }

        // Emit a time event on the SSE sink every TIME_STREAM_INTERVAL game minutes
        // so clients can update their in-game clock display.
        if (min.get() % TIME_STREAM_INTERVAL == 0) {
            GameTimeDto dto = currentTime();
            // Push to in-process subscribers (SSE connections on this pod).
            timeSink.tryEmitNext(dto);
            // Publish via pg_notify so other BFF pods also broadcast the event.
            notify.publish("game_events",
                    String.format("{\"type\":\"time\",\"day\":%d,\"hour\":%d,\"minute\":%d}",
                            dto.dayNumber(), dto.hour(), dto.minute()))
                    .subscribe();
        }
    }

    /**
     * Persists the current in-game time atomics to the {@code game_time_state} row.
     * Fire-and-forget: errors are swallowed by the subscribe call — a persist failure
     * means the restored time after a restart may be slightly behind, which is
     * acceptable given the {@link #PERSIST_INTERVAL} cadence.
     */
    private void persistAsync() {
        timeRepo.findById(dbId.get())
                .doOnNext(s -> {
                    // Copy the current atomic values into the entity before saving.
                    s.setDayNumber(day.get());
                    s.setHour(hour.get());
                    s.setMinute(min.get());
                    s.setLastPersistedAt(Instant.now());
                })
                .flatMap(timeRepo::save)
                .subscribe();
    }

    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------

    /**
     * Returns a snapshot of the current in-game time as an immutable DTO.
     * This is a constant-time volatile read — safe to call on any thread.
     *
     * @return the current in-game day, hour, and minute
     */
    public GameTimeDto currentTime() {
        return new GameTimeDto(day.get(), hour.get(), min.get());
    }

    /**
     * Returns the in-process Reactor stream of in-game time events. A new event
     * is emitted every {@link #TIME_STREAM_INTERVAL} game minutes (every 5 real
     * seconds). The stream is backed by a multicast sink and does not replay
     * historical events to late subscribers — a new SSE connection sees the next
     * tick when it arrives.
     *
     * @return a {@link Flux} of {@link GameTimeDto} events; never completes normally
     */
    public Flux<GameTimeDto> timeStream() {
        return timeSink.asFlux();
    }
}
