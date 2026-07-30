package games.hugmesh.character;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * The validation contract of the canonical character model.
 *
 * <p>The load-bearing rule these pin: <b>null means default, a PRESENT bad value
 * is a loud error, and nothing is ever silently clamped.</b> A clamp would turn
 * an accidentally-omitted primitive {@code 0.0} into a plausible-looking 0.7
 * instead of a 400 — the same reasoning that made the original
 * {@code heightScale} reject-not-clamp, generalised to every proportion.
 */
class CharacterSpecTest {

    @Test
    void defaultsAreCompleteAndInsideEveryBound() {
        CharacterSpec s = CharacterSpec.defaults();

        assertThat(s.proportions()).hasSameSizeAs(CharacterSpec.PROPORTIONS);
        assertThat(s.colors()).hasSameSizeAs(CharacterSpec.COLORS);
        for (CharacterSpec.Bound b : CharacterSpec.PROPORTIONS) {
            assertThat(b.contains(s.p(b.key())))
                    .as("default of %s (%s) inside [%s, %s]", b.key(), s.p(b.key()), b.min(), b.max())
                    .isTrue();
        }
        for (CharacterSpec.ColorSlot c : CharacterSpec.COLORS) {
            assertThat(s.color(c.key())).matches("^#[0-9a-fA-F]{6}$");
        }
    }

    @Test
    void nullMeansDefaultForEveryField() {
        CharacterSpec s = CharacterSpec.builder()
                .renderStyle(null).bodyShape(null).headShape(null).hairStyle(null)
                .outfitType(null).hatType(null).heldMain(null).heldOff(null)
                .backItem(null).faceItem(null).expression(null)
                .proportion("height", null).color("body", null)
                .build();

        assertThat(s.renderStyle()).isEqualTo(CharacterSpec.DEFAULT_RENDER_STYLE);
        assertThat(s.bodyShape()).isEqualTo(CharacterSpec.DEFAULT_BODY_SHAPE);
        assertThat(s.hatType()).isEqualTo("none");
        assertThat(s.expression()).isEqualTo("neutral");
        assertThat(s.p("height")).isEqualTo(1.0);
        assertThat(s.color("body")).isEqualTo("#3f59b3");
    }

    @Test
    void aPresentOutOfRangeProportionIsRejectedNotClamped() {
        for (CharacterSpec.Bound b : CharacterSpec.PROPORTIONS) {
            assertThatThrownBy(() -> CharacterSpec.builder().proportion(b.key(), b.max() + 0.01).build())
                    .as("above max for %s", b.key())
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining(b.key());
            assertThatThrownBy(() -> CharacterSpec.builder().proportion(b.key(), b.min() - 0.01).build())
                    .as("below min for %s", b.key())
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining(b.key());
            // …and the boundaries themselves are legal.
            assertThatCode(() -> CharacterSpec.builder().proportion(b.key(), b.min()).build())
                    .doesNotThrowAnyException();
            assertThatCode(() -> CharacterSpec.builder().proportion(b.key(), b.max()).build())
                    .doesNotThrowAnyException();
        }
    }

    @Test
    void nonFiniteProportionsAreRejected() {
        assertThatThrownBy(() -> CharacterSpec.builder().proportion("height", Double.NaN).build())
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> CharacterSpec.builder().proportion("height", Double.POSITIVE_INFINITY).build())
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void unknownTokensAreRejectedWithTheAllowedSetInTheMessage() {
        assertThatThrownBy(() -> CharacterSpec.builder().renderStyle("ps1").build())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("renderStyle")
                .hasMessageContaining("lowpoly");
        assertThatThrownBy(() -> CharacterSpec.builder().heldMain("railgun").build())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("heldMain");
        assertThatThrownBy(() -> CharacterSpec.builder().expression("smug").build())
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void colourMustBeSixHexDigits() {
        assertThatThrownBy(() -> CharacterSpec.builder().color("body", "red").build())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("bodyColor");
        assertThatThrownBy(() -> CharacterSpec.builder().color("body", "#fff").build())
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> CharacterSpec.builder().color("nope", "#ffffff").build())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("colour slot");
        assertThat(CharacterSpec.builder().color("body", "#AbCdEf").build().color("body"))
                .isEqualTo("#AbCdEf");
    }

    @Test
    void everyLegalTokenBuilds() {
        for (String v : CharacterSpec.RENDER_STYLES) assertThat(CharacterSpec.builder().renderStyle(v).build().renderStyle()).isEqualTo(v);
        for (String v : CharacterSpec.BODY_SHAPES) assertThat(CharacterSpec.builder().bodyShape(v).build().bodyShape()).isEqualTo(v);
        for (String v : CharacterSpec.HEAD_SHAPES) assertThat(CharacterSpec.builder().headShape(v).build().headShape()).isEqualTo(v);
        for (String v : CharacterSpec.HAIR_STYLES) assertThat(CharacterSpec.builder().hairStyle(v).build().hairStyle()).isEqualTo(v);
        for (String v : CharacterSpec.OUTFIT_TYPES) assertThat(CharacterSpec.builder().outfitType(v).build().outfitType()).isEqualTo(v);
        for (String v : CharacterSpec.HAT_TYPES) assertThat(CharacterSpec.builder().hatType(v).build().hatType()).isEqualTo(v);
        for (String v : CharacterSpec.HELD_MAIN) assertThat(CharacterSpec.builder().heldMain(v).build().heldMain()).isEqualTo(v);
        for (String v : CharacterSpec.HELD_OFF) assertThat(CharacterSpec.builder().heldOff(v).build().heldOff()).isEqualTo(v);
        for (String v : CharacterSpec.BACK_ITEMS) assertThat(CharacterSpec.builder().backItem(v).build().backItem()).isEqualTo(v);
        for (String v : CharacterSpec.FACE_ITEMS) assertThat(CharacterSpec.builder().faceItem(v).build().faceItem()).isEqualTo(v);
        for (String v : CharacterSpec.EXPRESSIONS) assertThat(CharacterSpec.builder().expression(v).build().expression()).isEqualTo(v);
    }

    @Test
    void specIsImmutableAndToBuilderCopies() {
        CharacterSpec a = CharacterSpec.builder().bodyShape("stocky").proportion("chest", 1.3).build();
        CharacterSpec b = a.toBuilder().bodyShape("round").build();

        assertThat(a.bodyShape()).isEqualTo("stocky");
        assertThat(b.bodyShape()).isEqualTo("round");
        assertThat(b.p("chest")).isEqualTo(1.3);
        assertThatThrownBy(() -> a.proportions().put("chest", 9.0))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void withExpressionChangesOnlyTheFace() {
        CharacterSpec a = CharacterSpec.builder().bodyShape("round").hatType("crown").build();
        CharacterSpec happy = a.withExpression("happy");

        assertThat(happy.expression()).isEqualTo("happy");
        assertThat(happy.bodyShape()).isEqualTo("round");
        assertThat(happy.hatType()).isEqualTo("crown");
        assertThat(a.expression()).isEqualTo("neutral");
    }

    // ── Portable document ───────────────────────────────────────────────────

    @Test
    void documentRoundTripsEveryField() {
        UUID tex = UUID.randomUUID();
        CharacterSpec original = CharacterSpec.builder()
                .renderStyle("lowpoly").bodyShape("stocky").headShape("tapered")
                .hairStyle("ponytail").outfitType("armor").hatType("helm")
                .heldMain("axe").heldOff("shield").backItem("cape").faceItem("visor")
                .expression("determined")
                .proportion("height", 1.25).proportion("chest", 1.35)
                .proportion("legLength", 0.80).proportion("hand", 1.4)
                .color("body", "#112233").color("skin", "#445566").color("eye", "#778899")
                .skinTextureAssetId(tex)
                .build();

        Map<String, Object> doc = CharacterDocument.toMap(original, "Rook");
        CharacterSpec back = CharacterDocument.fromMap(doc);

        assertThat(CharacterDocument.nameOf(doc)).isEqualTo("Rook");
        assertThat(back.renderStyle()).isEqualTo("lowpoly");
        assertThat(back.bodyShape()).isEqualTo("stocky");
        assertThat(back.headShape()).isEqualTo("tapered");
        assertThat(back.hairStyle()).isEqualTo("ponytail");
        assertThat(back.outfitType()).isEqualTo("armor");
        assertThat(back.hatType()).isEqualTo("helm");
        assertThat(back.heldMain()).isEqualTo("axe");
        assertThat(back.heldOff()).isEqualTo("shield");
        assertThat(back.backItem()).isEqualTo("cape");
        assertThat(back.faceItem()).isEqualTo("visor");
        assertThat(back.expression()).isEqualTo("determined");
        assertThat(back.p("height")).isEqualTo(1.25);
        assertThat(back.p("chest")).isEqualTo(1.35);
        assertThat(back.p("legLength")).isEqualTo(0.80);
        assertThat(back.p("hand")).isEqualTo(1.4);
        assertThat(back.color("body")).isEqualTo("#112233");
        assertThat(back.skinTextureAssetId()).isEqualTo(tex);
        assertThat(back.proportions()).isEqualTo(original.proportions());
        assertThat(back.colors()).isEqualTo(original.colors());
    }

    @Test
    void documentToleratesMissingSectionsAndUnknownKeys() {
        // A v1 reader must not fall over on a sparse or extended document.
        Map<String, Object> sparse = new LinkedHashMap<>();
        sparse.put("kind", CharacterDocument.KIND);
        sparse.put("schemaVersion", 1);
        sparse.put("somethingFromTheFuture", Map.of("a", 1));

        CharacterSpec s = CharacterDocument.fromMap(sparse);
        assertThat(s.bodyShape()).isEqualTo(CharacterSpec.DEFAULT_BODY_SHAPE);
        assertThat(s.p("height")).isEqualTo(1.0);

        assertThat(CharacterDocument.fromMap(null).renderStyle())
                .isEqualTo(CharacterSpec.DEFAULT_RENDER_STYLE);
    }

    @Test
    void documentRejectsWrongKindNewerSchemaAndBadValues() {
        assertThatThrownBy(() -> CharacterDocument.fromMap(Map.of("kind", "hugmesh.tileset")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("hugmesh.character");

        assertThatThrownBy(() -> CharacterDocument.fromMap(
                Map.of("kind", CharacterDocument.KIND, "schemaVersion", 99)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("newer");

        // An import is not a way around validation.
        assertThatThrownBy(() -> CharacterDocument.fromMap(
                Map.of("proportions", Map.of("height", 99.0))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("height");
        assertThatThrownBy(() -> CharacterDocument.fromMap(
                Map.of("shape", Map.of("body", "gelatinous"))))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> CharacterDocument.fromMap(
                Map.of("texture", Map.of("assetId", "not-a-uuid"))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("UUID");
    }

    @Test
    void documentAcceptsNumbersArrivingAsStrings() {
        // JSON from another generator may quote its numbers.
        CharacterSpec s = CharacterDocument.fromMap(Map.of("proportions", Map.of("height", "1.2")));
        assertThat(s.p("height")).isEqualTo(1.2);
    }

    @Test
    void char3dConfigMappingSpeaksTheVendoredAssemblerShape() {
        CharacterSpec s = CharacterSpec.builder()
                .renderStyle("lowpoly").bodyShape("round").headShape("square")
                .hairStyle("bun").backItem("wings").hatType("crown")
                .proportion("height", 1.3).proportion("chest", 1.4)
                .color("skin", "#ddccbb").color("eye", "#102030")
                .build();

        Map<String, Object> c3d = CharacterDocument.toChar3DConfig(s, "Wren");

        assertThat(c3d).containsEntry("schemaVersion", 1).containsEntry("name", "Wren");
        assertThat(c3d).containsEntry("style", "toon");         // lowpoly → toon
        @SuppressWarnings("unchecked") Map<String, Object> body = (Map<String, Object>) c3d.get("body");
        assertThat(body).containsEntry("archetype", "humanoid").containsEntry("height", 1.3);
        assertThat((Double) body.get("build")).isBetween(0.0, 1.0);
        @SuppressWarnings("unchecked") Map<String, Object> head = (Map<String, Object>) c3d.get("head");
        assertThat(head).containsEntry("family", "humanoid").containsEntry("variant", "square");
        @SuppressWarnings("unchecked") Map<String, Object> parts = (Map<String, Object>) c3d.get("parts");
        assertThat(parts).containsEntry("wings", "feathered").containsEntry("hair", "bun");
        @SuppressWarnings("unchecked") Map<String, Object> mats = (Map<String, Object>) c3d.get("materials");
        assertThat(mats).containsKeys("fur", "accent", "detail", "line", "iris", "skin", "hair");
        assertThat(mats).containsEntry("skin", "#ddccbb").containsEntry("iris", "#102030");
    }

    @Test
    void char3dHeightIsClampedIntoTheirNarrowerRange() {
        // Their bound is 0.5..1.5 and ours 0.7..1.4, so pass-through is safe —
        // this pins that it stays safe if either bound ever moves.
        for (double h : new double[]{0.70, 1.00, 1.40}) {
            @SuppressWarnings("unchecked")
            Map<String, Object> body = (Map<String, Object>) CharacterDocument.toChar3DConfig(
                    CharacterSpec.builder().proportion("height", h).build(), "x").get("body");
            assertThat((Double) body.get("height")).isBetween(0.5, 1.5);
        }
    }

    // ── Expressions ─────────────────────────────────────────────────────────

    @Test
    void everyExpressionResolvesToTheFiveFaceJoints() {
        CharacterSpec s = CharacterSpec.defaults();
        for (String e : CharacterSpec.EXPRESSIONS) {
            Map<String, Expressions.Pose> pose = Expressions.resolve(s, e);
            if ("neutral".equals(e)) {
                assertThat(pose).as("neutral is rest").isEmpty();
                continue;
            }
            assertThat(pose.keySet()).as("joints posed by %s", e)
                    .containsExactlyInAnyOrderElementsOf(Expressions.FACE_JOINTS);
        }
    }

    @Test
    void expressionOffsetsScaleWithHeadSize() {
        // Authored in skull-height fractions so a chibi head and a small head
        // get proportionally equivalent — not identical — face movement.
        CharacterSpec small = CharacterSpec.builder().proportion("head", 0.7).build();
        CharacterSpec big = CharacterSpec.builder().proportion("head", 1.5).build();

        double dSmall = Expressions.resolve(small, "surprised").get("BrowL").offset().y();
        double dBig = Expressions.resolve(big, "surprised").get("BrowL").offset().y();

        assertThat(dBig).isGreaterThan(dSmall);
        assertThat(dBig / dSmall).isCloseTo(1.5 / 0.7, org.assertj.core.data.Offset.offset(1e-9));
    }

    @Test
    void winkIsAsymmetricAndNeutralIsRest() {
        CharacterSpec s = CharacterSpec.defaults();
        Map<String, Expressions.Pose> wink = Expressions.resolve(s, "wink");

        assertThat(wink.get("EyeL").scale().y()).isLessThan(0.3);   // shut
        assertThat(wink.get("EyeR").scale().y()).isEqualTo(1.0);    // open
        assertThat(Expressions.forJoint(Map.of(), "Mouth")).isEqualTo(Expressions.Pose.REST);
    }

    @Test
    void resolveAllOmitsNeutralAndCoversTheRest() {
        Map<String, Map<String, Expressions.Pose>> all = Expressions.resolveAll(CharacterSpec.defaults());
        assertThat(all).doesNotContainKey("neutral");
        assertThat(all.keySet()).hasSize(CharacterSpec.EXPRESSIONS.size() - 1);
    }

    @Test
    void unknownExpressionResolvesToRestRatherThanThrowing() {
        // The token whitelist is the gate; the table is defensive.
        assertThat(Expressions.resolve(CharacterSpec.defaults(), "nonsense")).isEmpty();
        assertThat(Expressions.resolve(CharacterSpec.defaults(), null)).isEmpty();
    }
}
