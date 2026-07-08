package games.snapdragon.bff.service.realtime;

/**
 * One player command scheduled against the authoritative tick loop
 * (see docs/realtime-server-authoritative-design.md, P2).
 *
 * <h2>Deterministic total order</h2>
 *
 * <p>The pair {@code (forTick, seq)} is the canonical sort key used by
 * {@link AuthoritativeTickService} to drain the pending queue in an order that
 * is bit-identical across every server pod handling the same match, regardless
 * of the order in which commands arrive over the network.
 *
 * <ul>
 *   <li>{@code forTick} — the authoritative tick at which this command
 *       executes. It is computed at submission time as
 *       {@code sim.tick() + inputDelayTicks} (the lag buffer). This stamp is
 *       always strictly in the future so it can never collide with a tick the
 *       sim has already sealed.</li>
 *   <li>{@code seq} — a monotonic per-tick arrival counter, allocated by
 *       {@link AuthoritativeTickService} under the match lock. Two commands
 *       targeting the same tick receive strictly increasing {@code seq} values
 *       regardless of which pod originated them, so there are no ordering ties
 *       within a tick.</li>
 * </ul>
 *
 * <p>Two server pods handed the same set of commands can recover the
 * identical state by feeding them in {@code (forTick, seq)} ascending order to
 * the same {@link SimCore} seed. This is the bit-exactness contract the Java
 * reference sim ({@link ReferenceArenaSim}) and the future Rust cores
 * (snapdragon-rts-core / snapdragon-fps-core) all depend on.
 *
 * <h2>Caller-role access</h2>
 *
 * <pre>
 *  Caller            │ Creates SimCommand?
 *  ──────────────────┼──────────────────────────────────────────────────────
 *  Player            │ Yes — via submitInput (only for their own playerId)
 *  Admin/Superadmin  │ No direct creation; receives them indirectly through
 *                    │ recoverMatch + commandsAfter replay
 *  Mod               │ No access to the realtime package
 * </pre>
 *
 * @param forTick     authoritative tick this command executes at; always
 *                    {@code > sim.tick()} at submission time (the lag-buffer
 *                    invariant)
 * @param seq         per-tick monotonic sequence stamp, allocated by
 *                    {@link AuthoritativeTickService} under the match lock;
 *                    never negative; strictly increases within a given tick
 * @param playerId    the submitting player's Casdoor subject id; used by the
 *                    sim to identify which entity the command applies to
 * @param payloadJson sim-specific command payload serialised as a JSON string;
 *                    opaque to the tick loop itself — the active
 *                    {@link SimCore} implementation interprets it
 *
 * @see AuthoritativeTickService#submitInput
 * @see SimCore#step
 */
public record SimCommand(long forTick, int seq, String playerId, String payloadJson) {
}
