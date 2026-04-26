package games.snapdragon.bff.model;

import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Table;

import java.time.Instant;

/**
 * One row of the upload history (table: {@code game_uploads}). The latest
 * non-superseded row per slug is the active version; older rows are
 * preserved so rollback is a one-row UPDATE plus a symlink flip.
 */
@Table("game_uploads")
public class GameUpload {

    @Id
    private Long id;
    private String slug;
    private String versionDir;
    private String contentHash;
    /** JSON-serialised manifest exactly as the uploader sent it. */
    private String manifestJson;
    private String uploadedBy;
    private Instant uploadedAt;
    private Instant supersededAt;

    public Long getId() { return id; }
    public String getSlug() { return slug; }
    public String getVersionDir() { return versionDir; }
    public String getContentHash() { return contentHash; }
    public String getManifestJson() { return manifestJson; }
    public String getUploadedBy() { return uploadedBy; }
    public Instant getUploadedAt() { return uploadedAt; }
    public Instant getSupersededAt() { return supersededAt; }

    public void setSlug(String slug) { this.slug = slug; }
    public void setVersionDir(String versionDir) { this.versionDir = versionDir; }
    public void setContentHash(String contentHash) { this.contentHash = contentHash; }
    public void setManifestJson(String manifestJson) { this.manifestJson = manifestJson; }
    public void setUploadedBy(String uploadedBy) { this.uploadedBy = uploadedBy; }
    public void setSupersededAt(Instant supersededAt) { this.supersededAt = supersededAt; }
}
