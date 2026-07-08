package games.snapdragon.bff.service;

import games.snapdragon.bff.model.CharacterDto;
import games.snapdragon.bff.model.InputEvent;
import games.snapdragon.bff.model.PlayerMovement;
import games.snapdragon.bff.model.PlayerViewEvent;
import games.snapdragon.bff.model.SessionControlEvent;
import games.snapdragon.bff.model.WorldState;
import reactor.core.publisher.Sinks;

/**
 * Per-multiplayer-session Reactor sink bundle — one instance per live session.
 *
 * <h2>Purpose</h2>
 *
 * <p>Groups the six typed {@link Sinks.Many} that fan SSE events out to every
 * subscriber belonging to a single multiplayer session.  Lazy-allocated by
 * {@link GameStateService} on the first emit or subscription for a given session
 * id, then stored in the {@code ConcurrentHashMap<String, SessionSinks>} that
 * {@code GameStateService} owns.
 *
 * <h2>Channels</h2>
 *
 * <pre>
 *  Field           │ Event type            │ Buffer │ Use
 *  ────────────────┼───────────────────────┼────────┼──────────────────────────────────
 *  movement        │ PlayerMovement        │    64  │ Per-character position + action
 *  world           │ WorldState            │    64  │ Full-roster snapshot per chapter
 *  character       │ CharacterDto          │    16  │ HP/XP stat changes
 *  view            │ PlayerViewEvent       │   128  │ Camera orientation (admin obs.)
 *  sessionControl  │ SessionControlEvent   │    16  │ Pause/resume/close lifecycle
 *  input           │ InputEvent            │   128  │ Host-authoritative relay + chess
 * </pre>
 *
 * <h2>Isolation invariant</h2>
 *
 * <p>Events emitted on session A's sinks <em>never</em> reach a subscriber of
 * session B.  The {@link GameStateService#applyControl} write path routes to
 * the matching {@code SessionSinks} keyed by session id only.  This invariant
 * is asserted by the {@code SessionIsolationTest} integration test.
 *
 * <h2>Backpressure strategy</h2>
 *
 * <p>Every sink is a {@link Sinks.Many#multicast() multicast}
 * {@link Sinks.MulticastSpec#onBackpressureBuffer(int, boolean) onBackpressureBuffer}
 * with {@code autoCancel=false}.  The {@code autoCancel=false} flag means a
 * subscriber dropping out (SSE connection closed by the browser) does NOT
 * terminate the producer-side sink — subsequent emits continue to reach any
 * remaining subscribers.  Without this flag a single client disconnect would
 * silently kill the entire session's event stream for every other player.
 *
 * <p>The integer capacities are sized for bursty gameplay: movement and input
 * channels handle up to 128 events per subscriber round-trip; lifecycle and
 * character-stat channels are sized smaller because they change infrequently.
 * Overflow events are dropped by {@link Sinks.EmitResult#FAIL_OVERFLOW} — the
 * next emit replaces the lost frame, which is acceptable for game state updates
 * but would be lossy for chat or audit events (those use Kafka, not these sinks).
 *
 * <h2>Lifecycle</h2>
 *
 * <p>The {@code SessionSinks} instance is created on first access and held for
 * the lifetime of the pod.  Sessions are bounded by Postgres row count, so the
 * map's memory footprint is small.  A future {@code SessionSweeper} job may
 * remove completed session entries from the map; until then all allocations
 * persist in the pod's heap.
 *
 * @see GameStateService
 * @see SessionControlEvent
 */
final class SessionSinks {

    /**
     * Per-character position and action events — emitted on every meaningful
     * movement delta (position changed, action label changed, or pressed state
     * changed).  Buffer of 64 handles bursts from rapid key-repeat.
     */
    final Sinks.Many<PlayerMovement>      movement;

    /**
     * Full roster snapshot for a chapter — emitted after every movement update
     * that changed position, carrying all characters currently active in the
     * affected chapter.  Buffer of 64 mirrors {@link #movement}.
     */
    final Sinks.Many<WorldState>          world;

    /**
     * Character stat changes (HP, XP, level) — low-frequency; buffer of 16
     * is generous relative to the expected rate.
     */
    final Sinks.Many<CharacterDto>        character;

    /**
     * Per-player camera orientation samples, posted by the Rust GDExtension's
     * {@code view_egress} signal at approximately 10 Hz.  Admin observers
     * subscribe here to watch each player's view without joining the session.
     * Buffer of 128 accommodates the higher sample rate.
     */
    final Sinks.Many<PlayerViewEvent>     view;

    /**
     * Session lifecycle control events: {@code SESSION_PAUSED},
     * {@code SESSION_RESUMED}, {@code SESSION_CLOSED}, {@code PLAYER_PAUSED},
     * {@code PLAYER_RESUMED}.  Subscribed by the host shell of every player in
     * the session so they can collapse the game iframe on close or freeze input
     * focus on pause.  Buffer of 16 is sufficient for lifecycle bursts.
     */
    final Sinks.Many<SessionControlEvent> sessionControl;

    /**
     * Per-session generalised input relay.  The host-authoritative chess relay
     * rides this channel: a guest's {@code control/move} is forwarded here so
     * the host WASM engine can apply it, and the host's {@code world/state}
     * board echo fans out ONLY to this session's input subscribers — never to
     * the global input firehose.  Buffer of 128 matches the view channel's rate.
     */
    final Sinks.Many<InputEvent>          input;

    /**
     * Allocates all six sinks with the buffer sizes and {@code autoCancel=false}
     * flag described in the class-level Javadoc.
     */
    SessionSinks() {
        this.movement       = Sinks.many().multicast().onBackpressureBuffer(64,  false);
        this.world          = Sinks.many().multicast().onBackpressureBuffer(64,  false);
        this.character      = Sinks.many().multicast().onBackpressureBuffer(16,  false);
        this.view           = Sinks.many().multicast().onBackpressureBuffer(128, false);
        this.sessionControl = Sinks.many().multicast().onBackpressureBuffer(16,  false);
        this.input          = Sinks.many().multicast().onBackpressureBuffer(128, false);
    }
}
