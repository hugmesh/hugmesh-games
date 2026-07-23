package games.hugmesh.rpgbuilder.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import javax.xml.parsers.DocumentBuilderFactory;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Specs for {@link ProceduralAssetService}: determinism (same inputs ⇒
 * byte-identical SVG), well-formed XML output for every supported kind, and
 * seed-sensitivity (different slug/theme ⇒ different bytes). Runs fully offline.
 */
class ProceduralAssetServiceTest {

    private final ProceduralAssetService svc = new ProceduralAssetService();

    @ParameterizedTest
    @ValueSource(strings = {"tileset", "sprite", "portrait", "icon"})
    void generate_isDeterministicPerInputs(String kind) {
        byte[] a = svc.generate(kind, "cave-floor", "stone");
        byte[] b = svc.generate(kind, "cave-floor", "stone");
        assertThat(a).isEqualTo(b);                 // byte-identical
        assertThat(a).isNotEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"tileset", "sprite", "portrait", "icon"})
    void generate_isWellFormedSvg(String kind) throws Exception {
        byte[] svg = svc.generate(kind, "town-square", "grass");
        String text = new String(svg, StandardCharsets.UTF_8);
        assertThat(text).startsWith("<svg").contains("xmlns='http://www.w3.org/2000/svg'").endsWith("</svg>");
        // Parses as XML with no DTD/entity fetches → structurally well-formed.
        var dbf = DocumentBuilderFactory.newInstance();
        dbf.setNamespaceAware(true);
        var doc = dbf.newDocumentBuilder().parse(new ByteArrayInputStream(svg));
        assertThat(doc.getDocumentElement().getLocalName()).isEqualTo("svg");
    }

    @Test
    void generate_seedSensitiveOnSlugAndTheme() {
        byte[] base = svc.generate("icon", "potion", "red");
        assertThat(svc.generate("icon", "elixir", "red")).isNotEqualTo(base);   // slug matters
        assertThat(svc.generate("icon", "potion", "blue")).isNotEqualTo(base);  // theme matters
    }

    @Test
    void generate_toleratesNullTheme() {
        byte[] a = svc.generate("sprite", "hero", null);
        byte[] b = svc.generate("sprite", "hero", null);
        assertThat(a).isEqualTo(b).isNotEmpty();
    }

    @Test
    void generate_rejectsUnsupportedKind() {
        assertThat(svc.supports("audio")).isFalse();
        assertThatThrownBy(() -> svc.generate("audio", "boom", null))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
