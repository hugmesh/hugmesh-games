package games.hugmesh.bff.model;

import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

import java.time.Instant;

/**
 * Tiny key/value row for runtime-toggled system settings. The
 * {@code wasm_console_debug_enabled} key gates the diagnostics ingest
 * pipeline; future booleans reuse this table instead of growing schema.
 */
@Table("system_settings")
public class SystemSetting {

    @Id
    private String key;
    private String value;
    @Column("updated_at")
    private Instant updatedAt;
    @Column("updated_by")
    private String updatedBy;

    public String  getKey() { return key; }
    public String  getValue() { return value; }
    public Instant getUpdatedAt() { return updatedAt; }
    public String  getUpdatedBy() { return updatedBy; }

    public void setKey(String key) { this.key = key; }
    public void setValue(String value) { this.value = value; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
    public void setUpdatedBy(String updatedBy) { this.updatedBy = updatedBy; }
}
