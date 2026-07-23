package games.hugmesh.bff.model;

import java.time.Instant;

public record SessionDto(
        Long id,
        String casdoorId,
        Long characterId,
        boolean isController,
        Instant connectedAt,
        Instant lastPing
) {
    public static SessionDto from(PlayerSession s) {
        return new SessionDto(s.getId(), s.getCasdoorId(), s.getCharacterId(),
                s.isController(), s.getConnectedAt(), s.getLastPing());
    }
}
