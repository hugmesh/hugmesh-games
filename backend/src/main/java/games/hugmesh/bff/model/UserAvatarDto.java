package games.hugmesh.bff.model;

import java.time.Instant;
import java.util.Map;

/**
 * Wire DTO for one saved user avatar.
 *
 * <p>{@code document} is the portable Character Document as a plain map —
 * Jackson serialises it directly, so the wire shape is exactly what
 * {@code games.hugmesh.character.CharacterDocument} defines. The owner's
 * casdoor id is deliberately absent: the list is already scoped to the
 * caller, and an avatar id circulating as an image URL should not carry
 * its owner's identity with it.
 *
 * @param active whether this is the account's active avatar (at most one)
 */
public record UserAvatarDto(
        String id,
        String name,
        Map<String, Object> document,
        boolean active,
        Instant createdAt,
        Instant updatedAt
) {}
