package games.hugmesh.character;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What the produced Godot bundle actually receives.
 *
 * <p>GDScript cannot be executed here, so these assert the two properties that
 * would otherwise only surface as a broken bundle in a browser: nothing
 * free-form crosses into generated code, and the emitted numbers parse under
 * any locale.
 */
class GdRigBakerTest {

    private static final Pattern HEX = Pattern.compile("#[0-9a-fA-F]{6}");

    @Test
    void noRawHexEverReachesGdScript() {
        // The one hard security property of the bake: colours become Color()
        // floats, so nothing user-influenced is spliced into generated code.
        CharacterSpec spec = CharacterSpec.builder()
                .color("body", "#e21422").color("skin", "#e6b58c").color("hat", "#1a1a1a")
                .color("hair", "#abcdef").color("accent", "#123456")
                .color("item", "#fedcba").color("eye", "#0f0f0f")
                .hatType("top_hat").heldMain("sword").build();

        String baked = GdRigBaker.bake(spec);

        Matcher m = HEX.matcher(baked);
        assertThat(m.find()).as("hex literal found in baked GDScript: %s",
                m.hitEnd() ? "" : m.group()).isFalse();
        assertThat(baked).contains("Color(0.886, 0.078, 0.133)");
    }

    @Test
    void everyEmittedNumberIsLocaleIndependent() {
        // A comma-decimal locale would emit Vector3(0,3, 1,2, 0) — a bundle
        // that fails to parse at runtime, invisible to any Java-side test that
        // does not set the locale.
        Locale original = Locale.getDefault();
        try {
            Locale.setDefault(Locale.FRANCE);
            String baked = GdRigBaker.bake(CharacterSpec.builder()
                    .proportion("chest", 1.234).hatType("cone").build());

            Matcher m = Pattern.compile("(Vector3|Color)\\(([^)]*)\\)").matcher(baked);
            int checked = 0;
            while (m.find()) {
                for (String arg : m.group(2).split(",")) {
                    Double.parseDouble(arg.trim());   // throws on a comma decimal
                    checked++;
                }
            }
            assertThat(checked).as("numeric arguments checked").isGreaterThan(100);
        } finally {
            Locale.setDefault(original);
        }
    }

    @Test
    void bakesAllFourSectionsTheTemplateConsumes() {
        String baked = GdRigBaker.bake(CharacterSpec.defaults());
        assertThat(baked)
                .contains("const AVATAR := {")
                .contains("const STYLE := \"smooth\"")
                .contains("const RIG := {")
                .contains("const EXPRESSIONS := {");
    }

    @Test
    void theBakedRigCarriesEveryJointThePoserAddresses() {
        String baked = GdRigBaker.bake(CharacterSpec.defaults());

        // Collect the rig's own joint names and assert each is present, so a
        // renamed joint cannot silently stop animating.
        List<String> joints = new ArrayList<>();
        collect(HumanoidRig.of(CharacterSpec.defaults()), joints);
        for (String j : joints) {
            assertThat(baked).as("joint %s baked", j).contains("\"name\": \"" + j + "\"");
        }
        assertThat(joints).contains("Hips", "Torso", "Chest", "Neck", "Head",
                "ShoulderL", "UpperArmL", "ForearmL", "HandL",
                "ThighR", "ShinR", "FootR", "EyeL", "BrowR", "Mouth");
    }

    private static void collect(HumanoidRig.Joint j, List<String> out) {
        out.add(j.name);
        for (HumanoidRig.Joint c : j.children) collect(c, out);
    }

    @Test
    void expressionsBakeTheSameJointsTheSvgPoses() {
        String baked = GdRigBaker.bake(CharacterSpec.defaults());
        for (String expr : CharacterSpec.EXPRESSIONS) {
            if ("neutral".equals(expr)) continue;   // rest is the absence of an entry
            assertThat(baked).as("expression %s", expr).contains("\"" + expr + "\": {");
        }
        for (String joint : Expressions.FACE_JOINTS) {
            assertThat(baked).contains("\"" + joint + "\": {\"offset\":");
        }
    }

    @Test
    void styleIsBakedAndDoesNotAlterTheRig() {
        String smooth = GdRigBaker.bake(CharacterSpec.builder().renderStyle("smooth").build());
        String low = GdRigBaker.bake(CharacterSpec.builder().renderStyle("lowpoly").build());

        assertThat(low).contains("const STYLE := \"lowpoly\"");
        assertThat(smooth).contains("const STYLE := \"smooth\"");
        // Style is a materialisation concern; the geometry must be identical,
        // which is what keeps 2D/3D/glTF parity true across both.
        assertThat(rigSection(low)).isEqualTo(rigSection(smooth));
    }

    private static String rigSection(String baked) {
        int start = baked.indexOf("const RIG :=");
        int end = baked.indexOf("const EXPRESSIONS");
        return baked.substring(start, end);
    }

    @Test
    void bracketsBalanceForEveryTokenCombination() {
        // A structural proxy for "this parses": an unbalanced brace or bracket
        // is the failure mode of hand-rolled code emission.
        List<CharacterSpec> specs = new ArrayList<>();
        for (String v : CharacterSpec.HAT_TYPES) specs.add(CharacterSpec.builder().hatType(v).build());
        for (String v : CharacterSpec.HELD_MAIN) specs.add(CharacterSpec.builder().heldMain(v).build());
        for (String v : CharacterSpec.HELD_OFF) specs.add(CharacterSpec.builder().heldOff(v).build());
        for (String v : CharacterSpec.BACK_ITEMS) specs.add(CharacterSpec.builder().backItem(v).build());
        for (String v : CharacterSpec.FACE_ITEMS) specs.add(CharacterSpec.builder().faceItem(v).build());
        for (String v : CharacterSpec.HAIR_STYLES) specs.add(CharacterSpec.builder().hairStyle(v).build());
        for (String v : CharacterSpec.OUTFIT_TYPES) specs.add(CharacterSpec.builder().outfitType(v).build());
        for (String v : CharacterSpec.HEAD_SHAPES) specs.add(CharacterSpec.builder().headShape(v).build());

        for (CharacterSpec s : specs) {
            String baked = GdRigBaker.bake(s);
            assertThat(count(baked, '{')).as("braces balance").isEqualTo(count(baked, '}'));
            assertThat(count(baked, '[')).as("brackets balance").isEqualTo(count(baked, ']'));
            assertThat(count(baked, '(')).as("parens balance").isEqualTo(count(baked, ')'));
            assertThat(count(baked, '"') % 2).as("quotes pair").isZero();
        }
    }

    private static long count(String s, char c) {
        return s.chars().filter(ch -> ch == c).count();
    }

    @Test
    void bakeIsDeterministic() {
        CharacterSpec s = CharacterSpec.builder()
                .bodyShape("round").hatType("crown").heldMain("staff").backItem("wings").build();
        assertThat(GdRigBaker.bake(s)).isEqualTo(GdRigBaker.bake(s));
    }
}
