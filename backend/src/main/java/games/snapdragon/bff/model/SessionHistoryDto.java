package games.snapdragon.bff.model;

import java.time.Instant;
import java.util.List;

/**
 * A single past session with derived co-players (users whose session window
 * overlapped with this one).
 */
public record SessionHistoryDto(
        Long id,
        String sessionToken,
        Long characterId,
        boolean isController,
        Instant connectedAt,
        Instant disconnectedAt,
        long durationMinutes,
        List<CoPlayerDto> coPlayers
) {
    public record CoPlayerDto(String casdoorId, String characterName) {}
}
