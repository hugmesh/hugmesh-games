package games.hugmesh.bff.model;

import java.util.Set;

/**
 * Closed vocabulary of {@code input_event_kinds.kind} values the BFF
 * recognises. Mirror of the seed data in Liquibase changeset 026.
 *
 * <p>Adding a new kind is a two-step:
 * <ol>
 *   <li>Add a Liquibase changeset that inserts a row into
 *       {@code input_event_kinds} (and rows in
 *       {@code input_event_types} for every type under that kind).</li>
 *   <li>Add the constant here so static usages compile.</li>
 * </ol>
 *
 * <p>The Java side is intentionally a bag of string constants rather
 * than an enum: new kinds may need to land at runtime via the upload
 * pipeline (a new game declaring a new event type), and the
 * controller path validates against the DB anyway. An enum would
 * forbid that.
 */
public final class InputEventKinds {
    public static final String GAMEPAD  = "gamepad";
    public static final String MOUSE    = "mouse";
    public static final String KEYBOARD = "keyboard";
    public static final String TOUCH    = "touch";
    public static final String AUDIO    = "audio";
    public static final String WORLD    = "world";
    /** Legacy JRPG control commands — movement / cast / interact. */
    public static final String CONTROL  = "control";

    /** Used by tests to assert the seed list stays in sync with the DB. */
    public static final Set<String> ALL_KNOWN = Set.of(
        GAMEPAD, MOUSE, KEYBOARD, TOUCH, AUDIO, WORLD, CONTROL
    );

    private InputEventKinds() { /* constants only */ }
}
