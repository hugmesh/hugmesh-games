package com.threewizards.model;

import java.util.List;

/**
 * Wire shape returned by {@code GET /api/federation/manifest}.
 *
 * <p>The Angular host's bootstrap calls this endpoint, then feeds {@code remotes}
 * into native-federation's {@code initFederation(...)}. Game cards on the home
 * route resolve a clicked game-slug to the matching {@code remote-game} entry.
 *
 * <p>The {@code metadata} field stays a JSON-string blob on the wire (the BFF
 * doesn't deserialise it; the host does only when it needs the flag).
 */
public record FederationManifestDto(
        String generatedAt,
        List<FederationRemoteDto> remotes
) {
    public record FederationRemoteDto(
            String slug,
            String displayName,
            String kind,
            String remoteEntryUrl,
            String exposedModule,
            String gameSlug,
            String version,
            String metadata
    ) {
        public static FederationRemoteDto from(FederationModule m) {
            return new FederationRemoteDto(
                    m.getSlug(),
                    m.getDisplayName(),
                    m.getKind(),
                    m.getRemoteEntryUrl(),
                    m.getExposedModule(),
                    m.getGameSlug(),
                    m.getVersion(),
                    m.getMetadata()
            );
        }
    }
}
