package com.threewizards.model;

public record PlayerState(
        double x,
        double y,
        double z,
        double rotationY,
        int health,
        int mana
) {
    public static PlayerState defaults() {
        return new PlayerState(0.0, 1.0, 0.0, 0.0, 100, 100);
    }

    public PlayerState withPosition(double x, double y, double z) {
        return new PlayerState(x, y, z, this.rotationY, this.health, this.mana);
    }
}
