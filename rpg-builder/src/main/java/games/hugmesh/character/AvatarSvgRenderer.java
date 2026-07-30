package games.hugmesh.character;

import games.hugmesh.character.Expressions.Pose;
import games.hugmesh.character.HumanoidRig.Joint;
import games.hugmesh.character.HumanoidRig.Part;
import games.hugmesh.character.HumanoidRig.Shape;
import games.hugmesh.character.HumanoidRig.Vec3;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The 2D face of a character — an orthographic projection of {@link HumanoidRig}.
 *
 * <p>This is the mechanism behind decision 028's "the 2D side matches the 3D
 * side": it is <b>the same rig</b>, viewed from +Z, rather than a parallel
 * drawing routine. The previous maker had a canvas routine with its own
 * hard-coded body widths next to a GDScript factory with its own hard-coded
 * mesh dimensions; the two could disagree and nothing would catch it. Here a
 * change to the rig shows up in the SVG for free, and a test asserts the drawn
 * proportions actually track the spec.
 *
 * <p>Projection: {@code screen.x = ox + world.x·s}, {@code screen.y = oy −
 * world.y·s} (world Y is up, SVG Y is down), with {@code s} and the offsets
 * chosen to fit the character's bounding box in the viewport. Parts are painted
 * back-to-front by world Z so nearer geometry occludes — ties broken by name so
 * output is byte-stable.
 *
 * <p>Style is honoured in 2D too, because that is the point of N64 mode:
 * {@code lowpoly} paints flat fills with a crisp darker stroke and no
 * gradients; {@code smooth} adds a soft vertical gradient per colour.
 *
 * <p>Dependency-free (this file is synced into the other services), and
 * deterministic: the same spec renders byte-identical output every time.
 */
public final class AvatarSvgRenderer {

    private AvatarSvgRenderer() {}

    /** Guard rails so a caller cannot ask for an absurd allocation. */
    public static final int MIN_DIM = 16;
    public static final int MAX_DIM = 1024;

    /** Depth shading is deliberately subtle — silhouette aid, not a light rig. */
    private static final double MAX_DEPTH_DARKEN = 0.18;

    /** Full-body front view using the spec's own expression. */
    public static String render(CharacterSpec spec, int width, int height) {
        return draw(spec, spec.expression(), width, height, false);
    }

    /** Full-body front view with an explicit expression. */
    public static String renderExpression(CharacterSpec spec, String expression, int width, int height) {
        return draw(spec, expression, width, height, false);
    }

    /**
     * Head-and-shoulders crop — the shape a profile picture or a post
     * attachment wants.
     */
    public static String renderPortrait(CharacterSpec spec, String expression, int width, int height) {
        return draw(spec, expression, width, height, true);
    }

    // ── Projection pipeline ─────────────────────────────────────────────────

    /** A part resolved to world space, with any expression pose folded in. */
    private record Placed(String name, Shape shape, Vec3 pos, Vec3 size, double rotZ,
                          double taper, String color) {
        double halfW() { return size.x() / 2; }
        double halfH() { return size.y() / 2; }
    }

    private static String draw(CharacterSpec spec, String expression, int width, int height,
                               boolean portrait) {
        int w = clampDim(width, "width");
        int h = clampDim(height, "height");

        Joint root = HumanoidRig.of(spec);
        Map<String, Pose> pose = Expressions.resolve(spec, expression);

        List<Placed> parts = new ArrayList<>();
        Map<String, Vec3> jointWorld = new LinkedHashMap<>();
        flatten(root, Vec3.ZERO, pose, parts, jointWorld);

        // Painter's algorithm. Ties broken by name so the byte output is stable
        // — an unstable sort here would make the "renders identically twice"
        // guarantee (and any golden-file comparison) quietly false.
        parts.sort(Comparator.comparingDouble((Placed p) -> p.pos().z())
                .thenComparing(Placed::name));

        double[] box = portrait ? portraitBox(parts, jointWorld) : fullBox(parts);
        double minX = box[0], maxX = box[1], minY = box[2], maxY = box[3];

        double margin = Math.max(2.0, Math.min(w, h) * 0.04);
        double spanX = Math.max(1e-6, maxX - minX);
        double spanY = Math.max(1e-6, maxY - minY);
        double s = Math.min((w - 2 * margin) / spanX, (h - 2 * margin) / spanY);
        double ox = w / 2.0 - (minX + maxX) / 2.0 * s;
        double oy = h / 2.0 + (minY + maxY) / 2.0 * s;

        // Depth range drives the shading ramp.
        double minZ = parts.stream().mapToDouble(p -> p.pos().z()).min().orElse(0);
        double maxZ = parts.stream().mapToDouble(p -> p.pos().z()).max().orElse(1);

        StringBuilder body = new StringBuilder();
        Map<String, String> gradients = new LinkedHashMap<>();
        for (Placed p : parts) {
            String fill = shade(p.color(), p.pos().z(), minZ, maxZ);
            String paint = fill;
            if (!spec.lowPoly()) {
                paint = "url(#" + gradients.computeIfAbsent(fill, c -> "g" + gradients.size()) + ")";
            }
            emit(body, p, paint, fill, spec.lowPoly(), s, ox, oy);
        }

        StringBuilder svg = new StringBuilder(body.length() + 512);
        svg.append("<svg xmlns='http://www.w3.org/2000/svg' viewBox='0 0 ")
                .append(w).append(' ').append(h).append("' width='").append(w)
                .append("' height='").append(h).append("' role='img'>");
        if (!gradients.isEmpty()) {
            svg.append("<defs>");
            gradients.forEach((color, id) -> svg.append("<linearGradient id='").append(id)
                    .append("' x1='0' y1='0' x2='0' y2='1'>")
                    .append("<stop offset='0' stop-color='").append(lighten(color, 1.12)).append("'/>")
                    .append("<stop offset='1' stop-color='").append(HumanoidRig.darken(color, 0.88)).append("'/>")
                    .append("</linearGradient>"));
            svg.append("</defs>");
        }
        svg.append(body).append("</svg>");
        return svg.toString();
    }

    /**
     * Depth-first flatten that folds an expression pose into the five face
     * joints: the offset moves the joint, the scale resizes its parts, the
     * rotation adds to theirs. Nothing else in the tree is touched.
     */
    private static void flatten(Joint joint, Vec3 parentWorld, Map<String, Pose> pose,
                                List<Placed> out, Map<String, Vec3> jointWorld) {
        Pose p = pose.get(joint.name);
        Vec3 here = parentWorld.plus(joint.pos);
        if (p != null) here = here.plus(p.offset());
        jointWorld.put(joint.name, here);

        for (Part part : joint.parts) {
            Vec3 size = part.size();
            double rotZ = part.rot().z();
            if (p != null) {
                size = new Vec3(size.x() * p.scale().x(), size.y() * p.scale().y(),
                        size.z() * p.scale().z());
                rotZ += p.rot().z();
            }
            out.add(new Placed(part.name(), part.shape(), here.plus(part.pos()), size, rotZ,
                    part.taper(), part.color()));
        }
        for (Joint c : joint.children) flatten(c, here, pose, out, jointWorld);
    }

    private static double[] fullBox(List<Placed> parts) {
        double minX = Double.MAX_VALUE, maxX = -Double.MAX_VALUE;
        double minY = Double.MAX_VALUE, maxY = -Double.MAX_VALUE;
        for (Placed p : parts) {
            minX = Math.min(minX, p.pos().x() - p.halfW());
            maxX = Math.max(maxX, p.pos().x() + p.halfW());
            minY = Math.min(minY, p.pos().y() - p.halfH());
            maxY = Math.max(maxY, p.pos().y() + p.halfH());
        }
        return new double[]{minX, maxX, minY, maxY};
    }

    /**
     * Everything from the top of the head down to just below the shoulders.
     * Anchored on the Neck joint rather than a hard-coded height so it stays
     * correct across the whole {@code height}/{@code neck}/{@code head} range.
     */
    private static double[] portraitBox(List<Placed> parts, Map<String, Vec3> jointWorld) {
        Vec3 neck = jointWorld.getOrDefault("Neck", jointWorld.get("Head"));
        double cut = neck == null ? 0 : neck.y() - 0.16;
        List<Placed> above = parts.stream().filter(p -> p.pos().y() + p.halfH() >= cut).toList();
        double[] box = fullBox(above.isEmpty() ? parts : above);
        box[2] = Math.max(box[2], cut);
        // Keep a little air around the head so a square viewport does not crop it.
        double padX = (box[1] - box[0]) * 0.10;
        box[0] -= padX;
        box[1] += padX;
        return box;
    }

    // ── Shape emission ──────────────────────────────────────────────────────

    private static void emit(StringBuilder out, Placed p, String paint, String flat,
                             boolean lowPoly, double s, double ox, double oy) {
        double cx = ox + p.pos().x() * s;
        double cy = oy - p.pos().y() * s;
        double w = p.size().x() * s;
        double h = p.size().y() * s;

        String stroke = lowPoly
                ? " stroke='" + HumanoidRig.darken(flat, 0.62) + "' stroke-width='1'"
                : "";
        String transform = Math.abs(p.rotZ()) < 1e-6 ? ""
                : " transform='rotate(" + fmt(-Math.toDegrees(p.rotZ())) + ' '
                        + fmt(cx) + ' ' + fmt(cy) + ")'";
        String tag = " data-part='" + safeName(p.name()) + "'";

        switch (p.shape()) {
            case SPHERE -> out.append("<ellipse").append(tag)
                    .append(" cx='").append(fmt(cx)).append("' cy='").append(fmt(cy))
                    .append("' rx='").append(fmt(w / 2)).append("' ry='").append(fmt(h / 2))
                    .append("' fill='").append(paint).append('\'').append(stroke).append(transform)
                    .append("/>");
            case CYL -> {
                if (p.taper() >= 0.999) {
                    rect(out, tag, cx, cy, w, h, paint, stroke, transform);
                } else {
                    // Truncated cone → trapezoid. taper is topRadius/bottomRadius,
                    // so the top edge is the bottom edge scaled by it.
                    double topW = w * p.taper();
                    double x0 = cx - w / 2, x1 = cx + w / 2;
                    double tx0 = cx - topW / 2, tx1 = cx + topW / 2;
                    double y0 = cy - h / 2, y1 = cy + h / 2;
                    out.append("<polygon").append(tag).append(" points='")
                            .append(fmt(tx0)).append(',').append(fmt(y0)).append(' ')
                            .append(fmt(tx1)).append(',').append(fmt(y0)).append(' ')
                            .append(fmt(x1)).append(',').append(fmt(y1)).append(' ')
                            .append(fmt(x0)).append(',').append(fmt(y1))
                            .append("' fill='").append(paint).append('\'').append(stroke)
                            .append(transform).append("/>");
                }
            }
            default -> rect(out, tag, cx, cy, w, h, paint, stroke, transform);
        }
    }

    private static void rect(StringBuilder out, String tag, double cx, double cy, double w, double h,
                             String paint, String stroke, String transform) {
        out.append("<rect").append(tag)
                .append(" x='").append(fmt(cx - w / 2)).append("' y='").append(fmt(cy - h / 2))
                .append("' width='").append(fmt(w)).append("' height='").append(fmt(h))
                .append("' fill='").append(paint).append('\'').append(stroke).append(transform)
                .append("/>");
    }

    // ── Helpers ─────────────────────────────────────────────────────────────

    /** Further-back parts sit slightly darker so the silhouette reads. */
    private static String shade(String color, double z, double minZ, double maxZ) {
        double span = maxZ - minZ;
        double t = span < 1e-9 ? 1.0 : (z - minZ) / span;   // 0 = furthest, 1 = nearest
        return HumanoidRig.darken(color, 1.0 - MAX_DEPTH_DARKEN * (1.0 - t));
    }

    private static String lighten(String hex, double f) {
        int r = Math.min(255, (int) Math.round(Integer.parseInt(hex.substring(1, 3), 16) * f));
        int g = Math.min(255, (int) Math.round(Integer.parseInt(hex.substring(3, 5), 16) * f));
        int b = Math.min(255, (int) Math.round(Integer.parseInt(hex.substring(5, 7), 16) * f));
        return String.format("#%02x%02x%02x", r, g, b);
    }

    /**
     * Part names come from {@link HumanoidRig}'s own constants, never from user
     * input — but the attribute is filtered anyway so this can never become an
     * injection point if a future part name is derived from something else.
     */
    private static String safeName(String n) {
        StringBuilder b = new StringBuilder(n.length());
        for (char c : n.toCharArray()) {
            if (Character.isLetterOrDigit(c) || c == '_' || c == '-') b.append(c);
        }
        return b.toString();
    }

    /** Locale-independent, trailing-zero-free — keeps the output byte-stable. */
    private static String fmt(double v) {
        double r = Math.round(v * 1000.0) / 1000.0;
        if (r == Math.rint(r) && Math.abs(r) < 1e9) return Long.toString((long) r);
        return String.format(Locale.ROOT, "%.3f", r);
    }

    private static int clampDim(int v, String field) {
        if (v < MIN_DIM || v > MAX_DIM) {
            throw new IllegalArgumentException(field + " must be between " + MIN_DIM
                    + " and " + MAX_DIM + " (got " + v + ")");
        }
        return v;
    }
}
