package games.hugmesh.rpgbuilder.entity;

import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

import java.util.UUID;

/**
 * One row per project: the parametric humanoid hero baked into every export.
 * Entity-doubles-as-DTO (repo idiom) — every field is public presentation, no
 * secrets to hide from Jackson.
 *
 * <p>All value fields are BOXED types ({@code Double}/{@code String}),
 * deliberately not primitives: an omitted PUT field deserializes to
 * {@code null} and is stamped to its column default by
 * {@code AvatarConfigService}, never to a spurious {@code 0.0}/empty string
 * (which a primitive would silently produce and a clamp would then turn into
 * the wrong non-default value).
 *
 * <p>Widened by changelog 009 (decision 028) from the original seven fields to
 * the full {@code CharacterSpec} surface. The seven originals keep their exact
 * names and meanings so existing rows, the Six Realms seed and every caller
 * continue to work unchanged; validation of ALL of it now lives in
 * {@code CharacterSpec}, so there is one whitelist rather than four.
 */
@Table("rpg_avatar_config")
public class RpgAvatarConfig {
    @Id
    private UUID id;
    @Column("project_id")
    private UUID projectId;

    // ── Shape tokens (body/head predate 009) ────────────────────────────────
    @Column("render_style")
    private String renderStyle;
    @Column("body_shape")
    private String bodyShape;
    @Column("head_shape")
    private String headShape;
    @Column("hair_style")
    private String hairStyle;
    @Column("outfit_type")
    private String outfitType;

    // ── Item slots (hat predates 009) ───────────────────────────────────────
    @Column("hat_type")
    private String hatType;
    @Column("held_main")
    private String heldMain;
    @Column("held_off")
    private String heldOff;
    @Column("back_item")
    private String backItem;
    @Column("face_item")
    private String faceItem;

    // ── Proportions (height predates 009) ───────────────────────────────────
    @Column("height_scale")
    private Double heightScale;
    @Column("head_scale")
    private Double headScale;
    @Column("neck_scale")
    private Double neckScale;
    @Column("chest_scale")
    private Double chestScale;
    @Column("waist_scale")
    private Double waistScale;
    @Column("shoulder_scale")
    private Double shoulderScale;
    @Column("arm_length_scale")
    private Double armLengthScale;
    @Column("arm_thickness_scale")
    private Double armThicknessScale;
    @Column("leg_length_scale")
    private Double legLengthScale;
    @Column("leg_thickness_scale")
    private Double legThicknessScale;
    @Column("hand_scale")
    private Double handScale;
    @Column("foot_scale")
    private Double footScale;

    // ── Colours (body/head/hat predate 009) ─────────────────────────────────
    @Column("body_color")
    private String bodyColor;
    @Column("head_color")
    private String headColor;
    @Column("hat_color")
    private String hatColor;
    @Column("hair_color")
    private String hairColor;
    @Column("accent_color")
    private String accentColor;
    @Column("item_color")
    private String itemColor;
    @Column("eye_color")
    private String eyeColor;

    /** Uploaded texture applied to the skin/outfit slots; null = flat colours. */
    @Column("skin_texture_asset_id")
    private UUID skinTextureAssetId;

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }
    public UUID getProjectId() { return projectId; }
    public void setProjectId(UUID projectId) { this.projectId = projectId; }

    public String getRenderStyle() { return renderStyle; }
    public void setRenderStyle(String renderStyle) { this.renderStyle = renderStyle; }
    public String getBodyShape() { return bodyShape; }
    public void setBodyShape(String bodyShape) { this.bodyShape = bodyShape; }
    public String getHeadShape() { return headShape; }
    public void setHeadShape(String headShape) { this.headShape = headShape; }
    public String getHairStyle() { return hairStyle; }
    public void setHairStyle(String hairStyle) { this.hairStyle = hairStyle; }
    public String getOutfitType() { return outfitType; }
    public void setOutfitType(String outfitType) { this.outfitType = outfitType; }

    public String getHatType() { return hatType; }
    public void setHatType(String hatType) { this.hatType = hatType; }
    public String getHeldMain() { return heldMain; }
    public void setHeldMain(String heldMain) { this.heldMain = heldMain; }
    public String getHeldOff() { return heldOff; }
    public void setHeldOff(String heldOff) { this.heldOff = heldOff; }
    public String getBackItem() { return backItem; }
    public void setBackItem(String backItem) { this.backItem = backItem; }
    public String getFaceItem() { return faceItem; }
    public void setFaceItem(String faceItem) { this.faceItem = faceItem; }

    public Double getHeightScale() { return heightScale; }
    public void setHeightScale(Double v) { this.heightScale = v; }
    public Double getHeadScale() { return headScale; }
    public void setHeadScale(Double v) { this.headScale = v; }
    public Double getNeckScale() { return neckScale; }
    public void setNeckScale(Double v) { this.neckScale = v; }
    public Double getChestScale() { return chestScale; }
    public void setChestScale(Double v) { this.chestScale = v; }
    public Double getWaistScale() { return waistScale; }
    public void setWaistScale(Double v) { this.waistScale = v; }
    public Double getShoulderScale() { return shoulderScale; }
    public void setShoulderScale(Double v) { this.shoulderScale = v; }
    public Double getArmLengthScale() { return armLengthScale; }
    public void setArmLengthScale(Double v) { this.armLengthScale = v; }
    public Double getArmThicknessScale() { return armThicknessScale; }
    public void setArmThicknessScale(Double v) { this.armThicknessScale = v; }
    public Double getLegLengthScale() { return legLengthScale; }
    public void setLegLengthScale(Double v) { this.legLengthScale = v; }
    public Double getLegThicknessScale() { return legThicknessScale; }
    public void setLegThicknessScale(Double v) { this.legThicknessScale = v; }
    public Double getHandScale() { return handScale; }
    public void setHandScale(Double v) { this.handScale = v; }
    public Double getFootScale() { return footScale; }
    public void setFootScale(Double v) { this.footScale = v; }

    public String getBodyColor() { return bodyColor; }
    public void setBodyColor(String bodyColor) { this.bodyColor = bodyColor; }
    public String getHeadColor() { return headColor; }
    public void setHeadColor(String headColor) { this.headColor = headColor; }
    public String getHatColor() { return hatColor; }
    public void setHatColor(String hatColor) { this.hatColor = hatColor; }
    public String getHairColor() { return hairColor; }
    public void setHairColor(String hairColor) { this.hairColor = hairColor; }
    public String getAccentColor() { return accentColor; }
    public void setAccentColor(String accentColor) { this.accentColor = accentColor; }
    public String getItemColor() { return itemColor; }
    public void setItemColor(String itemColor) { this.itemColor = itemColor; }
    public String getEyeColor() { return eyeColor; }
    public void setEyeColor(String eyeColor) { this.eyeColor = eyeColor; }

    public UUID getSkinTextureAssetId() { return skinTextureAssetId; }
    public void setSkinTextureAssetId(UUID v) { this.skinTextureAssetId = v; }
}
