package games.hugmesh.bff.model.accounts;

import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

import java.time.Instant;

/**
 * One row in {@code accounts.mods}. Created when a
 * {@code producer_contacts} application with
 * {@code requested_role='mod'} is APPROVED. {@code oncall_slot} is
 * used by the dispute-routing pipeline to pick which mod sees a
 * given session report first ({@code primary} → {@code secondary}
 * → fall through to any mod with the matching game scope).
 */
@Table("accounts.mods")
public class ModProfile {

    public static final String SLOT_PRIMARY   = "primary";
    public static final String SLOT_SECONDARY = "secondary";

    @Id
    @Column("casdoor_user_id")
    private String casdoorUserId;

    @Column("display_name")           private String displayName;
    @Column("contact_email")          private String contactEmail;
    @Column("oncall_slot")            private String oncallSlot;
    @Column("approved_via_contact_id") private Long approvedViaContactId;
    @Column("approved_at")            private Instant approvedAt;
    @Column("updated_at")             private Instant updatedAt;

    public String getCasdoorUserId() { return casdoorUserId; }
    public String getDisplayName() { return displayName; }
    public String getContactEmail() { return contactEmail; }
    public String getOncallSlot() { return oncallSlot; }
    public Long getApprovedViaContactId() { return approvedViaContactId; }
    public Instant getApprovedAt() { return approvedAt; }
    public Instant getUpdatedAt() { return updatedAt; }

    public void setCasdoorUserId(String s) { this.casdoorUserId = s; }
    public void setDisplayName(String s) { this.displayName = s; }
    public void setContactEmail(String s) { this.contactEmail = s; }
    public void setOncallSlot(String s) { this.oncallSlot = s; }
    public void setApprovedViaContactId(Long n) { this.approvedViaContactId = n; }
    public void setApprovedAt(Instant t) { this.approvedAt = t; }
    public void setUpdatedAt(Instant t) { this.updatedAt = t; }
}
