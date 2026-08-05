package games.hugmesh.bff.model;

import io.r2dbc.postgresql.codec.Json;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.Transient;
import org.springframework.data.domain.Persistable;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

import java.time.Instant;

/**
 * One saved user avatar — a name plus the portable Character Document
 * (decision 028) that every renderer derives from.
 *
 * <p>{@code document} is typed as the driver's {@link Json} wrapper rather
 * than {@code String}: Postgres rejects a plain varchar bind against a
 * {@code jsonb} column, and {@code Json} is a dialect simple type in both
 * directions, so the generic {@code save()} path works without a custom
 * converter or hand-written SQL. {@link games.hugmesh.bff.service.UserAvatarService}
 * owns the Map ⇄ Json conversion.
 *
 * <p>Implements {@link Persistable} because the id is application-assigned
 * ({@code Crypto.mintUid()}), not database-generated. Without the explicit
 * {@code isNew} flag R2DBC's {@code save()} would issue an UPDATE that
 * affects zero rows and throw {@code TransientDataAccessResourceException}.
 */
@Table("user_avatars")
public class UserAvatar implements Persistable<String> {

    @Id
    private String id;
    @Column("casdoor_id")
    private String casdoorId;
    private String name;
    private Json document;
    @Column("is_active")
    private boolean active;
    @Column("created_at")
    private Instant createdAt;
    @Column("updated_at")
    private Instant updatedAt;

    @Transient
    private boolean newRecord;

    public String  getId() { return id; }
    public String  getCasdoorId() { return casdoorId; }
    public String  getName() { return name; }
    public Json    getDocument() { return document; }
    public boolean isActive() { return active; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }

    public void setId(String id) { this.id = id; }
    public void setCasdoorId(String casdoorId) { this.casdoorId = casdoorId; }
    public void setName(String name) { this.name = name; }
    public void setDocument(Json document) { this.document = document; }
    public void setActive(boolean active) { this.active = active; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }

    public UserAvatar markNew() { this.newRecord = true; return this; }

    @Override
    public boolean isNew() { return newRecord; }
}
