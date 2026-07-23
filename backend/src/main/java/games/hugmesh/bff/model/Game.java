package games.hugmesh.bff.model;

import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Table;
import java.time.Instant;

/**
 * Top-level umbrella. A Game groups one or more playable Levels (each its own WASM).
 * Example: "Three Wizards Saga" → [Cave level, Field level, …].
 */
@Table("games")
public class Game {

    @Id
    private Long id;
    private String name;
    private String description;
    private String slug;
    private String thumbnailUrl;
    private int sortOrder;
    private Instant createdAt;
    /** Filesystem path of the active hot-upload version (migration 021). */
    private String currentVersionDir;
    /**
     * Brand/domain that OWNS this game (migration 043). {@code "default"} (the
     * back-compat value every pre-merger row backfills to) means UNSCOPED / global
     * — visible and manageable from any brand, per the row-domain predicate
     * {@link games.hugmesh.bff.security.Roles#domainApplies(String, String, boolean)}.
     * A real brand value (e.g. {@code "brandx"}) scopes the game to that brand:
     * stamped at upload time by {@code GameUploadService} from the uploader's
     * issuer-derived domain, enforced by {@code DomainScopeService}.
     */
    private String domain;

    public Long getId() { return id; }
    public String getName() { return name; }
    public String getDescription() { return description; }
    public String getSlug() { return slug; }
    public String getThumbnailUrl() { return thumbnailUrl; }
    public int getSortOrder() { return sortOrder; }
    public Instant getCreatedAt() { return createdAt; }
    public String getCurrentVersionDir() { return currentVersionDir; }
    public String getDomain() { return domain; }

    public void setCurrentVersionDir(String currentVersionDir) { this.currentVersionDir = currentVersionDir; }
    public void setDomain(String domain) { this.domain = domain; }
}
