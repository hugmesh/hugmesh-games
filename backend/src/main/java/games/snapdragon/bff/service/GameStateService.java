package games.snapdragon.bff.service;

import games.snapdragon.bff.model.*;
import games.snapdragon.bff.repository.PlayerCharacterRepository;
import games.snapdragon.bff.repository.PlayerPositionRepository;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.DependsOn;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;

import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * <p>Per-level position state, keyed by (characterId, chapterId). A
 * character traversing cave → field → cave is restored to the cave
 * coordinates they last had on re-entry — the prior single-row
 * layout clobbered position across levels and lost the per-level
 * pose every transition.
 *
 * <p>Three storage tiers, ordered by hotness:
 * <ol>
 *   <li>In-memory {@link #characterStates} keyed by {@code Key}.
 *       Hot path, single-pod authority while a session holds the
 *       controller role.</li>
 *   <li>Cassandra {@link PositionCacheService} — cross-pod read
 *       cache, populated on every move via write-through. Pod B can
 *       see a position updated on pod A inside one round-trip.</li>
 *   <li>Postgres {@code player_positions} (Liquibase 027) — durable
 *       source of truth. Every move persists here too so a cold
 *       restart with empty Cassandra still hydrates correctly.</li>
 * </ol>
 *
 * <p>SSE fan-out is per level: when a character moves in level L,
 * the {@link WorldState} carries {@code chapterId=L} and the full
 * roster of characters currently active at L. Subscribers in level
 * M ignore the event because their renderer keys off the matching
 * level id.
 *
 * <p>{@code @DependsOn("liquibase")} gates the {@code @PostConstruct}
 * character lookup on Liquibase finishing — see GameTimeService for
 * the longer note about why this matters under the @SpringBootTest
 * parallel bean init (and why it's a no-op under production startup).
 */
@Service
@DependsOn("liquibase")
public class GameStateService {

    private static final Logger log = LoggerFactory.getLogger(GameStateService.class);

    private static final double MOVE_STEP = 0.1;
    private static final double JUMP_STEP = 1.0;

    /** Default level id used when a control arrives without an
     *  explicit level and the character has no current_level_id
     *  populated yet. Matches the cave row in 002-seed-data. */
    private static final long DEFAULT_LEVEL_ID = 1L;

    private final PlayerCharacterRepository characterRepo;
    private final PlayerPositionRepository  positionRepo;
    private final GameTimeService           gameTimeService;
    private final PgNotificationService     pgNotify;

    /** Cassandra cross-pod cache. Optional (null under Postgres-only
     *  test configs); the service degrades to PG-only when absent. */
    private final PositionCacheService      positionCache;

    /** Per-(characterId, chapterId) in-memory state. Map of map so the
     *  outer key gives O(1) lookup on the hot movement path; the
     *  inner map's small size (one entry per level the character has
     *  visited this session) keeps overhead negligible. */
    private final ConcurrentHashMap<Long, ConcurrentHashMap<Long, AtomicReference<PlayerState>>> characterStates =
            new ConcurrentHashMap<>();

    /** Last action label per (characterId, chapterId). Holds the prior
     *  action so the state-change predicate can spot a release event
     *  even when neither the position nor the action label changes. */
    private final ConcurrentHashMap<Key, String>  lastActions  = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Key, Boolean> lastPressed  = new ConcurrentHashMap<>();
    /** Last level id observed for each character — used as the
     *  fallback when a control event arrives without an explicit
     *  chapterId. Updated on every move and on every TW_LEVEL_TRANSITION
     *  hook (see {@link #onLevelEnter}). */
    private final ConcurrentHashMap<Long, Long> activeLevel = new ConcurrentHashMap<>();

    private final AtomicLong tick = new AtomicLong(0);

    // Typed event sinks — emit only when content actually changes.
    // autoCancel=false on every Sink: see scenario-sse-disconnect for
    // the failure mode the flag protects against.
    private final Sinks.Many<PlayerMovement>  movementSink    = Sinks.many().multicast().onBackpressureBuffer(64, false);
    private final Sinks.Many<WorldState>      worldSink       = Sinks.many().multicast().onBackpressureBuffer(64, false);
    private final Sinks.Many<CharacterDto>    charStatSink    = Sinks.many().multicast().onBackpressureBuffer(16, false);
    private final Sinks.Many<SceneEvent>      sceneSink       = Sinks.many().multicast().onBackpressureBuffer(16, false);
    private final Sinks.Many<InputEvent>      inputSink       = Sinks.many().multicast().onBackpressureBuffer(128, false);
    private final Sinks.Many<InputEvent>      audioSink       = Sinks.many().multicast().onBackpressureBuffer(64, false);
    private final Sinks.Many<WasmConsoleLine> wasmConsoleSink = Sinks.many().multicast().onBackpressureBuffer(64, false);

    private final String sessionId = UUID.randomUUID().toString();

    /** {@code positionCacheProvider} is an ObjectProvider so the
     *  Cassandra-backed cache stays optional — under Postgres-only
     *  test slices the bean isn't in the context, getIfAvailable()
     *  returns null, and the service degrades to PG + in-memory only. */
    public GameStateService(PlayerCharacterRepository characterRepo,
                            PlayerPositionRepository  positionRepo,
                            GameTimeService           gameTimeService,
                            PgNotificationService     pgNotify,
                            ObjectProvider<PositionCacheService> positionCacheProvider) {
        this.characterRepo  = characterRepo;
        this.positionRepo   = positionRepo;
        this.gameTimeService = gameTimeService;
        this.pgNotify       = pgNotify;
        this.positionCache  = positionCacheProvider.getIfAvailable();
    }

    @PostConstruct
    public void init() {
        // Seed the default character on the default level so the
        // single-player local-play case has state from the moment the
        // first SSE subscriber connects. Multi-player connects hydrate
        // additional characters lazily via applyControl / hydrate(...).
        characterRepo.findById(1L)
                .flatMap(c -> {
                    long chapterId = c.getCurrentChapterId() != null ? c.getCurrentChapterId() : DEFAULT_LEVEL_ID;
                    activeLevel.put(c.getId(), chapterId);
                    return positionRepo.findByCharacterIdAndChapterId(c.getId(), chapterId)
                            .map(pos -> PlayerState.defaults().fromCharacter(c)
                                    .withPosition(pos.getPosX(), pos.getPosY(), pos.getPosZ()))
                            .switchIfEmpty(Mono.just(PlayerState.defaults().fromCharacter(c)))
                            .doOnNext(state -> {
                                putState(c.getId(), chapterId, state);
                                log.info("Loaded character '{}' lv{} at lvId={} ({},{},{})",
                                        c.getName(), c.getLevel(), chapterId,
                                        String.format("%.1f", state.x()),
                                        String.format("%.1f", state.y()),
                                        String.format("%.1f", state.z()));
                            });
                })
                .switchIfEmpty(Mono.fromRunnable(() ->
                        log.warn("Character 1 not found, lazy-init will populate state on first control event")))
                .subscribe();
    }

    /**
     * Apply a control event for {@code characterId}, resolving its
     * level via (in priority order):
     *   1) {@code event.chapterId()} — the iframe's URL has a level_id
     *      query param the patched Godot bundle threads through;
     *   2) {@link #activeLevel} — last level observed for this
     *      character;
     *   3) {@link #DEFAULT_LEVEL_ID}.
     */
    public void applyControl(ControlEvent event, Long characterId) {
        Long chapterId = resolveLevelId(characterId, event.chapterId());
        AtomicReference<PlayerState> ref = ensureState(characterId, chapterId);
        Key key = new Key(characterId, chapterId);

        String prevAction = lastActions.get(key);
        Boolean prevPressed = lastPressed.get(key);

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
            lastActions.put(key, event.action());
            lastPressed.put(key, event.pressed());
            activeLevel.put(characterId, chapterId);
            long t = tick.incrementAndGet();

            PlayerMovement movement = new PlayerMovement(
                    characterId, chapterId,
                    after.x(), after.y(), after.z(), after.rotationY(),
                    event.action(), event.pressed(), t);

            movementSink.tryEmitNext(movement);
            emitWorldStateForLevel(chapterId, t);

            if (posChanged) {
                persistPositionAsync(characterId, chapterId, after);
                if (positionCache != null) {
                    positionCache.upsert(
                            characterId, chapterId,
                            after.x(), after.y(), after.z(), after.rotationY(),
                            event.action(), event.pressed(), t)
                            .subscribe();
                }
            }

            pgNotify.publish("game_events",
                    String.format("{\"type\":\"movement\",\"cid\":%d,\"chapterId\":%d,\"x\":%.2f,\"y\":%.2f,\"z\":%.2f}",
                            characterId, chapterId, after.x(), after.y(), after.z()))
                    .subscribe();
        }
    }

    /**
     * Hydrate a character's state for {@code chapterId} from cache
     * (Cassandra → Postgres → defaults) so a session member who
     * connects but hasn't moved yet still appears in the next world
     * snapshot. Called from SessionController on session connect.
     */
    public Mono<PlayerState> hydrate(long characterId, long chapterId) {
        return loadState(characterId, chapterId)
                .doOnNext(state -> {
                    putState(characterId, chapterId, state);
                    activeLevel.put(characterId, chapterId);
                    long t = tick.incrementAndGet();
                    emitWorldStateForLevel(chapterId, t);
                });
    }

    /**
     * Hook called by the host on a TW_LEVEL_TRANSITION — drops the
     * character's previous-level membership and seeds the new level
     * from cache. Idempotent; missing membership is a no-op.
     */
    public void onLevelEnter(long characterId, long fromLevel, long toLevel) {
        if (positionCache != null && fromLevel != toLevel) {
            positionCache.markDeparted(characterId, fromLevel).subscribe();
        }
        hydrate(characterId, toLevel).subscribe();
    }

    public void notifyCharacterStatChange(CharacterDto dto) {
        charStatSink.tryEmitNext(dto);
    }

    public void notifySceneEvent(SceneEvent event) {
        sceneSink.tryEmitNext(event);
    }

    public Flux<PlayerMovement>  movementStream()    { return movementSink.asFlux(); }
    public Flux<WorldState>      worldStream()       { return worldSink.asFlux(); }
    public Flux<CharacterDto>    charStatStream()    { return charStatSink.asFlux(); }
    public Flux<SceneEvent>      sceneStream()       { return sceneSink.asFlux(); }
    public Flux<InputEvent>      inputStream()       { return inputSink.asFlux(); }
    public Flux<InputEvent>      audioStream()       { return audioSink.asFlux(); }
    public Flux<WasmConsoleLine> wasmConsoleStream() { return wasmConsoleSink.asFlux(); }

    /** Push a generalised input event onto the SSE channel. The audio
     *  kind also fan-outs to the audio sink so a subscriber can opt
     *  into just the audio surface without filtering the full input
     *  firehose. */
    public void emitInput(InputEvent ev) {
        inputSink.tryEmitNext(ev);
        if (InputEventKinds.AUDIO.equals(ev.kind())) {
            audioSink.tryEmitNext(ev);
        }
    }

    /** Forward one console line from the WASM iframe to the host's
     *  devtools subscribers. */
    public void emitWasmConsole(WasmConsoleLine line) {
        wasmConsoleSink.tryEmitNext(line);
    }

    public GameState currentState() {
        long chapterId = activeLevel.getOrDefault(1L, DEFAULT_LEVEL_ID);
        AtomicReference<PlayerState> ref = ensureState(1L, chapterId);
        Key key = new Key(1L, chapterId);
        return new GameState(sessionId, ref.get(), lastActions.get(key),
                false, System.currentTimeMillis(), tick.get(), gameTimeService.currentTime());
    }

    // ── helpers ───────────────────────────────────────────────────

    private Long resolveLevelId(long characterId, Long explicit) {
        if (explicit != null) return explicit;
        Long known = activeLevel.get(characterId);
        return known != null ? known : DEFAULT_LEVEL_ID;
    }

    private AtomicReference<PlayerState> ensureState(long characterId, long chapterId) {
        return characterStates
                .computeIfAbsent(characterId, id -> new ConcurrentHashMap<>())
                .computeIfAbsent(chapterId, id -> new AtomicReference<>(PlayerState.defaults()));
    }

    private void putState(long characterId, long chapterId, PlayerState state) {
        characterStates
                .computeIfAbsent(characterId, id -> new ConcurrentHashMap<>())
                .put(chapterId, new AtomicReference<>(state));
    }

    /**
     * Cassandra-first read with PG fallback. Returns defaults when
     * neither tier has a row yet (first time this character touches
     * this level).
     */
    private Mono<PlayerState> loadState(long characterId, long chapterId) {
        Mono<PlayerState> fromCache = positionCache != null
                ? positionCache.findOne(characterId, chapterId)
                        .map(c -> PlayerState.defaults()
                                .withPosition(c.getPosX(), c.getPosY(), c.getPosZ()))
                : Mono.empty();
        return fromCache
                .switchIfEmpty(positionRepo.findByCharacterIdAndChapterId(characterId, chapterId)
                        .map(p -> PlayerState.defaults()
                                .withPosition(p.getPosX(), p.getPosY(), p.getPosZ())))
                .switchIfEmpty(Mono.just(PlayerState.defaults()));
    }

    /**
     * Build the full roster of characters currently active in
     * {@code chapterId} and emit one {@link WorldState} on the world
     * sink. Includes:
     *   - every in-memory character keyed at {@code chapterId},
     *   - every character known to the Cassandra membership index
     *     (so cross-pod players appear without local activity).
     */
    private void emitWorldStateForLevel(long chapterId, long t) {
        Set<Long> roster = new HashSet<>();
        characterStates.forEach((cid, perLevel) -> {
            if (perLevel.containsKey(chapterId)) roster.add(cid);
        });

        // Add cross-pod characters from Cassandra membership. The Mono
        // is fire-and-forget — if the cluster's slow we fall back to
        // the local roster only for this tick, the next move catches
        // the cross-pod member up.
        Flux<Long> cassandraRoster = positionCache != null
                ? positionCache.charactersInLevel(chapterId)
                : Flux.empty();

        cassandraRoster.collectList()
                .subscribe(remote -> {
                    Set<Long> all = new HashSet<>(roster);
                    all.addAll(remote);
                    List<PlayerMovement> snapshot = all.stream()
                            .map(cid -> snapshotFor(cid, chapterId, t))
                            .toList();
                    worldSink.tryEmitNext(new WorldState(chapterId, snapshot, t));
                }, err -> {
                    // Cassandra unreachable — emit local-only roster.
                    List<PlayerMovement> snapshot = roster.stream()
                            .map(cid -> snapshotFor(cid, chapterId, t))
                            .toList();
                    worldSink.tryEmitNext(new WorldState(chapterId, snapshot, t));
                });
    }

    private PlayerMovement snapshotFor(long characterId, long chapterId, long t) {
        ConcurrentHashMap<Long, AtomicReference<PlayerState>> perLevel = characterStates.get(characterId);
        AtomicReference<PlayerState> ref = perLevel != null ? perLevel.get(chapterId) : null;
        PlayerState ps = ref != null ? ref.get() : PlayerState.defaults();
        Key key = new Key(characterId, chapterId);
        return new PlayerMovement(
                characterId, chapterId,
                ps.x(), ps.y(), ps.z(), ps.rotationY(),
                lastActions.get(key), false, t);
    }

    private void persistPositionAsync(long characterId, long chapterId, PlayerState state) {
        positionRepo.findByCharacterIdAndChapterId(characterId, chapterId)
                .switchIfEmpty(Mono.fromSupplier(() -> {
                    PlayerPosition fresh = new PlayerPosition();
                    fresh.setCharacterId(characterId);
                    fresh.setChapterId(chapterId);
                    return fresh;
                }))
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

    /** Composite key for the per-(character, level) maps. */
    private record Key(long characterId, long chapterId) {}
}
