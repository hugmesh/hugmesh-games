package com.threewizards.model;

public record PlayerState(
        double x,
        double y,
        double z,
        double rotationY,
        int health,
        int mana,
        int level,
        int xp,
        int xpToNext,
        int maxHp,
        int maxMana,
        int attack,
        int defense
) {
    public static PlayerState defaults() {
        return new PlayerState(0.0, 1.0, 0.0, 0.0, 100, 50, 1, 0, 100, 100, 50, 10, 5);
    }

    public PlayerState withPosition(double x, double y, double z) {
        return new PlayerState(x, y, z, rotationY, health, mana,
                level, xp, xpToNext, maxHp, maxMana, attack, defense);
    }

    public PlayerState fromCharacter(PlayerCharacter c) {
        return new PlayerState(x, y, z, rotationY,
                c.getCurrentHp(), c.getCurrentMp(),
                c.getLevel(), c.getXp(), c.getXpToNext(),
                c.getMaxHp(), c.getMaxMp(),
                c.getAttack(), c.getDefense());
    }
}
