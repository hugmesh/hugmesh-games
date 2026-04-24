package com.threewizards.model;

public record CharacterDto(
        Long id,
        String name,
        int level,
        int xp,
        int xpToNext,
        int maxHp,
        int currentHp,
        int maxMp,
        int currentMp,
        int attack,
        int defense,
        int speed,
        int magicPower,
        Long currentLevelId
) {
    public static CharacterDto from(PlayerCharacter c) {
        return new CharacterDto(
                c.getId(), c.getName(),
                c.getLevel(), c.getXp(), c.getXpToNext(),
                c.getMaxHp(), c.getCurrentHp(),
                c.getMaxMp(), c.getCurrentMp(),
                c.getAttack(), c.getDefense(), c.getSpeed(), c.getMagicPower(),
                c.getCurrentLevelId()
        );
    }
}
