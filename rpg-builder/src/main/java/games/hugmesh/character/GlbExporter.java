package games.hugmesh.character;

import games.hugmesh.character.HumanoidRig.Joint;
import games.hugmesh.character.HumanoidRig.Part;
import games.hugmesh.character.HumanoidRig.Vec3;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Binary glTF 2.0 (.glb) export of a character — the "exportable and alterable
 * elsewhere" arm of decision 028.
 *
 * <p><b>The joint tree survives the export.</b> Every {@link Joint} becomes a
 * glTF node with its own translation and its children, and every {@link Part}
 * becomes a child mesh node under the joint that owns it. Flattening to one
 * merged mesh would be far less code and would produce a picture rather than a
 * character: keeping the hierarchy is what lets someone open the file in
 * Blender and pose the arms, and it gives a future animation export somewhere
 * to bind. It is also the same tree {@code avatar_factory.gd} instantiates and
 * {@link AvatarSvgRenderer} projects, so the .glb cannot drift from the game
 * model or the 2D preview.
 *
 * <p><b>Style is honoured, not decorated.</b> {@code lowpoly} drops the radial
 * and ring counts to the same values {@code avatar_factory.gd} uses and
 * flat-shades every facet, so the exported mesh has the N64 silhouette the
 * author picked rather than a smooth mesh with a filter over it. The segment
 * constants are deliberately duplicated from the GDScript rather than derived,
 * because they are a shared contract between two languages — the
 * {@code segmentCountsMatchTheGodotFactory} test pins them together.
 *
 * <p><b>Colour is converted, not copied.</b> {@code baseColorFactor} is LINEAR
 * in glTF while our {@code #rrggbb} is sRGB. The naive {@code /255.0} that
 * {@link GdRigBaker} correctly uses for Godot's {@code Color} (which takes sRGB
 * and converts internally) would wash every material out here — mid-grey would
 * arrive as 0.5 instead of 0.216 and the whole character would render pale.
 *
 * <p><b>Zero dependencies, like the rest of this package.</b> The JSON chunk is
 * hand-rolled with a {@link StringBuilder} because this file is synced verbatim
 * into services with different dependency sets. That makes two failure modes
 * ours to prevent, and both are pinned by tests: an out-of-range index (the
 * classic hand-rolled-glTF bug — viewers crash or drop geometry) and a
 * locale-formatted float ({@code 0,5} is not JSON).
 */
public final class GlbExporter {

    private GlbExporter() {}

    /** Content type of the produced bytes. */
    public static final String MIME_TYPE = "model/gltf-binary";

    // ── Container constants ─────────────────────────────────────────────────

    private static final int MAGIC = 0x46546C67;        // "glTF", little-endian
    private static final int VERSION = 2;
    private static final int CHUNK_JSON = 0x4E4F534A;   // "JSON"
    private static final int CHUNK_BIN = 0x004E4942;    // "BIN\0"

    /** The two chunk paddings are NOT interchangeable — the spec names each. */
    private static final byte JSON_PAD = 0x20;          // space
    private static final byte BIN_PAD = 0x00;

    // ── glTF enum values ────────────────────────────────────────────────────

    private static final int UNSIGNED_SHORT = 5123;
    private static final int FLOAT = 5126;
    private static final int ARRAY_BUFFER = 34962;
    private static final int ELEMENT_ARRAY_BUFFER = 34963;
    private static final int TRIANGLES = 4;

    /** Indices are UNSIGNED_SHORT, which caps one primitive at this many. */
    private static final int MAX_VERTICES = 65536;

    // ── Tessellation ────────────────────────────────────────────────────────
    // Mirrors avatar_factory.gd's LOWPOLY_/SMOOTH_ constants exactly: the
    // export and the in-game mesh are meant to be the same object, and a
    // silhouette that only matches in one of them is the drift decision 028
    // exists to remove.

    static final int LOWPOLY_RADIAL = 6;
    static final int LOWPOLY_RINGS = 3;
    static final int SMOOTH_RADIAL = 16;
    static final int SMOOTH_RINGS = 8;

    /** Below this a radius is a degenerate ring — a cone apex, not a face. */
    private static final double EPS = 1e-9;

    // ── Public API ──────────────────────────────────────────────────────────

    /** The character as a .glb, under a generic scene name. */
    public static byte[] export(CharacterSpec spec) {
        return export(spec, "Character");
    }

    /** The character as a .glb, naming the scene (what a DCC tool shows). */
    public static byte[] export(CharacterSpec spec, String name) {
        return export(HumanoidRig.of(spec), name, spec.lowPoly());
    }

    /**
     * Exports an already-built rig.
     *
     * <p>Package-private: the seam exists so tests can drive shapes the current
     * rig never emits ({@link HumanoidRig.Shape#QUAD}), which would otherwise
     * ship untested until the first part that uses one.
     */
    static byte[] export(Joint root, String name, boolean lowPoly) {
        Doc doc = new Doc(lowPoly);
        int rootNode = doc.addJoint(root);
        return doc.write(name, rootNode);
    }

    // ── Document assembly ───────────────────────────────────────────────────

    /** A glTF node: a joint (no mesh) or a part (a mesh, no children). */
    private record NodeDef(String name, Vec3 translation, double[] rotation, int mesh,
                           List<Integer> children) {}

    private record ViewDef(int offset, int length, int target) {}

    private record AccDef(int view, int componentType, int count, String type,
                          float[] min, float[] max) {}

    private static final class Doc {

        private final boolean lowPoly;
        private final List<NodeDef> nodes = new ArrayList<>();
        private final List<MeshBuf> meshes = new ArrayList<>();

        /** Colour → material index. Deduped: one PBR material per distinct hex. */
        private final Map<String, Integer> materials = new LinkedHashMap<>();

        Doc(boolean lowPoly) { this.lowPoly = lowPoly; }

        /**
         * Adds one joint and its subtree, returning the joint's node index.
         *
         * <p>The joint's own slot is reserved BEFORE its children are added so
         * a parent always precedes its children in the node array — not
         * required by the spec, but it keeps the emitted JSON readable and the
         * indices stable for the determinism test.
         */
        int addJoint(Joint joint) {
            int self = nodes.size();
            nodes.add(null);
            List<Integer> children = new ArrayList<>();
            for (Part p : joint.parts) {
                int n = addPart(p);
                if (n >= 0) children.add(n);
            }
            for (Joint c : joint.children) children.add(addJoint(c));
            nodes.set(self, new NodeDef(joint.name, joint.pos, null, -1, children));
            return self;
        }

        /** @return the part's node index, or -1 when it has no geometry */
        private int addPart(Part p) {
            // Same rule avatar_factory.gd applies: a part with a collapsed
            // dimension is not renderable, and here it would additionally
            // produce a zero-extent accessor bound.
            if (p.size().x() <= 0 || p.size().y() <= 0 || p.size().z() <= 0) return -1;

            MeshBuf m = new MeshBuf(p.name(), lowPoly, material(p.color()));
            tessellate(p, m);
            if (m.empty()) return -1;

            int mesh = meshes.size();
            meshes.add(m);
            int node = nodes.size();
            nodes.add(new NodeDef(p.name(), p.pos(), quaternion(p.rot()), mesh, List.of()));
            return node;
        }

        private int material(String hex) {
            Integer hit = materials.get(hex);
            if (hit != null) return hit;
            int index = materials.size();
            materials.put(hex, index);
            return index;
        }

        private int radial() { return lowPoly ? LOWPOLY_RADIAL : SMOOTH_RADIAL; }

        private int rings() { return lowPoly ? LOWPOLY_RINGS : SMOOTH_RINGS; }

        // ── Primitives ──────────────────────────────────────────────────────
        // Every primitive is centred on the part's own origin, because that is
        // what `pos` means everywhere else in this package (the SVG draws the
        // same rectangles centred, avatar_factory positions the same meshes
        // centred). Sizes are FULL extents, so the half-extents below are what
        // the geometry is actually built from.

        private void tessellate(Part p, MeshBuf m) {
            double hx = p.size().x() / 2, hy = p.size().y() / 2, hz = p.size().z() / 2;
            switch (p.shape()) {
                case BOX -> box(m, hx, hy, hz);
                case CYL -> cylinder(m, hx, hz, p.size().y(), p.taper());
                case SPHERE -> ellipsoid(m, hx, hy, hz);
                case QUAD -> plane(m, hx, hy);
            }
        }

        /** Six flat-shaded quads — a box has no smooth reading of its corners. */
        private void box(MeshBuf m, double hx, double hy, double hz) {
            Vec3 x = v(hx, 0, 0), y = v(0, hy, 0), z = v(0, 0, hz);
            face(m, x, y, z, v(1, 0, 0));            // +X: Y × Z
            face(m, neg(x), z, y, v(-1, 0, 0));      // −X: Z × Y
            face(m, y, z, x, v(0, 1, 0));            // +Y: Z × X
            face(m, neg(y), x, z, v(0, -1, 0));      // −Y: X × Z
            face(m, z, x, y, v(0, 0, 1));            // +Z: X × Y
            face(m, neg(z), y, x, v(0, 0, -1));      // −Z: Y × X
        }

        /** {@code u × v} must be the outward normal, which fixes the winding. */
        private void face(MeshBuf m, Vec3 centre, Vec3 u, Vec3 vv, Vec3 n) {
            m.quad(add(centre, neg(u), neg(vv)), add(centre, u, neg(vv)),
                    add(centre, u, vv), add(centre, neg(u), vv), n, n, n, n);
        }

        private void plane(MeshBuf m, double hx, double hy) {
            face(m, Vec3.ZERO, v(hx, 0, 0), v(0, hy, 0), v(0, 0, 1));
        }

        /**
         * Cylinder along Y honouring {@code taper} (top radius ÷ bottom
         * radius), so a robe skirt, a hat crown and a wizard's cone all come
         * out of the same routine. {@code taper == 0} collapses the top ring to
         * an apex — a real cone, not a needle-thin cylinder.
         */
        private void cylinder(MeshBuf m, double ax, double az, double h, double taper) {
            int seg = radial();
            double t = Math.max(0, taper);
            double topAx = ax * t, topAz = az * t;
            boolean apex = topAx <= EPS || topAz <= EPS;
            double y0 = -h / 2, y1 = h / 2;
            Vec3 tip = v(0, y1, 0);

            for (int i = 0; i < seg; i++) {
                double a0 = 2 * Math.PI * i / seg, a1 = 2 * Math.PI * (i + 1) / seg;
                Vec3 b0 = v(ax * Math.cos(a0), y0, az * Math.sin(a0));
                Vec3 b1 = v(ax * Math.cos(a1), y0, az * Math.sin(a1));
                Vec3 n0 = sideNormal(ax, az, h, t, a0);
                Vec3 n1 = sideNormal(ax, az, h, t, a1);

                if (apex) {
                    // The apex has no single surface normal; the facet's own is
                    // the only honest answer there.
                    Vec3 fn = faceNormal(b0, tip, b1);
                    m.tri(b0, tip, b1, n0, fn == null ? n0 : fn, n1);
                } else {
                    Vec3 t0 = v(topAx * Math.cos(a0), y1, topAz * Math.sin(a0));
                    Vec3 t1 = v(topAx * Math.cos(a1), y1, topAz * Math.sin(a1));
                    m.quad(b0, t0, t1, b1, n0, n0, n1, n1);
                }
            }

            // Caps are discs: their normal is the disc's, never the side's,
            // even in smooth mode.
            disc(m, seg, ax, az, y0, v(0, -1, 0));
            if (!apex) disc(m, seg, topAx, topAz, y1, v(0, 1, 0));
        }

        private void disc(MeshBuf m, int seg, double ax, double az, double y, Vec3 n) {
            Vec3 centre = v(0, y, 0);
            for (int i = 0; i < seg; i++) {
                double a0 = 2 * Math.PI * i / seg, a1 = 2 * Math.PI * (i + 1) / seg;
                Vec3 p0 = v(ax * Math.cos(a0), y, az * Math.sin(a0));
                Vec3 p1 = v(ax * Math.cos(a1), y, az * Math.sin(a1));
                if (n.y() < 0) m.tri(centre, p0, p1, n, n, n);
                else m.tri(centre, p1, p0, n, n, n);
            }
        }

        /**
         * Outward normal of the tapered side at angle {@code a}.
         *
         * <p>Derived analytically rather than averaged from the facets: for a
         * cone the radius factor cancels, so the same normal serves the bottom
         * and top ring and the smooth path shares vertices instead of doubling
         * them.
         */
        private static Vec3 sideNormal(double ax, double az, double h, double taper, double a) {
            Vec3 n = v(az * h * Math.cos(a), ax * az * (1 - taper), ax * h * Math.sin(a));
            Vec3 unit = normalize(n);
            return unit == null ? v(0, 1, 0) : unit;
        }

        /** UV sphere, scaled per axis — the rig's spheres are rarely round. */
        private void ellipsoid(MeshBuf m, double rx, double ry, double rz) {
            int seg = radial(), stacks = rings();
            for (int j = 0; j < stacks; j++) {
                double p0 = Math.PI * j / stacks, p1 = Math.PI * (j + 1) / stacks;
                for (int i = 0; i < seg; i++) {
                    double a0 = 2 * Math.PI * i / seg, a1 = 2 * Math.PI * (i + 1) / seg;
                    Vec3 a = onEllipsoid(rx, ry, rz, a0, p0);
                    Vec3 b = onEllipsoid(rx, ry, rz, a1, p0);
                    Vec3 c = onEllipsoid(rx, ry, rz, a1, p1);
                    Vec3 d = onEllipsoid(rx, ry, rz, a0, p1);
                    Vec3 na = surfaceNormal(a, rx, ry, rz), nb = surfaceNormal(b, rx, ry, rz);
                    Vec3 nc = surfaceNormal(c, rx, ry, rz), nd = surfaceNormal(d, rx, ry, rz);

                    // The pole stacks are triangles: half of each quad there is
                    // a zero-area sliver, which would give a NaN face normal.
                    if (j == 0) m.tri(a, c, d, na, nc, nd);
                    else if (j == stacks - 1) m.tri(a, b, c, na, nb, nc);
                    else m.quad(a, b, c, d, na, nb, nc, nd);
                }
            }
        }

        private static Vec3 onEllipsoid(double rx, double ry, double rz, double a, double p) {
            return v(rx * Math.sin(p) * Math.cos(a), ry * Math.cos(p), rz * Math.sin(p) * Math.sin(a));
        }

        private static Vec3 surfaceNormal(Vec3 p, double rx, double ry, double rz) {
            Vec3 n = normalize(v(p.x() / (rx * rx), p.y() / (ry * ry), p.z() / (rz * rz)));
            return n == null ? v(0, 1, 0) : n;
        }

        // ── Serialisation ───────────────────────────────────────────────────

        byte[] write(String name, int rootNode) {
            ByteArrayOutputStream bin = new ByteArrayOutputStream();
            List<ViewDef> views = new ArrayList<>();
            List<AccDef> accessors = new ArrayList<>();
            // Per mesh: {indices, POSITION, NORMAL} accessor indices.
            List<int[]> primitives = new ArrayList<>();

            for (MeshBuf m : meshes) {
                if (m.vertexCount() > MAX_VERTICES) {
                    throw new IllegalStateException("part " + m.name + " needs "
                            + m.vertexCount() + " vertices — over the UNSIGNED_SHORT index limit");
                }
                int idxAcc = accessors.size();
                accessors.add(new AccDef(views.size(), UNSIGNED_SHORT, m.indices.size(),
                        "SCALAR", null, null));
                views.add(writeIndices(bin, m));

                int posAcc = accessors.size();
                accessors.add(new AccDef(views.size(), FLOAT, m.vertexCount(), "VEC3",
                        m.min(), m.max()));
                views.add(writeVertexComponent(bin, m, 0));

                int normAcc = accessors.size();
                accessors.add(new AccDef(views.size(), FLOAT, m.vertexCount(), "VEC3", null, null));
                views.add(writeVertexComponent(bin, m, 3));

                primitives.add(new int[]{idxAcc, posAcc, normAcc});
            }

            byte[] binary = bin.toByteArray();
            String json = json(name, rootNode, primitives, accessors, views, binary.length);
            return container(json.getBytes(StandardCharsets.UTF_8), binary);
        }

        private ViewDef writeIndices(ByteArrayOutputStream bin, MeshBuf m) {
            pad(bin, 4);
            int offset = bin.size();
            for (int i : m.indices) u16(bin, i);
            int length = bin.size() - offset;
            return new ViewDef(offset, length, ELEMENT_ARRAY_BUFFER);
        }

        /** Writes POSITION ({@code from == 0}) or NORMAL ({@code from == 3}). */
        private ViewDef writeVertexComponent(ByteArrayOutputStream bin, MeshBuf m, int from) {
            pad(bin, 4);
            int offset = bin.size();
            for (float[] vert : m.vertices) {
                f32(bin, vert[from]);
                f32(bin, vert[from + 1]);
                f32(bin, vert[from + 2]);
            }
            return new ViewDef(offset, bin.size() - offset, ARRAY_BUFFER);
        }

        private String json(String name, int rootNode, List<int[]> primitives,
                            List<AccDef> accessors, List<ViewDef> views, int binLength) {
            StringBuilder sb = new StringBuilder(8192);
            sb.append("{\"asset\":{\"version\":\"2.0\",\"generator\":")
                    .append(quote("HugMesh character exporter (rpg-builder, decision 028)"))
                    .append("},\"scene\":0,\"scenes\":[{\"name\":").append(quote(name))
                    .append(",\"nodes\":[").append(rootNode).append("]}],\"nodes\":[");

            for (int i = 0; i < nodes.size(); i++) {
                NodeDef n = nodes.get(i);
                if (i > 0) sb.append(',');
                sb.append("{\"name\":").append(quote(n.name()));
                if (!isZero(n.translation())) {
                    sb.append(",\"translation\":[").append(num(n.translation().x())).append(',')
                            .append(num(n.translation().y())).append(',')
                            .append(num(n.translation().z())).append(']');
                }
                if (n.rotation() != null) {
                    sb.append(",\"rotation\":[");
                    for (int k = 0; k < 4; k++) {
                        if (k > 0) sb.append(',');
                        sb.append(num(n.rotation()[k]));
                    }
                    sb.append(']');
                }
                if (n.mesh() >= 0) sb.append(",\"mesh\":").append(n.mesh());
                if (!n.children().isEmpty()) {
                    sb.append(",\"children\":[");
                    for (int k = 0; k < n.children().size(); k++) {
                        if (k > 0) sb.append(',');
                        sb.append(n.children().get(k));
                    }
                    sb.append(']');
                }
                sb.append('}');
            }

            sb.append("],\"meshes\":[");
            for (int i = 0; i < meshes.size(); i++) {
                MeshBuf m = meshes.get(i);
                int[] acc = primitives.get(i);
                if (i > 0) sb.append(',');
                sb.append("{\"name\":").append(quote(m.name))
                        .append(",\"primitives\":[{\"attributes\":{\"POSITION\":").append(acc[1])
                        .append(",\"NORMAL\":").append(acc[2])
                        .append("},\"indices\":").append(acc[0])
                        .append(",\"material\":").append(m.material)
                        .append(",\"mode\":").append(TRIANGLES).append("}]}");
            }

            sb.append("],\"materials\":[");
            boolean first = true;
            for (String hex : materials.keySet()) {
                if (!first) sb.append(',');
                first = false;
                double[] rgb = linearRgb(hex);
                sb.append("{\"name\":").append(quote("mat" + hex))
                        .append(",\"pbrMetallicRoughness\":{\"baseColorFactor\":[")
                        .append(num(rgb[0])).append(',').append(num(rgb[1])).append(',')
                        .append(num(rgb[2])).append(",1],\"metallicFactor\":0,")
                        .append("\"roughnessFactor\":0.9}}");
            }

            sb.append("],\"accessors\":[");
            for (int i = 0; i < accessors.size(); i++) {
                AccDef a = accessors.get(i);
                if (i > 0) sb.append(',');
                sb.append("{\"bufferView\":").append(a.view())
                        .append(",\"componentType\":").append(a.componentType())
                        .append(",\"count\":").append(a.count())
                        .append(",\"type\":\"").append(a.type()).append('"');
                if (a.min() != null) {
                    sb.append(",\"min\":").append(bounds(a.min()))
                            .append(",\"max\":").append(bounds(a.max()));
                }
                sb.append('}');
            }

            sb.append("],\"bufferViews\":[");
            for (int i = 0; i < views.size(); i++) {
                ViewDef view = views.get(i);
                if (i > 0) sb.append(',');
                sb.append("{\"buffer\":0,\"byteOffset\":").append(view.offset())
                        .append(",\"byteLength\":").append(view.length())
                        .append(",\"target\":").append(view.target()).append('}');
            }

            sb.append("],\"buffers\":[{\"byteLength\":").append(binLength).append("}]}");
            return sb.toString();
        }
    }

    // ── GLB container ───────────────────────────────────────────────────────

    /**
     * Wraps the two chunks in the 12-byte header. Every length here is
     * little-endian and every chunk is padded to a 4-byte boundary — with the
     * padding BYTE the spec names for that chunk type, because a JSON chunk
     * padded with NULs is rejected by strict loaders.
     */
    private static byte[] container(byte[] json, byte[] bin) {
        int jsonPad = padding(json.length);
        int binPad = padding(bin.length);
        int total = 12 + 8 + json.length + jsonPad + 8 + bin.length + binPad;

        ByteArrayOutputStream out = new ByteArrayOutputStream(total);
        u32(out, MAGIC);
        u32(out, VERSION);
        u32(out, total);

        u32(out, json.length + jsonPad);
        u32(out, CHUNK_JSON);
        out.writeBytes(json);
        for (int i = 0; i < jsonPad; i++) out.write(JSON_PAD);

        u32(out, bin.length + binPad);
        u32(out, CHUNK_BIN);
        out.writeBytes(bin);
        for (int i = 0; i < binPad; i++) out.write(BIN_PAD);

        return out.toByteArray();
    }

    private static int padding(int length) { return (4 - (length % 4)) % 4; }

    private static void pad(ByteArrayOutputStream out, int to) {
        while (out.size() % to != 0) out.write(0);
    }

    private static void u32(ByteArrayOutputStream out, int v) {
        out.write(v);
        out.write(v >>> 8);
        out.write(v >>> 16);
        out.write(v >>> 24);
    }

    private static void u16(ByteArrayOutputStream out, int v) {
        out.write(v);
        out.write(v >>> 8);
    }

    private static void f32(ByteArrayOutputStream out, float v) {
        u32(out, Float.floatToIntBits(v));
    }

    // ── Mesh buffer ─────────────────────────────────────────────────────────

    /** Dedup key: the exact stored floats, so identity is bit-for-bit. */
    private record VKey(int px, int py, int pz, int nx, int ny, int nz) {}

    /**
     * One part's geometry, indexed as it is built.
     *
     * <p>Vertices are deduped on position AND normal, which is what makes style
     * structural rather than a flag: the smooth path shares a vertex between
     * neighbouring facets because both want the same analytic normal, and the
     * flat path cannot, because each facet asks for its own. That is also why
     * lowpoly is genuinely cheaper rather than just coarser.
     */
    private static final class MeshBuf {

        final String name;
        final boolean flat;
        final int material;
        final List<float[]> vertices = new ArrayList<>();
        final List<Integer> indices = new ArrayList<>();
        private final Map<VKey, Integer> seen = new HashMap<>();

        MeshBuf(String name, boolean flat, int material) {
            this.name = name;
            this.flat = flat;
            this.material = material;
        }

        boolean empty() { return indices.isEmpty(); }

        int vertexCount() { return vertices.size(); }

        void quad(Vec3 a, Vec3 b, Vec3 c, Vec3 d, Vec3 na, Vec3 nb, Vec3 nc, Vec3 nd) {
            tri(a, b, c, na, nb, nc);
            tri(a, c, d, na, nc, nd);
        }

        void tri(Vec3 a, Vec3 b, Vec3 c, Vec3 na, Vec3 nb, Vec3 nc) {
            Vec3 fn = faceNormal(a, b, c);
            // A zero-area triangle has no normal; emitting one would put a NaN
            // in the buffer and a `NaN` token in the JSON, which is not JSON.
            if (fn == null) return;
            if (flat) { na = fn; nb = fn; nc = fn; }
            indices.add(vertex(a, na));
            indices.add(vertex(b, nb));
            indices.add(vertex(c, nc));
        }

        private int vertex(Vec3 p, Vec3 n) {
            float px = (float) p.x(), py = (float) p.y(), pz = (float) p.z();
            float nx = (float) n.x(), ny = (float) n.y(), nz = (float) n.z();
            VKey key = new VKey(Float.floatToIntBits(px), Float.floatToIntBits(py),
                    Float.floatToIntBits(pz), Float.floatToIntBits(nx),
                    Float.floatToIntBits(ny), Float.floatToIntBits(nz));
            Integer hit = seen.get(key);
            if (hit != null) return hit;
            int index = vertices.size();
            vertices.add(new float[]{px, py, pz, nx, ny, nz});
            seen.put(key, index);
            return index;
        }

        float[] min() {
            float[] m = {Float.MAX_VALUE, Float.MAX_VALUE, Float.MAX_VALUE};
            for (float[] v : vertices) for (int i = 0; i < 3; i++) m[i] = Math.min(m[i], v[i]);
            return m;
        }

        float[] max() {
            float[] m = {-Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE};
            for (float[] v : vertices) for (int i = 0; i < 3; i++) m[i] = Math.max(m[i], v[i]);
            return m;
        }
    }

    // ── Colour ──────────────────────────────────────────────────────────────

    /**
     * {@code #rrggbb} (sRGB) → linear RGB, the sRGB EOTF the glTF spec names.
     *
     * <p>NOT {@code channel / 255.0}: glTF's {@code baseColorFactor} is linear,
     * so the naive division makes every material far too bright — mid-grey
     * would arrive at 0.5 instead of 0.216 and the whole character reads
     * washed out.
     */
    private static double[] linearRgb(String hex) {
        if (hex == null || !hex.matches("^#[0-9a-fA-F]{6}$")) {
            throw new IllegalStateException("unvalidated colour reached the glTF exporter: " + hex);
        }
        return new double[]{
                linear(Integer.parseInt(hex.substring(1, 3), 16) / 255.0),
                linear(Integer.parseInt(hex.substring(3, 5), 16) / 255.0),
                linear(Integer.parseInt(hex.substring(5, 7), 16) / 255.0)};
    }

    private static double linear(double c) {
        return c <= 0.04045 ? c / 12.92 : Math.pow((c + 0.055) / 1.055, 2.4);
    }

    // ── Vector helpers ──────────────────────────────────────────────────────

    private static Vec3 v(double x, double y, double z) { return new Vec3(x, y, z); }

    private static Vec3 neg(Vec3 a) { return v(-a.x(), -a.y(), -a.z()); }

    private static Vec3 add(Vec3 a, Vec3 b, Vec3 c) { return a.plus(b).plus(c); }

    private static Vec3 sub(Vec3 a, Vec3 b) { return v(a.x() - b.x(), a.y() - b.y(), a.z() - b.z()); }

    private static Vec3 cross(Vec3 a, Vec3 b) {
        return v(a.y() * b.z() - a.z() * b.y(),
                a.z() * b.x() - a.x() * b.z(),
                a.x() * b.y() - a.y() * b.x());
    }

    /** @return the unit vector, or null when it has no direction */
    private static Vec3 normalize(Vec3 a) {
        double len = Math.sqrt(a.x() * a.x() + a.y() * a.y() + a.z() * a.z());
        if (!(len > EPS)) return null;
        return v(a.x() / len, a.y() / len, a.z() / len);
    }

    /** @return the CCW-outward normal, or null when the triangle is degenerate */
    private static Vec3 faceNormal(Vec3 a, Vec3 b, Vec3 c) {
        return normalize(cross(sub(b, a), sub(c, b)));
    }

    private static boolean isZero(Vec3 a) {
        return a.x() == 0 && a.y() == 0 && a.z() == 0;
    }

    /**
     * Euler → unit quaternion in the order Godot's {@code Node3D.rotation}
     * composes (YXZ), because {@code avatar_factory.gd} assigns these very
     * numbers to that property — an export that used a different order would
     * disagree with the game for any multi-axis part. Every rotation the rig
     * emits today is single-axis, where the order is unobservable; pinning it
     * now is what keeps that true when one is not.
     *
     * @return {@code [x, y, z, w]}, or null for no rotation
     */
    private static double[] quaternion(Vec3 euler) {
        if (isZero(euler)) return null;
        double[] qx = {Math.sin(euler.x() / 2), 0, 0, Math.cos(euler.x() / 2)};
        double[] qy = {0, Math.sin(euler.y() / 2), 0, Math.cos(euler.y() / 2)};
        double[] qz = {0, 0, Math.sin(euler.z() / 2), Math.cos(euler.z() / 2)};
        double[] q = mul(mul(qy, qx), qz);

        double len = Math.sqrt(q[0] * q[0] + q[1] * q[1] + q[2] * q[2] + q[3] * q[3]);
        if (len <= EPS) return null;
        for (int i = 0; i < 4; i++) q[i] /= len;
        return q;
    }

    private static double[] mul(double[] a, double[] b) {
        return new double[]{
                a[3] * b[0] + a[0] * b[3] + a[1] * b[2] - a[2] * b[1],
                a[3] * b[1] - a[0] * b[2] + a[1] * b[3] + a[2] * b[0],
                a[3] * b[2] + a[0] * b[1] - a[1] * b[0] + a[2] * b[3],
                a[3] * b[3] - a[0] * b[0] - a[1] * b[1] - a[2] * b[2]};
    }

    // ── JSON scalars ────────────────────────────────────────────────────────

    /**
     * A JSON number. Locale.ROOT is load-bearing: a comma-decimal default
     * locale would emit {@code 0,5}, and unlike a broken float in generated
     * GDScript this one produces a file that no viewer can even parse.
     */
    private static String num(double value) {
        if (!Double.isFinite(value)) {
            throw new IllegalStateException("non-finite value reached the glTF writer: " + value);
        }
        double r = Math.round(value * 1e6) / 1e6;
        if (r == Math.rint(r) && Math.abs(r) < 1e9) return Long.toString((long) r);
        String s = String.format(Locale.ROOT, "%.6f", r);
        int end = s.length();
        while (s.charAt(end - 1) == '0') end--;
        if (s.charAt(end - 1) == '.') end--;   // "12.000000" → "12", never "12."
        return s.substring(0, end);
    }

    /**
     * Accessor bounds, printed EXACTLY rather than rounded.
     *
     * <p>{@code min}/{@code max} must bracket the stored floats; rounding to
     * six places could nudge a bound inside the data and make the accessor
     * invalid. {@link Float#toString} round-trips and is locale-independent by
     * contract, which {@link String#format} is not.
     */
    private static String bounds(float[] values) {
        StringBuilder sb = new StringBuilder(48).append('[');
        for (int i = 0; i < values.length; i++) {
            if (!Float.isFinite(values[i])) {
                throw new IllegalStateException("non-finite accessor bound: " + values[i]);
            }
            if (i > 0) sb.append(',');
            sb.append(Float.toString(values[i]));
        }
        return sb.append(']').toString();
    }

    /** JSON string literal. Names are ours, but the scene name is a caller's. */
    private static String quote(String s) {
        StringBuilder sb = new StringBuilder(s.length() + 8).append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20) sb.append(String.format(Locale.ROOT, "\\u%04x", (int) c));
                    else sb.append(c);
                }
            }
        }
        return sb.append('"').toString();
    }
}
