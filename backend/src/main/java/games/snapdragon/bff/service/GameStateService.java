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
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * <p>Per-chapter position state, keyed by (characterId, chapterId). A
 * character traversing cave → field → cave is restored to the cave
 * coordinates they last had on re-entry — the prior single-row
 * layout clobbered position across chapters and lost the per-chapter
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
 * <p>SSE fan-out is per chapter: when a character moves in chapter L,
 * the {@link WorldState} carries {@code chapterId=L} and the full
 * roster of characters currently active at L. Subscribers in chapter
 * M ignore the event because their renderer keys off the matching
 * chapter id.
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

    /** Default chapter id used when a control arrives without an
     *  explicit chapter and the character has no current_chapter_id
     *  populated yet. Matches the cave row in 002-seed-data. */
    private static final long DEFAULT_CHAPTER_ID = 1L;

    private final PlayerCharacterRepository characterRepo;
    private final PlayerPositionRepository  positionRepo;
    private final GameTimeService           gameTimeService;
    private final PgNotificationService     pgNotify;

    /** Cassandra cross-pod cache. Optional (null under Postgres-only
     *  test configs); the service degrades to PG-only when absent. */
    private final PositionCacheService      positionCache;

    /** Per-(characterId, chapterId) in-memory state. Map of map so the
     *  outer key gives O(1) lookup on the hot movement path; the
     *  inner map's small size (one entry per chapter the character has
     *  visited this session) keeps overhead negligible. */
    private final ConcurrentHashMap<Long, ConcurrentHashMap<Long, AtomicReference<PlayerState>>> characterStates =
            new ConcurrentHashMap<>();

    /** Last action label per (characterId, chapterId). Holds the prior
     *  action so the state-change predicate can spot a release event
     *  even when neither the position nor the action label changes. */
    private final ConcurrentHashMap<Key, String>  lastActions  = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Key, Boolean> lastPressed  = new ConcurrentHashMap<>();
    /** Last chapter id observed for each character — used as the
     *  fallback when a control event arrives without an explicit
     *  chapterId. Updated on every move and on every TW_CHAPTER_TRANSITION
     *  hook (see {@link #onChapterEnter}). */
    private final ConcurrentHashMap<Long, Long> activeChapter = new ConcurrentHashMap<>();

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

    /**
     * Per-multiplayer-session sink bundles. Lazy-allocated on first emit
     * or stream access for a given session. The map is never pruned at
     * runtime (sessions are bounded by Postgres) — a SessionSweeper job
     * can {@code remove} expired sessions but does not exist yet because
     * the in-memory cost is small (4 sinks × tens of sessions).
     *
     * <p><strong>Isolation invariant:</strong>
     * {@link #applyControl(ControlEvent, Long, String)} routes to the
     * keyed bundle ONLY; a subscriber of session A never sees session
     * B's emissions. Tested in {@code SessionIsolationTest}.
     */
    private final ConcurrentHashMap<String, SessionSinks> sessionSinks = new ConcurrentHashMap<>();

    private final String sessionId = games.snapdragon.bff.util.Crypto.mintUid();

    /** {@code positionCacheProvider} is an ObjectProvider so the
     *  Cassandra-backed cache stays optional — under Postgres-only
     *  test slices the bean isn't in the context, getIfAvailable()
     *  returns null, and the service degrades to PG + in-memory only. */
    /**
     * Optional Kafka tee for streaming events. When
     * {@code app.kafka.enabled=true} every emission to the in-process
     * Sinks.Many is mirrored to the {@code game_events} Kafka topic
     * (and, for session-scoped events, the per-session topic created
     * by {@link games.snapdragon.bff.audit.SessionTopicManager}).
     * SSE remains the gameplay-driving path; this publisher is purely
     * additive so the existing web patterns keep working.
     */
    private final java.util.Optional<games.snapdragon.bff.audit.GameEventPublisher> eventPublisher;

    /**
     * Cross-pod receiver. When wired, registers a handler per channel so
     * a movement event produced by Pod A → Kafka → Pod B's subscriber
     * lands in Pod B's matching per-session sink. Self-loops are
     * prevented at the subscriber via {@code pod_id} filtering.
     */
    private final java.util.Optional<games.snapdragon.bff.audit.KafkaSessionSubscriber> kafkaSubscriber;

    /**
     * Optional ClickHouse cross-pod recovery surface. When wired,
     * {@link #hydrate(long, long)} consults
     * {@code snapdragon.player_state_current FINAL} BEFORE the legacy
     * Cassandra → Postgres → defaults chain — the warehouse has the
     * freshest cross-pod position because every channel emission tees
     * into it. Returns {@code Mono.empty()} when no row exists; the
     * caller seamlessly falls through to the existing chain.
     */
    private final java.util.Optional<games.snapdragon.bff.audit.PlayerStateRecoveryService> stateRecovery;

    public GameStateService(PlayerCharacterRepository characterRepo,
                            PlayerPositionRepository  positionRepo,
                            GameTimeService           gameTimeService,
                            PgNotificationService     pgNotify,
                            ObjectProvider<PositionCacheService> positionCacheProvider,
                            java.util.Optional<games.snapdragon.bff.audit.GameEventPublisher> eventPublisher,
                            java.util.Optional<games.snapdragon.bff.audit.KafkaSessionSubscriber> kafkaSubscriber,
                            java.util.Optional<games.snapdragon.bff.audit.PlayerStateRecoveryService> stateRecovery) {
        this.characterRepo  = characterRepo;
        this.positionRepo   = positionRepo;
        this.gameTimeService = gameTimeService;
        this.pgNotify       = pgNotify;
        this.positionCache  = positionCacheProvider.getIfAvailable();
        this.eventPublisher = eventPublisher;
        this.kafkaSubscriber = kafkaSubscriber;
        this.stateRecovery  = stateRecovery;
    }

    @PostConstruct
    public void init() {
        // Register cross-pod re-emit handlers — one per channel. The
        // subscriber pulls each game_events row, filters self-published
        // (via pod_id), and dispatches to the matching handler below.
        // Each handler emits into the LOCAL session-scoped sink so an
        // SSE subscriber on this pod sees the cross-pod event without
        // ever knowing which pod produced it.
        //
        // SSE remains the gameplay-driving path; this completes the
        // cross-pod loop introduced by decision 021 (Phase-6 + Phase-7
        // delivered the producer side).
        kafkaSubscriber.ifPresent(sub -> {
            sub.registerHandler("movement",     (sid, json) -> reemitToSession(sid, "movement",     json));
            sub.registerHandler("world",        (sid, json) -> reemitToSession(sid, "world",        json));
            sub.registerHandler("character",    (sid, json) -> reemitToSession(sid, "character",    json));
            sub.registerHandler("scene",        (sid, json) -> reemitToSession(sid, "scene",        json));
            sub.registerHandler("view",         (sid, json) -> reemitToSession(sid, "view",         json));
            sub.registerHandler("input",        (sid, json) -> reemitToSession(sid, "input",        json));
            sub.registerHandler("audio",        (sid, json) -> reemitToSession(sid, "audio",        json));
            sub.registerHandler("wasm-console", (sid, json) -> reemitToSession(sid, "wasm-console", json));
            log.info("[kafka-sub] handlers registered for 8 channels");
        });

        // Seed the default character on the default chapter so the
        // single-player local-play case has state from the moment the
        // first SSE subscriber connects. Multi-player connects hydrate
        // additional characters lazily via applyControl / hydrate(...).
        characterRepo.findById(1L)
                .flatMap(c -> {
                    long chapterId = c.getCurrentChapterId() != null ? c.getCurrentChapterId() : DEFAULT_CHAPTER_ID;
                    activeChapter.put(c.getId(), chapterId);
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
     * chapter via (in priority order):
     *   1) {@code event.chapterId()} — the iframe's URL has a chapter_id
     *      query param the patched Godot bundle threads through;
     *   2) {@link #activeChapter} — last chapter observed for this
     *      character;
     *   3) {@link #DEFAULT_CHAPTER_ID}.
     */
    public void applyControl(ControlEvent event, Long characterId) {
        applyControl(event, characterId, null);
    }

    /**
     * Multiplayer-aware overload. When {@code sessionId} is non-null, the
     * resulting movement event also fans out on the session-scoped sinks
     * keyed by {@code sessionId}, so a subscriber to
     * {@code /api/game/stream?sessionId=X} sees only session X's events.
     * The legacy global sinks still emit so the admin firehose at
     * {@code /api/game/stream} (no session filter) keeps working.
     */
    public void applyControl(ControlEvent event, Long characterId, String sessionIdOpt) {
        Long chapterId = resolveChapterId(characterId, event.chapterId());
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
            activeChapter.put(characterId, chapterId);
            long t = tick.incrementAndGet();

            PlayerMovement movement = new PlayerMovement(
                    characterId, chapterId,
                    after.x(), after.y(), after.z(), after.rotationY(),
                    event.action(), event.pressed(), t);

            movementSink.tryEmitNext(movement);
            if (sessionIdOpt != null) {
                sessionSinksFor(sessionIdOpt).movement.tryEmitNext(movement);
            }
            // Kafka tee: every movement emission also goes to the
            // `game_events` topic + per-session topic via the publisher.
            // SSE still drives the live HUD; this is the analytics +
            // cross-pod-state-sync feeder (decision 021). `characterId`
            // is included in payload so the ClickHouse
            // `player_state_current` ReplacingMergeTree can dedupe by
            // it for the cross-pod recovery query.
            eventPublisher.ifPresent(pub -> pub.publish(
                    "movement",
                    /* gameSlug */ null,
                    sessionIdOpt,
                    /* casdoorId */ null,
                    java.util.Map.of(
                            "characterId", String.valueOf(characterId),
                            "chapterId", chapterId,
                            "x", after.x(), "y", after.y(), "z", after.z(),
                            "rotationY", after.rotationY(),
                            "action", event.action(),
                            "pressed", event.pressed(),
                            "tick", t)));
            emitWorldStateForChapter(chapterId, t, sessionIdOpt);

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
        return hydrate(characterId, chapterId, null, null);
    }

    /**
     * Cross-pod-aware hydrate. When {@code gameSlug} + {@code sessionId}
     * are provided AND the ClickHouse recovery service is wired, the
     * BFF consults the {@code player_state_current FINAL} view BEFORE
     * the legacy Cassandra → Postgres → defaults chain. This is how a
     * player reconnecting via a different pod picks up their freshest
     * cross-pod position without waiting for the Cassandra cache to
     * roll forward.
     *
     * <p>{@code Mono.empty()} from the recovery service is the
     * expected miss path — fall through to the existing chain.
     */
    public Mono<PlayerState> hydrate(long characterId, long chapterId,
                                     String gameSlug, String sessionId) {
        Mono<PlayerState> source;
        if (stateRecovery.isPresent() && gameSlug != null && sessionId != null) {
            source = stateRecovery.get()
                    .recover(gameSlug, sessionId, characterId)
                    .map(rec -> PlayerState.defaults()
                            .withPosition(rec.posX(), rec.posY(), rec.posZ()))
                    .switchIfEmpty(loadState(characterId, chapterId));
        } else {
            source = loadState(characterId, chapterId);
        }
        return source
                .doOnNext(state -> {
                    putState(characterId, chapterId, state);
                    activeChapter.put(characterId, chapterId);
                    long t = tick.incrementAndGet();
                    emitWorldStateForChapter(chapterId, t);
                });
    }

    /**
     * Hook called by the host on a TW_CHAPTER_TRANSITION — drops the
     * character's previous-chapter membership and seeds the new chapter
     * from cache. Idempotent; missing membership is a no-op.
     */
    public void onChapterEnter(long characterId, long fromChapterId, long toChapterId) {
        if (positionCache != null && fromChapterId != toChapterId) {
            positionCache.markDeparted(characterId, fromChapterId).subscribe();
        }
        hydrate(characterId, toChapterId).subscribe();
    }

    public void notifyCharacterStatChange(CharacterDto dto) {
        charStatSink.tryEmitNext(dto);
        eventPublisher.ifPresent(pub -> pub.publish(
                "character", null, null, null, dto));
    }

    public void notifySceneEvent(SceneEvent event) {
        sceneSink.tryEmitNext(event);
        eventPublisher.ifPresent(pub -> pub.publish(
                "scene", null, null, null, event));
    }

    public Flux<PlayerMovement>  movementStream()    { return movementSink.asFlux(); }
    public Flux<WorldState>      worldStream()       { return worldSink.asFlux(); }
    public Flux<CharacterDto>    charStatStream()    { return charStatSink.asFlux(); }
    public Flux<SceneEvent>      sceneStream()       { return sceneSink.asFlux(); }
    public Flux<InputEvent>      inputStream()       { return inputSink.asFlux(); }
    public Flux<InputEvent>      audioStream()       { return audioSink.asFlux(); }
    public Flux<WasmConsoleLine> wasmConsoleStream() { return wasmConsoleSink.asFlux(); }

    /** Per-session movement stream — only events from {@code sessionId}. */
    public Flux<PlayerMovement>  movementStream(String sessionId)  { return sessionSinksFor(sessionId).movement.asFlux(); }
    /** Per-session world-snapshot stream — only events from {@code sessionId}. */
    public Flux<WorldState>      worldStream(String sessionId)     { return sessionSinksFor(sessionId).world.asFlux(); }
    /** Per-session character-stat stream — only events from {@code sessionId}. */
    public Flux<CharacterDto>    charStatStream(String sessionId)  { return sessionSinksFor(sessionId).character.asFlux(); }
    /** Per-session player-view stream — admin watches each slot's camera. */
    public Flux<PlayerViewEvent> viewStream(String sessionId)      { return sessionSinksFor(sessionId).view.asFlux(); }

    /**
     * Push a per-player view event onto the session-scoped {@code view}
     * channel. Called by the diagnostics / view-egress controller when
     * the Rust GDExtension's {@code view_egress} signal forwards a
     * camera + orientation sample.
     */
    public void emitView(PlayerViewEvent ev) {
        if (ev.sessionId() != null) {
            sessionSinksFor(ev.sessionId()).view.tryEmitNext(ev);
        }
        eventPublisher.ifPresent(pub -> pub.publish(
                "view", null, ev.sessionId(), null, ev));
    }

    /**
     * Notify the per-session character channel of a stat change. The
     * legacy global {@link #charStatSink} is also emitted so the admin
     * firehose retains its single-stream view.
     */
    public void notifyCharacterStatChange(CharacterDto dto, String sessionIdOpt) {
        charStatSink.tryEmitNext(dto);
        if (sessionIdOpt != null) {
            sessionSinksFor(sessionIdOpt).character.tryEmitNext(dto);
        }
        eventPublisher.ifPresent(pub -> pub.publish(
                "character", null, sessionIdOpt, null, dto));
    }

    private SessionSinks sessionSinksFor(String sessionId) {
        return sessionSinks.computeIfAbsent(sessionId, id -> new SessionSinks());
    }

    /**
     * Cross-pod re-emit entry point. The Kafka subscriber dispatches
     * here once per consumed game_events row that didn't originate on
     * this pod. We deserialise the payload string into the matching
     * channel-specific record type and emit into the local SessionSinks
     * (for the four per-session channels) or the global sink (for the
     * four global-only channels).
     *
     * <p>Best-effort throughout — a deserialise failure logs + drops
     * the event; the gameplay path isn't disturbed. Per decision 021
     * the streaming pipeline is purely additive over the in-process
     * Sinks.Many; cross-pod re-emit improves the multi-pod experience
     * but never blocks the same-pod path.
     */
    private void reemitToSession(String sessionId, String channel, String payloadJson) {
        if (payloadJson == null || payloadJson.isEmpty()) return;
        try {
            com.fasterxml.jackson.databind.ObjectMapper mapper = sharedMapper;
            com.fasterxml.jackson.databind.JsonNode root = mapper.readTree(payloadJson);
            switch (channel) {
                case "movement" -> {
                    games.snapdragon.bff.model.PlayerMovement m = new games.snapdragon.bff.model.PlayerMovement(
                            root.path("characterId").asLong(0L),
                            root.has("chapterId") && !root.get("chapterId").isNull()
                                    ? root.path("chapterId").asLong() : null,
                            root.path("x").asDouble(),
                            root.path("y").asDouble(),
                            root.path("z").asDouble(),
                            root.path("rotationY").asDouble(),
                            root.path("action").asText(null),
                            root.path("pressed").asBoolean(false),
                            root.path("tick").asLong(0L));
                    movementSink.tryEmitNext(m);
                    if (sessionId != null && !sessionId.isEmpty()) {
                        sessionSinksFor(sessionId).movement.tryEmitNext(m);
                    }
                }
                case "world" -> {
                    games.snapdragon.bff.model.WorldState w = mapper.treeToValue(root,
                            games.snapdragon.bff.model.WorldState.class);
                    worldSink.tryEmitNext(w);
                    if (sessionId != null && !sessionId.isEmpty()) {
                        sessionSinksFor(sessionId).world.tryEmitNext(w);
                    }
                }
                case "character" -> {
                    games.snapdragon.bff.model.CharacterDto c = mapper.treeToValue(root,
                            games.snapdragon.bff.model.CharacterDto.class);
                    charStatSink.tryEmitNext(c);
                    if (sessionId != null && !sessionId.isEmpty()) {
                        sessionSinksFor(sessionId).character.tryEmitNext(c);
                    }
                }
                case "scene" -> {
                    games.snapdragon.bff.model.SceneEvent s = mapper.treeToValue(root,
                            games.snapdragon.bff.model.SceneEvent.class);
                    sceneSink.tryEmitNext(s);
                }
                case "view" -> {
                    games.snapdragon.bff.model.PlayerViewEvent v = mapper.treeToValue(root,
                            games.snapdragon.bff.model.PlayerViewEvent.class);
                    String vSession = sessionId != null && !sessionId.isEmpty() ? sessionId : v.sessionId();
                    if (vSession != null && !vSession.isEmpty()) {
                        sessionSinksFor(vSession).view.tryEmitNext(v);
                    }
                }
                case "input" -> {
                    games.snapdragon.bff.model.InputEvent i = mapper.treeToValue(root,
                            games.snapdragon.bff.model.InputEvent.class);
                    inputSink.tryEmitNext(i);
                }
                case "audio" -> {
                    games.snapdragon.bff.model.InputEvent a = mapper.treeToValue(root,
                            games.snapdragon.bff.model.InputEvent.class);
                    audioSink.tryEmitNext(a);
                }
                case "wasm-console" -> {
                    games.snapdragon.bff.model.WasmConsoleLine line = mapper.treeToValue(root,
                            games.snapdragon.bff.model.WasmConsoleLine.class);
                    wasmConsoleSink.tryEmitNext(line);
                }
                default -> log.debug("[kafka-sub] dropped unknown channel={}", channel);
            }
        } catch (Exception ex) {
            log.debug("[kafka-sub] reemit failed for channel={} session={}: {}",
                    channel, sessionId, ex.getMessage());
        }
    }

    /** Shared ObjectMapper for cross-pod re-emit deserialisation. */
    private final com.fasterxml.jackson.databind.ObjectMapper sharedMapper =
            new com.fasterxml.jackson.databind.ObjectMapper();

    /** Push a generalised input event onto the SSE channel. The audio
     *  kind also fan-outs to the audio sink so a subscriber can opt
     *  into just the audio surface without filtering the full input
     *  firehose. */
    public void emitInput(InputEvent ev) {
        inputSink.tryEmitNext(ev);
        if (InputEventKinds.AUDIO.equals(ev.kind())) {
            audioSink.tryEmitNext(ev);
            eventPublisher.ifPresent(pub -> pub.publish(
                    "audio", null, null, null, ev));
        }
        eventPublisher.ifPresent(pub -> pub.publish(
                "input", null, null, null, ev));
    }

    /** Forward one console line from the WASM iframe to the host's
     *  devtools subscribers. */
    public void emitWasmConsole(WasmConsoleLine line) {
        wasmConsoleSink.tryEmitNext(line);
        eventPublisher.ifPresent(pub -> pub.publish(
                "wasm-console", null, null, null, line));
    }

    public GameState currentState() {
        long chapterId = activeChapter.getOrDefault(1L, DEFAULT_CHAPTER_ID);
        AtomicReference<PlayerState> ref = ensureState(1L, chapterId);
        Key key = new Key(1L, chapterId);
        return new GameState(sessionId, ref.get(), lastActions.get(key),
                false, System.currentTimeMillis(), tick.get(), gameTimeService.currentTime());
    }

    // ── helpers ───────────────────────────────────────────────────

    private Long resolveChapterId(long characterId, Long explicit) {
        if (explicit != null) return explicit;
        Long known = activeChapter.get(characterId);
        return known != null ? known : DEFAULT_CHAPTER_ID;
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
     * this chapter).
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
    private void emitWorldStateForChapter(long chapterId, long t) {
        emitWorldStateForChapter(chapterId, t, null);
    }

    private void emitWorldStateForChapter(long chapterId, long t, String sessionIdOpt) {
        Set<Long> roster = new HashSet<>();
        characterStates.forEach((cid, perChapter) -> {
            if (perChapter.containsKey(chapterId)) roster.add(cid);
        });

        // Add cross-pod characters from Cassandra membership. The Mono
        // is fire-and-forget — if the cluster's slow we fall back to
        // the local roster only for this tick, the next move catches
        // the cross-pod member up.
        Flux<Long> cassandraRoster = positionCache != null
                ? positionCache.charactersInChapter(chapterId)
                : Flux.empty();

        cassandraRoster.collectList()
                .subscribe(remote -> {
                    Set<Long> all = new HashSet<>(roster);
                    all.addAll(remote);
                    List<PlayerMovement> snapshot = all.stream()
                            .map(cid -> snapshotFor(cid, chapterId, t))
                            .toList();
                    WorldState world = new WorldState(chapterId, snapshot, t);
                    worldSink.tryEmitNext(world);
                    if (sessionIdOpt != null) {
                        sessionSinksFor(sessionIdOpt).world.tryEmitNext(world);
                    }
                    eventPublisher.ifPresent(pub -> pub.publish(
                            "world", null, sessionIdOpt, null, world));
                }, err -> {
                    // Cassandra unreachable — emit local-only roster.
                    List<PlayerMovement> snapshot = roster.stream()
                            .map(cid -> snapshotFor(cid, chapterId, t))
                            .toList();
                    WorldState world = new WorldState(chapterId, snapshot, t);
                    worldSink.tryEmitNext(world);
                    if (sessionIdOpt != null) {
                        sessionSinksFor(sessionIdOpt).world.tryEmitNext(world);
                    }
                    eventPublisher.ifPresent(pub -> pub.publish(
                            "world", null, sessionIdOpt, null, world));
                });
    }

    private PlayerMovement snapshotFor(long characterId, long chapterId, long t) {
        ConcurrentHashMap<Long, AtomicReference<PlayerState>> perChapter = characterStates.get(characterId);
        AtomicReference<PlayerState> ref = perChapter != null ? perChapter.get(chapterId) : null;
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

    /** Composite key for the per-(character, chapter) maps. */
    private record Key(long characterId, long chapterId) {}
}
