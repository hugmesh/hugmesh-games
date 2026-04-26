package games.snapdragon.bff.model;

import java.util.List;

/**
 * Emitted on the shared world SSE stream whenever any character moves
 * inside a given level.
 *
 * <p>One {@code WorldState} per level — a subscriber receives only
 * the world state for the level the moved character belongs to, so
 * the wire never confuses a player in cave with a player in field.
 * The {@code chapterId} field tells subscribers whether to apply the
 * delta or drop it.
 *
 * <p>{@code characters} contains every active character known to be
 * present in {@code chapterId} at tick {@code tick} — including the
 * mover. Subscribers re-render every avatar listed; characters
 * absent from the list are unchanged from their last known state.
 */
public record WorldState(Long chapterId, List<PlayerMovement> characters, long tick) {

    /** Legacy 2-arg constructor — kept for tests + callers that
     *  predate the per-level cache. The SSE pipeline always passes
     *  the explicit chapterId. */
    public WorldState(List<PlayerMovement> characters, long tick) {
        this(null, characters, tick);
    }
}
