package com.threewizards.model;

public record ControlEvent(
        String action,
        boolean pressed,
        long timestamp
) {}
