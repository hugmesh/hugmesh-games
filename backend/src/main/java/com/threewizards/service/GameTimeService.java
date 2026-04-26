package com.threewizards.service;

import com.threewizards.model.GameTimeDto;
import com.threewizards.model.GameTimeState;
import com.threewizards.repository.GameTimeRepository;
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
 * @DependsOn("liquibase") makes the @PostConstruct game_time_state
 * lookup wait until migrations finish. In production this is a no-op
 * (Spring Boot's LiquibaseAutoConfiguration already runs first), but
 * @SpringBootTest brings beans up in parallel — without the explicit
 * dependency, the R2DBC connection pool opens connections before
 * Liquibase commits and Postgres MVCC pins those connections to a
 * pre-migration snapshot. Same reason GameStateService carries it.
 */
@Service
@DependsOn("liquibase")
public class GameTimeService {

    private static final Logger log = LoggerFactory.getLogger(GameTimeService.class);

    // 1 game minute advances every real second; persist every 10 ticks
    private static final int PERSIST_INTERVAL = 10;
    // Emit a time event to SSE stream every 5 game minutes
    private static final int TIME_STREAM_INTERVAL = 5;

    private final GameTimeRepository timeRepo;
    private final PgNotificationService notify;

    private final AtomicLong dbId   = new AtomicLong(1);
    private final AtomicInteger day  = new AtomicInteger(1);
    private final AtomicInteger hour = new AtomicInteger(6);
    private final AtomicInteger min  = new AtomicInteger(0);

    private final Sinks.Many<GameTimeDto> timeSink =
            Sinks.many().multicast().onBackpressureBuffer(16);

    public GameTimeService(GameTimeRepository timeRepo, PgNotificationService notify) {
        this.timeRepo = timeRepo;
        this.notify   = notify;
    }

    @PostConstruct
    public void init() {
        timeRepo.findById(1L)
                .switchIfEmpty(Mono.defer(this::seedInitialTime))
                .doOnNext(state -> {
                    dbId.set(state.getId());
                    day.set(state.getDayNumber());
                    hour.set(state.getHour());
                    min.set(state.getMinute());
                    log.info("Game time loaded: Day {} {}:{}", state.getDayNumber(),
                            String.format("%02d", state.getHour()),
                            String.format("%02d", state.getMinute()));
                })
                .subscribe();

        Flux.interval(Duration.ofSeconds(1))
                .subscribe(this::advanceTick);
    }

    private Mono<GameTimeState> seedInitialTime() {
        return timeRepo.save(new GameTimeState());
    }

    private void advanceTick(Long tick) {
        int newMin = min.incrementAndGet();
        if (newMin >= 60) {
            min.set(0);
            int newHour = hour.incrementAndGet();
            if (newHour >= 24) {
                hour.set(0);
                day.incrementAndGet();
            }
        }

        if (tick % PERSIST_INTERVAL == 0) {
            persistAsync();
        }

        if (min.get() % TIME_STREAM_INTERVAL == 0) {
            GameTimeDto dto = currentTime();
            timeSink.tryEmitNext(dto);
            notify.publish("game_events",
                    String.format("{\"type\":\"time\",\"day\":%d,\"hour\":%d,\"minute\":%d}",
                            dto.dayNumber(), dto.hour(), dto.minute()))
                    .subscribe();
        }
    }

    private void persistAsync() {
        timeRepo.findById(dbId.get())
                .doOnNext(s -> {
                    s.setDayNumber(day.get());
                    s.setHour(hour.get());
                    s.setMinute(min.get());
                    s.setLastPersistedAt(Instant.now());
                })
                .flatMap(timeRepo::save)
                .subscribe();
    }

    public GameTimeDto currentTime() {
        return new GameTimeDto(day.get(), hour.get(), min.get());
    }

    public Flux<GameTimeDto> timeStream() {
        return timeSink.asFlux();
    }
}
