package games.hugmesh.character;

import java.util.ArrayList;
import java.util.List;

/**
 * Turns a {@link CharacterSpec} into an articulated humanoid — the single
 * geometric truth every renderer projects.
 *
 * <p>The rig is a tree of {@link Joint}s (plain transforms) carrying
 * {@link Part}s (primitives). Deliberately <b>no skinning and no
 * {@code Skeleton3D}</b>: limbs are rigid segments pivoting at plain joints.
 * That is exactly how N64-era characters were built, it keeps the GDScript a
 * dumb instantiator, and it animates by rotating joints — which is all
 * {@code avatar_poser.gd} needs for walking, gestures and expressions.
 *
 * <pre>
 * Root
 * └─ Hips ─────────────────────── pelvis, skirt
 *    ├─ Torso ─────────────────── waist
 *    │  └─ Chest ──────────────── chest, back item
 *    │     ├─ Neck ─── Head ───── skull, hair, hat, face item
 *    │     │                      EyeL EyeR BrowL BrowR Mouth  ← expression targets
 *    │     ├─ ShoulderL ─ UpperArmL ─ ForearmL ─ HandL ── off-hand item
 *    │     └─ ShoulderR ─ UpperArmR ─ ForearmR ─ HandR ── main-hand item
 *    ├─ ThighL ─ ShinL ─ FootL
 *    └─ ThighR ─ ShinR ─ FootR
 * </pre>
 *
 * <p><b>Orientation.</b> +Y up, <b>+Z is the character's front</b>, +X is the
 * character's own left. Godot's native forward is −Z, so the consumer rotates
 * the assembled holder by π about Y (which flips the whole avatar, leaving its
 * left hand its left hand). The SVG front view projects from +Z.
 *
 * <p><b>Units are metres</b>, ground plane at {@link #GROUND}. A default
 * character stands ~1.94 m with a 1:7 head-to-height ratio; the extremes of the
 * {@link CharacterSpec#PROPORTIONS} bounds stay recognisably bipedal.
 *
 * <p>{@code renderStyle} does NOT change the rig — the same tree is emitted for
 * {@code smooth} and {@code lowpoly}. Style is a materialisation concern
 * (segment counts, flat shading, nearest-neighbour filtering) applied by each
 * consumer, so 2D/3D/glTF parity holds across both.
 */
public final class HumanoidRig {

    private HumanoidRig() {}

    /** Sole height above y=0, so a walk cycle has room to lift a foot. */
    public static final double GROUND = 0.06;

    public record Vec3(double x, double y, double z) {
        public static final Vec3 ZERO = new Vec3(0, 0, 0);
        public Vec3 plus(Vec3 o) { return new Vec3(x + o.x, y + o.y, z + o.z); }
    }

    /** Primitive kinds every consumer knows how to materialise. */
    public enum Shape { BOX, CYL, SPHERE, QUAD }

    /**
     * One primitive hanging off a joint.
     *
     * @param size   full extents (width, height, depth) — NOT radii
     * @param taper  for {@link Shape#CYL}: top-radius ÷ bottom-radius. 1.0 is a
     *               plain cylinder, 0.0 a cone, anything between a truncated
     *               cone (robe skirts, hat crowns). Ignored by other shapes.
     * @param tex    texture slot an uploaded skin maps onto: {@code skin},
     *               {@code outfit} or {@code none}
     */
    public record Part(String name, Shape shape, Vec3 pos, Vec3 size, Vec3 rot,
                       double taper, String color, String tex) {

        public static Part box(String n, Vec3 pos, Vec3 size, String color, String tex) {
            return new Part(n, Shape.BOX, pos, size, Vec3.ZERO, 1.0, color, tex);
        }

        public static Part cyl(String n, Vec3 pos, double dia, double len, String color, String tex) {
            return new Part(n, Shape.CYL, pos, new Vec3(dia, len, dia), Vec3.ZERO, 1.0, color, tex);
        }

        public static Part taperedCyl(String n, Vec3 pos, double botDia, double topDia,
                                      double len, String color, String tex) {
            double t = botDia == 0 ? 1.0 : topDia / botDia;
            return new Part(n, Shape.CYL, pos, new Vec3(botDia, len, botDia), Vec3.ZERO, t, color, tex);
        }

        public static Part sphere(String n, Vec3 pos, Vec3 size, String color, String tex) {
            return new Part(n, Shape.SPHERE, pos, size, Vec3.ZERO, 1.0, color, tex);
        }

        public Part rotated(Vec3 r) {
            return new Part(name, shape, pos, size, r, taper, color, tex);
        }
    }

    /** A transform node. {@code pos} is the offset from its parent joint. */
    public static final class Joint {
        public final String name;
        public final Vec3 pos;
        public final List<Part> parts = new ArrayList<>();
        public final List<Joint> children = new ArrayList<>();

        public Joint(String name, Vec3 pos) {
            this.name = name;
            this.pos = pos;
        }

        Joint add(Part p) { parts.add(p); return this; }

        Joint addIf(boolean cond, Part p) { if (cond) parts.add(p); return this; }

        Joint child(Joint c) { children.add(c); return this; }

        /** Depth-first walk — used by every renderer and by the golden tests. */
        public void walk(Vec3 parentWorld, java.util.function.BiConsumer<Vec3, Part> visit) {
            Vec3 here = parentWorld.plus(pos);
            for (Part p : parts) visit.accept(here.plus(p.pos()), p);
            for (Joint c : children) c.walk(here, visit);
        }

        /** Finds a joint by name anywhere in the subtree, or null. */
        public Joint find(String n) {
            if (name.equals(n)) return this;
            for (Joint c : children) {
                Joint hit = c.find(n);
                if (hit != null) return hit;
            }
            return null;
        }
    }

    // ── Assembly ────────────────────────────────────────────────────────────

    /** Builds the full rig. Pure and deterministic — same spec, same tree. */
    public static Joint of(CharacterSpec s) {
        // Proportions.
        double h = s.p("height"), hd = s.p("head"), nk = s.p("neck");
        double ch = s.p("chest"), wa = s.p("waist"), sh = s.p("shoulder");
        double al = s.p("armLength"), at = s.p("armThickness");
        double ll = s.p("legLength"), lt = s.p("legThickness");
        double hnd = s.p("hand"), ft = s.p("foot");

        // Base girth from the coarse body-shape preset; the fine sliders ride on top.
        double girth = switch (s.bodyShape()) {
            case "stocky" -> 1.15;
            case "round" -> 1.30;
            default -> 0.88;
        };

        // Segment lengths.
        double thighLen = 0.42 * ll * h;
        double shinLen = 0.38 * ll * h;
        double footH = 0.07 * ft;
        double waistLen = 0.20 * h;
        double chestLen = 0.28 * h;
        double neckLen = 0.10 * nk;
        double upperArmLen = 0.28 * al * h;
        double foreArmLen = 0.26 * al * h;
        double handLen = 0.11 * hnd;

        // Girths.
        double pelvisW = 0.30 * girth * wa, pelvisD = 0.20 * girth;
        double waistW = 0.30 * girth * wa;
        double chestW = 0.38 * girth * ch, chestD = 0.24 * girth * ch;
        double shoulderX = 0.20 * sh * girth;
        double armDia = 0.11 * at * girth, foreDia = 0.096 * at * girth;
        double handW = 0.09 * hnd;
        double thighDia = 0.15 * lt * girth, shinDia = 0.12 * lt * girth;
        double footW = 0.11 * ft, footD = 0.24 * ft;
        double neckDia = 0.12 * nk;
        double skullW = 0.26 * hd, skullH = 0.28 * hd, skullD = 0.24 * hd;
        double hipX = 0.11 * girth;

        // Colours per region.
        String outfit = s.color("body");
        String skin = s.color("skin");
        String accent = s.color("accent");
        String limb = s.limbsClothed() ? outfit : skin;
        String limbTex = s.limbsClothed() ? "outfit" : "skin";
        boolean bare = "bare".equals(s.outfitType());

        // Stack the leg upward so the sole always lands exactly on GROUND.
        double ankleY = GROUND + footH;
        double kneeY = ankleY + shinLen;
        double hipY = kneeY + thighLen;
        double hipsY = hipY + 0.09 * h;

        Joint root = new Joint("Root", Vec3.ZERO);
        Joint hips = new Joint("Hips", new Vec3(0, hipsY, 0));
        root.child(hips);

        hips.add(Part.box("Pelvis", new Vec3(0, -0.04, 0),
                new Vec3(pelvisW, 0.18, pelvisD), bare ? skin : outfit, bare ? "skin" : "outfit"));

        // Skirt: a truncated cone from the hips down. Preserves the silhouette
        // mass of the pre-rig "robe" (which the @godot red-hero pixel proof
        // samples) while the legs underneath are now real.
        double skirt = s.skirtLength() * h;
        if (skirt > 0) {
            hips.add(Part.taperedCyl("Skirt", new Vec3(0, -0.04 - skirt / 2, 0),
                    pelvisW * 1.9, pelvisW * 1.05, skirt, outfit, "outfit"));
        }

        // ── Torso ────────────────────────────────────────────────────────────
        Joint torso = new Joint("Torso", new Vec3(0, 0.12 * h, 0));
        hips.child(torso);
        torso.add(Part.taperedCyl("Waist", new Vec3(0, waistLen / 2, 0),
                waistW, chestW * 0.92, waistLen, bare ? skin : outfit, bare ? "skin" : "outfit"));

        Joint chest = new Joint("Chest", new Vec3(0, waistLen, 0));
        torso.child(chest);
        chest.add(new Part("Chest", Shape.BOX, new Vec3(0, chestLen / 2, 0),
                new Vec3(chestW, chestLen, chestD), Vec3.ZERO, 1.0,
                bare ? skin : outfit, bare ? "skin" : "outfit"));
        if (!bare) {
            chest.add(Part.box("Trim", new Vec3(0, chestLen * 0.30, chestD * 0.5),
                    new Vec3(chestW * 0.22, chestLen * 0.60, 0.02), accent, "outfit"));
        }
        backItem(chest, s, chestW, chestLen, chestD, accent);

        // ── Head ─────────────────────────────────────────────────────────────
        Joint neck = new Joint("Neck", new Vec3(0, chestLen, 0));
        chest.child(neck);
        neck.add(Part.cyl("NeckCol", new Vec3(0, neckLen / 2, 0), neckDia, neckLen, skin, "skin"));

        Joint head = new Joint("Head", new Vec3(0, neckLen, 0));
        neck.child(head);
        skull(head, s, skullW, skullH, skullD, skin);
        face(head, s, skullH, skullD, hd);
        hair(head, s, skullW, skullH, skullD);
        hat(head, s, skullW, skullH);
        faceItem(head, s, skullW, skullH, skullD);

        // ── Arms ─────────────────────────────────────────────────────────────
        for (int side = 0; side < 2; side++) {
            boolean left = side == 0;
            String sfx = left ? "L" : "R";
            double sx = left ? shoulderX : -shoulderX;

            Joint shoulder = new Joint("Shoulder" + sfx, new Vec3(sx, chestLen * 0.80, 0));
            chest.child(shoulder);
            shoulder.add(Part.sphere("ShoulderBall" + sfx, Vec3.ZERO,
                    new Vec3(armDia * 1.2, armDia * 1.2, armDia * 1.2),
                    bare ? skin : outfit, bare ? "skin" : "outfit"));

            Joint upper = new Joint("UpperArm" + sfx, new Vec3(0, -0.02, 0));
            shoulder.child(upper);
            // A robe/dress sleeve covers the upper arm; a tunic's stops at the elbow.
            String upperCol = bare ? skin : outfit;
            upper.add(Part.cyl("UpperArm" + sfx, new Vec3(0, -upperArmLen / 2, 0),
                    armDia, upperArmLen, upperCol, bare ? "skin" : "outfit"));

            Joint fore = new Joint("Forearm" + sfx, new Vec3(0, -upperArmLen, 0));
            upper.child(fore);
            fore.add(Part.cyl("Forearm" + sfx, new Vec3(0, -foreArmLen / 2, 0),
                    foreDia, foreArmLen, limb, limbTex));

            Joint hand = new Joint("Hand" + sfx, new Vec3(0, -foreArmLen, 0));
            fore.child(hand);
            hand.add(Part.box("Hand" + sfx, new Vec3(0, -handLen / 2, 0),
                    new Vec3(handW, handLen, handW * 0.7), skin, "skin"));

            if (left) heldOff(hand, s, handLen);
            else heldMain(hand, s, handLen, h);
        }

        // ── Legs ─────────────────────────────────────────────────────────────
        for (int side = 0; side < 2; side++) {
            boolean left = side == 0;
            String sfx = left ? "L" : "R";
            double lx = left ? hipX : -hipX;

            Joint thigh = new Joint("Thigh" + sfx, new Vec3(lx, -(hipsY - hipY), 0));
            hips.child(thigh);
            thigh.add(Part.cyl("Thigh" + sfx, new Vec3(0, -thighLen / 2, 0),
                    thighDia, thighLen, limb, limbTex));

            Joint shin = new Joint("Shin" + sfx, new Vec3(0, -thighLen, 0));
            thigh.child(shin);
            shin.add(Part.cyl("Shin" + sfx, new Vec3(0, -shinLen / 2, 0),
                    shinDia, shinLen, limb, limbTex));

            Joint foot = new Joint("Foot" + sfx, new Vec3(0, -shinLen, 0));
            shin.child(foot);
            foot.add(Part.box("Foot" + sfx, new Vec3(0, -footH / 2, footD * 0.18),
                    new Vec3(footW, footH, footD), bare ? skin : accent, "none"));
        }

        return root;
    }

    // ── Head sub-assemblies ─────────────────────────────────────────────────

    private static void skull(Joint head, CharacterSpec s, double w, double hh, double d, String skin) {
        Vec3 at = new Vec3(0, hh / 2, 0);
        switch (s.headShape()) {
            case "square" -> head.add(Part.box("Skull", at, new Vec3(w, hh, d), skin, "skin"));
            case "oval" -> head.add(Part.sphere("Skull", at, new Vec3(w * 0.92, hh * 1.12, d), skin, "skin"));
            case "tapered" -> head.add(Part.taperedCyl("Skull", at, w, w * 0.62, hh, skin, "skin"));
            default -> head.add(Part.sphere("Skull", at, new Vec3(w, hh, d), skin, "skin"));
        }
    }

    /**
     * The five expression targets, each its OWN joint so {@link Expressions}
     * can translate/scale it without rebuilding geometry — and so the SVG and
     * the Godot poser apply the identical table.
     */
    private static void face(Joint head, CharacterSpec s, double hh, double d, double hd) {
        String eye = s.color("eye");
        String hair = s.color("hair");
        double eyeX = 0.075 * hd, eyeY = hh * 0.58, z = d * 0.46;
        double eyeW = 0.055 * hd, eyeH = 0.055 * hd;

        for (int i = 0; i < 2; i++) {
            String sfx = i == 0 ? "L" : "R";
            double x = i == 0 ? eyeX : -eyeX;
            Joint e = new Joint("Eye" + sfx, new Vec3(x, eyeY, z));
            e.add(Part.sphere("Eye" + sfx, Vec3.ZERO, new Vec3(eyeW, eyeH, eyeW * 0.6), eye, "none"));
            head.child(e);

            Joint b = new Joint("Brow" + sfx, new Vec3(x, eyeY + 0.055 * hd, z));
            b.add(Part.box("Brow" + sfx, Vec3.ZERO,
                    new Vec3(eyeW * 1.4, 0.014 * hd, 0.02), hair, "none"));
            head.child(b);
        }

        Joint mouth = new Joint("Mouth", new Vec3(0, hh * 0.26, z));
        mouth.add(Part.box("Mouth", Vec3.ZERO,
                new Vec3(0.075 * hd, 0.018 * hd, 0.02), darken(s.color("skin"), 0.55), "none"));
        head.child(mouth);
    }

    private static void hair(Joint head, CharacterSpec s, double w, double hh, double d) {
        String c = s.color("hair");
        if ("none".equals(s.hairStyle())) return;
        double capY = hh * 0.72;
        switch (s.hairStyle()) {
            case "short" -> head.add(Part.sphere("Hair", new Vec3(0, capY, 0),
                    new Vec3(w * 1.06, hh * 0.62, d * 1.06), c, "none"));
            case "long" -> {
                head.add(Part.sphere("Hair", new Vec3(0, capY, 0),
                        new Vec3(w * 1.08, hh * 0.66, d * 1.08), c, "none"));
                head.add(Part.box("HairBack", new Vec3(0, hh * 0.18, -d * 0.5),
                        new Vec3(w * 0.92, hh * 1.15, 0.06), c, "none"));
            }
            case "ponytail" -> {
                head.add(Part.sphere("Hair", new Vec3(0, capY, 0),
                        new Vec3(w * 1.06, hh * 0.60, d * 1.06), c, "none"));
                head.add(Part.taperedCyl("Ponytail", new Vec3(0, hh * 0.30, -d * 0.62),
                        w * 0.30, w * 0.10, hh * 0.95, c, "none"));
            }
            case "spiky" -> {
                head.add(Part.sphere("Hair", new Vec3(0, capY, 0),
                        new Vec3(w * 1.04, hh * 0.52, d * 1.04), c, "none"));
                for (int i = -1; i <= 1; i++) {
                    head.add(Part.taperedCyl("Spike" + (i + 1),
                            new Vec3(i * w * 0.28, hh * 1.02, -d * 0.1),
                            w * 0.24, 0.0, hh * 0.42, c, "none"));
                }
            }
            case "bun" -> {
                head.add(Part.sphere("Hair", new Vec3(0, capY, 0),
                        new Vec3(w * 1.06, hh * 0.60, d * 1.06), c, "none"));
                head.add(Part.sphere("Bun", new Vec3(0, hh * 1.02, -d * 0.42),
                        new Vec3(w * 0.46, w * 0.46, w * 0.46), c, "none"));
            }
            case "braid" -> {
                head.add(Part.sphere("Hair", new Vec3(0, capY, 0),
                        new Vec3(w * 1.06, hh * 0.62, d * 1.06), c, "none"));
                for (int i = 0; i < 3; i++) {
                    head.add(Part.sphere("Braid" + i,
                            new Vec3(0, hh * 0.12 - i * hh * 0.30, -d * 0.58),
                            new Vec3(w * 0.28, w * 0.28, w * 0.28), c, "none"));
                }
            }
            default -> { }
        }
    }

    private static void hat(Joint head, CharacterSpec s, double w, double hh) {
        String c = s.color("hat");
        String accent = s.color("accent");
        double top = hh * 1.02;
        switch (s.hatType()) {
            case "cap" -> {
                head.add(Part.taperedCyl("HatCrown", new Vec3(0, top, 0), w * 1.02, w * 0.86,
                        hh * 0.30, c, "none"));
                head.add(Part.box("HatBrim", new Vec3(0, top - hh * 0.12, w * 0.62),
                        new Vec3(w * 0.94, 0.02, w * 0.66), c, "none"));
            }
            case "cone" -> head.add(Part.taperedCyl("HatCone", new Vec3(0, top + hh * 0.42, 0),
                    w * 1.10, 0.0, hh * 1.30, c, "none"));
            case "top_hat" -> {
                head.add(Part.cyl("HatStack", new Vec3(0, top + hh * 0.42, 0), w * 0.82, hh * 0.95, c, "none"));
                head.add(Part.cyl("HatBrim", new Vec3(0, top - hh * 0.04, 0), w * 1.42, 0.03, c, "none"));
                head.add(Part.cyl("HatBand", new Vec3(0, top + hh * 0.10, 0), w * 0.85, hh * 0.12, accent, "none"));
            }
            case "helm" -> {
                head.add(Part.sphere("HelmDome", new Vec3(0, hh * 0.74, 0),
                        new Vec3(w * 1.14, hh * 0.86, w * 1.14), c, "none"));
                head.add(Part.box("HelmNasal", new Vec3(0, hh * 0.50, w * 0.52),
                        new Vec3(w * 0.12, hh * 0.48, 0.03), c, "none"));
            }
            case "crown" -> {
                head.add(Part.cyl("CrownBand", new Vec3(0, top, 0), w * 1.06, hh * 0.18, c, "none"));
                for (int i = 0; i < 5; i++) {
                    double a = Math.PI * 2 * i / 5.0;
                    head.add(Part.taperedCyl("CrownSpike" + i,
                            new Vec3(Math.sin(a) * w * 0.44, top + hh * 0.20, Math.cos(a) * w * 0.44),
                            w * 0.16, 0.0, hh * 0.28, accent, "none"));
                }
            }
            case "hood" -> {
                head.add(Part.sphere("Hood", new Vec3(0, hh * 0.62, -w * 0.10),
                        new Vec3(w * 1.30, hh * 1.10, w * 1.30), c, "none"));
                head.add(Part.taperedCyl("HoodDrape", new Vec3(0, hh * 0.02, -w * 0.34),
                        w * 0.80, w * 1.10, hh * 0.70, c, "none"));
            }
            case "bandana" -> head.add(Part.cyl("Bandana", new Vec3(0, hh * 0.82, 0),
                    w * 1.06, hh * 0.22, c, "none"));
            case "straw" -> {
                head.add(Part.taperedCyl("StrawCrown", new Vec3(0, top + hh * 0.08, 0),
                        w * 0.92, w * 0.70, hh * 0.34, c, "none"));
                head.add(Part.taperedCyl("StrawBrim", new Vec3(0, top - hh * 0.08, 0),
                        w * 1.85, w * 1.55, 0.03, c, "none"));
            }
            default -> { }
        }
    }

    private static void faceItem(Joint head, CharacterSpec s, double w, double hh, double d) {
        String c = s.color("item");
        double z = d * 0.52, eyeY = hh * 0.58;
        switch (s.faceItem()) {
            case "glasses" -> {
                for (int i = 0; i < 2; i++) {
                    double x = (i == 0 ? 1 : -1) * w * 0.28;
                    head.add(Part.cyl("Lens" + i, new Vec3(x, eyeY, z),
                            w * 0.34, 0.012, c, "none").rotated(new Vec3(Math.PI / 2, 0, 0)));
                }
                head.add(Part.box("Bridge", new Vec3(0, eyeY, z), new Vec3(w * 0.24, 0.012, 0.012), c, "none"));
            }
            case "mask" -> head.add(Part.box("Mask", new Vec3(0, hh * 0.34, z * 0.96),
                    new Vec3(w * 0.92, hh * 0.40, 0.04), c, "none"));
            case "visor" -> head.add(Part.box("Visor", new Vec3(0, eyeY, z),
                    new Vec3(w * 1.02, hh * 0.20, 0.04), c, "none"));
            case "eyepatch" -> {
                head.add(Part.box("Eyepatch", new Vec3(-w * 0.28, eyeY, z),
                        new Vec3(w * 0.34, hh * 0.22, 0.03), "#1a1a1a", "none"));
                head.add(Part.box("EyepatchStrap", new Vec3(0, eyeY + hh * 0.10, 0),
                        new Vec3(w * 1.06, 0.014, w * 1.06), "#1a1a1a", "none"));
            }
            default -> { }
        }
    }

    // ── Item sub-assemblies ─────────────────────────────────────────────────

    private static void backItem(Joint chest, CharacterSpec s, double w, double len, double d, String accent) {
        String c = s.color("item");
        double back = -d * 0.55;
        switch (s.backItem()) {
            case "cape" -> chest.add(Part.taperedCyl("Cape", new Vec3(0, -len * 0.10, back * 0.9),
                    w * 1.05, w * 1.55, len * 2.6, s.color("body"), "outfit"));
            case "backpack" -> {
                chest.add(Part.box("Backpack", new Vec3(0, len * 0.45, back - 0.06),
                        new Vec3(w * 0.72, len * 0.80, 0.16), c, "none"));
                chest.add(Part.box("BackpackStrap", new Vec3(0, len * 0.45, back + 0.02),
                        new Vec3(w * 0.80, 0.03, 0.03), accent, "none"));
            }
            case "wings" -> {
                for (int i = 0; i < 2; i++) {
                    double x = (i == 0 ? 1 : -1) * w * 0.42;
                    chest.add(Part.taperedCyl("Wing" + i, new Vec3(x, len * 0.55, back),
                            w * 0.60, w * 0.12, len * 1.60, c, "none")
                            .rotated(new Vec3(0, 0, (i == 0 ? -1 : 1) * 0.55)));
                }
            }
            case "quiver" -> {
                chest.add(Part.cyl("Quiver", new Vec3(w * 0.30, len * 0.45, back - 0.04),
                        0.10, len * 1.05, c, "none").rotated(new Vec3(0, 0, -0.35)));
                chest.add(Part.taperedCyl("Arrows", new Vec3(w * 0.34, len * 1.02, back - 0.04),
                        0.07, 0.03, len * 0.42, accent, "none").rotated(new Vec3(0, 0, -0.35)));
            }
            case "scabbard" -> chest.add(Part.taperedCyl("Scabbard",
                    new Vec3(-w * 0.34, -len * 0.10, back - 0.02),
                    0.07, 0.05, len * 2.0, c, "none").rotated(new Vec3(0, 0, 0.30)));
            default -> { }
        }
    }

    /** Right hand. Items hang from the grip, so a gesture carries them along. */
    private static void heldMain(Joint hand, CharacterSpec s, double handLen, double h) {
        String c = s.color("item");
        String accent = s.color("accent");
        double g = -handLen * 0.9;   // grip point, just below the palm
        switch (s.heldMain()) {
            case "sword" -> {
                hand.add(Part.box("SwordGrip", new Vec3(0, g - 0.06, 0), new Vec3(0.03, 0.14, 0.03), accent, "none"));
                hand.add(Part.box("SwordGuard", new Vec3(0, g - 0.14, 0), new Vec3(0.20, 0.03, 0.04), accent, "none"));
                hand.add(Part.box("SwordBlade", new Vec3(0, g - 0.14 - 0.34 * h, 0),
                        new Vec3(0.07, 0.68 * h, 0.02), c, "none"));
            }
            case "staff" -> {
                hand.add(Part.cyl("Staff", new Vec3(0, g - 0.10, 0), 0.045, 1.55 * h, "#6b4a2a", "none"));
                hand.add(Part.sphere("StaffOrb", new Vec3(0, g - 0.10 + 0.78 * h, 0),
                        new Vec3(0.14, 0.14, 0.14), accent, "none"));
            }
            case "axe" -> {
                hand.add(Part.cyl("AxeHaft", new Vec3(0, g - 0.32, 0), 0.045, 0.80, "#6b4a2a", "none"));
                hand.add(Part.box("AxeHead", new Vec3(0.10, g - 0.66, 0), new Vec3(0.22, 0.20, 0.04), c, "none"));
            }
            case "bow" -> {
                hand.add(Part.taperedCyl("BowLimb", new Vec3(0, g - 0.02, 0), 0.05, 0.02, 1.20 * h, c, "none"));
                hand.add(Part.box("BowString", new Vec3(0, g - 0.02, 0.07),
                        new Vec3(0.012, 1.14 * h, 0.012), accent, "none"));
            }
            case "wand" -> {
                hand.add(Part.taperedCyl("Wand", new Vec3(0, g - 0.16, 0), 0.030, 0.016, 0.36, "#3a2a1c", "none"));
                hand.add(Part.sphere("WandTip", new Vec3(0, g - 0.34, 0), new Vec3(0.07, 0.07, 0.07), accent, "none"));
            }
            case "pickaxe" -> {
                hand.add(Part.cyl("PickHaft", new Vec3(0, g - 0.30, 0), 0.042, 0.74, "#6b4a2a", "none"));
                hand.add(Part.box("PickHead", new Vec3(0, g - 0.64, 0), new Vec3(0.40, 0.06, 0.05), c, "none"));
            }
            case "lantern" -> lantern(hand, g, c, accent);
            case "book" -> book(hand, g, c, accent);
            default -> { }
        }
    }

    /** Left hand. */
    private static void heldOff(Joint hand, CharacterSpec s, double handLen) {
        String c = s.color("item");
        String accent = s.color("accent");
        double g = -handLen * 0.9;
        switch (s.heldOff()) {
            case "shield" -> {
                hand.add(Part.cyl("Shield", new Vec3(0, g - 0.14, 0.09), 0.52, 0.05, c, "none")
                        .rotated(new Vec3(Math.PI / 2, 0, 0)));
                hand.add(Part.cyl("ShieldBoss", new Vec3(0, g - 0.14, 0.13), 0.16, 0.04, accent, "none")
                        .rotated(new Vec3(Math.PI / 2, 0, 0)));
            }
            case "torch" -> {
                hand.add(Part.cyl("TorchHaft", new Vec3(0, g - 0.16, 0), 0.045, 0.38, "#6b4a2a", "none"));
                hand.add(Part.taperedCyl("TorchFlame", new Vec3(0, g - 0.42, 0), 0.13, 0.0, 0.22,
                        "#ffa53a", "none"));
            }
            case "book" -> book(hand, g, c, accent);
            case "lantern" -> lantern(hand, g, c, accent);
            case "orb" -> hand.add(Part.sphere("Orb", new Vec3(0, g - 0.11, 0),
                    new Vec3(0.17, 0.17, 0.17), accent, "none"));
            default -> { }
        }
    }

    private static void lantern(Joint hand, double g, String c, String accent) {
        hand.add(Part.box("LanternRing", new Vec3(0, g - 0.05, 0), new Vec3(0.02, 0.10, 0.02), c, "none"));
        hand.add(Part.box("LanternBody", new Vec3(0, g - 0.18, 0), new Vec3(0.14, 0.18, 0.14), c, "none"));
        hand.add(Part.box("LanternGlow", new Vec3(0, g - 0.18, 0), new Vec3(0.10, 0.12, 0.10), "#ffd98a", "none"));
    }

    private static void book(Joint hand, double g, String c, String accent) {
        hand.add(Part.box("BookCover", new Vec3(0, g - 0.06, 0.05), new Vec3(0.24, 0.06, 0.32), c, "none"));
        hand.add(Part.box("BookPages", new Vec3(0, g - 0.055, 0.055), new Vec3(0.21, 0.05, 0.29), "#efe6d2", "none"));
        hand.add(Part.box("BookRibbon", new Vec3(0.06, g - 0.03, 0.05), new Vec3(0.02, 0.01, 0.34), accent, "none"));
    }

    // ── Colour helper ───────────────────────────────────────────────────────

    /** Multiplies a {@code #rrggbb} toward black — used for the mouth line. */
    static String darken(String hex, double f) {
        int r = (int) Math.round(Integer.parseInt(hex.substring(1, 3), 16) * f);
        int g = (int) Math.round(Integer.parseInt(hex.substring(3, 5), 16) * f);
        int b = (int) Math.round(Integer.parseInt(hex.substring(5, 7), 16) * f);
        return String.format("#%02x%02x%02x", clamp(r), clamp(g), clamp(b));
    }

    private static int clamp(int v) { return Math.max(0, Math.min(255, v)); }
}
