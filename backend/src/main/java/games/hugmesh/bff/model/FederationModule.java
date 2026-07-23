package games.hugmesh.bff.model;

import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Table;

import java.time.Instant;

/**
 * One row of the runtime federation manifest (table: {@code federation_modules}).
 *
 * <p>Replaces a static {@code federation.manifest.json} so new game remotes can
 * appear in the portal without redeploying the host shell. See
 * {@code docs/PLAN-module-federation-and-native.md} (section A) for the full
 * three-tier topology — host → per-game remote → shared WASM fragment.
 */
@Table("federation_modules")
public class FederationModule {

    @Id
    private Long id;
    private String slug;
    private String displayName;
    /** {@code 'host' | 'remote-shell' | 'remote-game' | 'fragment'} */
    private String kind;
    private String remoteEntryUrl;
    private String exposedModule;
    /** When {@code kind = 'remote-game'}, the matching {@code games.slug}. */
    private String gameSlug;
    private String version;
    private boolean enabled;
    /**
     * Free-form JSON metadata (integrity hashes, scope flags, dependencies).
     * Stored as a string here because Spring Data R2DBC doesn't ship a JSONB
     * codec out of the box; controllers parse it into a {@code Map} as needed.
     */
    private String metadata;
    private Instant createdAt;
    private Instant updatedAt;

    public Long getId() { return id; }
    public String getSlug() { return slug; }
    public String getDisplayName() { return displayName; }
    public String getKind() { return kind; }
    public String getRemoteEntryUrl() { return remoteEntryUrl; }
    public String getExposedModule() { return exposedModule; }
    public String getGameSlug() { return gameSlug; }
    public String getVersion() { return version; }
    public boolean isEnabled() { return enabled; }
    public String getMetadata() { return metadata; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }

    public void setSlug(String slug) { this.slug = slug; }
    public void setDisplayName(String displayName) { this.displayName = displayName; }
    public void setKind(String kind) { this.kind = kind; }
    public void setRemoteEntryUrl(String remoteEntryUrl) { this.remoteEntryUrl = remoteEntryUrl; }
    public void setExposedModule(String exposedModule) { this.exposedModule = exposedModule; }
    public void setGameSlug(String gameSlug) { this.gameSlug = gameSlug; }
    public void setVersion(String version) { this.version = version; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public void setMetadata(String metadata) { this.metadata = metadata; }
}
