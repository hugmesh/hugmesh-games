package games.snapdragon.bff.service.realtime;

/**
 * One player command scheduled against the authoritative tick loop
 * (see docs/realtime-server-authoritative-design.md, P2).
 *
 * <p><strong>Deterministic total order = {@code (forTick, seq)}.</strong>
 * {@code forTick} is the authoritative tick the command executes at
 * (submission tick + the input-delay lag buffer); {@code seq} is the
 * per-tick monotonic arrival stamp. Two servers handed the same set of
 * commands resolve them in the identical order regardless of wire
 * arrival — the bit-exactness contract the Rust sim cores
 * (snapdragon-rts-core / snapdragon-fps-core) and the Java reference
 * sim all depend on.
 *
 * @param forTick     authoritative tick this command executes at
 * @param seq         per-tick monotonic sequence (arrival order within the tick)
 * @param playerId    the submitting player's casdoor id
 * @param payloadJson sim-specific command payload, opaque to the loop
 */
public record SimCommand(long forTick, int seq, String playerId, String payloadJson) {
}
