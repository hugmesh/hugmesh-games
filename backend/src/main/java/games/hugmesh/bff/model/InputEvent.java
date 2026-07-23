package games.hugmesh.bff.model;

import java.time.Instant;
import java.util.Map;

/**
 * Generalised input/output event over the BFF's wire protocol.
 *
 * <p>Every kind of user-driven mutation (gamepad button, mouse move,
 * key press, touch gesture, audio cue, game-emitted world event,
 * legacy JRPG control command) shares this single shape. A
 * discriminator pair — {@link #kind()} + {@link #type()} — selects
 * the per-kind payload schema; the payload itself is left as a
 * {@code Map<String,Object>} so the wire format can evolve without a
 * breaking change to the controller.
 *
 * <p>Reference data for the {@code (kind, type)} space lives in the
 * {@code input_event_kinds} + {@code input_event_types} tables (see
 * Liquibase changeset 026); the BFF refuses any event whose
 * (kind, type) doesn't appear there. Per-game opt-in lives in
 * {@code game_event_support} so a chrome-less demo can reject
 * {@code control:cast} without writing a single line of Java.
 *
 * <p>Persistence shape mirrors the {@code input_events} table 1:1.
 * {@code id} + {@code occurredAt} are server-assigned on insert.
 */
public record InputEvent(
        Long id,
        String gameSlug,
        Long characterId,
        String sessionId,
        String casdoorUserId,
        String kind,
        String type,
        Map<String, Object> payload,
        Instant occurredAt
) {

    /** Inbound (client → BFF). The BFF stamps id + occurredAt. */
    public static InputEvent inbound(
            String gameSlug,
            Long characterId,
            String sessionId,
            String casdoorUserId,
            String kind,
            String type,
            Map<String, Object> payload
    ) {
        return new InputEvent(null, gameSlug, characterId, sessionId,
                              casdoorUserId, kind, type, payload, null);
    }
}
