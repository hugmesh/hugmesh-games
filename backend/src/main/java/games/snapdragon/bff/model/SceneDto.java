package games.snapdragon.bff.model;

import java.util.List;

public record SceneDto(
        Long id,
        String name,
        String slug,
        String sceneType,
        String assetUrl,
        List<SceneElementDto> elements
) {}
