package games.hugmesh.rpgbuilder.service;

import games.hugmesh.character.CharacterDocument;
import games.hugmesh.character.CharacterSpec;
import games.hugmesh.rpgbuilder.entity.RpgAvatarConfig;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The entity ⇄ {@link CharacterSpec} mapping that changelog 009 introduced.
 *
 * <p>Pure and DB-free: everything here is the translation layer, and getting it
 * wrong is silent — a field that fails to map does not throw, it just quietly
 * renders as the default forever. So every one of the new columns is asserted
 * individually rather than by a spot check.
 */
class AvatarConfigMappingTest {

    @Test
    void everyFieldSurvivesEntityToSpecAndBack() {
        UUID texture = UUID.randomUUID();
        RpgAvatarConfig in = new RpgAvatarConfig();
        in.setRenderStyle("lowpoly");
        in.setBodyShape("stocky");
        in.setHeadShape("tapered");
        in.setHairStyle("ponytail");
        in.setOutfitType("armor");
        in.setHatType("helm");
        in.setHeldMain("axe");
        in.setHeldOff("shield");
        in.setBackItem("cape");
        in.setFaceItem("visor");
        in.setHeightScale(1.25);
        in.setHeadScale(1.40);
        in.setNeckScale(0.80);
        in.setChestScale(1.35);
        in.setWaistScale(0.75);
        in.setShoulderScale(1.30);
        in.setArmLengthScale(1.20);
        in.setArmThicknessScale(1.45);
        in.setLegLengthScale(0.80);
        in.setLegThicknessScale(1.10);
        in.setHandScale(1.55);
        in.setFootScale(0.65);
        in.setBodyColor("#112233");
        in.setHeadColor("#445566");
        in.setHatColor("#778899");
        in.setHairColor("#aabbcc");
        in.setAccentColor("#ddeeff");
        in.setItemColor("#010203");
        in.setEyeColor("#040506");
        in.setSkinTextureAssetId(texture);

        CharacterSpec spec = AvatarConfigService.toSpec(in);
        RpgAvatarConfig out = AvatarConfigService.fromSpec(spec, in.getProjectId());

        assertThat(out.getRenderStyle()).isEqualTo("lowpoly");
        assertThat(out.getBodyShape()).isEqualTo("stocky");
        assertThat(out.getHeadShape()).isEqualTo("tapered");
        assertThat(out.getHairStyle()).isEqualTo("ponytail");
        assertThat(out.getOutfitType()).isEqualTo("armor");
        assertThat(out.getHatType()).isEqualTo("helm");
        assertThat(out.getHeldMain()).isEqualTo("axe");
        assertThat(out.getHeldOff()).isEqualTo("shield");
        assertThat(out.getBackItem()).isEqualTo("cape");
        assertThat(out.getFaceItem()).isEqualTo("visor");
        assertThat(out.getHeightScale()).isEqualTo(1.25);
        assertThat(out.getHeadScale()).isEqualTo(1.40);
        assertThat(out.getNeckScale()).isEqualTo(0.80);
        assertThat(out.getChestScale()).isEqualTo(1.35);
        assertThat(out.getWaistScale()).isEqualTo(0.75);
        assertThat(out.getShoulderScale()).isEqualTo(1.30);
        assertThat(out.getArmLengthScale()).isEqualTo(1.20);
        assertThat(out.getArmThicknessScale()).isEqualTo(1.45);
        assertThat(out.getLegLengthScale()).isEqualTo(0.80);
        assertThat(out.getLegThicknessScale()).isEqualTo(1.10);
        assertThat(out.getHandScale()).isEqualTo(1.55);
        assertThat(out.getFootScale()).isEqualTo(0.65);
        assertThat(out.getBodyColor()).isEqualTo("#112233");
        assertThat(out.getHatColor()).isEqualTo("#778899");
        assertThat(out.getHairColor()).isEqualTo("#aabbcc");
        assertThat(out.getAccentColor()).isEqualTo("#ddeeff");
        assertThat(out.getItemColor()).isEqualTo("#010203");
        assertThat(out.getEyeColor()).isEqualTo("#040506");
        assertThat(out.getSkinTextureAssetId()).isEqualTo(texture);
    }

    @Test
    void skinIsCarriedByThePreExistingHeadColorColumn() {
        // head_color has always held the skin tone. Renaming it would break
        // every existing row and the Six Realms seed for no benefit, so the
        // spec's "skin" slot maps onto it — pinned here because the indirection
        // is otherwise invisible.
        RpgAvatarConfig in = new RpgAvatarConfig();
        in.setHeadColor("#e6b58c");

        CharacterSpec spec = AvatarConfigService.toSpec(in);
        assertThat(spec.color("skin")).isEqualTo("#e6b58c");
        assertThat(AvatarConfigService.fromSpec(spec, null).getHeadColor()).isEqualTo("#e6b58c");
    }

    @Test
    void anEmptyEntityBecomesTheFullDefaultSpec() {
        CharacterSpec spec = AvatarConfigService.toSpec(new RpgAvatarConfig());
        RpgAvatarConfig out = AvatarConfigService.fromSpec(spec, null);

        assertThat(out.getRenderStyle()).isEqualTo("smooth");
        assertThat(out.getOutfitType()).isEqualTo("robe");
        assertThat(out.getHeldMain()).isEqualTo("none");
        assertThat(out.getSkinTextureAssetId()).isNull();
        for (CharacterSpec.Bound b : CharacterSpec.PROPORTIONS) {
            assertThat(spec.p(b.key())).as("default of %s", b.key()).isEqualTo(b.dflt());
        }
        assertThat(AvatarConfigService.toSpec(null).bodyShape())
                .isEqualTo(CharacterSpec.DEFAULT_BODY_SHAPE);
    }

    @Test
    void defaultConfigMatchesTheSpecDefaults() {
        UUID pid = UUID.randomUUID();
        RpgAvatarConfig d = AvatarConfigService.defaultConfig(pid);

        assertThat(d.getProjectId()).isEqualTo(pid);
        assertThat(d.getId()).as("not persisted").isNull();
        assertThat(d.getBodyShape()).isEqualTo(CharacterSpec.DEFAULT_BODY_SHAPE);
        assertThat(d.getBodyColor()).isEqualTo(AvatarConfigService.DEFAULT_BODY_COLOR);
        assertThat(d.getHeadColor()).isEqualTo(AvatarConfigService.DEFAULT_HEAD_COLOR);
        assertThat(d.getHatColor()).isEqualTo(AvatarConfigService.DEFAULT_HAT_COLOR);
        assertThat(d.getHeightScale()).isEqualTo(AvatarConfigService.DEFAULT_HEIGHT_SCALE);
    }

    @Test
    void everyNewProportionRejectsOutOfRangeUnderItsWireName() {
        // The 400 must name the field the caller actually sent (heightScale,
        // chestScale …), not the internal spec key — that error contract
        // predates 009 and clients depend on it.
        record Case(String wire, java.util.function.BiConsumer<RpgAvatarConfig, Double> set) {}
        var cases = java.util.List.of(
                new Case("headScale", RpgAvatarConfig::setHeadScale),
                new Case("neckScale", RpgAvatarConfig::setNeckScale),
                new Case("chestScale", RpgAvatarConfig::setChestScale),
                new Case("waistScale", RpgAvatarConfig::setWaistScale),
                new Case("shoulderScale", RpgAvatarConfig::setShoulderScale),
                new Case("armLengthScale", RpgAvatarConfig::setArmLengthScale),
                new Case("armThicknessScale", RpgAvatarConfig::setArmThicknessScale),
                new Case("legLengthScale", RpgAvatarConfig::setLegLengthScale),
                new Case("legThicknessScale", RpgAvatarConfig::setLegThicknessScale),
                new Case("handScale", RpgAvatarConfig::setHandScale),
                new Case("footScale", RpgAvatarConfig::setFootScale));

        for (Case c : cases) {
            RpgAvatarConfig high = new RpgAvatarConfig();
            c.set().accept(high, 9.0);
            assertThatThrownBy(() -> AvatarConfigService.toSpec(high))
                    .as("reject for %s", c.wire())
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining(c.wire());

            RpgAvatarConfig low = new RpgAvatarConfig();
            c.set().accept(low, 0.01);
            assertThatThrownBy(() -> AvatarConfigService.toSpec(low))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    void everyNewTokenSetIsEnforced() {
        RpgAvatarConfig style = new RpgAvatarConfig();
        style.setRenderStyle("ps1");
        assertThatThrownBy(() -> AvatarConfigService.toSpec(style))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("renderStyle");

        RpgAvatarConfig outfit = new RpgAvatarConfig();
        outfit.setOutfitType("spacesuit");
        assertThatThrownBy(() -> AvatarConfigService.toSpec(outfit))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("outfitType");

        RpgAvatarConfig weapon = new RpgAvatarConfig();
        weapon.setHeldMain("railgun");
        assertThatThrownBy(() -> AvatarConfigService.toSpec(weapon))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("heldMain");

        RpgAvatarConfig colour = new RpgAvatarConfig();
        colour.setEyeColor("chartreuse");
        assertThatThrownBy(() -> AvatarConfigService.toSpec(colour))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("eyeColor");
    }

    @Test
    void tokensWidenedBy009AreNowAccepted() {
        // 008's CHECKs only admitted round|square and none|cap|cone|top_hat.
        // If the migration failed to drop and re-add them, these would still be
        // rejected at write time — so assert the service side admits them.
        for (String head : java.util.List.of("oval", "tapered")) {
            RpgAvatarConfig c = new RpgAvatarConfig();
            c.setHeadShape(head);
            assertThat(AvatarConfigService.toSpec(c).headShape()).isEqualTo(head);
        }
        for (String hat : java.util.List.of("helm", "crown", "hood", "bandana", "straw")) {
            RpgAvatarConfig c = new RpgAvatarConfig();
            c.setHatType(hat);
            assertThat(AvatarConfigService.toSpec(c).hatType()).isEqualTo(hat);
        }
    }

    @Test
    void documentExportAndImportRoundTripThroughTheEntity() {
        RpgAvatarConfig in = new RpgAvatarConfig();
        in.setRenderStyle("lowpoly");
        in.setOutfitType("dress");
        in.setHeldOff("orb");
        in.setChestScale(1.3);
        in.setBodyColor("#abcdef");

        Map<String, Object> doc = CharacterDocument.toMap(AvatarConfigService.toSpec(in), "Wren");
        RpgAvatarConfig back = AvatarConfigService.fromSpec(CharacterDocument.fromMap(doc), null);

        assertThat(back.getRenderStyle()).isEqualTo("lowpoly");
        assertThat(back.getOutfitType()).isEqualTo("dress");
        assertThat(back.getHeldOff()).isEqualTo("orb");
        assertThat(back.getChestScale()).isEqualTo(1.3);
        assertThat(back.getBodyColor()).isEqualTo("#abcdef");
    }

    @Test
    void legacyConstantsStillMatchTheSpec() {
        // RpgGodotProjectRenderer re-validates against these aliases; if they
        // ever stopped tracking CharacterSpec the renderer would silently
        // substitute defaults for legitimate new tokens.
        assertThat(AvatarConfigService.BODY_SHAPES).isEqualTo(CharacterSpec.BODY_SHAPES);
        assertThat(AvatarConfigService.HEAD_SHAPES).isEqualTo(CharacterSpec.HEAD_SHAPES);
        assertThat(AvatarConfigService.HAT_TYPES).isEqualTo(CharacterSpec.HAT_TYPES);
        assertThat(AvatarConfigService.MIN_HEIGHT_SCALE).isEqualTo(CharacterSpec.bound("height").min());
        assertThat(AvatarConfigService.MAX_HEIGHT_SCALE).isEqualTo(CharacterSpec.bound("height").max());
        assertThat(AvatarConfigService.HEAD_SHAPES).contains("oval", "tapered");
        assertThat(AvatarConfigService.HAT_TYPES).contains("helm", "crown", "hood", "bandana", "straw");
    }
}
