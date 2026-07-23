package games.hugmesh.bff.model;

public record SkillDto(
        Long id,
        String name,
        String skillType,
        String description,
        int manaCost,
        String effectJson,
        String iconUrl
) {
    public static SkillDto from(Skill s) {
        return new SkillDto(s.getId(), s.getName(), s.getSkillType(),
                s.getDescription(), s.getManaCost(), s.getEffectJson(), s.getIconUrl());
    }
}
