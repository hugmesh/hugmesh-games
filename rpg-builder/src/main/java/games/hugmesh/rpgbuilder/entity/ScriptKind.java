package games.hugmesh.rpgbuilder.entity;

/**
 * RPG-maker script body kind. Enforced by {@code ck_rpg_scripts_kind}
 * in Liquibase 002 / 005:
 *
 * <ul>
 *   <li>{@code TRIGGER} — fires when a Mudlet-shaped trigger pattern
 *       matches the output stream.</li>
 *   <li>{@code ALIAS}   — substitutes a typed command before it ships.</li>
 *   <li>{@code KEY}     — bound to a keyboard event.</li>
 *   <li>{@code SCRIPT}  — generic GDScript snippet, runs once on load.</li>
 *   <li>{@code PAUSE}   — runs in response to the BFF's
 *       {@code session-control} SSE channel: the
 *       {@code session_control_received} HostBridge signal invokes the
 *       body. Game logic decides what pausing means — typically
 *       {@code get_tree().paused = true} but a per-game implementation
 *       can save state, gray out NPCs, etc.</li>
 * </ul>
 */
public enum ScriptKind { TRIGGER, ALIAS, KEY, SCRIPT, PAUSE }
