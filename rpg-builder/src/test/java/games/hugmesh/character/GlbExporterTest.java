package games.hugmesh.character;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import games.hugmesh.character.HumanoidRig.Joint;
import games.hugmesh.character.HumanoidRig.Part;
import games.hugmesh.character.HumanoidRig.Shape;
import games.hugmesh.character.HumanoidRig.Vec3;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;
import static org.assertj.core.data.Offset.offset;

/**
 * What a viewer actually receives.
 *
 * <p>A .glb cannot be eyeballed and the JSON is hand-rolled, so these assert
 * the properties whose absence would otherwise only surface as "Blender says
 * the file is corrupt": the container arithmetic, that every index in the
 * document points at something that exists (the classic hand-rolled-glTF bug —
 * an out-of-range accessor index crashes or silently drops geometry), that the
 * POSITION bounds really do bracket the bytes in the BIN chunk, and that the
 * rig's joint tree survived rather than being flattened into a picture.
 */
class GlbExporterTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final int MAGIC = 0x46546C67;
    private static final int CHUNK_JSON = 0x4E4F534A;
    private static final int CHUNK_BIN = 0x004E4942;

    // ── Container ───────────────────────────────────────────────────────────

    @Test
    void containerHeaderAndChunksAreWellFormed() {
        byte[] glb = GlbExporter.export(CharacterSpec.defaults());
        ByteBuffer b = ByteBuffer.wrap(glb).order(ByteOrder.LITTLE_ENDIAN);

        assertThat(b.getInt()).as("magic 'glTF'").isEqualTo(MAGIC);
        assertThat(b.getInt()).as("version").isEqualTo(2);
        assertThat(b.getInt()).as("declared total length").isEqualTo(glb.length);

        int jsonLen = b.getInt();
        assertThat(b.getInt()).as("first chunk is JSON").isEqualTo(CHUNK_JSON);
        assertThat(jsonLen % 4).as("JSON chunk is 4-byte aligned").isZero();
        b.position(b.position() + jsonLen);

        int binLen = b.getInt();
        assertThat(b.getInt()).as("second chunk is BIN").isEqualTo(CHUNK_BIN);
        assertThat(binLen % 4).as("BIN chunk is 4-byte aligned").isZero();

        assertThat(12 + 8 + jsonLen + 8 + binLen)
                .as("header + both chunks account for every byte").isEqualTo(glb.length);
        assertThat(glb.length % 4).isZero();
    }

    @Test
    void jsonChunkIsPaddedWithSpacesNotNuls() {
        // A NUL-padded JSON chunk is rejected by strict loaders. Four scene
        // names of consecutive lengths guarantee at least one export whose JSON
        // actually needs padding, so this cannot pass vacuously.
        int padded = 0;
        for (String name : List.of("a", "ab", "abc", "abcd")) {
            byte[] glb = GlbExporter.export(CharacterSpec.defaults(), name);
            Glb parsed = parse(glb);
            padded += parsed.jsonPad > 0 ? 1 : 0;
            for (int i = 0; i < parsed.jsonPad; i++) {
                assertThat(parsed.jsonChunk[parsed.jsonChunk.length - 1 - i])
                        .as("JSON pad byte %d of %s", i, name).isEqualTo((byte) 0x20);
            }
        }
        assertThat(padded).as("at least one export needed padding").isPositive();
    }

    @Test
    void assetDeclaresGltf2AndNamesTheGenerator() {
        JsonNode json = parse(GlbExporter.export(CharacterSpec.defaults(), "Hero")).json;
        assertThat(json.at("/asset/version").asText()).isEqualTo("2.0");
        assertThat(json.at("/asset/generator").asText()).contains("HugMesh");
        assertThat(json.at("/scenes/0/name").asText()).isEqualTo("Hero");
        assertThat(json.get("scene").asInt()).isZero();
    }

    // ── Referential integrity ───────────────────────────────────────────────

    @Test
    void everyReferencedIndexIsInRange() {
        validate(GlbExporter.export(CharacterSpec.builder()
                .renderStyle("lowpoly").bodyShape("round").headShape("tapered")
                .hairStyle("ponytail").outfitType("armor").hatType("crown")
                .heldMain("staff").heldOff("shield").backItem("wings").faceItem("glasses")
                .build()));
    }

    @Test
    void everyWhitelistedTokenProducesAStructurallyValidGlb() {
        List<CharacterSpec> specs = new ArrayList<>();
        for (String style : CharacterSpec.RENDER_STYLES) {
            for (String v : CharacterSpec.HAT_TYPES) specs.add(spec(style, b -> b.hatType(v)));
            for (String v : CharacterSpec.HELD_MAIN) specs.add(spec(style, b -> b.heldMain(v)));
            for (String v : CharacterSpec.HELD_OFF) specs.add(spec(style, b -> b.heldOff(v)));
            for (String v : CharacterSpec.BACK_ITEMS) specs.add(spec(style, b -> b.backItem(v)));
            for (String v : CharacterSpec.FACE_ITEMS) specs.add(spec(style, b -> b.faceItem(v)));
            for (String v : CharacterSpec.HAIR_STYLES) specs.add(spec(style, b -> b.hairStyle(v)));
            for (String v : CharacterSpec.OUTFIT_TYPES) specs.add(spec(style, b -> b.outfitType(v)));
            for (String v : CharacterSpec.HEAD_SHAPES) specs.add(spec(style, b -> b.headShape(v)));
            for (String v : CharacterSpec.BODY_SHAPES) specs.add(spec(style, b -> b.bodyShape(v)));
            // The bounds themselves: the extremes are where a collapsed
            // dimension or a degenerate facet would appear.
            for (CharacterSpec.Bound bound : CharacterSpec.PROPORTIONS) {
                specs.add(spec(style, b -> b.proportion(bound.key(), bound.min())));
                specs.add(spec(style, b -> b.proportion(bound.key(), bound.max())));
            }
        }

        assertThat(specs).hasSizeGreaterThan(100);
        for (CharacterSpec s : specs) validate(GlbExporter.export(s));
    }

    private static CharacterSpec spec(String style,
                                      java.util.function.Consumer<CharacterSpec.Builder> tweak) {
        CharacterSpec.Builder b = CharacterSpec.builder().renderStyle(style);
        tweak.accept(b);
        return b.build();
    }

    /** Full structural pass: every index resolves, every accessor fits its view. */
    private static void validate(byte[] glb) {
        Glb g = parse(glb);
        JsonNode json = g.json;

        JsonNode nodes = json.path("nodes");
        JsonNode meshes = json.path("meshes");
        JsonNode materials = json.path("materials");
        JsonNode accessors = json.path("accessors");
        JsonNode views = json.path("bufferViews");
        assertThat(nodes.size()).isPositive();
        assertThat(meshes.size()).isPositive();

        assertThat(json.path("buffers").size()).isEqualTo(1);
        assertThat(json.at("/buffers/0/byteLength").asInt())
                .as("declared buffer length fits the BIN chunk").isLessThanOrEqualTo(g.bin.length);

        for (JsonNode root : json.at("/scenes/0/nodes")) {
            assertThat(root.asInt()).isBetween(0, nodes.size() - 1);
        }

        // A node with two parents is not a tree — Blender and three.js both
        // mis-transform the duplicate.
        Set<Integer> claimed = new HashSet<>();
        for (JsonNode node : nodes) {
            for (JsonNode child : node.path("children")) {
                int c = child.asInt();
                assertThat(c).as("child index").isBetween(0, nodes.size() - 1);
                assertThat(claimed.add(c)).as("node %d has a single parent", c).isTrue();
            }
            if (node.has("mesh")) {
                assertThat(node.get("mesh").asInt()).isBetween(0, meshes.size() - 1);
            }
            if (node.has("rotation")) {
                assertThat(node.get("rotation").size()).isEqualTo(4);
                double len = 0;
                for (JsonNode q : node.get("rotation")) len += q.asDouble() * q.asDouble();
                assertThat(Math.sqrt(len)).as("unit quaternion").isCloseTo(1.0, offset(1e-6));
            }
            if (node.has("translation")) assertThat(node.get("translation").size()).isEqualTo(3);
        }

        for (JsonNode mesh : meshes) {
            for (JsonNode prim : mesh.path("primitives")) {
                assertThat(prim.get("mode").asInt()).as("TRIANGLES").isEqualTo(4);
                assertThat(prim.get("material").asInt()).isBetween(0, materials.size() - 1);
                int indices = prim.get("indices").asInt();
                assertThat(indices).isBetween(0, accessors.size() - 1);
                assertThat(accessors.get(indices).get("componentType").asInt()).isEqualTo(5123);
                assertThat(accessors.get(indices).get("count").asInt() % 3)
                        .as("index count is whole triangles").isZero();

                int position = prim.at("/attributes/POSITION").asInt();
                int normal = prim.at("/attributes/NORMAL").asInt();
                assertThat(position).isBetween(0, accessors.size() - 1);
                assertThat(normal).isBetween(0, accessors.size() - 1);
                assertThat(accessors.get(position).get("count").asInt())
                        .isEqualTo(accessors.get(normal).get("count").asInt());
                // Viewers reject a POSITION accessor with no bounds.
                assertThat(accessors.get(position).path("min").size()).isEqualTo(3);
                assertThat(accessors.get(position).path("max").size()).isEqualTo(3);

                // Every index must address a vertex that exists.
                int vertices = accessors.get(position).get("count").asInt();
                for (int i : readIndices(g, accessors.get(indices))) {
                    assertThat(i).as("triangle index").isBetween(0, vertices - 1);
                }
            }
        }

        for (JsonNode acc : accessors) {
            int view = acc.get("bufferView").asInt();
            assertThat(view).isBetween(0, views.size() - 1);
            int size = acc.get("componentType").asInt() == 5126 ? 4 : 2;
            int components = "VEC3".equals(acc.get("type").asText()) ? 3 : 1;
            assertThat(acc.get("count").asInt() * size * components)
                    .as("accessor fits its bufferView")
                    .isLessThanOrEqualTo(views.get(view).get("byteLength").asInt());
        }

        for (JsonNode view : views) {
            assertThat(view.get("buffer").asInt()).isZero();
            int off = view.get("byteOffset").asInt();
            assertThat(off % 4).as("bufferView is 4-byte aligned").isZero();
            assertThat(off + view.get("byteLength").asInt()).isLessThanOrEqualTo(g.bin.length);
        }

        for (JsonNode material : materials) {
            JsonNode factor = material.at("/pbrMetallicRoughness/baseColorFactor");
            assertThat(factor.size()).isEqualTo(4);
            for (JsonNode c : factor) assertThat(c.asDouble()).isBetween(0.0, 1.0);
            assertThat(material.at("/pbrMetallicRoughness/metallicFactor").asDouble()).isZero();
        }
    }

    // ── Geometry ────────────────────────────────────────────────────────────

    @Test
    void positionBoundsBracketTheActualVertexData() {
        for (String style : CharacterSpec.RENDER_STYLES) {
            Glb g = parse(GlbExporter.export(CharacterSpec.builder()
                    .renderStyle(style).hatType("cone").heldMain("sword").build()));

            int checked = 0;
            for (JsonNode mesh : g.json.path("meshes")) {
                for (JsonNode prim : mesh.path("primitives")) {
                    JsonNode acc = g.json.path("accessors").get(prim.at("/attributes/POSITION").asInt());
                    float[] min = floats(acc.get("min"));
                    float[] max = floats(acc.get("max"));
                    float[][] verts = readVec3(g, acc);
                    assertThat(verts).isNotEmpty();
                    for (float[] v : verts) {
                        for (int i = 0; i < 3; i++) {
                            assertThat(v[i]).as("%s axis %d within bounds", mesh.get("name").asText(), i)
                                    .isGreaterThanOrEqualTo(min[i]).isLessThanOrEqualTo(max[i]);
                        }
                    }
                    // And the bounds must be tight, not a lazy huge box.
                    for (int i = 0; i < 3; i++) {
                        float lo = Float.MAX_VALUE, hi = -Float.MAX_VALUE;
                        for (float[] v : verts) { lo = Math.min(lo, v[i]); hi = Math.max(hi, v[i]); }
                        assertThat(min[i]).isEqualTo(lo);
                        assertThat(max[i]).isEqualTo(hi);
                    }
                    checked++;
                }
            }
            assertThat(checked).as("POSITION accessors checked for %s", style).isGreaterThan(20);
        }
    }

    @Test
    void normalsAreUnitLength() {
        Glb g = parse(GlbExporter.export(CharacterSpec.builder().hairStyle("spiky").build()));
        int checked = 0;
        for (JsonNode mesh : g.json.path("meshes")) {
            for (JsonNode prim : mesh.path("primitives")) {
                for (float[] n : readVec3(g, g.json.path("accessors")
                        .get(prim.at("/attributes/NORMAL").asInt()))) {
                    double len = Math.sqrt(n[0] * n[0] + n[1] * n[1] + n[2] * n[2]);
                    assertThat(len).as("normal length in %s", mesh.get("name").asText())
                            .isCloseTo(1.0, offset(1e-4));
                    checked++;
                }
            }
        }
        assertThat(checked).isGreaterThan(100);
    }

    @Test
    void everyMeshIsWoundOutwardAndItsNormalsAgree() {
        // Structural validity says nothing about which way a face points, and
        // an inside-out mesh looks fine in a double-sided preview and wrong in
        // every real engine. Every rig primitive is a CLOSED surface, so its
        // signed volume must be positive under CCW-outward winding — and each
        // stored normal must agree with the facet it belongs to, which is what
        // pins the hand-derived analytic normals for tapered cylinders and
        // ellipsoids.
        for (String style : CharacterSpec.RENDER_STYLES) {
            Glb g = parse(GlbExporter.export(CharacterSpec.builder()
                    .renderStyle(style).hatType("cone").hairStyle("spiky").outfitType("robe")
                    .headShape("oval").heldOff("orb").build()));

            int checked = 0;
            for (JsonNode mesh : g.json.path("meshes")) {
                String name = mesh.get("name").asText();
                JsonNode prim = mesh.at("/primitives/0");
                float[][] pos = readVec3(g, g.json.path("accessors")
                        .get(prim.at("/attributes/POSITION").asInt()));
                float[][] nrm = readVec3(g, g.json.path("accessors")
                        .get(prim.at("/attributes/NORMAL").asInt()));
                int[] idx = readIndices(g, g.json.path("accessors").get(prim.get("indices").asInt()));

                double volume = 0;
                for (int t = 0; t < idx.length; t += 3) {
                    float[] a = pos[idx[t]], b = pos[idx[t + 1]], c = pos[idx[t + 2]];
                    double[] face = cross(sub(b, a), sub(c, b));
                    volume += dot(a, cross(sub(b, a), sub(c, a))) / 6.0;
                    for (int k = 0; k < 3; k++) {
                        assertThat(dot(nrm[idx[t + k]], face))
                                .as("%s: stored normal agrees with its facet", name).isPositive();
                    }
                }
                assertThat(volume).as("%s is wound outward", name).isPositive();
                checked++;
            }
            assertThat(checked).as("closed meshes checked for %s", style).isGreaterThan(20);
        }
    }

    private static double[] sub(float[] a, float[] b) {
        return new double[]{a[0] - b[0], a[1] - b[1], a[2] - b[2]};
    }

    private static double[] cross(double[] a, double[] b) {
        return new double[]{a[1] * b[2] - a[2] * b[1], a[2] * b[0] - a[0] * b[2],
                a[0] * b[1] - a[1] * b[0]};
    }

    private static double dot(float[] a, double[] b) {
        return a[0] * b[0] + a[1] * b[1] + a[2] * b[2];
    }

    @Test
    void nodeHierarchyMirrorsTheRigJointTree() {
        CharacterSpec spec = CharacterSpec.defaults();
        Glb g = parse(GlbExporter.export(spec));
        JsonNode nodes = g.json.path("nodes");

        int root = g.json.at("/scenes/0/nodes/0").asInt();
        assertThat(nodes.get(root).get("name").asText()).isEqualTo("Root");

        // Walking BY NAME through parent/child is the assertion: a flattened
        // export would still contain the names somewhere, but not the chain.
        int hips = child(nodes, root, "Hips");
        int chest = child(nodes, child(nodes, hips, "Torso"), "Chest");
        int head = child(nodes, child(nodes, chest, "Neck"), "Head");
        assertThat(child(nodes, head, "EyeL")).isNotNegative();
        assertThat(child(nodes, head, "Mouth")).isNotNegative();

        for (String side : List.of("L", "R")) {
            int hand = child(nodes, child(nodes, child(nodes,
                    child(nodes, chest, "Shoulder" + side), "UpperArm" + side), "Forearm" + side),
                    "Hand" + side);
            assertThat(nodes.get(hand).get("name").asText()).isEqualTo("Hand" + side);

            int foot = child(nodes, child(nodes, child(nodes, hips, "Thigh" + side), "Shin" + side),
                    "Foot" + side);
            assertThat(nodes.get(foot).get("name").asText()).isEqualTo("Foot" + side);
        }

        // Joint translations carry through, so a rebuilt pose in Blender lands
        // where the game puts it.
        Vec3 rigHips = HumanoidRig.of(spec).find("Hips").pos;
        JsonNode t = nodes.get(hips).get("translation");
        assertThat(t.get(1).asDouble()).isCloseTo(rigHips.y(), offset(1e-5));

        // Every part is a mesh node under the joint that owns it.
        int meshNodes = 0;
        for (JsonNode node : nodes) if (node.has("mesh")) meshNodes++;
        assertThat(meshNodes).isEqualTo(g.json.path("meshes").size());
    }

    @Test
    void lowPolyProducesStrictlyFewerVerticesThanSmooth() {
        for (String head : CharacterSpec.HEAD_SHAPES) {
            long smooth = vertexCount(CharacterSpec.builder()
                    .renderStyle("smooth").headShape(head).build());
            long low = vertexCount(CharacterSpec.builder()
                    .renderStyle("lowpoly").headShape(head).build());
            assertThat(low).as("lowpoly is genuinely cheaper for headShape=%s", head)
                    .isLessThan(smooth);
        }
    }

    @Test
    void segmentCountsMatchTheGodotFactory() throws Exception {
        // The .glb and the in-game mesh are meant to be the same object; the
        // constants live in two languages, so nothing but a test keeps them
        // equal.
        String gd;
        try (InputStream in = GlbExporterTest.class
                .getResourceAsStream("/godot-template/scripts/avatar_factory.gd")) {
            assertThat(in).as("avatar_factory.gd on the classpath").isNotNull();
            gd = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        assertThat(gd).contains("LOWPOLY_RADIAL := " + GlbExporter.LOWPOLY_RADIAL)
                .contains("LOWPOLY_RINGS := " + GlbExporter.LOWPOLY_RINGS)
                .contains("SMOOTH_RADIAL := " + GlbExporter.SMOOTH_RADIAL)
                .contains("SMOOTH_RINGS := " + GlbExporter.SMOOTH_RINGS);
    }

    @Test
    void quadPartsTessellate() {
        // Shape.QUAD is part of the contract but no rig part uses one yet, so
        // without this it would ship untested until the first one does.
        Joint root = new Joint("Root", Vec3.ZERO);
        root.add(new Part("Banner", Shape.QUAD, new Vec3(0, 1, 0), new Vec3(0.5, 0.8, 0.02),
                Vec3.ZERO, 1.0, "#ff0000", "none"));

        byte[] glb = GlbExporter.export(root, "Banner", false);
        validate(glb);

        Glb g = parse(glb);
        assertThat(g.json.path("meshes")).hasSize(1);
        JsonNode acc = g.json.path("accessors")
                .get(g.json.at("/meshes/0/primitives/0/attributes/POSITION").asInt());
        assertThat(acc.get("count").asInt()).as("one flat rectangle").isEqualTo(4);
        assertThat(floats(acc.get("min"))[2]).isZero();
    }

    // ── Colour ──────────────────────────────────────────────────────────────

    @Test
    void srgbColoursAreConvertedToLinear() {
        CharacterSpec.Builder b = CharacterSpec.builder();
        for (CharacterSpec.ColorSlot slot : CharacterSpec.COLORS) b.color(slot.key(), "#808080");
        JsonNode json = parse(GlbExporter.export(b.build())).json;

        boolean sawMidGrey = false;
        for (JsonNode material : json.path("materials")) {
            for (int i = 0; i < 3; i++) {
                double c = material.at("/pbrMetallicRoughness/baseColorFactor").get(i).asDouble();
                // 128/255 is 0.502 in sRGB but 0.216 in linear. A naive /255
                // would put 0.502 here and wash the whole character out.
                assertThat(c).as("no raw sRGB channel reached baseColorFactor")
                        .isNotCloseTo(0.5019607, offset(1e-3));
                if (Math.abs(c - 0.2158605) < 1e-3) sawMidGrey = true;
            }
        }
        assertThat(sawMidGrey).as("#808080 arrives as linear 0.216").isTrue();
    }

    @Test
    void colourEndpointsSurviveTheTransfer() {
        // The curve must be an identity at both ends, or white stops being
        // white and black stops being black.
        assertThat(anyChannel("#ffffff", 1.0)).isTrue();
        assertThat(anyChannel("#000000", 0.0)).isTrue();
    }

    private static boolean anyChannel(String hex, double expected) {
        CharacterSpec.Builder b = CharacterSpec.builder();
        for (CharacterSpec.ColorSlot slot : CharacterSpec.COLORS) b.color(slot.key(), hex);
        JsonNode json = parse(GlbExporter.export(b.build())).json;
        for (JsonNode material : json.path("materials")) {
            JsonNode factor = material.at("/pbrMetallicRoughness/baseColorFactor");
            if (Math.abs(factor.get(0).asDouble() - expected) < 1e-6) return true;
        }
        return false;
    }

    @Test
    void materialsAreDedupedPerDistinctColour() {
        // Every part carries a colour; one material per part would bloat the
        // file and give a DCC user 60 identical slots to wade through.
        Glb g = parse(GlbExporter.export(CharacterSpec.defaults()));
        assertThat(g.json.path("materials").size())
                .isLessThan(g.json.path("meshes").size());

        Set<String> names = new HashSet<>();
        for (JsonNode material : g.json.path("materials")) {
            assertThat(names.add(material.get("name").asText())).isTrue();
        }
    }

    // ── Stability ───────────────────────────────────────────────────────────

    @Test
    void exportIsByteForByteDeterministic() {
        CharacterSpec spec = CharacterSpec.builder()
                .bodyShape("round").hatType("crown").heldMain("staff").heldOff("orb")
                .backItem("wings").faceItem("visor").hairStyle("braid").build();
        assertThat(GlbExporter.export(spec, "Hero")).isEqualTo(GlbExporter.export(spec, "Hero"));
    }

    @Test
    void everyNumberIsLocaleIndependent() {
        // A comma-decimal locale does not fail the JSON parse — it silently
        // splits one number into two, so `[0,5,1,0,1]` reads as a five-element
        // baseColorFactor. Checking the array LENGTHS is what catches it.
        Locale original = Locale.getDefault();
        try {
            Locale.setDefault(Locale.FRANCE);
            Glb g = parse(GlbExporter.export(CharacterSpec.builder()
                    .proportion("chest", 1.234).hatType("cone").heldOff("shield").build()));

            for (JsonNode node : g.json.path("nodes")) {
                if (node.has("translation")) assertThat(node.get("translation").size()).isEqualTo(3);
                if (node.has("rotation")) assertThat(node.get("rotation").size()).isEqualTo(4);
            }
            for (JsonNode material : g.json.path("materials")) {
                assertThat(material.at("/pbrMetallicRoughness/baseColorFactor").size()).isEqualTo(4);
            }
            for (JsonNode acc : g.json.path("accessors")) {
                if (acc.has("min")) {
                    assertThat(acc.get("min").size()).isEqualTo(3);
                    assertThat(acc.get("max").size()).isEqualTo(3);
                }
            }
            validate(GlbExporter.export(CharacterSpec.defaults()));
        } finally {
            Locale.setDefault(original);
        }
    }

    // ── Parsing helpers ─────────────────────────────────────────────────────

    /** A parsed container. Plain fields, so the assertions below stay terse. */
    private static final class Glb {
        final JsonNode json;
        final byte[] jsonChunk;
        final int jsonPad;
        final byte[] bin;

        Glb(JsonNode json, byte[] jsonChunk, int jsonPad, byte[] bin) {
            this.json = json;
            this.jsonChunk = jsonChunk;
            this.jsonPad = jsonPad;
            this.bin = bin;
        }
    }

    private static Glb parse(byte[] glb) {
        ByteBuffer b = ByteBuffer.wrap(glb).order(ByteOrder.LITTLE_ENDIAN);
        assertThat(b.getInt()).isEqualTo(MAGIC);
        assertThat(b.getInt()).isEqualTo(2);
        assertThat(b.getInt()).isEqualTo(glb.length);

        int jsonLen = b.getInt();
        assertThat(b.getInt()).isEqualTo(CHUNK_JSON);
        byte[] jsonChunk = new byte[jsonLen];
        b.get(jsonChunk);

        int binLen = b.getInt();
        assertThat(b.getInt()).isEqualTo(CHUNK_BIN);
        byte[] bin = new byte[binLen];
        b.get(bin);
        assertThat(b.hasRemaining()).as("nothing trails the BIN chunk").isFalse();

        String text = new String(jsonChunk, StandardCharsets.UTF_8);
        int pad = 0;
        while (pad < text.length() && text.charAt(text.length() - 1 - pad) != '}') pad++;
        try {
            return new Glb(MAPPER.readTree(text), jsonChunk, pad, bin);
        } catch (Exception e) {
            return fail("JSON chunk does not parse: " + e.getMessage() + "\n" + text);
        }
    }

    private static float[][] readVec3(Glb g, JsonNode accessor) {
        JsonNode view = g.json.path("bufferViews").get(accessor.get("bufferView").asInt());
        ByteBuffer b = ByteBuffer.wrap(g.bin).order(ByteOrder.LITTLE_ENDIAN);
        b.position(view.get("byteOffset").asInt() + accessor.path("byteOffset").asInt(0));
        int count = accessor.get("count").asInt();
        float[][] out = new float[count][3];
        for (int i = 0; i < count; i++) {
            out[i][0] = b.getFloat();
            out[i][1] = b.getFloat();
            out[i][2] = b.getFloat();
        }
        return out;
    }

    private static int[] readIndices(Glb g, JsonNode accessor) {
        JsonNode view = g.json.path("bufferViews").get(accessor.get("bufferView").asInt());
        ByteBuffer b = ByteBuffer.wrap(g.bin).order(ByteOrder.LITTLE_ENDIAN);
        b.position(view.get("byteOffset").asInt() + accessor.path("byteOffset").asInt(0));
        int[] out = new int[accessor.get("count").asInt()];
        for (int i = 0; i < out.length; i++) out[i] = Short.toUnsignedInt(b.getShort());
        return out;
    }

    private static float[] floats(JsonNode array) {
        float[] out = new float[array.size()];
        for (int i = 0; i < out.length; i++) out[i] = (float) array.get(i).asDouble();
        return out;
    }

    private static int child(JsonNode nodes, int parent, String name) {
        for (JsonNode c : nodes.get(parent).path("children")) {
            if (nodes.get(c.asInt()).path("name").asText().equals(name)) return c.asInt();
        }
        return fail("node %s has no child named %s",
                nodes.get(parent).path("name").asText(), name);
    }

    private static long vertexCount(CharacterSpec spec) {
        Glb g = parse(GlbExporter.export(spec));
        long total = 0;
        for (JsonNode mesh : g.json.path("meshes")) {
            for (JsonNode prim : mesh.path("primitives")) {
                total += g.json.path("accessors")
                        .get(prim.at("/attributes/POSITION").asInt()).get("count").asInt();
            }
        }
        return total;
    }
}
