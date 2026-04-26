package com.threewizards.service;

import com.threewizards.model.*;
import com.threewizards.repository.PlayerCharacterRepository;
import com.threewizards.repository.PlayerPositionRepository;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.DependsOn;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * @DependsOn("liquibase") gates the @PostConstruct character lookup
 * on Liquibase finishing — see GameTimeService for the longer note
 * about why this matters for @SpringBootTest's parallel bean init
 * (and why it's a no-op under the production startup ordering).
 */
@Service
@DependsOn("liquibase")
public class GameStateService {

    private static final Logger log = LoggerFactory.getLogger(GameStateService.class);

    private static final double MOVE_STEP = 0.1;
    private static final double JUMP_STEP = 1.0;

    private final PlayerCharacterRepository characterRepo;
    private final PlayerPositionRepository positionRepo;
    private final GameTimeService gameTimeService;
    private final PgNotificationService pgNotify;

    // Per-character state — keyed by character DB id
    private final ConcurrentHashMap<Long, AtomicReference<PlayerState>> characterStates = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Long, String> lastActions = new ConcurrentHashMap<>();
    // Tracks the last pressed-state for the action above so we can detect key
    // releases even when neither the position nor the action label changes —
    // critical because the Godot player only stops moving when it receives a
    // pressed=false event, and without this map keyups on a held direction
    // get swallowed and the character walks forever.
    private final ConcurrentHashMap<Long, Boolean> lastPressed = new ConcurrentHashMap<>();
    private final AtomicLong tick = new AtomicLong(0);

    // Typed event sinks — emit only when content actually changes
    // autoCancel=false — without it, multicast sinks terminate the moment all
    // subscribers disconnect. A single SSE reconnect (iframe remount, browser
    // nav, etc.) would kill the sink permanently; subsequent tryEmitNext calls
    // silently drop and no new subscriber ever sees another event. That's the
    // "first spell works, second doesn't, movement dies" symptom.
    private final Sinks.Many<PlayerMovement> movementSink = Sinks.many().multicast().onBackpressureBuffer(64, false);
    private final Sinks.Many<WorldState>      worldSink    = Sinks.many().multicast().onBackpressureBuffer(64, false);
    private final Sinks.Many<CharacterDto>    charStatSink = Sinks.many().multicast().onBackpressureBuffer(16, false);
    private final Sinks.Many<SceneEvent>      sceneSink    = Sinks.many().multicast().onBackpressureBuffer(16, false);

    private final String sessionId = UUID.randomUUID().toString();

    public GameStateService(PlayerCharacterRepository characterRepo,
                            PlayerPositionRepository positionRepo,
                            GameTimeService gameTimeService,
                            PgNotificationService pgNotify) {
        this.characterRepo = characterRepo;
        this.positionRepo  = positionRepo;
        this.gameTimeService = gameTimeService;
        this.pgNotify = pgNotify;
    }

    @PostConstruct
    public void init() {
        characterRepo.findById(1L)
                .flatMap(c -> positionRepo.findByCharacterId(c.getId())
                        .map(pos -> PlayerState.defaults().fromCharacter(c).withPosition(pos.getPosX(), pos.getPosY(), pos.getPosZ()))
                        .switchIfEmpty(Mono.just(PlayerState.defaults().fromCharacter(c)))
                        .doOnNext(state -> log.info("Loaded character '{}' lv{} at ({},{},{})",
                                c.getName(), c.getLevel(),
                                String.format("%.1f", state.x()),
                                String.format("%.1f", state.y()),
                                String.format("%.1f", state.z()))))
                .switchIfEmpty(Mono.fromSupplier(PlayerState::defaults)
                        .doOnNext(s -> log.warn("Character 1 not found, using defaults")))
                .doOnNext(state -> characterStates.put(1L, new AtomicReference<>(state)))
                .subscribe();
        // lastActions is lazily populated by applyControl; ConcurrentHashMap rejects
        // null values, and downstream code treats an absent key the same as "no
        // previous action", so we don't seed it here.
    }

    public void applyControl(ControlEvent event, Long characterId) {
        AtomicReference<PlayerState> ref = characterStates.computeIfAbsent(
                characterId, id -> new AtomicReference<>(PlayerState.defaults()));
        String prevAction = lastActions.get(characterId);
        Boolean prevPressed = lastPressed.get(characterId);

        PlayerState before = ref.get();
        PlayerState after = switch (event.action()) {
            case "MOVE_FORWARD"  -> event.pressed() ? before.withPosition(before.x(), before.y(), before.z() - MOVE_STEP) : before;
            case "MOVE_BACKWARD" -> event.pressed() ? before.withPosition(before.x(), before.y(), before.z() + MOVE_STEP) : before;
            case "MOVE_LEFT"     -> event.pressed() ? before.withPosition(before.x() - MOVE_STEP, before.y(), before.z()) : before;
            case "MOVE_RIGHT"    -> event.pressed() ? before.withPosition(before.x() + MOVE_STEP, before.y(), before.z()) : before;
            case "JUMP"          -> event.pressed() ? before.withPosition(before.x(), before.y() + JUMP_STEP, before.z()) : before;
            default -> before;
        };

        boolean posChanged     = !before.equals(after);
        boolean actionChanged  = !Objects.equals(prevAction, event.action());
        boolean pressedChanged = prevPressed == null || prevPressed != event.pressed();

        if (posChanged || actionChanged || pressedChanged) {
            ref.set(after);
            lastActions.put(characterId, event.action());
            lastPressed.put(characterId, event.pressed());
            long t = tick.incrementAndGet();

            PlayerMovement movement = new PlayerMovement(
                    characterId, after.x(), after.y(), after.z(),
                    after.rotationY(), event.action(), event.pressed(), t);

            movementSink.tryEmitNext(movement);
            emitWorldState(t);

            if (posChanged) {
                persistPositionAsync(characterId, after);
            }

            pgNotify.publish("game_events",
                    String.format("{\"type\":\"movement\",\"cid\":%d,\"x\":%.2f,\"y\":%.2f,\"z\":%.2f}",
                            characterId, after.x(), after.y(), after.z()))
                    .subscribe();
        }
    }

    public void notifyCharacterStatChange(CharacterDto dto) {
        charStatSink.tryEmitNext(dto);
    }

    public void notifySceneEvent(SceneEvent event) {
        sceneSink.tryEmitNext(event);
    }

    // Streams — returned to controller for merging into the single unified SSE
    public Flux<PlayerMovement> movementStream() { return movementSink.asFlux(); }
    public Flux<WorldState>      worldStream()    { return worldSink.asFlux(); }
    public Flux<CharacterDto>    charStatStream() { return charStatSink.asFlux(); }
    public Flux<SceneEvent>      sceneStream()    { return sceneSink.asFlux(); }

    public GameState currentState() {
        AtomicReference<PlayerState> ref = characterStates.computeIfAbsent(
                1L, id -> new AtomicReference<>(PlayerState.defaults()));
        return new GameState(sessionId, ref.get(), lastActions.get(1L),
                false, System.currentTimeMillis(), tick.get(), gameTimeService.currentTime());
    }

    private void emitWorldState(long t) {
        List<PlayerMovement> all = characterStates.entrySet().stream()
                .map(e -> {
                    PlayerState ps = e.getValue().get();
                    return new PlayerMovement(e.getKey(), ps.x(), ps.y(), ps.z(),
                            ps.rotationY(), lastActions.get(e.getKey()), false, t);
                })
                .toList();
        worldSink.tryEmitNext(new WorldState(all, t));
    }

    private void persistPositionAsync(Long characterId, PlayerState state) {
        positionRepo.findByCharacterId(characterId)
                .doOnNext(pos -> {
                    pos.setPosX(state.x());
                    pos.setPosY(state.y());
                    pos.setPosZ(state.z());
                    pos.setRotationY(state.rotationY());
                    pos.setUpdatedAt(Instant.now());
                })
                .flatMap(positionRepo::save)
                .subscribe();
    }
}
