package games.hugmesh.vnbuilder.service;

import games.hugmesh.character.AvatarSvgRenderer;
import games.hugmesh.character.CharacterDocument;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;
import org.w3c.dom.Document;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.fail;

/**
 * Specs for {@link ProceduralAssetService} — proves the generator is a PURE
 * function of its inputs (same seed ⇒ byte-identical, different seed/theme ⇒
 * differ) and that the emitted SVG is well-formed. Mirrors the offline unit
 * style used across the builder services.
 */
class ProceduralAssetServiceTest {

    private final ProceduralAssetService svc = new ProceduralAssetService();

    @Test void sameSeedIsByteIdentical_background() {
        byte[] a = svc.generate("background", "town-square", "dusk");
        byte[] b = svc.generate("background", "town-square", "dusk");
        assertThat(a).isEqualTo(b);
    }

    @Test void sameSeedIsByteIdentical_portrait() {
        byte[] a = svc.generate("portrait", "dani", "#ff8800");
        byte[] b = svc.generate("portrait", "dani", "#ff8800");
        assertThat(a).isEqualTo(b);
    }

    @Test void differentThemeDiffers() {
        byte[] dawn = svc.generate("background", "town-square", "dawn");
        byte[] dusk = svc.generate("background", "town-square", "dusk");
        assertThat(dawn).isNotEqualTo(dusk);
    }

    @Test void differentSlugDiffers() {
        byte[] hero = svc.generate("portrait", "hero", null);
        byte[] villain = svc.generate("portrait", "villain", null);
        assertThat(hero).isNotEqualTo(villain);
    }

    @Test void kindIsCaseInsensitive() {
        assertThat(svc.generate("BACKGROUND", "s", "void"))
                .isEqualTo(svc.generate("background", "s", "void"));
    }

    @Test void namedThemeSelectsThatTheme() {
        String svg = new String(svc.generate("background", "x", "void"), StandardCharsets.UTF_8);
        assertThat(svg).contains("data-theme=\"void\"");
    }

    @Test void backgroundIsWellFormedSvg() throws Exception {
        assertWellFormedSvg(svc.generate("background", "forest-path", "forest"));
    }

    @Test void portraitIsWellFormedSvg() throws Exception {
        assertWellFormedSvg(svc.generate("portrait", "dani", "#3366cc"));
    }

    @Test void portraitWithoutHexThemeStillWellFormed() throws Exception {
        assertWellFormedSvg(svc.generate("portrait", "narrator", null));
    }

    @Test void unknownKindIsRejected() {
        assertThatThrownBy(() -> svc.generate("sprite", "x", null))
                .isInstanceOf(ResponseStatusException.class);
    }

    // ---- character-portrait (canonical parametric humanoid) ---------------

    @Test void characterPortrait_wellFormedSvgAtPortraitDimensions() throws Exception {
        byte[] out = svc.generate("character-portrait", "dani", null, null);
        assertWellFormedSvg(out);
        Document doc = parse(out);
        // Exactly the legacy portrait's 512x768, so the two kinds are drop-in
        // interchangeable anywhere a VN portrait slots in.
        assertThat(doc.getDocumentElement().getAttribute("width")).isEqualTo("512");
        assertThat(doc.getDocumentElement().getAttribute("height")).isEqualTo("768");
    }

    @Test void characterPortrait_sameInputsAreByteIdentical() {
        Map<String, Object> doc = Map.of("colors", Map.of("body", "#8800ff"));
        assertThat(svc.generate("character-portrait", "dani", null, doc))
                .isEqualTo(svc.generate("character-portrait", "dani", null, doc));
    }

    @Test void characterPortrait_documentIsHonoured() {
        Map<String, Object> doc = Map.of(
                "kind", "hugmesh.character",
                "schemaVersion", 1,
                "shape", Map.of("body", "stocky", "hair", "long"),
                "colors", Map.of("body", "#112233"),
                "expression", "happy");
        byte[] out = svc.generate("character-portrait", "dani", null, doc);
        // End-to-end: the service must render EXACTLY what the canonical
        // renderer produces for this document — no re-interpretation.
        String expected = AvatarSvgRenderer.renderPortrait(
                CharacterDocument.fromMap(doc), "happy", 512, 768);
        assertThat(new String(out, StandardCharsets.UTF_8)).isEqualTo(expected);
        assertContainsShadeOf(new String(out, StandardCharsets.UTF_8), 0x11, 0x22, 0x33);
    }

    @Test void characterPortrait_noDocumentSeedsBodyColourLikeTheFlatPortrait() {
        // The flat portrait's seed path: a hex theme wins outright…
        String themed = new String(svc.generate("character-portrait", "dani", "#112233", null),
                StandardCharsets.UTF_8);
        assertContainsShadeOf(themed, 0x11, 0x22, 0x33);
        // …otherwise the hue is seeded from the slug, so slugs must differ.
        assertThat(svc.generate("character-portrait", "hero", null, null))
                .isNotEqualTo(svc.generate("character-portrait", "villain", null, null));
    }

    @Test void characterPortrait_invalidColourIsA400() {
        Map<String, Object> doc = Map.of("colors", Map.of("body", "not-a-colour"));
        assertThatThrownBy(() -> svc.generate("character-portrait", "dani", null, doc))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        e -> assertThat(e.getStatusCode().value()).isEqualTo(400));
    }

    @Test void characterPortrait_outOfRangeProportionIsA400() {
        Map<String, Object> doc = Map.of("proportions", Map.of("height", 3.0));
        assertThatThrownBy(() -> svc.generate("character-portrait", "dani", null, doc))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        e -> assertThat(e.getStatusCode().value()).isEqualTo(400));
    }

    // ---- legacy portrait golden -------------------------------------------

    /** Saved projects reference "portrait" assets by their regenerable inputs,
     *  so its bytes are pinned against the pre-character-portrait output. */
    @Test void legacyPortrait_goldenUnchanged_hexTheme() throws Exception {
        assertThat(svc.generate("portrait", "dani", "#3366cc"))
                .isEqualTo(golden("portrait-dani-3366cc.svg"));
    }

    @Test void legacyPortrait_goldenUnchanged_seededHue() throws Exception {
        assertThat(svc.generate("portrait", "narrator", null))
                .isEqualTo(golden("portrait-narrator-noTheme.svg"));
    }

    private static byte[] golden(String name) throws Exception {
        try (var in = ProceduralAssetServiceTest.class.getResourceAsStream("/golden/" + name)) {
            assertThat(in).as("missing golden resource " + name).isNotNull();
            return in.readAllBytes();
        }
    }

    /** Asserts some fill in the SVG is the given colour times a uniform
     *  brightness factor — how the renderer's depth-shade and gradient stops
     *  transform a colour — i.e. the colour genuinely drove the art. */
    private static void assertContainsShadeOf(String svg, int r, int g, int b) {
        Matcher m = Pattern.compile("#([0-9a-fA-F]{6})").matcher(svg);
        while (m.find()) {
            int cr = Integer.parseInt(m.group(1).substring(0, 2), 16);
            int cg = Integer.parseInt(m.group(1).substring(2, 4), 16);
            int cb = Integer.parseInt(m.group(1).substring(4, 6), 16);
            if (cb < 20) continue;                       // too dark to attribute
            double k = (double) cb / b;
            // ±3 absorbs the renderer's two per-channel rounding steps.
            if (Math.abs(cr - Math.round(r * k)) <= 3
                    && Math.abs(cg - Math.round(g * k)) <= 3) return;
        }
        fail("no shade of #%02x%02x%02x found in the SVG output".formatted(r, g, b));
    }

    private static void assertWellFormedSvg(byte[] bytes) throws Exception {
        assertThat(bytes).isNotEmpty();
        Document doc = parse(bytes);
        assertThat(doc.getDocumentElement().getLocalName()).isEqualTo("svg");
        assertThat(doc.getDocumentElement().getElementsByTagName("*").getLength()).isGreaterThan(0);
    }

    private static Document parse(byte[] bytes) throws Exception {
        DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
        f.setNamespaceAware(true);
        // No external entities / DTDs in our output; harden the parser anyway.
        f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        DocumentBuilder db = f.newDocumentBuilder();
        return db.parse(new ByteArrayInputStream(bytes));
    }
}
