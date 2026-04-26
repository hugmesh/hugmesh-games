package games.snapdragon.bff.model;

public record ControlEvent(
        String action,
        boolean pressed,
        long timestamp
) {}
