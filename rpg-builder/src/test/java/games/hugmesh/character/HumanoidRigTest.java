package games.hugmesh.character;

import games.hugmesh.character.HumanoidRig.Joint;
import games.hugmesh.character.HumanoidRig.Part;
import games.hugmesh.character.HumanoidRig.Vec3;
import org.assertj.core.data.Offset;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Geometric invariants of the rig.
 *
 * <p>These are the properties every renderer silently relies on. The
 * highest-value one is {@link #solesLandOnTheGroundPlaneForEveryLegProportion()}
 * — the leg is stacked upward FROM the ground precisely so that changing
 * {@code legLength} or {@code foot} can never leave a character floating or
 * sunk, which is the failure mode a naive top-down layout produces and which no
 * screenshot review reliably catches.
 */
class HumanoidRigTest {

    /** Flattens the rig to world-space parts, the way a renderer consumes it. */
    private static Map<String, List<Placed>> flatten(Joint root) {
        Map<String, List<Placed>> out = new LinkedHashMap<>();
        root.walk(Vec3.ZERO, (world, part) ->
                out.computeIfAbsent(part.name(), k -> new ArrayList<>()).add(new Placed(world, part)));
        return out;
    }

    private record Placed(Vec3 world, Part part) {
        double bottom() { return world.y() - part.size().y() / 2; }
        double top() { return world.y() + part.size().y() / 2; }
    }

    private static Placed one(Joint root, String name) {
        List<Placed> hits = flatten(root).get(name);
        assertThat(hits).as("part %s exists exactly once", name).isNotNull().hasSize(1);
        return hits.get(0);
    }

    // ── Structure ───────────────────────────────────────────────────────────

    @Test
    void theSkeletonHasEveryJointTheAnimatorAndExpressionTableAddress() {
        Joint root = HumanoidRig.of(CharacterSpec.defaults());

        List<String> required = List.of(
                "Root", "Hips", "Torso", "Chest", "Neck", "Head",
                "ShoulderL", "UpperArmL", "ForearmL", "HandL",
                "ShoulderR", "UpperArmR", "ForearmR", "HandR",
                "ThighL", "ShinL", "FootL", "ThighR", "ShinR", "FootR",
                "EyeL", "EyeR", "BrowL", "BrowR", "Mouth");

        for (String j : required) {
            assertThat(root.find(j)).as("joint %s", j).isNotNull();
        }
        // Every face joint the expression table poses must actually exist.
        for (String j : Expressions.FACE_JOINTS) {
            assertThat(root.find(j)).as("expression target %s", j).isNotNull();
        }
    }

    @Test
    void theRigIsLaterallySymmetric() {
        Joint root = HumanoidRig.of(CharacterSpec.builder().hatType("crown").build());
        Map<String, List<Placed>> parts = flatten(root);

        for (String base : List.of("UpperArm", "Forearm", "Hand", "Thigh", "Shin", "Foot", "Eye", "Brow")) {
            Placed l = parts.get(base + "L").get(0);
            Placed r = parts.get(base + "R").get(0);
            assertThat(l.world().x()).as("%s mirrored in x", base)
                    .isCloseTo(-r.world().x(), Offset.offset(1e-9));
            assertThat(l.world().y()).as("%s level in y", base)
                    .isCloseTo(r.world().y(), Offset.offset(1e-9));
            assertThat(l.part().size()).as("%s same size", base).isEqualTo(r.part().size());
        }
    }

    @Test
    void thePartsStackHeadAboveTorsoAboveHipsAboveFeet() {
        Joint root = HumanoidRig.of(CharacterSpec.defaults());
        Map<String, List<Placed>> p = flatten(root);

        double skull = p.get("Skull").get(0).world().y();
        double chest = p.get("Chest").get(0).world().y();
        double pelvis = p.get("Pelvis").get(0).world().y();
        double foot = p.get("FootL").get(0).world().y();

        assertThat(skull).isGreaterThan(chest);
        assertThat(chest).isGreaterThan(pelvis);
        assertThat(pelvis).isGreaterThan(foot);
        // A recognisable humanoid: head roughly a seventh of total height.
        double total = p.get("Skull").get(0).top() - HumanoidRig.GROUND;
        double headH = p.get("Skull").get(0).part().size().y();
        assertThat(headH / total).isBetween(0.10, 0.20);
    }

    @Test
    void handsHangBesideTheHipsNotPastTheKnees() {
        Joint root = HumanoidRig.of(CharacterSpec.defaults());
        Map<String, List<Placed>> p = flatten(root);

        double hand = p.get("HandL").get(0).bottom();
        double hip = p.get("Pelvis").get(0).world().y();
        double knee = p.get("ShinL").get(0).top();

        assertThat(hand).as("fingertips below the hips").isLessThan(hip);
        assertThat(hand).as("fingertips above the knees").isGreaterThan(knee);
    }

    // ── The invariant that matters most ─────────────────────────────────────

    @Test
    void solesLandOnTheGroundPlaneForEveryLegProportion() {
        CharacterSpec.Bound legLen = CharacterSpec.bound("legLength");
        CharacterSpec.Bound foot = CharacterSpec.bound("foot");
        CharacterSpec.Bound height = CharacterSpec.bound("height");

        for (double l : new double[]{legLen.min(), 1.0, legLen.max()}) {
            for (double f : new double[]{foot.min(), 1.0, foot.max()}) {
                for (double h : new double[]{height.min(), 1.0, height.max()}) {
                    CharacterSpec s = CharacterSpec.builder()
                            .proportion("legLength", l).proportion("foot", f)
                            .proportion("height", h).build();
                    Joint root = HumanoidRig.of(s);
                    for (String side : List.of("FootL", "FootR")) {
                        assertThat(one(root, side).bottom())
                                .as("sole of %s at legLength=%s foot=%s height=%s", side, l, f, h)
                                .isCloseTo(HumanoidRig.GROUND, Offset.offset(1e-9));
                    }
                }
            }
        }
    }

    @Test
    void everyLegalSpecProducesFiniteNonDegenerateGeometry() {
        // Sweep the extremes of all twelve bounds together with every token, and
        // assert nothing collapses to zero size or NaN.
        List<CharacterSpec> specs = new ArrayList<>();
        for (CharacterSpec.Bound b : CharacterSpec.PROPORTIONS) {
            specs.add(CharacterSpec.builder().proportion(b.key(), b.min()).build());
            specs.add(CharacterSpec.builder().proportion(b.key(), b.max()).build());
        }
        for (String hat : CharacterSpec.HAT_TYPES) specs.add(CharacterSpec.builder().hatType(hat).build());
        for (String v : CharacterSpec.HELD_MAIN) specs.add(CharacterSpec.builder().heldMain(v).build());
        for (String v : CharacterSpec.HELD_OFF) specs.add(CharacterSpec.builder().heldOff(v).build());
        for (String v : CharacterSpec.BACK_ITEMS) specs.add(CharacterSpec.builder().backItem(v).build());
        for (String v : CharacterSpec.FACE_ITEMS) specs.add(CharacterSpec.builder().faceItem(v).build());
        for (String v : CharacterSpec.HAIR_STYLES) specs.add(CharacterSpec.builder().hairStyle(v).build());
        for (String v : CharacterSpec.OUTFIT_TYPES) specs.add(CharacterSpec.builder().outfitType(v).build());
        for (String v : CharacterSpec.HEAD_SHAPES) specs.add(CharacterSpec.builder().headShape(v).build());
        for (String v : CharacterSpec.BODY_SHAPES) specs.add(CharacterSpec.builder().bodyShape(v).build());
        for (String v : CharacterSpec.RENDER_STYLES) specs.add(CharacterSpec.builder().renderStyle(v).build());

        for (CharacterSpec s : specs) {
            Joint root = HumanoidRig.of(s);
            int[] count = {0};
            root.walk(Vec3.ZERO, (world, part) -> {
                count[0]++;
                assertThat(Double.isFinite(world.x()) && Double.isFinite(world.y()) && Double.isFinite(world.z()))
                        .as("finite position for %s", part.name()).isTrue();
                assertThat(part.size().x()).as("width of %s", part.name()).isGreaterThan(0);
                assertThat(part.size().y()).as("height of %s", part.name()).isGreaterThan(0);
                assertThat(part.size().z()).as("depth of %s", part.name()).isGreaterThan(0);
                assertThat(part.color()).as("colour of %s", part.name()).matches("^#[0-9a-fA-F]{6}$");
                assertThat(part.tex()).isIn("skin", "outfit", "none");
            });
            assertThat(count[0]).as("parts emitted").isGreaterThan(15);
        }
    }

    // ── Proportions actually do something ───────────────────────────────────

    @Test
    void eachProportionMovesTheGeometryItNames() {
        double baseHead = one(HumanoidRig.of(CharacterSpec.defaults()), "Skull").part().size().y();
        double bigHead = one(HumanoidRig.of(CharacterSpec.builder().proportion("head", 1.5).build()),
                "Skull").part().size().y();
        assertThat(bigHead).isGreaterThan(baseHead);

        double baseThigh = one(HumanoidRig.of(CharacterSpec.defaults()), "ThighL").part().size().y();
        double longThigh = one(HumanoidRig.of(CharacterSpec.builder().proportion("legLength", 1.3).build()),
                "ThighL").part().size().y();
        assertThat(longThigh).isGreaterThan(baseThigh);

        double baseChest = one(HumanoidRig.of(CharacterSpec.defaults()), "Chest").part().size().x();
        double wideChest = one(HumanoidRig.of(CharacterSpec.builder().proportion("chest", 1.4).build()),
                "Chest").part().size().x();
        assertThat(wideChest).isGreaterThan(baseChest);

        double baseArm = one(HumanoidRig.of(CharacterSpec.defaults()), "UpperArmL").part().size().y();
        double longArm = one(HumanoidRig.of(CharacterSpec.builder().proportion("armLength", 1.3).build()),
                "UpperArmL").part().size().y();
        assertThat(longArm).isGreaterThan(baseArm);

        double baseArmT = one(HumanoidRig.of(CharacterSpec.defaults()), "UpperArmL").part().size().x();
        double thickArm = one(HumanoidRig.of(CharacterSpec.builder().proportion("armThickness", 1.5).build()),
                "UpperArmL").part().size().x();
        assertThat(thickArm).isGreaterThan(baseArmT);

        double baseShoulder = Math.abs(one(HumanoidRig.of(CharacterSpec.defaults()), "ShoulderBallL").world().x());
        double wideShoulder = Math.abs(one(HumanoidRig.of(
                CharacterSpec.builder().proportion("shoulder", 1.4).build()), "ShoulderBallL").world().x());
        assertThat(wideShoulder).isGreaterThan(baseShoulder);

        double baseFoot = one(HumanoidRig.of(CharacterSpec.defaults()), "FootL").part().size().z();
        double bigFoot = one(HumanoidRig.of(CharacterSpec.builder().proportion("foot", 1.6).build()),
                "FootL").part().size().z();
        assertThat(bigFoot).isGreaterThan(baseFoot);

        double baseHand = one(HumanoidRig.of(CharacterSpec.defaults()), "HandL").part().size().x();
        double bigHand = one(HumanoidRig.of(CharacterSpec.builder().proportion("hand", 1.6).build()),
                "HandL").part().size().x();
        assertThat(bigHand).isGreaterThan(baseHand);
    }

    @Test
    void tallerCharactersAreActuallyTaller() {
        double shortTop = one(HumanoidRig.of(CharacterSpec.builder().proportion("height", 0.7).build()),
                "Skull").top();
        double tallTop = one(HumanoidRig.of(CharacterSpec.builder().proportion("height", 1.4).build()),
                "Skull").top();
        assertThat(tallTop).isGreaterThan(shortTop * 1.2);
    }

    @Test
    void bodyShapePresetDrivesGirthWhileSlidersRideOnTop() {
        double slim = one(HumanoidRig.of(CharacterSpec.builder().bodyShape("slim").build()), "Chest")
                .part().size().x();
        double stocky = one(HumanoidRig.of(CharacterSpec.builder().bodyShape("stocky").build()), "Chest")
                .part().size().x();
        double round = one(HumanoidRig.of(CharacterSpec.builder().bodyShape("round").build()), "Chest")
                .part().size().x();
        assertThat(slim).isLessThan(stocky);
        assertThat(stocky).isLessThan(round);

        double slimWide = one(HumanoidRig.of(CharacterSpec.builder()
                .bodyShape("slim").proportion("chest", 1.4).build()), "Chest").part().size().x();
        assertThat(slimWide).isGreaterThan(slim);
    }

    // ── Items and outfits ───────────────────────────────────────────────────

    @Test
    void itemSlotsAppearOnlyWhenSelectedAndHangFromTheRightJoint() {
        assertThat(flatten(HumanoidRig.of(CharacterSpec.defaults()))).doesNotContainKeys(
                "SwordBlade", "Shield", "Cape", "Backpack", "Visor", "HatCone");

        // Main hand → HandR, off hand → HandL. Getting these backwards would put
        // the sword in the shield hand, which only a screenshot would reveal.
        Joint armed = HumanoidRig.of(CharacterSpec.builder()
                .heldMain("sword").heldOff("shield").backItem("cape").faceItem("visor")
                .hatType("cone").build());
        assertThat(armed.find("HandR").parts.stream().map(Part::name)).contains("SwordBlade");
        assertThat(armed.find("HandL").parts.stream().map(Part::name)).contains("Shield");
        assertThat(armed.find("Chest").parts.stream().map(Part::name)).contains("Cape");
        assertThat(armed.find("Head").parts.stream().map(Part::name)).contains("Visor", "HatCone");
    }

    @Test
    void everyHeldItemTokenProducesGeometry() {
        for (String v : CharacterSpec.HELD_MAIN) {
            Joint r = HumanoidRig.of(CharacterSpec.builder().heldMain(v).build());
            int n = r.find("HandR").parts.size();
            assertThat(n).as("main-hand parts for %s", v).isEqualTo("none".equals(v) ? 1 : greaterThanOne(n));
        }
        for (String v : CharacterSpec.HELD_OFF) {
            Joint r = HumanoidRig.of(CharacterSpec.builder().heldOff(v).build());
            int n = r.find("HandL").parts.size();
            assertThat(n).as("off-hand parts for %s", v).isEqualTo("none".equals(v) ? 1 : greaterThanOne(n));
        }
    }

    /** Identity that also asserts >1, keeping the message above readable. */
    private static int greaterThanOne(int n) {
        assertThat(n).isGreaterThan(1);
        return n;
    }

    @Test
    void outfitDecidesWhetherLimbsAreSkinOrCloth() {
        String skin = CharacterSpec.defaults().color("skin");
        String outfit = CharacterSpec.defaults().color("body");

        Part bareThigh = one(HumanoidRig.of(CharacterSpec.builder().outfitType("bare").build()), "ThighL").part();
        assertThat(bareThigh.color()).isEqualTo(skin);
        assertThat(bareThigh.tex()).isEqualTo("skin");

        Part armoredThigh = one(HumanoidRig.of(CharacterSpec.builder().outfitType("armor").build()), "ThighL").part();
        assertThat(armoredThigh.color()).isEqualTo(outfit);
        assertThat(armoredThigh.tex()).isEqualTo("outfit");
    }

    @Test
    void onlySkirtedOutfitsGrowASkirtAndRobeIsTheLongest() {
        assertThat(flatten(HumanoidRig.of(CharacterSpec.builder().outfitType("jumpsuit").build())))
                .doesNotContainKey("Skirt");
        double robe = one(HumanoidRig.of(CharacterSpec.builder().outfitType("robe").build()), "Skirt")
                .part().size().y();
        double dress = one(HumanoidRig.of(CharacterSpec.builder().outfitType("dress").build()), "Skirt")
                .part().size().y();
        double tunic = one(HumanoidRig.of(CharacterSpec.builder().outfitType("tunic").build()), "Skirt")
                .part().size().y();
        assertThat(robe).isGreaterThan(dress);
        assertThat(dress).isGreaterThan(tunic);
    }

    @Test
    void theDefaultRobeSilhouetteStaysBroadEnoughForThePixelProof() {
        // The @godot suite finds the Six Realms hero by scanning for its
        // saturated red across the centre crop. The rig replaced a single fat
        // "robe" cylinder, so the outfit must still present real width — this
        // guards the e2e proof against a future slimming of the skirt.
        CharacterSpec seed = CharacterSpec.builder()
                .bodyShape("stocky").hatType("top_hat").color("body", "#e21422").build();
        Joint root = HumanoidRig.of(seed);
        Placed skirt = one(root, "Skirt");

        assertThat(skirt.part().size().x()).isGreaterThan(0.55);
        assertThat(skirt.part().color()).isEqualTo("#e21422");
        long redParts = flatten(root).values().stream().flatMap(List::stream)
                .filter(pl -> "#e21422".equals(pl.part().color())).count();
        assertThat(redParts).as("outfit-coloured parts").isGreaterThanOrEqualTo(4);
    }

    @Test
    void equipmentCarriesItsSlotSoAConsumerCanStripIt() {
        // NPCs share the project's rig. Without slots they inherited the hero's
        // gear verbatim — every shopkeeper in the crown, every hand on the
        // sword. Body parts must stay SLOT_BODY or stripping equipment would
        // also strip the character.
        CharacterSpec s = CharacterSpec.builder()
                .hatType("crown").faceItem("visor").backItem("cape")
                .heldMain("sword").heldOff("shield").hairStyle("ponytail").build();
        Map<String, List<Placed>> parts = flatten(HumanoidRig.of(s));

        assertThat(parts.get("CrownBand").get(0).part().slot()).isEqualTo(HumanoidRig.SLOT_HAT);
        assertThat(parts.get("Visor").get(0).part().slot()).isEqualTo(HumanoidRig.SLOT_FACE);
        assertThat(parts.get("Cape").get(0).part().slot()).isEqualTo(HumanoidRig.SLOT_BACK);
        assertThat(parts.get("SwordBlade").get(0).part().slot()).isEqualTo(HumanoidRig.SLOT_HELD_MAIN);
        assertThat(parts.get("Shield").get(0).part().slot()).isEqualTo(HumanoidRig.SLOT_HELD_OFF);
        assertThat(parts.get("Ponytail").get(0).part().slot()).isEqualTo(HumanoidRig.SLOT_HAIR);

        for (String body : List.of("Pelvis", "Chest", "Skull", "UpperArmL", "ThighR", "FootL",
                "HandR", "EyeL", "Mouth")) {
            assertThat(parts.get(body).get(0).part().slot())
                    .as("%s is body, not equipment", body).isEqualTo(Part.SLOT_BODY);
        }
    }

    @Test
    void strippingEquipmentLeavesAWholeCharacter() {
        CharacterSpec armed = CharacterSpec.builder()
                .hatType("crown").backItem("cape").heldMain("sword").heldOff("shield").build();
        Map<String, List<Placed>> all = flatten(HumanoidRig.of(armed));

        List<String> keptIfStripped = all.values().stream().flatMap(List::stream)
                .filter(pl -> !HumanoidRig.EQUIPMENT_SLOTS.contains(pl.part().slot()))
                .map(pl -> pl.part().name()).toList();

        // Every limb survives; nothing the author equipped does.
        assertThat(keptIfStripped).contains("Pelvis", "Chest", "Skull",
                "UpperArmL", "ForearmR", "HandL", "ThighL", "ShinR", "FootL", "Mouth");
        assertThat(keptIfStripped).doesNotContain("CrownBand", "Cape", "SwordBlade", "Shield");
    }

    @Test
    void renderStyleDoesNotChangeTheRig() {
        // Style is a materialisation concern (segment counts, flat shading), so
        // 2D/3D/glTF parity must hold identically across both.
        Joint smooth = HumanoidRig.of(CharacterSpec.builder().renderStyle("smooth").build());
        Joint low = HumanoidRig.of(CharacterSpec.builder().renderStyle("lowpoly").build());
        assertThat(flatten(low).keySet()).isEqualTo(flatten(smooth).keySet());
        assertThat(one(low, "Skull").part().size()).isEqualTo(one(smooth, "Skull").part().size());
    }

    @Test
    void assemblyIsDeterministic() {
        CharacterSpec s = CharacterSpec.builder()
                .bodyShape("round").hatType("straw").heldMain("staff").backItem("quiver").build();
        assertThat(flatten(HumanoidRig.of(s)).toString()).isEqualTo(flatten(HumanoidRig.of(s)).toString());
    }
}
