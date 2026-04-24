package com.threewizards.model;

public record GameState(
        String sessionId,
        PlayerState player,
        String action,
        boolean pressed,
        long timestamp,
        long tick,
        GameTimeDto gameTime
) {}
