package games.hugmesh.rpgbuilder.entity;

import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

import java.time.OffsetDateTime;
import java.util.UUID;

@Table("rpg_build_jobs")
public class RpgBuildJob {
    @Id
    private UUID id;
    @Column("project_id")
    private UUID projectId;
    private BuildStatus status;
    @Column("progress_pct")
    private int progressPct;
    @Column("log_tail")
    private String logTail;
    @Column("target_slug")
    private String targetSlug;
    @Column("output_zip_path")
    private String outputZipPath;
    @Column("error_message")
    private String errorMessage;
    @Column("owner_casdoor_id")
    private String ownerCasdoorId;
    // Entities double as DTOs here, and build rows are echoed to the
    // maker UI by the enqueue POST + every status poll — the caller's
    // raw JWT must never ride along. WRITE_ONLY keeps the column
    // round-tripping through R2DBC while Jackson omits it on the way out.
    @com.fasterxml.jackson.annotation.JsonProperty(access = com.fasterxml.jackson.annotation.JsonProperty.Access.WRITE_ONLY)
    @Column("bearer_token")
    private String bearerToken;
    @Column("created_at")
    private OffsetDateTime createdAt;
    @Column("started_at")
    private OffsetDateTime startedAt;
    @Column("finished_at")
    private OffsetDateTime finishedAt;

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }
    public UUID getProjectId() { return projectId; }
    public void setProjectId(UUID projectId) { this.projectId = projectId; }
    public BuildStatus getStatus() { return status; }
    public void setStatus(BuildStatus status) { this.status = status; }
    public int getProgressPct() { return progressPct; }
    public void setProgressPct(int progressPct) { this.progressPct = progressPct; }
    public String getLogTail() { return logTail; }
    public void setLogTail(String logTail) { this.logTail = logTail; }
    public String getTargetSlug() { return targetSlug; }
    public void setTargetSlug(String targetSlug) { this.targetSlug = targetSlug; }
    public String getOutputZipPath() { return outputZipPath; }
    public void setOutputZipPath(String outputZipPath) { this.outputZipPath = outputZipPath; }
    public String getErrorMessage() { return errorMessage; }
    public void setErrorMessage(String errorMessage) { this.errorMessage = errorMessage; }
    public String getOwnerCasdoorId() { return ownerCasdoorId; }
    public void setOwnerCasdoorId(String ownerCasdoorId) { this.ownerCasdoorId = ownerCasdoorId; }
    public String getBearerToken() { return bearerToken; }
    public void setBearerToken(String bearerToken) { this.bearerToken = bearerToken; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(OffsetDateTime createdAt) { this.createdAt = createdAt; }
    public OffsetDateTime getStartedAt() { return startedAt; }
    public void setStartedAt(OffsetDateTime startedAt) { this.startedAt = startedAt; }
    public OffsetDateTime getFinishedAt() { return finishedAt; }
    public void setFinishedAt(OffsetDateTime finishedAt) { this.finishedAt = finishedAt; }
}
