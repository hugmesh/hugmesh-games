package games.snapdragon.bff.model;

/**
 * Control payload from the iframe — keyboard / D-pad / touch input.
 * <p>
 * {@code chapterId} is nullable for backward compatibility with older
 * Godot bundles that haven't been re-exported with the chapter-aware
 * bridge_glue. When null, GameStateService falls back to the
 * character's {@code current_chapter_id} from the player_characters
 * row, which the host shell updates on every TW_CHAPTER_TRANSITION.
 */
public record ControlEvent(
        String action,
        boolean pressed,
        long timestamp,
        Long chapterId
) {
    /** Backward-compatible constructor for tests + legacy callers
     *  that pre-date the per-chapter position cache (added 2026-04-26). */
    public ControlEvent(String action, boolean pressed, long timestamp) {
        this(action, pressed, timestamp, null);
    }
}
