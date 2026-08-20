package games.hugmesh.bff.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import games.hugmesh.bff.model.*;
import games.hugmesh.bff.repository.PlayerCharacterRepository;
import games.hugmesh.bff.repository.PlayerPositionRepository;
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
 * SSE fan-out hub and authoritative in-memory game state store.
 *
 * <h2>Responsibility</h2>
 *
 * <p>Two interleaved concerns live here:
 * <ol>
 *   <li><b>State store</b> — holds per-(characterId, chapterId) position state in
 *       {@link #characterStates}, the last action label in {@link #lastActions},
 *       and the last observed chapter per character in {@link #activeChapter}.
 *       These are the hot-path structures consulted on every player input.</li>
 *   <li><b>SSE fan-out</b> — owns the global {@link Sinks.Many} instances for all
 *       eight event channels (movement/world/character/scene/input/audio/wasm-console/
 *       sessionControl) and the per-session {@link SessionSinks} bundles keyed by
 *       session id.  {@link #applyControl} is the write path: it mutates state and
 *       emits events in the same synchronised block.</li>
 * </ol>
 *
 * <h2>Three-tier position storage</h2>
 *
 * <p>Position state is maintained across three tiers, ordered by hotness:
 * <ol>
 *   <li><b>In-memory</b> ({@link #characterStates}) — the hot path.  Single-pod
 *       authority while a session is active.  Updated on every move.</li>
 *   <li><b>Cassandra</b> ({@link #positionCache}) — cross-pod read cache, populated
 *       on every position change via write-through.  Pod B can see a position
 *       updated on pod A inside one round-trip.  Optional (null when Cassandra is
 *       not configured); the service degrades gracefully to tiers 1 + 3.</li>
 *   <li><b>Postgres</b> ({@code player_positions}, Liquibase 027) — durable
 *       source of truth.  Persisted asynchronously on every position change via
 *       {@link #persistPositionAsync}.  A cold restart with empty Cassandra still
 *       hydrates correctly from Postgres.</li>
 * </ol>
 *
 * <h2>Per-chapter position model</h2>
 *
 * <p>Position state is keyed by {@code (characterId, chapterId)} via the
 * composite {@link Key} record.  A character traversing cave → field → cave
 * is restored to the cave coordinates they last had on re-entry — the prior
 * single-row layout clobbered position across chapters and lost the per-chapter
 * pose on every transition.
 *
 * <h2>Caller-role matrix</h2>
 *
 * <pre>
 *  Operation                        │ Player │ Mod │ Admin │ Superadmin
 *  ─────────────────────────────────┼────────┼─────┼───────┼───────────
 *  applyControl                     │  yes   │  -  │   -   │     -
 *  hydrate                          │  yes   │  -  │  yes  │    yes
 *  onChapterEnter                   │  yes   │  -  │   -   │     -
 *  movementStream / worldStream     │  yes   │ yes │  yes  │    yes
 *  sessionControlStream             │  yes   │ yes │  yes  │    yes
 *  viewStream (session-scoped)      │   -    │  -  │  yes  │    yes
 *  emitSessionControl               │  yes*  │ yes │  yes  │    yes
 *  notifyCharacterStatChange        │  yes*  │  -  │  yes  │    yes
 * </pre>
 *
 * <p>* Via {@link GameSessionService}'s lifecycle methods; not direct.
 *
 * <h2>Session isolation</h2>
 *
 * <p>{@link #applyControl(ControlEvent, Long, String)} routes to the
 * session-keyed {@link SessionSinks} only — a subscriber of session A never
 * sees session B's events.  The legacy global sinks still emit so the admin
 * firehose at {@code /api/game/stream} (no session filter) continues to work.
 * The isolation contract is asserted by {@code SessionIsolationTest}.
 *
 * <h2>Optional Kafka integration</h2>
 *
 * <p>When {@code app.kafka.enabled=true}, every SSE emission is also teed into
 * Kafka via {@link #eventPublisher}.  Cross-pod re-emission is handled by
 * {@link #kafkaSubscriber} registering one handler per channel in
 * {@link #init()}.  ClickHouse state recovery is available via {@link #stateRecovery}
 * for the freshest cross-pod position on reconnect.  All three are
 * {@link java.util.Optional} so the in-process gameplay path works without Kafka.
 *
 * <h2>{@code @DependsOn("liquibase")} rationale</h2>
 *
 * <p>The {@link #init()} {@code @PostConstruct} loads character 1 from Postgres.
 * Under {@code @SpringBootTest} parallel bean init, the bean might be created
 * before Liquibase has applied the schema; {@code @DependsOn("liquibase")} gates
 * the constructor on Liquibase finishing.  Under production startup this is a
 * no-op because Liquibase runs in the same thread as the application context.
 *
 * @see SessionSinks
 * @see GameSessionService
 * @see PositionCacheService
 */
@Service
@DependsOn("liquibase")
public class GameStateService {

    private static final Logger log = LoggerFactory.getLogger(GameStateService.class);

    /** Position delta for a single MOVE_* input event (world units). */
    private static final double MOVE_STEP = 0.1;
    /** Vertical delta applied by a JUMP event (world units). */
    private static final double JUMP_STEP = 1.0;

    /**
     * Default chapter id used when a control arrives without an explicit chapter
     * and the character has no {@code current_chapter_id} populated yet.
     * Matches the cave row in 002-seed-data.
     */
    private static final long DEFAULT_CHAPTER_ID = 1L;

    private final PlayerCharacterRepository characterRepo;
    private final PlayerPositionRepository  positionRepo;
    private final GameTimeService           gameTimeService;
    private final PgNotificationService     pgNotify;

    /**
     * Cassandra cross-pod position cache.  Optional (null under Postgres-only test
     * configs); the service degrades to PG-only when absent.
     */
    private final PositionCacheService      positionCache;

    /**
     * Per-(characterId, chapterId) in-memory state.  Outer map is keyed by
     * characterId for O(1) lookup on the hot movement path; inner map is keyed
     * by chapterId (small — one entry per chapter the character has visited this
     * session).
     */
    private final ConcurrentHashMap<Long, ConcurrentHashMap<Long, AtomicReference<PlayerState>>> characterStates =
            new ConcurrentHashMap<>();

    /**
     * Last action label per (characterId, chapterId).  Holds the prior action so
     * the state-change predicate can detect a release event even when neither the
     * position nor the action label changes.
     */
    private final ConcurrentHashMap<Key, String>  lastActions  = new ConcurrentHashMap<>();

    /**
     * Last pressed state per (characterId, chapterId).  Paired with
     * {@link #lastActions} to detect key-release events that are otherwise
     * invisible if position and action are unchanged.
     */
    private final ConcurrentHashMap<Key, Boolean> lastPressed  = new ConcurrentHashMap<>();

    /**
     * Last chapter id observed for each character.  Used as the fallback when a
     * control event arrives without an explicit {@code chapterId}.  Updated on
     * every move and on every {@code TW_CHAPTER_TRANSITION} hook via
     * {@link #onChapterEnter}.
     */
    private final ConcurrentHashMap<Long, Long> activeChapter = new ConcurrentHashMap<>();

    /** Monotonically increasing tick counter — threaded through every emitted event
     *  so subscribers can detect missed or out-of-order frames. */
    private final AtomicLong tick = new AtomicLong(0);

    // -------------------------------------------------------------------------
    // Global typed event sinks
    // -------------------------------------------------------------------------

    // autoCancel=false on every Sink: see scenario-sse-disconnect for the failure
    // mode the flag protects against. A single subscriber dropping must not
    // terminate the producer-side sink for all remaining subscribers.

    /** Global movement stream — every character on every chapter. */
    private final Sinks.Many<PlayerMovement>  movementSink    = Sinks.many().multicast().onBackpressureBuffer(64, false);
    /** Global world-snapshot stream — full roster per chapter, every session. */
    private final Sinks.Many<WorldState>      worldSink       = Sinks.many().multicast().onBackpressureBuffer(64, false);
    /** Global character-stat stream — HP/XP changes, every session. */
    private final Sinks.Many<CharacterDto>    charStatSink    = Sinks.many().multicast().onBackpressureBuffer(16, false);
    /** Global scene event stream — scene element changes, every session. */
    private final Sinks.Many<SceneEvent>      sceneSink       = Sinks.many().multicast().onBackpressureBuffer(16, false);
    /** Global input relay — every input event across all sessions. */
    private final Sinks.Many<InputEvent>      inputSink       = Sinks.many().multicast().onBackpressureBuffer(128, false);
    /** Global audio relay — subset of inputSink filtered to AUDIO kind. */
    private final Sinks.Many<InputEvent>      audioSink       = Sinks.many().multicast().onBackpressureBuffer(64, false);
    /** WASM iframe console lines forwarded from the Rust GDExtension bridge. */
    private final Sinks.Many<WasmConsoleLine> wasmConsoleSink = Sinks.many().multicast().onBackpressureBuffer(64, false);
    /** Global session-control stream — pause/resume/close across all sessions. */
    private final Sinks.Many<SessionControlEvent> sessionControlSink =
            Sinks.many().multicast().onBackpressureBuffer(16, false);

    // -------------------------------------------------------------------------
    // Per-session sink bundles
    // -------------------------------------------------------------------------

    /**
     * Per-multiplayer-session sink bundles. Lazy-allocated on first emit or stream
     * access for a given session.  The map is never pruned at runtime (sessions are
     * bounded by Postgres rows) — a future SessionSweeper job may {@code remove}
     * expired entries, but the in-memory cost is small (six sinks × tens of sessions).
     *
     * <p><strong>Isolation invariant:</strong> {@link #applyControl} routes to the
     * keyed bundle ONLY; a subscriber of session A never sees session B's emissions.
     * Tested in {@code SessionIsolationTest}.
     */
    private final ConcurrentHashMap<String, SessionSinks> sessionSinks = new ConcurrentHashMap<>();

    /** Stable pod-local identifier for self-loop filtering in the cross-pod Kafka subscriber. */
    private final String sessionId = games.hugmesh.bff.util.Crypto.mintUid();

    // -------------------------------------------------------------------------
    // Optional infrastructure integrations
    // -------------------------------------------------------------------------

    /**
     * Optional Kafka tee for streaming events. When {@code app.kafka.enabled=true}
     * every emission to the in-process {@link Sinks.Many} is mirrored to the
     * {@code game_events} Kafka topic (and, for session-scoped events, the
     * per-session topic created by {@link games.hugmesh.bff.audit.SessionTopicManager}).
     * SSE remains the gameplay-driving path; this publisher is purely additive so
     * the existing web patterns keep working.
     */
    private final java.util.Optional<games.hugmesh.bff.audit.GameEventPublisher> eventPublisher;

    /**
     * Cross-pod receiver. When wired, registers a handler per channel so a movement
     * event produced by Pod A → Kafka → Pod B's subscriber lands in Pod B's matching
     * per-session sink.  Self-loops are prevented at the subscriber via
     * {@code pod_id} filtering.
     */
    private final java.util.Optional<games.hugmesh.bff.audit.KafkaSessionSubscriber> kafkaSubscriber;

    /**
     * Optional ClickHouse cross-pod recovery surface. When wired,
     * {@link #hydrate(long, long, String, String)} consults the
     * {@code hugmesh.player_state_current FINAL} view BEFORE the legacy
     * Cassandra → Postgres → defaults chain — the warehouse has the freshest
     * cross-pod position because every channel emission tees into it.  Returns
     * {@link Mono#empty()} when no row exists; the caller seamlessly falls through
     * to the existing chain.
     */
    private final java.util.Optional<games.hugmesh.bff.audit.PlayerStateRecoveryService> stateRecovery;

    /**
     * Shared ObjectMapper for cross-pod re-emit deserialisation.  One instance
     * per service to avoid the cost of repeated instantiation on the Kafka
     * consumer thread.
     */
    private final ObjectMapper sharedMapper = new ObjectMapper();

    /**
     * {@code positionCacheProvider} is an {@link ObjectProvider} so the
     * Cassandra-backed cache stays optional — under Postgres-only test slices
     * the bean isn't in the context, {@code getIfAvailable()} returns null, and
     * the service degrades to PG + in-memory only.
     */
    public GameStateService(PlayerCharacterRepository characterRepo,
                            PlayerPositionRepository  positionRepo,
                            GameTimeService           gameTimeService,
                            PgNotificationService     pgNotify,
                            ObjectProvider<PositionCacheService> positionCacheProvider,
                            java.util.Optional<games.hugmesh.bff.audit.GameEventPublisher> eventPublisher,
                            java.util.Optional<games.hugmesh.bff.audit.KafkaSessionSubscriber> kafkaSubscriber,
                            java.util.Optional<games.hugmesh.bff.audit.PlayerStateRecoveryService> stateRecovery) {
        this.characterRepo  = characterRepo;
        this.positionRepo   = positionRepo;
        this.gameTimeService = gameTimeService;
        this.pgNotify       = pgNotify;
        this.positionCache  = positionCacheProvider.getIfAvailable();
        this.eventPublisher = eventPublisher;
        this.kafkaSubscriber = kafkaSubscriber;
        this.stateRecovery  = stateRecovery;
    }

    // -------------------------------------------------------------------------
    // Initialisation
    // -------------------------------------------------------------------------

    /**
     * Seeds the default character state and wires cross-pod Kafka re-emit handlers.
     *
     * <p>Two tasks performed at startup:
     * <ol>
     *   <li>Register cross-pod re-emit handlers — one per channel — so a movement
     *       event produced by Pod A → Kafka → Pod B's subscriber lands in Pod B's
     *       matching per-session sink.  Self-loops are suppressed by the subscriber
     *       via {@code pod_id} comparison before this handler is invoked.</li>
     *   <li>Load character 1 (the default single-player character) from Postgres so
     *       the first SSE subscriber sees state immediately rather than waiting for
     *       the first control event.  Multi-player characters hydrate lazily via
     *       {@link #applyControl} or an explicit {@link #hydrate} call.</li>
     * </ol>
     */
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
            // Web-native host-authoritative relay (PR #116 cross-pod parity): re-emit a
            // relayed sendWorld() blob onto the peer pod's per-session worldNative sink.
            sub.registerHandler("world-native", (sid, json) -> reemitToSession(sid, "world-native", json));
            log.info("[kafka-sub] handlers registered for 9 channels");
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

    // -------------------------------------------------------------------------
    // Control application (hot path)
    // -------------------------------------------------------------------------

    /**
     * Apply a control event for {@code characterId} on the single-player path.
     * Resolves the chapter via (in priority order): the event's {@code chapterId},
     * the last known chapter for this character, or {@link #DEFAULT_CHAPTER_ID}.
     *
     * @param event       the control event (action + pressed flag + optional chapterId)
     * @param characterId the character being controlled
     */
    public void applyControl(ControlEvent event, Long characterId) {
        applyControl(event, characterId, null);
    }

    /**
     * Multiplayer-aware control application.  When {@code sessionId} is non-null,
     * the resulting movement and world events also fan out on the session-scoped
     * {@link SessionSinks} keyed by {@code sessionId}, so a subscriber to
     * {@code /api/game/stream?sessionId=X} sees only session X's events.
     * The legacy global sinks still emit so the admin firehose at
     * {@code /api/game/stream} (no session filter) keeps working.
     *
     * <p>State-change gate: emits only when the position, action label, or
     * pressed flag has changed.  This avoids flooding the fan-out channels with
     * no-op events when the player holds a key.
     *
     * <p>After a position change: persists the new position to Postgres
     * asynchronously ({@link #persistPositionAsync}) and writes through to
     * Cassandra ({@link PositionCacheService#upsert}) when available.
     *
     * @param event        the control event
     * @param characterId  the character being controlled
     * @param sessionIdOpt the multiplayer session id, or null for the single-player path
     */
    public void applyControl(ControlEvent event, Long characterId, String sessionIdOpt) {
        // Resolve chapter: explicit from event > last-known > default.
        Long chapterId = resolveChapterId(characterId, event.chapterId());
        AtomicReference<PlayerState> ref = ensureState(characterId, chapterId);
        Key key = new Key(characterId, chapterId);

        String prevAction  = lastActions.get(key);
        Boolean prevPressed = lastPressed.get(key);

        PlayerState before = ref.get();
        // Translate the action string to a position delta; release events (pressed=false) leave position unchanged.
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
        // Detect key-release events: pressed flag changed even when position/action are the same.
        boolean pressedChanged = prevPressed == null || prevPressed != event.pressed();

        if (posChanged || actionChanged || pressedChanged) {
            // Update in-memory state atomically.
            ref.set(after);
            lastActions.put(key, event.action());
            lastPressed.put(key, event.pressed());
            activeChapter.put(characterId, chapterId);
            long t = tick.incrementAndGet();

            PlayerMovement movement = new PlayerMovement(
                    characterId, chapterId,
                    after.x(), after.y(), after.z(), after.rotationY(),
                    event.action(), event.pressed(), t);

            emitMovement(movement, sessionIdOpt);
            publishMovementAnalytics(event, characterId, chapterId, after, sessionIdOpt, t);
            // Rebuild + emit the full chapter roster snapshot after each move.
            emitWorldStateForChapter(chapterId, t, sessionIdOpt);

            if (posChanged) {
                persistPosition(event, characterId, chapterId, after, t);
            }

            notifyLegacyListeners(characterId, chapterId, after);
        }
    }

    /**
     * Emit the movement event on the global sink (admin firehose) and, when
     * multiplayer-scoped, on the session-scoped sink so per-session subscribers
     * stay isolated from other sessions.
     */
    private void emitMovement(PlayerMovement movement, String sessionIdOpt) {
        movementSink.tryEmitNext(movement);
        if (sessionIdOpt != null) {
            sessionSinksFor(sessionIdOpt).movement.tryEmitNext(movement);
        }
    }

    /**
     * Kafka tee: every movement emission also goes to the {@code game_events}
     * topic + per-session topic via the publisher. SSE still drives the live
     * HUD; this is the analytics + cross-pod-state-sync feeder (decision 021).
     * {@code characterId} is included in the payload so the ClickHouse
     * {@code player_state_current} ReplacingMergeTree can dedupe by it for the
     * cross-pod recovery query.
     */
    private void publishMovementAnalytics(ControlEvent event, long characterId, long chapterId,
                                          PlayerState after, String sessionIdOpt, long t) {
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
    }

    /**
     * Persist a changed position to Postgres asynchronously (never blocks the
     * event loop) and write through to the Cassandra cross-pod cache when
     * available.
     */
    private void persistPosition(ControlEvent event, long characterId, long chapterId,
                                 PlayerState after, long t) {
        persistPositionAsync(characterId, chapterId, after);
        if (positionCache != null) {
            positionCache.upsert(
                    characterId, chapterId,
                    after.x(), after.y(), after.z(), after.rotationY(),
                    event.action(), event.pressed(), t)
                    .subscribe();
        }
    }

    /** Postgres LISTEN/NOTIFY tap for any legacy subscriber listening on game_events. */
    private void notifyLegacyListeners(long characterId, long chapterId, PlayerState after) {
        pgNotify.publish("game_events",
                String.format("{\"type\":\"movement\",\"cid\":%d,\"chapterId\":%d,\"x\":%.2f,\"y\":%.2f,\"z\":%.2f}",
                        characterId, chapterId, after.x(), after.y(), after.z()))
                .subscribe();
    }

    // -------------------------------------------------------------------------
    // Hydration
    // -------------------------------------------------------------------------

    /**
     * Hydrate a character's state for {@code chapterId} from cache
     * (Cassandra → Postgres → defaults) so a session member who connects but
     * hasn't moved yet still appears in the next world snapshot.
     * Called from {@code SessionController} on session connect.
     *
     * <p>Delegates to {@link #hydrate(long, long, String, String)} without
     * ClickHouse recovery (gameSlug + sessionId are null, so the recovery
     * chain is skipped).
     *
     * @param characterId the character to hydrate
     * @param chapterId   the chapter to load position state for
     * @return a {@link Mono} emitting the loaded {@link PlayerState}, triggering a
     *         world snapshot emit as a side-effect
     */
    public Mono<PlayerState> hydrate(long characterId, long chapterId) {
        return hydrate(characterId, chapterId, null, null);
    }

    /**
     * Cross-pod-aware hydrate. When {@code gameSlug} + {@code sessionId} are provided
     * AND the ClickHouse recovery service is wired, the BFF consults the
     * {@code player_state_current FINAL} view BEFORE the legacy Cassandra →
     * Postgres → defaults chain.  This is how a player reconnecting via a different
     * pod picks up their freshest cross-pod position without waiting for the
     * Cassandra cache to roll forward.
     *
     * <p>{@code Mono.empty()} from the recovery service is the expected miss path —
     * the chain falls through to the existing Cassandra → Postgres → defaults
     * sequence transparently.
     *
     * <p>Reactive marble diagram:
     * <pre>
     *   stateRecovery.recover(gameSlug, sessionId, characterId)  ← ClickHouse FINAL
     *     → map(rec → PlayerState.defaults().withPosition(...))
     *     → switchIfEmpty(loadState(characterId, chapterId))      ← Cassandra → PG → defaults
     *   → doOnNext(
     *       putState(characterId, chapterId, state)               ← seed in-memory map
     *       activeChapter.put(characterId, chapterId)
     *       emitWorldStateForChapter(chapterId, tick++)           ← push roster snapshot
     *     )
     * </pre>
     *
     * @param characterId the character to hydrate
     * @param chapterId   the chapter to load position state for
     * @param gameSlug    the game slug for ClickHouse lookup; null skips recovery
     * @param sessionId   the session id for ClickHouse lookup; null skips recovery
     * @return a {@link Mono} emitting the loaded {@link PlayerState}
     */
    public Mono<PlayerState> hydrate(long characterId, long chapterId,
                                     String gameSlug, String sessionId) {
        Mono<PlayerState> source;
        if (stateRecovery.isPresent() && gameSlug != null && sessionId != null) {
            // Prefer ClickHouse for cross-pod freshness; fall through on miss.
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
                    // Seed the in-memory map and emit a world snapshot for the new character.
                    putState(characterId, chapterId, state);
                    activeChapter.put(characterId, chapterId);
                    long t = tick.incrementAndGet();
                    emitWorldStateForChapter(chapterId, t);
                });
    }

    // -------------------------------------------------------------------------
    // Chapter transition
    // -------------------------------------------------------------------------

    /**
     * Hook called by the host on a {@code TW_CHAPTER_TRANSITION} — drops the
     * character's previous-chapter membership in the Cassandra cache and seeds
     * the new chapter from the same load chain as {@link #hydrate}.
     * Idempotent; a missing membership in Cassandra is a no-op.
     *
     * @param characterId    the transitioning character
     * @param fromChapterId  the chapter being left
     * @param toChapterId    the chapter being entered
     */
    public void onChapterEnter(long characterId, long fromChapterId, long toChapterId) {
        // Mark the character as departed from the old chapter in the Cassandra roster.
        if (positionCache != null && fromChapterId != toChapterId) {
            positionCache.markDeparted(characterId, fromChapterId).subscribe();
        }
        // Hydrate position state for the new chapter; triggers a world snapshot emit.
        hydrate(characterId, toChapterId).subscribe();
    }

    // -------------------------------------------------------------------------
    // Stat / scene notification
    // -------------------------------------------------------------------------

    /**
     * Notify subscribers of a character stat change (HP/XP/level) on the global
     * channel.  Does NOT emit on a session-scoped channel — use
     * {@link #notifyCharacterStatChange(CharacterDto, String)} for the
     * multiplayer-aware overload.
     *
     * @param dto the updated character state
     */
    public void notifyCharacterStatChange(CharacterDto dto) {
        charStatSink.tryEmitNext(dto);
        eventPublisher.ifPresent(pub -> pub.publish(
                "character", null, null, null, dto));
    }

    /**
     * Notify the per-session character channel of a stat change.  Also emits on
     * the legacy global {@link #charStatSink} so the admin firehose retains its
     * single-stream view.
     *
     * @param dto          the updated character state
     * @param sessionIdOpt the session id for the per-session sink, or null for
     *                     the global-only path
     */
    public void notifyCharacterStatChange(CharacterDto dto, String sessionIdOpt) {
        charStatSink.tryEmitNext(dto);
        if (sessionIdOpt != null) {
            sessionSinksFor(sessionIdOpt).character.tryEmitNext(dto);
        }
        eventPublisher.ifPresent(pub -> pub.publish(
                "character", null, sessionIdOpt, null, dto));
    }

    /**
     * Notify subscribers of a scene event (element spawn/despawn/change) on the
     * global scene channel.
     *
     * @param event the scene change event
     */
    public void notifySceneEvent(SceneEvent event) {
        sceneSink.tryEmitNext(event);
        eventPublisher.ifPresent(pub -> pub.publish(
                "scene", null, null, null, event));
    }

    // -------------------------------------------------------------------------
    // Global stream accessors
    // -------------------------------------------------------------------------

    /** @return a {@link Flux} of all movement events across all sessions and chapters. */
    public Flux<PlayerMovement>  movementStream()    { return movementSink.asFlux(); }
    /** @return a {@link Flux} of all world-snapshot events across all sessions. */
    public Flux<WorldState>      worldStream()       { return worldSink.asFlux(); }
    /** @return a {@link Flux} of all character-stat events across all sessions. */
    public Flux<CharacterDto>    charStatStream()    { return charStatSink.asFlux(); }
    /** @return a {@link Flux} of all scene events across all sessions. */
    public Flux<SceneEvent>      sceneStream()       { return sceneSink.asFlux(); }
    /** @return a {@link Flux} of all input events across all sessions. */
    public Flux<InputEvent>      inputStream()       { return inputSink.asFlux(); }
    /** @return a {@link Flux} of all audio events across all sessions (subset of input). */
    public Flux<InputEvent>      audioStream()       { return audioSink.asFlux(); }
    /** @return a {@link Flux} of all WASM console lines across all sessions. */
    public Flux<WasmConsoleLine> wasmConsoleStream() { return wasmConsoleSink.asFlux(); }

    // -------------------------------------------------------------------------
    // Per-session stream accessors
    // -------------------------------------------------------------------------

    /**
     * Per-session movement stream — only events from {@code sessionId}.
     *
     * @param sessionId the session to scope the stream to
     * @return a {@link Flux} of movement events for this session only
     */
    public Flux<PlayerMovement>  movementStream(String sessionId)  { return sessionSinksFor(sessionId).movement.asFlux(); }

    /**
     * Per-session world-snapshot stream — only events from {@code sessionId}.
     *
     * @param sessionId the session to scope the stream to
     * @return a {@link Flux} of world-state events for this session only
     */
    public Flux<WorldState>      worldStream(String sessionId)     { return sessionSinksFor(sessionId).world.asFlux(); }

    /**
     * Per-session WEB-NATIVE world passthrough — the host-authoritative
     * {@code sendWorld()} payload from a web-native game (an arbitrary
     * game-specific JSON blob). Surfaced to subscribers under the same
     * {@code world} SSE event as {@link #worldStream(String)} (see
     * {@code GameStreamController}) so the guest bridge, which listens only to
     * the {@code world} channel, receives it with no change. See
     * {@link SessionSinks#worldNative} for why this is a distinct sink.
     *
     * @param sessionId the session to scope the stream to
     * @return a {@link Flux} of raw web-native world payloads for this session
     */
    public Flux<Object>          worldNativeStream(String sessionId) { return sessionSinksFor(sessionId).worldNative.asFlux(); }

    /**
     * Per-session character-stat stream — only events from {@code sessionId}.
     *
     * @param sessionId the session to scope the stream to
     * @return a {@link Flux} of character-stat events for this session only
     */
    public Flux<CharacterDto>    charStatStream(String sessionId)  { return sessionSinksFor(sessionId).character.asFlux(); }

    /**
     * Per-session player-view stream — admin observers watch each slot's camera
     * through this channel.
     *
     * @param sessionId the session to scope the stream to
     * @return a {@link Flux} of player-view events for this session only
     */
    public Flux<PlayerViewEvent> viewStream(String sessionId)      { return sessionSinksFor(sessionId).view.asFlux(); }

    /**
     * Per-session input stream — only events emitted for {@code sessionId}.
     * The host-authoritative chess relay subscribes here so a move in session A
     * never reaches session B (mirrors {@link #worldStream(String)}).
     *
     * @param sessionId the session to scope the stream to
     * @return a {@link Flux} of input events for this session only
     */
    public Flux<InputEvent>      inputStream(String sessionId) { return sessionSinksFor(sessionId).input.asFlux(); }

    /**
     * Per-session lifecycle events: pause / resume / closed.
     *
     * @param sessionId the session to scope the stream to
     * @return a {@link Flux} of session-control events for this session only
     */
    public Flux<SessionControlEvent> sessionControlStream(String sessionId) {
        return sessionSinksFor(sessionId).sessionControl.asFlux();
    }

    /**
     * Global session-control stream — every player's host shell subscribes here
     * to react to {@code SESSION_CLOSED} (collapse iframe) and to
     * {@code PLAYER_PAUSED} events that target their own Casdoor id.
     *
     * @return a {@link Flux} of all session-control events across all sessions
     */
    public Flux<SessionControlEvent> sessionControlStream() {
        return sessionControlSink.asFlux();
    }

    // -------------------------------------------------------------------------
    // Emit helpers (called by GameSessionService + ModerationService)
    // -------------------------------------------------------------------------

    /**
     * Emit a {@link SessionControlEvent} onto both the
     * per-session sink (admin observers see only their session's events) and the
     * global sink (every player's host shell can react to close events targeting
     * them).  Best-effort emit — overflow on the fan-out sinks is acceptable; the
     * next emit replaces the lost frame.
     *
     * @param ev the session-control event to emit; silently ignored when {@code null}
     *           or when {@code ev.sessionId()} is {@code null}
     */
    public void emitSessionControl(SessionControlEvent ev) {
        if (ev == null || ev.sessionId() == null) return;
        // Per-session sink: only session-scoped subscribers see this event.
        sessionSinksFor(ev.sessionId()).sessionControl.tryEmitNext(ev);
        // Global sink: every host-shell subscriber can react to close/pause events.
        sessionControlSink.tryEmitNext(ev);
    }

    /**
     * Push a per-player view event onto the session-scoped {@code view} channel.
     * Called by the diagnostics / view-egress controller when the Rust GDExtension's
     * {@code view_egress} signal forwards a camera + orientation sample.
     *
     * @param ev the view event carrying session id and camera state
     */
    public void emitView(PlayerViewEvent ev) {
        if (ev.sessionId() != null) {
            sessionSinksFor(ev.sessionId()).view.tryEmitNext(ev);
        }
        eventPublisher.ifPresent(pub -> pub.publish(
                "view", null, ev.sessionId(), null, ev));
    }

    /**
     * Push a generalised input event onto the global input SSE channel and
     * optionally onto the session-scoped input channel.  Audio events
     * additionally fan out to {@link #audioSink} so a subscriber can opt into
     * just the audio surface without filtering the full input firehose.
     *
     * <p>Session-scoped fan-out: when the event carries a {@code sessionId}
     * (the host-authoritative relay path — a guest {@code control/move} or the
     * host's {@code world/state} board echo), deliver it ONLY to that session's
     * input subscribers.  Without this a move emitted in session A reaches every
     * {@code /api/game/stream} subscriber — a cross-session leak.  See
     * {@code SessionIsolationTest}'s input-channel assertion.
     *
     * @param ev the input event to relay
     */
    public void emitInput(InputEvent ev) {
        // Emit on the global firehose for admin observers.
        inputSink.tryEmitNext(ev);
        String sid = ev.sessionId();
        // Session-scoped fan-out when the event is tied to a session.
        if (sid != null && !sid.isBlank()) {
            sessionSinksFor(sid).input.tryEmitNext(ev);
            // Host-authoritative WEB-NATIVE relay: a kind:'world' event is a
            // sendWorld() payload the host broadcasts for the guest to re-apply.
            // The guest bridge listens to the `world` SSE channel (never `input`),
            // so route the raw world payload onto the session's worldNative sink,
            // which GameStreamController merges into the `world` SSE event. Without
            // this the host's authoritative state never reaches the guest — the
            // whole web-native multiplayer relay is a no-op guest-side. The typed
            // WorldState `world` sink (Godot roster path) is left untouched.
            if (InputEventKinds.WORLD.equals(ev.kind()) && ev.payload() != null) {
                sessionSinksFor(sid).worldNative.tryEmitNext(ev.payload());
                // Cross-pod parity (PR #116 blocker 3): the in-process emit above only
                // reaches guests whose SSE subscription landed on THIS pod. Tee the raw
                // world payload to the dedicated `world-native` Kafka channel so a peer
                // pod's subscriber re-emits it onto ITS worldNative sink (see the
                // `world-native` handler in init() + reemitToSession). We publish
                // ev.payload() — NOT the full InputEvent — so no casdoorUserId reaches
                // the guest, exactly mirroring the in-pod sink contents. The existing
                // `input` publish below still ships the full event for the admin firehose.
                eventPublisher.ifPresent(pub -> pub.publish(
                        "world-native", null, sid, null, ev.payload()));
            }
        }
        // Audio events additionally go to the dedicated audio channel.
        if (InputEventKinds.AUDIO.equals(ev.kind())) {
            audioSink.tryEmitNext(ev);
            eventPublisher.ifPresent(pub -> pub.publish(
                    "audio", null, sid, null, ev));
        }
        eventPublisher.ifPresent(pub -> pub.publish(
                "input", null, sid, null, ev));
    }

    /**
     * Forward one WASM iframe console line from the Rust GDExtension bridge to
     * the host's devtools subscribers.
     *
     * @param line the console line to forward
     */
    public void emitWasmConsole(WasmConsoleLine line) {
        wasmConsoleSink.tryEmitNext(line);
        eventPublisher.ifPresent(pub -> pub.publish(
                "wasm-console", null, null, null, line));
    }

    /**
     * Snapshot of the current state for the default character (id=1) on its
     * active chapter.  Used by the {@code GET /api/game/state} polling endpoint
     * for clients that cannot use SSE.
     *
     * @return a {@link GameState} record reflecting the latest in-memory position
     *         and action for character 1
     */
    public GameState currentState() {
        long chapterId = activeChapter.getOrDefault(1L, DEFAULT_CHAPTER_ID);
        AtomicReference<PlayerState> ref = ensureState(1L, chapterId);
        Key key = new Key(1L, chapterId);
        return new GameState(sessionId, ref.get(), lastActions.get(key),
                false, System.currentTimeMillis(), tick.get(), gameTimeService.currentTime());
    }

    // -------------------------------------------------------------------------
    // Private helpers
    // -------------------------------------------------------------------------

    /**
     * Return or create the per-session {@link SessionSinks} bundle.
     * Lazy-allocated on first access; the map entry persists for pod lifetime.
     *
     * @param sessionId the session id to look up or create sinks for
     * @return the existing or newly created {@link SessionSinks}
     */
    private SessionSinks sessionSinksFor(String sessionId) {
        return sessionSinks.computeIfAbsent(sessionId, id -> new SessionSinks());
    }

    /**
     * Resolve the chapter id for a control event using the priority chain:
     * explicit value > last-known chapter for this character > DEFAULT_CHAPTER_ID.
     *
     * @param characterId the character whose active chapter is the fallback
     * @param explicit    the chapter id from the control event, or null
     * @return the resolved chapter id
     */
    private Long resolveChapterId(long characterId, Long explicit) {
        if (explicit != null) return explicit;
        Long known = activeChapter.get(characterId);
        return known != null ? known : DEFAULT_CHAPTER_ID;
    }

    /**
     * Return the {@link AtomicReference} for the given (characterId, chapterId),
     * creating a default-state entry if absent.
     *
     * @param characterId the character id
     * @param chapterId   the chapter id
     * @return an {@link AtomicReference} holding the current {@link PlayerState}
     */
    private AtomicReference<PlayerState> ensureState(long characterId, long chapterId) {
        return characterStates
                .computeIfAbsent(characterId, id -> new ConcurrentHashMap<>())
                .computeIfAbsent(chapterId, id -> new AtomicReference<>(PlayerState.defaults()));
    }

    /**
     * Write a {@link PlayerState} into the in-memory map for the given
     * (characterId, chapterId), replacing any existing entry.
     *
     * @param characterId the character id
     * @param chapterId   the chapter id
     * @param state       the new state to store
     */
    private void putState(long characterId, long chapterId, PlayerState state) {
        characterStates
                .computeIfAbsent(characterId, id -> new ConcurrentHashMap<>())
                .put(chapterId, new AtomicReference<>(state));
    }

    /**
     * Load a character's position state for a chapter from the tiered cache chain:
     * Cassandra → Postgres → defaults.  Returns a {@link Mono} emitting defaults
     * when neither tier has a row (first time this character touches this chapter).
     *
     * @param characterId the character to load
     * @param chapterId   the chapter to load position for
     * @return a {@link Mono} emitting a {@link PlayerState}, always non-empty
     */
    private Mono<PlayerState> loadState(long characterId, long chapterId) {
        // Try Cassandra first (cross-pod, lower latency than PG when warm).
        Mono<PlayerState> fromCache = positionCache != null
                ? positionCache.findOne(characterId, chapterId)
                        .map(c -> PlayerState.defaults()
                                .withPosition(c.getPosX(), c.getPosY(), c.getPosZ()))
                : Mono.empty();
        return fromCache
                // Fall through to Postgres when Cassandra has no row.
                .switchIfEmpty(positionRepo.findByCharacterIdAndChapterId(characterId, chapterId)
                        .map(p -> PlayerState.defaults()
                                .withPosition(p.getPosX(), p.getPosY(), p.getPosZ())))
                // Fall through to defaults when Postgres also has no row (first visit to chapter).
                .switchIfEmpty(Mono.just(PlayerState.defaults()));
    }

    /**
     * Build the full roster of characters currently active in {@code chapterId}
     * and emit one {@link WorldState} on the world sink.  Combines:
     * <ul>
     *   <li>Every character in the in-memory map that has an entry for this chapter.</li>
     *   <li>Every character known to the Cassandra membership index (cross-pod players).</li>
     * </ul>
     * Fire-and-forget: the Cassandra fetch is async; if the cluster is slow the
     * local roster is used for this tick and the cross-pod member appears on the
     * next move.
     *
     * @param chapterId    the chapter whose roster to snapshot
     * @param t            the current tick counter
     */
    private void emitWorldStateForChapter(long chapterId, long t) {
        emitWorldStateForChapter(chapterId, t, null);
    }

    /**
     * Session-scoped variant of {@link #emitWorldStateForChapter(long, long)}.
     * When {@code sessionIdOpt} is non-null, the emitted {@link WorldState} is
     * also pushed onto the per-session world sink so session subscribers see the
     * update without receiving events from other sessions.
     *
     * @param chapterId    the chapter whose roster to snapshot
     * @param t            the current tick counter
     * @param sessionIdOpt the session to additionally fan out to, or null
     */
    private void emitWorldStateForChapter(long chapterId, long t, String sessionIdOpt) {
        // Collect all characters currently active in this chapter from the in-memory map.
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
                    // Merge local and cross-pod character sets into a single roster.
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
                    // Cassandra unreachable — emit local-only roster so gameplay is not blocked.
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

    /**
     * Build a {@link PlayerMovement} snapshot for one character in the given chapter
     * at the given tick.  Falls back to {@link PlayerState#defaults()} when the
     * character has no in-memory state for this chapter.
     *
     * @param characterId the character to snapshot
     * @param chapterId   the chapter to read position for
     * @param t           the current tick counter
     * @return a movement record representing the character's last known position
     */
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

    /**
     * Asynchronously upsert the character's position in Postgres.  Finds an
     * existing row or constructs a fresh one, applies the new coordinates, and
     * saves — fire-and-forget via {@code .subscribe()} so the event loop thread
     * is not blocked on the write.
     *
     * @param characterId the character whose position to persist
     * @param chapterId   the chapter the position belongs to
     * @param state       the new position state to persist
     */
    private void persistPositionAsync(long characterId, long chapterId, PlayerState state) {
        positionRepo.findByCharacterIdAndChapterId(characterId, chapterId)
                .switchIfEmpty(Mono.fromSupplier(() -> {
                    // First position for this (character, chapter) pair — create a new row.
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

    // -------------------------------------------------------------------------
    // Cross-pod re-emit
    // -------------------------------------------------------------------------

    /**
     * Cross-pod re-emit entry point.  The Kafka subscriber dispatches here once
     * per consumed {@code game_events} row that did not originate on this pod.
     * Deserialises the JSON payload into the matching channel-specific record type
     * and emits into the local {@link SessionSinks} (per-session channels) or
     * global sinks (global-only channels).
     *
     * <p>Best-effort throughout — a deserialisation failure logs and drops the
     * event without disturbing the gameplay path.  Per decision 021 the streaming
     * pipeline is purely additive over the in-process {@link Sinks.Many}; cross-pod
     * re-emit improves the multi-pod experience but never blocks the same-pod path.
     *
     * @param sessionId   the session id from the Kafka message header, or null for
     *                    global events
     * @param channel     one of {@code movement|world|character|scene|view|input|audio|wasm-console}
     * @param payloadJson the JSON payload string from the Kafka message value
     */
    private void reemitToSession(String sessionId, String channel, String payloadJson) {
        if (payloadJson == null || payloadJson.isEmpty()) return;
        try {
            ObjectMapper mapper = sharedMapper;
            JsonNode root = mapper.readTree(payloadJson);
            switch (channel) {
                case "movement" -> {
                    // Reconstruct a PlayerMovement from the JSON payload fields.
                    PlayerMovement m = new PlayerMovement(
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
                    // Emit on global and session-scoped sinks in parallel.
                    movementSink.tryEmitNext(m);
                    if (sessionId != null && !sessionId.isEmpty()) {
                        sessionSinksFor(sessionId).movement.tryEmitNext(m);
                    }
                }
                case "world" -> {
                    WorldState w = mapper.treeToValue(root, WorldState.class);
                    worldSink.tryEmitNext(w);
                    if (sessionId != null && !sessionId.isEmpty()) {
                        sessionSinksFor(sessionId).world.tryEmitNext(w);
                    }
                }
                case "character" -> {
                    CharacterDto c = mapper.treeToValue(root, CharacterDto.class);
                    charStatSink.tryEmitNext(c);
                    if (sessionId != null && !sessionId.isEmpty()) {
                        sessionSinksFor(sessionId).character.tryEmitNext(c);
                    }
                }
                case "scene" -> {
                    // Scene events are global-only — no per-session sink.
                    SceneEvent s = mapper.treeToValue(root, SceneEvent.class);
                    sceneSink.tryEmitNext(s);
                }
                case "view" -> {
                    // View events carry their own sessionId; prefer the Kafka header value.
                    PlayerViewEvent v = mapper.treeToValue(root, PlayerViewEvent.class);
                    String vSession = sessionId != null && !sessionId.isEmpty() ? sessionId : v.sessionId();
                    if (vSession != null && !vSession.isEmpty()) {
                        sessionSinksFor(vSession).view.tryEmitNext(v);
                    }
                }
                case "input" -> {
                    // Input events go to the global firehose only (no per-session re-emit from Kafka).
                    InputEvent i = mapper.treeToValue(root, InputEvent.class);
                    inputSink.tryEmitNext(i);
                }
                case "audio" -> {
                    InputEvent a = mapper.treeToValue(root, InputEvent.class);
                    audioSink.tryEmitNext(a);
                }
                case "wasm-console" -> {
                    WasmConsoleLine line = mapper.treeToValue(root, WasmConsoleLine.class);
                    wasmConsoleSink.tryEmitNext(line);
                }
                case "world-native" -> {
                    // Web-native host-authoritative relay: the raw sendWorld() blob
                    // (an arbitrary game-world Map, e.g. chess {fen,status}) — NOT a
                    // typed Godot WorldState. Emit onto the per-session worldNative sink
                    // so the guest's `world` SSE (merged in GameStreamController) receives
                    // it exactly as it would in-pod. Session-scoped only — there is no
                    // global worldNative sink (the typed `world` sink is unrelated).
                    if (sessionId != null && !sessionId.isEmpty()) {
                        Object payload = mapper.convertValue(root, java.util.Map.class);
                        sessionSinksFor(sessionId).worldNative.tryEmitNext(payload);
                    }
                }
                default -> log.debug("[kafka-sub] dropped unknown channel={}", channel);
            }
        } catch (Exception ex) {
            // Log at DEBUG to avoid flooding on transient Kafka noise; don't disrupt gameplay.
            log.debug("[kafka-sub] reemit failed for channel={} session={}: {}",
                    channel, sessionId, ex.getMessage());
        }
    }

    // -------------------------------------------------------------------------
    // Composite key
    // -------------------------------------------------------------------------

    /** Composite key for the per-(character, chapter) in-memory maps. */
    private record Key(long characterId, long chapterId) {}
}
