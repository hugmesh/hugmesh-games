package games.snapdragon.bff.model;

import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

import java.time.Instant;

/**
 * One row in {@code producer_contacts} (Liquibase 031). Submission
 * record for a producer / mod application. {@code status} flips
 * PENDING → APPROVED / REJECTED via the admin console out of band;
 * approval also grants the matching Casdoor role.
 *
 * <p>Producer / mod role is open-coded as a string for the same
 * reason {@link AdminAction#action} is: keeps the R2DBC row codec
 * straightforward. App-level validation pins the enumeration.
 */
@Table("producer_contacts")
public class ProducerContact {

    public static final String STATUS_PENDING  = "PENDING";
    public static final String STATUS_APPROVED = "APPROVED";
    public static final String STATUS_REJECTED = "REJECTED";

    public static final String ROLE_PRODUCER = "producer";
    public static final String ROLE_MOD      = "mod";

    @Id
    private Long id;
    @Column("casdoor_user_id")     private String casdoorUserId;
    @Column("display_name")        private String displayName;
    @Column("contact_email")       private String contactEmail;
    @Column("jurisdiction")        private String jurisdiction;
    @Column("requested_role")      private String requestedRole;
    @Column("rationale")           private String rationale;
    @Column("status")              private String status;
    @Column("reviewed_by_casdoor_id") private String reviewedByCasdoorId;
    @Column("reviewer_notes")      private String reviewerNotes;
    @Column("submitted_at")        private Instant submittedAt;
    @Column("reviewed_at")         private Instant reviewedAt;

    public Long getId() { return id; }
    public String getCasdoorUserId() { return casdoorUserId; }
    public String getDisplayName() { return displayName; }
    public String getContactEmail() { return contactEmail; }
    public String getJurisdiction() { return jurisdiction; }
    public String getRequestedRole() { return requestedRole; }
    public String getRationale() { return rationale; }
    public String getStatus() { return status; }
    public String getReviewedByCasdoorId() { return reviewedByCasdoorId; }
    public String getReviewerNotes() { return reviewerNotes; }
    public Instant getSubmittedAt() { return submittedAt; }
    public Instant getReviewedAt() { return reviewedAt; }

    public void setId(Long id) { this.id = id; }
    public void setCasdoorUserId(String s) { this.casdoorUserId = s; }
    public void setDisplayName(String s)  { this.displayName = s; }
    public void setContactEmail(String s) { this.contactEmail = s; }
    public void setJurisdiction(String s) { this.jurisdiction = s; }
    public void setRequestedRole(String s){ this.requestedRole = s; }
    public void setRationale(String s)    { this.rationale = s; }
    public void setStatus(String s)       { this.status = s; }
    public void setReviewedByCasdoorId(String s) { this.reviewedByCasdoorId = s; }
    public void setReviewerNotes(String s){ this.reviewerNotes = s; }
    public void setSubmittedAt(Instant t) { this.submittedAt = t; }
    public void setReviewedAt(Instant t)  { this.reviewedAt = t; }
}
