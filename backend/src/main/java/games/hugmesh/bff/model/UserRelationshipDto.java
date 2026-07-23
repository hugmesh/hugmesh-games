package games.hugmesh.bff.model;

import java.time.Instant;

/**
 * Wire DTO for {@link UserRelationship}. Adds the {@code userName}
 * and {@code otherName} fields the friends UI displays alongside
 * the casdoor id. The names come from
 * {@code UserDirectoryService.displayNameFor} which falls back to
 * the UUID when the directory cache hasn't seen the user yet.
 *
 * <p>The casdoor_id fields stay on the wire so the frontend has a
 * stable handle for follow-up calls (accept / remove / block /
 * unblock all take the id as the {@code other} field — though the
 * controller accepts either id or username via the resolver).
 */
public record UserRelationshipDto(
        Long    id,
        String  userCasdoorId,
        String  userName,
        String  otherCasdoorId,
        String  otherName,
        String  relationship,
        Instant createdAt,
        Instant updatedAt
) {
    public static UserRelationshipDto from(UserRelationship r, String userName, String otherName) {
        return new UserRelationshipDto(
                r.getId(),
                r.getUserCasdoorId(), userName,
                r.getOtherCasdoorId(), otherName,
                r.getRelationship(),
                r.getCreatedAt(), r.getUpdatedAt());
    }
}
