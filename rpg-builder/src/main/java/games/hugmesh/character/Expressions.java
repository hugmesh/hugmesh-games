package games.hugmesh.character;

import games.hugmesh.character.HumanoidRig.Vec3;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Face poses — the table that makes an expression still and the in-game face
 * agree.
 *
 * <p>An expression is <b>render-time</b>, never baked geometry: it is a set of
 * local transforms applied to the five face joints the {@link HumanoidRig}
 * builds ({@code EyeL}, {@code EyeR}, {@code BrowL}, {@code BrowR},
 * {@code Mouth}). The SAME resolved table drives the SVG renderer, the glTF
 * export and {@code avatar_poser.gd}, so a character's happy face is the same
 * happy face whether it is a PNG attached to a post, a preview in the maker, or
 * a head turning toward you in a room.
 *
 * <p>Offsets are authored as fractions of <b>skull height</b> so they stay
 * proportional across the whole {@code head} bound (0.70–1.50); {@link #resolve}
 * turns them into concrete metres for one spec. Consumers never scale them
 * again.
 *
 * <p>Brow rotation sign convention: +Z rotation lifts the joint's <b>+X</b> end.
 * {@code BrowL} sits at +X (the character's own left), so its inner end is on
 * the −X side — an angry inward-down tilt is therefore <b>+</b> on the left brow
 * and <b>−</b> on the right.
 */
public final class Expressions {

    private Expressions() {}

    /** The joints an expression may move. Nothing else is touched. */
    public static final List<String> FACE_JOINTS =
            List.of("EyeL", "EyeR", "BrowL", "BrowR", "Mouth");

    /**
     * One joint's deviation from rest.
     *
     * @param offset translation in skull-height fractions (metres after {@link #resolve})
     * @param scale  multiplier on the joint's parts, 1.0 = unchanged
     * @param rot    Euler radians; only Z is used by the current table
     */
    public record Pose(Vec3 offset, Vec3 scale, Vec3 rot) {
        public static final Pose REST = new Pose(Vec3.ZERO, new Vec3(1, 1, 1), Vec3.ZERO);

        static Pose of(double dy, double sx, double sy, double rz) {
            return new Pose(new Vec3(0, dy, 0), new Vec3(sx, sy, 1), new Vec3(0, 0, rz));
        }
    }

    /** Authored table, in skull-height fractions. */
    private static final Map<String, Map<String, Pose>> TABLE = build();

    private static Map<String, Map<String, Pose>> build() {
        Map<String, Map<String, Pose>> t = new LinkedHashMap<>();

        t.put("neutral", Map.of());

        // Cheeks up, eyes squeezed to happy arcs, mouth broad.
        t.put("happy", faces(
                Pose.of(-0.010, 1.05, 0.55, 0), Pose.of(-0.010, 1.05, 0.55, 0),
                Pose.of(0.020, 1.00, 1.00, 0.10), Pose.of(0.020, 1.00, 1.00, -0.10),
                Pose.of(0.012, 1.55, 1.30, 0)));

        // Inner brows up-and-in, gaze lowered, mouth small and turned down.
        t.put("sad", faces(
                Pose.of(-0.008, 0.95, 0.80, 0), Pose.of(-0.008, 0.95, 0.80, 0),
                Pose.of(0.018, 1.00, 1.00, -0.28), Pose.of(0.018, 1.00, 1.00, 0.28),
                Pose.of(-0.022, 0.80, 1.10, 0)));

        // Brows driven down and inward, eyes narrowed, mouth a hard line.
        t.put("angry", faces(
                Pose.of(0.004, 1.10, 0.55, 0), Pose.of(0.004, 1.10, 0.55, 0),
                Pose.of(-0.030, 1.10, 1.20, 0.42), Pose.of(-0.030, 1.10, 1.20, -0.42),
                Pose.of(-0.006, 1.25, 0.70, 0)));

        t.put("surprised", faces(
                Pose.of(0.006, 1.40, 1.45, 0), Pose.of(0.006, 1.40, 1.45, 0),
                Pose.of(0.048, 1.00, 1.00, 0), Pose.of(0.048, 1.00, 1.00, 0),
                Pose.of(-0.014, 0.85, 2.30, 0)));

        // One eye shut — the asymmetry that proves per-joint posing works.
        t.put("wink", faces(
                Pose.of(-0.004, 1.05, 0.12, 0), Pose.REST,
                Pose.of(0.014, 1.00, 1.00, 0.16), Pose.REST,
                Pose.of(0.008, 1.35, 1.15, 0.10)));

        t.put("sleepy", faces(
                Pose.of(-0.012, 1.00, 0.22, 0), Pose.of(-0.012, 1.00, 0.22, 0),
                Pose.of(-0.014, 1.00, 1.00, -0.10), Pose.of(-0.014, 1.00, 1.00, 0.10),
                Pose.of(-0.010, 0.70, 0.85, 0)));

        t.put("determined", faces(
                Pose.of(0.002, 1.12, 0.78, 0), Pose.of(0.002, 1.12, 0.78, 0),
                Pose.of(-0.018, 1.08, 1.10, 0.24), Pose.of(-0.018, 1.08, 1.10, -0.24),
                Pose.of(-0.002, 1.30, 0.80, 0)));

        t.put("shout", faces(
                Pose.of(0.004, 1.15, 1.10, 0), Pose.of(0.004, 1.15, 1.10, 0),
                Pose.of(0.034, 1.00, 1.00, 0.12), Pose.of(0.034, 1.00, 1.00, -0.12),
                Pose.of(-0.026, 1.20, 3.10, 0)));

        return Map.copyOf(t);
    }

    private static Map<String, Pose> faces(Pose eyeL, Pose eyeR, Pose browL, Pose browR, Pose mouth) {
        Map<String, Pose> m = new LinkedHashMap<>();
        m.put("EyeL", eyeL);
        m.put("EyeR", eyeR);
        m.put("BrowL", browL);
        m.put("BrowR", browR);
        m.put("Mouth", mouth);
        return Map.copyOf(m);
    }

    /** Skull height in metres for {@code spec} — the unit offsets are authored in. */
    public static double skullHeight(CharacterSpec spec) {
        return 0.28 * spec.p("head");
    }

    /**
     * The pose for one expression with offsets resolved to metres for
     * {@code spec}. Unknown or {@code neutral} yields an empty map (rest).
     */
    public static Map<String, Pose> resolve(CharacterSpec spec, String expression) {
        Map<String, Pose> authored = TABLE.get(expression == null ? "neutral" : expression);
        if (authored == null || authored.isEmpty()) return Map.of();
        double unit = skullHeight(spec);
        Map<String, Pose> out = new LinkedHashMap<>();
        authored.forEach((joint, p) -> out.put(joint, new Pose(
                new Vec3(p.offset().x() * unit, p.offset().y() * unit, p.offset().z() * unit),
                p.scale(), p.rot())));
        return Map.copyOf(out);
    }

    /** Every expression resolved for {@code spec} — what the Godot bake emits. */
    public static Map<String, Map<String, Pose>> resolveAll(CharacterSpec spec) {
        Map<String, Map<String, Pose>> out = new LinkedHashMap<>();
        for (String name : TABLE.keySet()) {
            Map<String, Pose> p = resolve(spec, name);
            if (!p.isEmpty()) out.put(name, p);
        }
        return Map.copyOf(out);
    }

    /** Pose for one joint under one expression; {@link Pose#REST} when unposed. */
    public static Pose forJoint(Map<String, Pose> pose, String joint) {
        return pose.getOrDefault(joint, Pose.REST);
    }
}
