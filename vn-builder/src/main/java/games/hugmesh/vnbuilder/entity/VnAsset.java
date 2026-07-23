package games.hugmesh.vnbuilder.entity;

import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

import java.time.OffsetDateTime;
import java.util.UUID;

@Table("vn_assets")
public class VnAsset {
    @Id private UUID id;
    @Column("project_id") private UUID projectId;
    private String slug;
    private String kind;
    @Column("mime_type") private String mimeType;
    @Column("byte_size") private long byteSize;
    @Column("storage_path") private String storagePath;
    @Column("uploaded_by") private String uploadedBy;
    @Column("uploaded_at") private OffsetDateTime uploadedAt;

    public UUID getId() { return id; } public void setId(UUID id) { this.id = id; }
    public UUID getProjectId() { return projectId; } public void setProjectId(UUID p) { this.projectId = p; }
    public String getSlug() { return slug; } public void setSlug(String slug) { this.slug = slug; }
    public String getKind() { return kind; } public void setKind(String k) { this.kind = k; }
    public String getMimeType() { return mimeType; } public void setMimeType(String m) { this.mimeType = m; }
    public long getByteSize() { return byteSize; } public void setByteSize(long b) { this.byteSize = b; }
    public String getStoragePath() { return storagePath; } public void setStoragePath(String s) { this.storagePath = s; }
    public String getUploadedBy() { return uploadedBy; } public void setUploadedBy(String u) { this.uploadedBy = u; }
    public OffsetDateTime getUploadedAt() { return uploadedAt; } public void setUploadedAt(OffsetDateTime t) { this.uploadedAt = t; }
}
