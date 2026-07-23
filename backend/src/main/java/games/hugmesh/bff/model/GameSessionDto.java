package games.hugmesh.bff.model;

import java.time.Instant;
import java.util.List;

/**
 * The wire DTO for a multiplayer session — what the game-menu picker
 * renders in its "open sessions" list and what the host's session detail
 * view consumes after a player creates / joins.
 *
 * @param members  active membership snapshot at the time of the response.
 *                 Empty list on the create response (the creator's own row
 *                 is the immediate next step). The session-detail GET
 *                 returns the live list.
 */
public record GameSessionDto(
        String id,
        String name,
        String gameSlug,
        String creatorCasdoorId,
        int    maxPlayers,
        int    activePlayers,
        String visibility,
        Instant createdAt,
        Instant closedAt,
        Instant pausedAt,
        String  pausedByCasdoorId,
        String  pauseReason,
        List<MemberDto> members
) {
    public record MemberDto(
            String  casdoorId,
            Long    characterId,
            Integer slotIndex,
            boolean creator,
            Instant joinedAt
    ) {
        public static MemberDto from(SessionMember m) {
            return new MemberDto(
                    m.getCasdoorId(), m.getCharacterId(), m.getSlotIndex(),
                    m.isCreator(), m.getJoinedAt());
        }
    }

    public static GameSessionDto from(GameSession s, int activePlayers, List<MemberDto> members) {
        return new GameSessionDto(
                s.getId(), s.getName(), s.getGameSlug(), s.getCreatorCasdoorId(),
                s.getMaxPlayers(), activePlayers,
                s.getVisibility() != null ? s.getVisibility() : "public",
                s.getCreatedAt(), s.getClosedAt(),
                s.getPausedAt(), s.getPausedByCasdoorId(), s.getPauseReason(),
                members);
    }
}
