package games.hugmesh.vnbuilder.service;

import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;
import org.w3c.dom.Document;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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

    private static void assertWellFormedSvg(byte[] bytes) throws Exception {
        assertThat(bytes).isNotEmpty();
        DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
        f.setNamespaceAware(true);
        // No external entities / DTDs in our output; harden the parser anyway.
        f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        DocumentBuilder db = f.newDocumentBuilder();
        Document doc = db.parse(new ByteArrayInputStream(bytes));
        assertThat(doc.getDocumentElement().getLocalName()).isEqualTo("svg");
        assertThat(doc.getDocumentElement().getElementsByTagName("*").getLength()).isGreaterThan(0);
    }
}
