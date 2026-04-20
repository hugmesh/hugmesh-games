package com.threewizards.service;

import com.threewizards.model.ControlEvent;
import com.threewizards.model.GameState;
import com.threewizards.model.PlayerState;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;

import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

@Service
public class GameStateService {

    private static final Logger log = LoggerFactory.getLogger(GameStateService.class);

    private static final double MOVE_STEP = 0.1;
    private static final double JUMP_STEP = 1.0;

    private final String sessionId = UUID.randomUUID().toString();
    private final AtomicLong tick = new AtomicLong(0);
    private final AtomicReference<PlayerState> playerState =
            new AtomicReference<>(PlayerState.defaults());
    private volatile String lastAction = null;
    private volatile boolean lastPressed = false;

    // replay().limit(1): hot sink that never terminates, replays the latest
    // state to new subscribers so clients get current state immediately on connect.
    private final Sinks.Many<GameState> sink =
            Sinks.many().replay().limit(1);

    public final Flux<GameState> stateStream = sink.asFlux();

    public void applyControl(ControlEvent event) {
        log.info("CONTROL  action={} pressed={}", event.action(), event.pressed());

        lastAction = event.action();
        lastPressed = event.pressed();

        PlayerState before = playerState.get();
        playerState.getAndUpdate(p -> switch (event.action()) {
            case "MOVE_FORWARD"  -> event.pressed() ? p.withPosition(p.x(), p.y(), p.z() - MOVE_STEP) : p;
            case "MOVE_BACKWARD" -> event.pressed() ? p.withPosition(p.x(), p.y(), p.z() + MOVE_STEP) : p;
            case "MOVE_LEFT"     -> event.pressed() ? p.withPosition(p.x() - MOVE_STEP, p.y(), p.z()) : p;
            case "MOVE_RIGHT"    -> event.pressed() ? p.withPosition(p.x() + MOVE_STEP, p.y(), p.z()) : p;
            case "JUMP"          -> event.pressed() ? p.withPosition(p.x(), p.y() + JUMP_STEP, p.z()) : p;
            default -> p;
        });
        PlayerState after = playerState.get();

        if (!before.equals(after)) {
            log.info("STATE    pos=({}) hp={} mp={}",
                    String.format("%.2f, %.2f, %.2f", after.x(), after.y(), after.z()),
                    after.health(), after.mana());
        }

        emit();
    }

    public GameState currentState() {
        return buildState();
    }

    @PostConstruct
    public void startHeartbeat() {
        Flux.interval(Duration.ofSeconds(1))
                .subscribe(i -> emit());
    }

    private void emit() {
        GameState state = buildState();
        log.debug("SSE emit tick={} action={} pressed={}", state.tick(), state.action(), state.pressed());
        sink.tryEmitNext(state);
    }

    private GameState buildState() {
        return new GameState(
                sessionId,
                playerState.get(),
                lastAction,
                lastPressed,
                System.currentTimeMillis(),
                tick.incrementAndGet()
        );
    }
}
