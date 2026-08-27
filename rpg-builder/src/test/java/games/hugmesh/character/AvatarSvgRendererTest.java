package games.hugmesh.character;

import org.junit.jupiter.api.Test;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The 2D renderer's contract.
 *
 * <p>The tests that carry the weight of decision 028 are
 * {@link #drawnProportionsTrackTheSpecNotAHardCodedTable()} and
 * {@link #theSvgIsDrivenByTheRigNotByItsOwnGeometry()}: they are what would
 * fail if someone reintroduced a parallel hard-coded drawing routine — which is
 * exactly the drift the old canvas preview had against {@code avatar_factory.gd}.
 */
class AvatarSvgRendererTest {

    private static final Pattern FILL = Pattern.compile("fill='(#[0-9a-fA-F]{6})'");

    private static Document parse(String svg) throws Exception {
        DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
        f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        DocumentBuilder b = f.newDocumentBuilder();
        return b.parse(new ByteArrayInputStream(svg.getBytes(StandardCharsets.UTF_8)));
    }

    /** Bounding box of a named part in SVG user units, or null if absent. */
    private static double[] partBox(String svg, String partName) throws Exception {
        Document d = parse(svg);
        NodeList all = d.getElementsByTagName("*");
        for (int i = 0; i < all.getLength(); i++) {
            Element e = (Element) all.item(i);
            if (!partName.equals(e.getAttribute("data-part"))) continue;
            return switch (e.getTagName()) {
                case "rect" -> new double[]{num(e, "x"), num(e, "y"), num(e, "width"), num(e, "height")};
                case "ellipse" -> new double[]{num(e, "cx") - num(e, "rx"), num(e, "cy") - num(e, "ry"),
                        num(e, "rx") * 2, num(e, "ry") * 2};
                case "polygon" -> polyBox(e.getAttribute("points"));
                // The closed mouth is a quadratic curve: its box spans the corners
                // and the control point (which is where the smile/frown goes).
                case "path" -> pathBox(e.getAttribute("d"));
                default -> null;
            };
        }
        return null;
    }

    private static double num(Element e, String a) {
        String v = e.getAttribute(a);
        return v.isEmpty() ? 0 : Double.parseDouble(v);
    }

    private static double[] pathBox(String d) {
        String[] t = d.replace("M", "").replace("Q", "").trim().split("\\s+");
        double minX = Double.MAX_VALUE, maxX = -Double.MAX_VALUE;
        double minY = Double.MAX_VALUE, maxY = -Double.MAX_VALUE;
        for (int i = 0; i + 1 < t.length; i += 2) {
            double x = Double.parseDouble(t[i]), y = Double.parseDouble(t[i + 1]);
            minX = Math.min(minX, x); maxX = Math.max(maxX, x);
            minY = Math.min(minY, y); maxY = Math.max(maxY, y);
        }
        return new double[]{minX, minY, maxX - minX, Math.max(1.0, maxY - minY)};
    }

    private static double[] polyBox(String points) {
        double minX = Double.MAX_VALUE, maxX = -Double.MAX_VALUE;
        double minY = Double.MAX_VALUE, maxY = -Double.MAX_VALUE;
        for (String pair : points.trim().split("\\s+")) {
            String[] xy = pair.split(",");
            double x = Double.parseDouble(xy[0]), y = Double.parseDouble(xy[1]);
            minX = Math.min(minX, x); maxX = Math.max(maxX, x);
            minY = Math.min(minY, y); maxY = Math.max(maxY, y);
        }
        return new double[]{minX, minY, maxX - minX, maxY - minY};
    }

    private static List<String> partNames(String svg) throws Exception {
        List<String> out = new ArrayList<>();
        NodeList all = parse(svg).getElementsByTagName("*");
        for (int i = 0; i < all.getLength(); i++) {
            String n = ((Element) all.item(i)).getAttribute("data-part");
            if (!n.isEmpty()) out.add(n);
        }
        return out;
    }

    // ── Well-formedness across the whole parameter surface ──────────────────

    @Test
    void everyLegalSpecRendersWellFormedXml() throws Exception {
        List<CharacterSpec> specs = new ArrayList<>();
        for (String v : CharacterSpec.RENDER_STYLES) specs.add(CharacterSpec.builder().renderStyle(v).build());
        for (String v : CharacterSpec.BODY_SHAPES) specs.add(CharacterSpec.builder().bodyShape(v).build());
        for (String v : CharacterSpec.HEAD_SHAPES) specs.add(CharacterSpec.builder().headShape(v).build());
        for (String v : CharacterSpec.HAIR_STYLES) specs.add(CharacterSpec.builder().hairStyle(v).build());
        for (String v : CharacterSpec.OUTFIT_TYPES) specs.add(CharacterSpec.builder().outfitType(v).build());
        for (String v : CharacterSpec.HAT_TYPES) specs.add(CharacterSpec.builder().hatType(v).build());
        for (String v : CharacterSpec.HELD_MAIN) specs.add(CharacterSpec.builder().heldMain(v).build());
        for (String v : CharacterSpec.HELD_OFF) specs.add(CharacterSpec.builder().heldOff(v).build());
        for (String v : CharacterSpec.BACK_ITEMS) specs.add(CharacterSpec.builder().backItem(v).build());
        for (String v : CharacterSpec.FACE_ITEMS) specs.add(CharacterSpec.builder().faceItem(v).build());
        for (CharacterSpec.Bound b : CharacterSpec.PROPORTIONS) {
            specs.add(CharacterSpec.builder().proportion(b.key(), b.min()).build());
            specs.add(CharacterSpec.builder().proportion(b.key(), b.max()).build());
        }

        for (CharacterSpec s : specs) {
            for (String expr : CharacterSpec.EXPRESSIONS) {
                String svg = AvatarSvgRenderer.renderExpression(s, expr, 220, 320);
                Document d = parse(svg);   // throws if malformed
                assertThat(d.getDocumentElement().getTagName()).isEqualTo("svg");
                assertThat(svg).startsWith("<svg xmlns='http://www.w3.org/2000/svg'");
                assertThat(partNames(svg)).as("parts drawn").isNotEmpty();
            }
        }
    }

    @Test
    void outputIsDeterministic() {
        CharacterSpec s = CharacterSpec.builder()
                .bodyShape("round").hatType("straw").heldMain("staff")
                .backItem("quiver").hairStyle("ponytail").build();
        assertThat(AvatarSvgRenderer.render(s, 200, 300))
                .isEqualTo(AvatarSvgRenderer.render(s, 200, 300));
        assertThat(AvatarSvgRenderer.renderPortrait(s, "happy", 128, 128))
                .isEqualTo(AvatarSvgRenderer.renderPortrait(s, "happy", 128, 128));
    }

    @Test
    void noLocaleCanCorruptTheNumbers() throws Exception {
        // A comma-decimal locale would emit x='12,5' and break every SVG parser
        // — the same class of bug that bites baked GDScript floats. Commas ARE
        // legal inside <polygon points='x,y x,y'>, so check every OTHER
        // attribute individually rather than scanning the whole document.
        java.util.Locale original = java.util.Locale.getDefault();
        try {
            java.util.Locale.setDefault(java.util.Locale.FRANCE);
            CharacterSpec s = CharacterSpec.builder().hatType("cone").outfitType("robe").build();
            String svg = AvatarSvgRenderer.render(s, 200, 300);

            NodeList all = parse(svg).getElementsByTagName("*");
            int numeric = 0;
            for (int i = 0; i < all.getLength(); i++) {
                org.w3c.dom.NamedNodeMap attrs = all.item(i).getAttributes();
                for (int a = 0; a < attrs.getLength(); a++) {
                    String name = attrs.item(a).getNodeName();
                    String value = attrs.item(a).getNodeValue();
                    if (name.equals("points") || name.equals("viewBox")) continue;
                    assertThat(value).as("attribute %s", name).doesNotContain(",");
                    if (name.matches("x|y|cx|cy|rx|ry|width|height")) {
                        Double.parseDouble(value);   // throws on a comma decimal
                        numeric++;
                    }
                }
            }
            assertThat(numeric).as("numeric attributes checked").isGreaterThan(20);
        } finally {
            java.util.Locale.setDefault(original);
        }
    }

    // ── The rig really drives the drawing ───────────────────────────────────

    @Test
    void drawnProportionsTrackTheSpecNotAHardCodedTable() throws Exception {
        // Head proportion: the drawn skull must occupy more of a fixed viewport.
        double smallHead = partBox(AvatarSvgRenderer.render(
                CharacterSpec.builder().proportion("head", 0.7).build(), 200, 400), "Skull")[3];
        double bigHead = partBox(AvatarSvgRenderer.render(
                CharacterSpec.builder().proportion("head", 1.5).build(), 200, 400), "Skull")[3];
        assertThat(bigHead).as("chibi head draws larger").isGreaterThan(smallHead * 1.4);

        // Chest proportion: wider torso.
        double narrow = partBox(AvatarSvgRenderer.render(
                CharacterSpec.builder().proportion("chest", 0.7).build(), 200, 400), "Chest")[2];
        double wide = partBox(AvatarSvgRenderer.render(
                CharacterSpec.builder().proportion("chest", 1.4).build(), 200, 400), "Chest")[2];
        assertThat(wide).isGreaterThan(narrow);

        // Leg length: thigh occupies a larger share of the fixed viewport.
        double shortLeg = partBox(AvatarSvgRenderer.render(
                CharacterSpec.builder().proportion("legLength", 0.75).build(), 200, 400), "ThighL")[3];
        double longLeg = partBox(AvatarSvgRenderer.render(
                CharacterSpec.builder().proportion("legLength", 1.3).build(), 200, 400), "ThighL")[3];
        assertThat(longLeg).isGreaterThan(shortLeg);
    }

    @Test
    void theSvgIsDrivenByTheRigNotByItsOwnGeometry() throws Exception {
        // Every drawn part must correspond to a part the rig emitted — a
        // parallel drawing routine would invent or omit shapes.
        CharacterSpec s = CharacterSpec.builder()
                .hatType("top_hat").heldMain("sword").heldOff("shield")
                .backItem("cape").faceItem("glasses").hairStyle("spiky").build();

        List<String> rigNames = new ArrayList<>();
        HumanoidRig.of(s).walk(HumanoidRig.Vec3.ZERO, (w, p) -> rigNames.add(p.name()));

        assertThat(partNames(AvatarSvgRenderer.render(s, 260, 380)))
                .containsExactlyInAnyOrderElementsOf(rigNames);
    }

    @Test
    void everyLimbAndBothHandsAreActuallyDrawn() throws Exception {
        List<String> drawn = partNames(AvatarSvgRenderer.render(CharacterSpec.defaults(), 240, 360));
        assertThat(drawn).contains(
                "Skull", "Chest", "Pelvis",
                "UpperArmL", "UpperArmR", "ForearmL", "ForearmR", "HandL", "HandR",
                "ThighL", "ThighR", "ShinL", "ShinR", "FootL", "FootR",
                "EyeL", "EyeR", "BrowL", "BrowR", "Mouth");
    }

    @Test
    void itemsAddGeometryAndAppearOnTheCorrectSide() throws Exception {
        String bare = AvatarSvgRenderer.render(CharacterSpec.defaults(), 240, 360);
        String armed = AvatarSvgRenderer.render(CharacterSpec.builder()
                .heldMain("sword").heldOff("shield").build(), 240, 360);

        assertThat(partNames(armed).size()).isGreaterThan(partNames(bare).size());
        assertThat(partNames(armed)).contains("SwordBlade", "Shield");
        assertThat(partNames(bare)).doesNotContain("SwordBlade", "Shield");

        // HandR is at −X in the rig, which projects to the viewer's left.
        double[] sword = partBox(armed, "SwordBlade");
        double[] shield = partBox(armed, "Shield");
        assertThat(sword[0]).as("main hand draws opposite the off hand").isLessThan(shield[0]);
    }

    // ── Style, expression, portrait ─────────────────────────────────────────

    @Test
    void lowPolyAndSmoothLookDifferent() {
        CharacterSpec smooth = CharacterSpec.builder().renderStyle("smooth").build();
        CharacterSpec low = CharacterSpec.builder().renderStyle("lowpoly").build();

        String a = AvatarSvgRenderer.render(smooth, 200, 300);
        String b = AvatarSvgRenderer.render(low, 200, 300);

        assertThat(a).isNotEqualTo(b);
        assertThat(a).as("smooth uses gradients").contains("<linearGradient");
        assertThat(b).as("N64 mode is flat-filled").doesNotContain("<linearGradient");
        assertThat(b).as("N64 mode has crisp edges").contains("stroke-width='1'");
    }

    @Test
    void expressionChangesTheFaceAndOnlyTheFace() throws Exception {
        CharacterSpec s = CharacterSpec.defaults();
        String neutral = AvatarSvgRenderer.renderExpression(s, "neutral", 200, 300);
        String happy = AvatarSvgRenderer.renderExpression(s, "happy", 200, 300);
        String angry = AvatarSvgRenderer.renderExpression(s, "angry", 200, 300);

        assertThat(happy).isNotEqualTo(neutral);
        assertThat(angry).isNotEqualTo(happy);

        // The mouth moved; the pelvis did not.
        assertThat(partBox(happy, "Mouth")).isNotEqualTo(partBox(neutral, "Mouth"));
        assertThat(partBox(happy, "Pelvis")).isEqualTo(partBox(neutral, "Pelvis"));
    }

    @Test
    void winkClosesExactlyOneEye() throws Exception {
        String wink = AvatarSvgRenderer.renderExpression(CharacterSpec.defaults(), "wink", 200, 300);
        double left = partBox(wink, "EyeL")[3];
        double right = partBox(wink, "EyeR")[3];
        assertThat(left).as("winking eye is squashed").isLessThan(right * 0.5);
    }

    @Test
    void portraitCropsToTheHead() throws Exception {
        CharacterSpec s = CharacterSpec.defaults();
        double fullSkull = partBox(AvatarSvgRenderer.render(s, 256, 256), "Skull")[3];
        double portraitSkull = partBox(AvatarSvgRenderer.renderPortrait(s, "neutral", 256, 256), "Skull")[3];

        assertThat(portraitSkull).as("head fills far more of a portrait")
                .isGreaterThan(fullSkull * 2.0);
        assertThat(portraitSkull / 256.0).isBetween(0.25, 1.0);
    }

    @Test
    void portraitStaysCorrectAcrossTheHeightRange() throws Exception {
        // Anchored on the Neck joint rather than a fixed height, so a 0.7-height
        // character is not cropped at the chin and a 1.4 one not at the eyes.
        for (double h : new double[]{0.7, 1.0, 1.4}) {
            CharacterSpec s = CharacterSpec.builder().proportion("height", h).build();
            double skull = partBox(AvatarSvgRenderer.renderPortrait(s, "neutral", 200, 200), "Skull")[3];
            assertThat(skull).as("head visible at height %s", h).isGreaterThan(200 * 0.25);
        }
    }

    // ── Colour and safety ───────────────────────────────────────────────────

    @Test
    void everyFillDerivesFromTheSpecPalette() {
        // Nothing invents a colour: each flat fill must be a shade of a colour
        // the spec (or the rig's fixed item palette) asked for.
        CharacterSpec s = CharacterSpec.builder()
                .renderStyle("lowpoly")
                .color("body", "#e21422").color("skin", "#e6b58c").color("hat", "#1a1a1a")
                .hatType("top_hat").build();
        String svg = AvatarSvgRenderer.render(s, 220, 320);

        Matcher m = FILL.matcher(svg);
        int checked = 0;
        while (m.find()) {
            String fill = m.group(1);
            boolean derived = false;
            for (String base : List.of("#e21422", "#e6b58c", "#1a1a1a",
                    s.color("hair"), s.color("accent"), s.color("item"), s.color("eye"))) {
                if (isShadeOf(fill, base)) { derived = true; break; }
            }
            assertThat(derived).as("fill %s derives from the palette", fill).isTrue();
            checked++;
        }
        assertThat(checked).isGreaterThan(10);
    }

    /**
     * True when {@code fill} is {@code base} scaled UNIFORMLY by some factor in
     * [0.5, 1.15]. The renderer's depth shading is continuous, so the factor is
     * recovered from the channels rather than searched on a grid — a grid search
     * misses valid shades and produces a false failure.
     */
    private static boolean isShadeOf(String fill, String base) {
        int[] f = rgb(fill), b = rgb(base);
        int dominant = 0;
        for (int i = 1; i < 3; i++) if (b[i] > b[dominant]) dominant = i;
        if (b[dominant] == 0) return f[0] == 0 && f[1] == 0 && f[2] == 0;

        double factor = f[dominant] / (double) b[dominant];
        if (factor < 0.49 || factor > 1.16) return false;
        for (int i = 0; i < 3; i++) {
            if (Math.abs(Math.min(255, (int) Math.round(b[i] * factor)) - f[i]) > 1) return false;
        }
        return true;
    }

    private static int[] rgb(String hex) {
        return new int[]{
                Integer.parseInt(hex.substring(1, 3), 16),
                Integer.parseInt(hex.substring(3, 5), 16),
                Integer.parseInt(hex.substring(5, 7), 16)};
    }

    @Test
    void viewportBoundsAreEnforcedRatherThanAllocated() {
        CharacterSpec s = CharacterSpec.defaults();
        assertThatThrownBy(() -> AvatarSvgRenderer.render(s, 4, 100))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("width");
        assertThatThrownBy(() -> AvatarSvgRenderer.render(s, 100, 99_999))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("height");
        assertThatThrownBy(() -> AvatarSvgRenderer.render(s, AvatarSvgRenderer.MAX_DIM + 1, 100))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void everythingFitsInsideTheViewport() throws Exception {
        // A part drawn outside the viewBox is invisible — the auto-fit must hold
        // even at the extremes of the bounds, where naive scaling overflows.
        for (CharacterSpec.Bound b : CharacterSpec.PROPORTIONS) {
            for (double v : new double[]{b.min(), b.max()}) {
                CharacterSpec s = CharacterSpec.builder()
                        .proportion(b.key(), v).hatType("cone").heldMain("staff").build();
                String svg = AvatarSvgRenderer.render(s, 200, 300);
                Matcher m = Pattern.compile("(x|y|cx|cy)='(-?[0-9.]+)'").matcher(svg);
                while (m.find()) {
                    double n = Double.parseDouble(m.group(2));
                    assertThat(n).as("%s=%s coordinate %s in view", b.key(), v, m.group())
                            .isBetween(-40.0, 340.0);
                }
            }
        }
    }

    // ── The mouth: placed on the face, shaped by the emotion, opened by speech ──

    private static String mouthElement(String svg) {
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("<(path|ellipse) data-part='Mouth'[^>]*/>").matcher(svg);
        assertThat(m.find()).as("a Mouth element").isTrue();
        return m.group(0);
    }

    private static double attr(String element, String name) {
        java.util.regex.Matcher m = java.util.regex.Pattern.compile(" " + name + "='([-0-9.]+)'").matcher(element);
        assertThat(m.find()).as("attribute %s in %s", name, element).isTrue();
        return Double.parseDouble(m.group(1));
    }

    /** The closed mouth's quadratic path: x0 y0 (corner) cx cy (control) x1 y1 (corner). */
    private static double[] closedMouthPath(String svg) {
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("<path data-part='Mouth' d='M ([-0-9.]+) ([-0-9.]+) Q ([-0-9.]+) ([-0-9.]+) ([-0-9.]+) ([-0-9.]+)'")
                .matcher(svg);
        assertThat(m.find()).as("a closed-mouth path").isTrue();
        return new double[]{Double.parseDouble(m.group(1)), Double.parseDouble(m.group(2)),
                Double.parseDouble(m.group(3)), Double.parseDouble(m.group(4)),
                Double.parseDouble(m.group(5)), Double.parseDouble(m.group(6))};
    }

    @Test
    void theClosedMouthCurvesWithTheEmotion_smileUpFrownDown() {
        CharacterSpec s = CharacterSpec.defaults();
        double[] happy = closedMouthPath(AvatarSvgRenderer.renderPortrait(s, "happy", 256, 256));
        double[] sad = closedMouthPath(AvatarSvgRenderer.renderPortrait(s, "sad", 256, 256));
        double[] neutral = closedMouthPath(AvatarSvgRenderer.renderPortrait(s, "neutral", 256, 256));
        // SVG y grows downward: a smile's control point sits BELOW its corners.
        assertThat(happy[3]).as("happy control below corners").isGreaterThan(happy[1]);
        assertThat(sad[3]).as("sad control above corners").isLessThan(sad[1]);
        assertThat(Math.abs(neutral[3] - neutral[1])).as("neutral is nearly straight")
                .isLessThan(Math.abs(happy[3] - happy[1]) * 0.3);
        assertThat(happy[4]).isGreaterThan(happy[0]);
        assertThat(happy[5]).isEqualTo(happy[1]);
    }

    @Test
    void opennessOpensTheMouthAndKeepsTheEmotion() throws Exception {
        CharacterSpec s = CharacterSpec.defaults();
        String closed = AvatarSvgRenderer.renderPortrait(s, "happy", 256, 256, 0.0);
        String half = AvatarSvgRenderer.renderPortrait(s, "happy", 256, 256, 0.4);
        String open = AvatarSvgRenderer.renderPortrait(s, "happy", 256, 256, 0.9);
        assertThat(mouthElement(closed)).startsWith("<path");
        assertThat(mouthElement(half)).startsWith("<ellipse");
        assertThat(mouthElement(open)).startsWith("<ellipse");
        assertThat(attr(mouthElement(open), "ry")).isGreaterThan(attr(mouthElement(half), "ry"));
        assertThat(open).contains("fill='#c4525e'");
        assertThat(half).doesNotContain("fill='#c4525e'");
        // A smiling open mouth sits higher than a frowning open mouth (corners up).
        double happyCy = attr(mouthElement(open), "cy");
        double sadCy = attr(mouthElement(AvatarSvgRenderer.renderPortrait(s, "sad", 256, 256, 0.9)), "cy");
        assertThat(happyCy).isLessThan(sadCy);
        // Exactly ONE rig part is the mouth: the interior/tongue carry no data-part.
        assertThat(partNames(open).stream().filter("Mouth"::equals).count()).isEqualTo(1);
    }

    @Test
    void opennessIsClampedAndTheOldSignaturesMeanClosed() {
        CharacterSpec s = CharacterSpec.defaults();
        assertThat(AvatarSvgRenderer.renderExpression(s, "neutral", 200, 300))
                .isEqualTo(AvatarSvgRenderer.renderExpression(s, "neutral", 200, 300, 0.0));
        assertThat(AvatarSvgRenderer.renderPortrait(s, "shout", 128, 128))
                .isEqualTo(AvatarSvgRenderer.renderPortrait(s, "shout", 128, 128, -3.0));
        assertThat(AvatarSvgRenderer.renderPortrait(s, "shout", 128, 128, 1.0))
                .isEqualTo(AvatarSvgRenderer.renderPortrait(s, "shout", 128, 128, 42.0));
        assertThat(AvatarSvgRenderer.renderPortrait(s, "neutral", 128, 128, Double.NaN))
                .isEqualTo(AvatarSvgRenderer.renderPortrait(s, "neutral", 128, 128, 0.0));
    }

    @Test
    void theMouthSitsOnTheFaceForEveryHeadShape_belowTheEyesInsideTheSkull() throws Exception {
        for (String head : CharacterSpec.HEAD_SHAPES) {
            for (String hair : List.of("none", "long", "bun")) {
                CharacterSpec s = CharacterSpec.builder().headShape(head).hairStyle(hair).build();
                String svg = AvatarSvgRenderer.renderPortrait(s, "neutral", 256, 256, 0.5);
                double[] skull = partBox(svg, "Skull");
                double[] eyeL = partBox(svg, "EyeL");
                double[] eyeR = partBox(svg, "EyeR");
                double[] mouth = partBox(svg, "Mouth");
                assertThat(mouth).as("mouth drawn for %s/%s", head, hair).isNotNull();
                double mouthCx = mouth[0] + mouth[2] / 2, mouthCy = mouth[1] + mouth[3] / 2;
                double eyeCy = (eyeL[1] + eyeL[3] / 2 + eyeR[1] + eyeR[3] / 2) / 2;
                assertThat(mouthCy).as("%s/%s: mouth below the eyes", head, hair).isGreaterThan(eyeCy);
                assertThat(mouthCy).as("%s/%s: mouth above the chin", head, hair)
                        .isLessThan(skull[1] + skull[3]);
                assertThat(mouthCx).as("%s/%s: mouth centred", head, hair)
                        .isCloseTo(skull[0] + skull[2] / 2, org.assertj.core.data.Offset.offset(1.0));
                assertThat(mouth[0]).as("%s/%s: within the skull", head, hair).isGreaterThan(skull[0]);
                assertThat(mouth[0] + mouth[2]).isLessThan(skull[0] + skull[2]);
            }
        }
    }
}
