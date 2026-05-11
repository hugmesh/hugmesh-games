package games.snapdragon.vnbuilder.entity;

import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

import java.time.OffsetDateTime;
import java.util.UUID;

@Table("vn_build_jobs")
public class VnBuildJob {
    @Id private UUID id;
    @Column("project_id") private UUID projectId;
    private BuildStatus status;
    @Column("progress_pct") private int progressPct;
    @Column("log_tail") private String logTail;
    @Column("target_slug") private String targetSlug;
    @Column("output_zip_path") private String outputZipPath;
    @Column("error_message") private String errorMessage;
    @Column("owner_casdoor_id") private String ownerCasdoorId;
    @Column("bearer_token") private String bearerToken;
    @Column("created_at") private OffsetDateTime createdAt;
    @Column("started_at") private OffsetDateTime startedAt;
    @Column("finished_at") private OffsetDateTime finishedAt;

    public UUID getId() { return id; } public void setId(UUID id) { this.id = id; }
    public UUID getProjectId() { return projectId; } public void setProjectId(UUID p) { this.projectId = p; }
    public BuildStatus getStatus() { return status; } public void setStatus(BuildStatus s) { this.status = s; }
    public int getProgressPct() { return progressPct; } public void setProgressPct(int i) { this.progressPct = i; }
    public String getLogTail() { return logTail; } public void setLogTail(String s) { this.logTail = s; }
    public String getTargetSlug() { return targetSlug; } public void setTargetSlug(String s) { this.targetSlug = s; }
    public String getOutputZipPath() { return outputZipPath; } public void setOutputZipPath(String s) { this.outputZipPath = s; }
    public String getErrorMessage() { return errorMessage; } public void setErrorMessage(String s) { this.errorMessage = s; }
    public String getOwnerCasdoorId() { return ownerCasdoorId; } public void setOwnerCasdoorId(String s) { this.ownerCasdoorId = s; }
    public String getBearerToken() { return bearerToken; } public void setBearerToken(String s) { this.bearerToken = s; }
    public OffsetDateTime getCreatedAt() { return createdAt; } public void setCreatedAt(OffsetDateTime t) { this.createdAt = t; }
    public OffsetDateTime getStartedAt() { return startedAt; } public void setStartedAt(OffsetDateTime t) { this.startedAt = t; }
    public OffsetDateTime getFinishedAt() { return finishedAt; } public void setFinishedAt(OffsetDateTime t) { this.finishedAt = t; }
}
