package games.snapdragon.bff.model.accounts;

import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

import java.time.Instant;

/**
 * One row in {@code accounts.producers}. Created when a
 * {@code producer_contacts} application with
 * {@code requested_role='producer'} is APPROVED. Carries the
 * load-bearing producer state: stable contact email, jurisdiction,
 * and the originating application id for the audit chain.
 */
@Table("accounts.producers")
public class ProducerProfile {

    @Id
    @Column("casdoor_user_id")
    private String casdoorUserId;

    @Column("display_name")           private String displayName;
    @Column("contact_email")          private String contactEmail;
    @Column("jurisdiction")           private String jurisdiction;
    @Column("approved_via_contact_id") private Long approvedViaContactId;
    @Column("approved_at")            private Instant approvedAt;
    @Column("updated_at")             private Instant updatedAt;

    public String getCasdoorUserId() { return casdoorUserId; }
    public String getDisplayName() { return displayName; }
    public String getContactEmail() { return contactEmail; }
    public String getJurisdiction() { return jurisdiction; }
    public Long getApprovedViaContactId() { return approvedViaContactId; }
    public Instant getApprovedAt() { return approvedAt; }
    public Instant getUpdatedAt() { return updatedAt; }

    public void setCasdoorUserId(String s) { this.casdoorUserId = s; }
    public void setDisplayName(String s) { this.displayName = s; }
    public void setContactEmail(String s) { this.contactEmail = s; }
    public void setJurisdiction(String s) { this.jurisdiction = s; }
    public void setApprovedViaContactId(Long n) { this.approvedViaContactId = n; }
    public void setApprovedAt(Instant t) { this.approvedAt = t; }
    public void setUpdatedAt(Instant t) { this.updatedAt = t; }
}
