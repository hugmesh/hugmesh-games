package games.hugmesh.bff.model;

public record SceneElementDto(
        Long id,
        String elementType,
        String name,
        Float positionX,
        Float positionY,
        Float positionZ,
        Float rotationX,
        Float rotationY,
        Float rotationZ,
        String properties
) {}
