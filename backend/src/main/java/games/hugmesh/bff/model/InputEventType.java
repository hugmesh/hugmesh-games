package games.hugmesh.bff.model;

import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Table;

/**
 * Reference row from {@code input_event_types}. Composite primary
 * key: {@code (kind, type)}.
 *
 * <p>Spring Data R2DBC supports composite keys via a separate
 * {@code @Id} on each part only when paired with a custom mapper —
 * here we read this table read-only via the repository's
 * {@code @Query} methods, so the simple {@code @Id} on a synthetic
 * {@code lookupKey} field would be wrong. Instead the repository
 * issues raw SQL and decodes into this record manually; the field
 * order matches the seed columns 1:1.
 *
 * <p>Inspected by the controller path on every emit so a row can't
 * land in {@code input_events} that references a nonexistent
 * {@code (kind, type)} pair.
 */
@Table("input_event_types")
public record InputEventType(
        String kind,
        String type,
        String description,
        String examplePayload,
        String payloadSchema
) {}
