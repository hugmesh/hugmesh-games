package games.snapdragon.bff.model;

import java.util.Map;

/**
 * Per-game feature flags read from {@code games.features} (jsonb).
 * Closed vocabulary of well-known keys; unknown keys ride along
 * untouched (the upload pipeline tolerates new feature names so a
 * game can declare extras the BFF doesn't know yet).
 *
 * <p>Defaults match the JRPG-shaped TWiaT title: every flag on. Other
 * games narrow the set via {@code register.sql} during upload OR via
 * a hand-written Liquibase changeset (see 026-2 for the dodecahedron
 * narrowing).
 *
 * <p>The BFF gates routes on these in two places:
 * <ul>
 *   <li>JRPG-specific endpoints (spell loadout, quests, items,
 *       progression) check the matching flag and return
 *       {@code 405 Method Not Allowed} when the active game's
 *       feature is off.</li>
 *   <li>Input ingestion ({@link InputEventController}) checks the
 *       per-kind flag — a game with {@code gamepad: false} can't
 *       accidentally write gamepad rows even if a malicious client
 *       fabricates them.</li>
 * </ul>
 */
public record GameFeatures(
        boolean spellLoadout,
        boolean quests,
        boolean items,
        boolean charProgression,
        boolean audio,
        boolean gamepad,
        boolean mouse,
        boolean keyboard,
        boolean touch
) {
    public static final GameFeatures JRPG_DEFAULT = new GameFeatures(
            true, true, true, true,   // spell / quests / items / progression
            true, true, true, true, true  // audio / gamepad / mouse / keyboard / touch
    );

    /** Build from the DB jsonb column. Missing keys default to true. */
    public static GameFeatures fromMap(Map<String, Object> m) {
        if (m == null) return JRPG_DEFAULT;
        return new GameFeatures(
                bool(m, "instant_loadout",   true),
                bool(m, "quests",          true),
                bool(m, "items",           true),
                bool(m, "char_progression", true),
                bool(m, "audio",           true),
                bool(m, "gamepad",         true),
                bool(m, "mouse",           true),
                bool(m, "keyboard",        true),
                bool(m, "touch",           true)
        );
    }

    /** Look up a feature by event kind for the input-event gate. */
    public boolean accepts(String inputKind) {
        return switch (inputKind) {
            case InputEventKinds.GAMEPAD  -> gamepad;
            case InputEventKinds.MOUSE    -> mouse;
            case InputEventKinds.KEYBOARD -> keyboard;
            case InputEventKinds.TOUCH    -> touch;
            case InputEventKinds.AUDIO    -> audio;
            // World + Control are universal — gating them is the
            // game_event_support table's job, not the feature flags'.
            case InputEventKinds.WORLD, InputEventKinds.CONTROL -> true;
            default -> false;
        };
    }

    private static boolean bool(Map<String, Object> m, String key, boolean fallback) {
        Object v = m.get(key);
        return v instanceof Boolean b ? b : fallback;
    }
}
