package games.hugmesh.bff.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import games.hugmesh.bff.model.PlayerCharacter;
import games.hugmesh.bff.model.UserAvatar;
import games.hugmesh.bff.model.UserAvatarDto;
import games.hugmesh.bff.repository.PlayerCharacterRepository;
import games.hugmesh.bff.repository.UserAvatarRepository;
import games.hugmesh.bff.util.Crypto;
import games.hugmesh.character.CharacterDocument;
import games.hugmesh.character.CharacterSpec;
import io.r2dbc.postgresql.codec.Json;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.Map;

/**
 * Cross-service user avatars (decision 028): many named avatars per user,
 * each storing a portable Character Document; at most one active.
 *
 * <p>Ownership model: every mutation is keyed to the caller's casdoor id
 * (the JWT subject the controller passes in — services never read the
 * reactive security context). CRUD is owner-only; the rendered image is
 * deliberately NOT owner-gated (see the controller) because an avatar
 * exists to be seen by other users.
 *
 * <p>Documents are stored NORMALISED: the validated spec is re-serialised
 * through {@link CharacterDocument#toMap}, so the row always holds a
 * complete, canonical document (defaults materialised, unknown keys
 * dropped) rather than whatever shape the client posted.
 */
@Service
public class UserAvatarService {

    /**
     * Per-user cap, enforced here rather than as a DB constraint because the
     * refusal needs a human-readable message a client can show — a raw
     * constraint violation would surface as an opaque 500.
     */
    public static final int MAX_AVATARS_PER_USER = 12;

    private final UserAvatarRepository avatars;
    private final PlayerCharacterRepository characters;
    private final ObjectMapper json;

    public UserAvatarService(UserAvatarRepository avatars,
                             PlayerCharacterRepository characters,
                             ObjectMapper json) {
        this.avatars = avatars;
        this.characters = characters;
        this.json = json;
    }

    /** The caller's avatars, oldest first. */
    public Flux<UserAvatarDto> listFor(String casdoorId) {
        return avatars.findByCasdoorIdOrderByCreatedAtAsc(casdoorId).map(this::toDto);
    }

    /**
     * Create a new avatar for {@code casdoorId}. The id is server-minted and
     * the owner server-stamped — nothing identifying arrives from the body.
     */
    public Mono<UserAvatarDto> create(String casdoorId, String name, CharacterSpec spec) {
        // Count-then-insert is deliberately NOT race-proof: parallel POSTs from
        // one user can momentarily overshoot the cap, and a CHECK cannot count
        // rows. The cap exists for the human-readable 409, not as a hard
        // security boundary — the caller is spending their own quota, and any
        // overshoot is bounded by their own request concurrency. If live smoke
        // ever shows abuse, the fix is an advisory lock keyed on casdoor_id,
        // not a bigger window here.
        return avatars.countByCasdoorId(casdoorId)
                .defaultIfEmpty(0L)
                .flatMap(count -> {
                    if (count >= MAX_AVATARS_PER_USER) {
                        return Mono.error(new ResponseStatusException(HttpStatus.CONFLICT,
                                "avatar limit reached — you already have " + MAX_AVATARS_PER_USER
                                        + " avatars; delete one before creating another"));
                    }
                    Instant now = Instant.now();
                    UserAvatar row = new UserAvatar();
                    row.setId(Crypto.mintUid());
                    row.setCasdoorId(casdoorId);
                    row.setName(name);
                    row.setDocument(toJson(spec, name));
                    row.setActive(false);
                    row.setCreatedAt(now);
                    row.setUpdatedAt(now);
                    return avatars.save(row.markNew());
                })
                .map(this::toDto);
    }

    /** Rename / re-document an avatar the caller owns. */
    public Mono<UserAvatarDto> update(String id, String casdoorId, String name, CharacterSpec spec) {
        return owned(id, casdoorId)
                .flatMap(row -> {
                    row.setName(name);
                    row.setDocument(toJson(spec, name));
                    row.setUpdatedAt(Instant.now());
                    return avatars.save(row);
                })
                .map(this::toDto);
    }

    /** Delete an avatar the caller owns. Bound characters survive (FK SET NULL). */
    public Mono<Void> delete(String id, String casdoorId) {
        return owned(id, casdoorId).flatMap(avatars::delete);
    }

    /**
     * Make {@code id} the caller's active avatar. After the ownership check
     * the swap is ONE statement ({@code is_active = (id = :id)} across the
     * user's whole set), so two concurrent selects cannot interleave a read
     * and a write and leave two actives — the partial unique index is the
     * backstop, not the mechanism.
     */
    public Mono<Void> select(String id, String casdoorId) {
        return owned(id, casdoorId)
                .flatMap(row -> avatars.activateExclusively(casdoorId, id))
                .then();
    }

    /**
     * Bind an avatar to one of the caller's characters (null unbinds). Both
     * sides are ownership-checked: a foreign character AND a foreign avatar
     * are each a 403 — otherwise an avatar id harvested from an image URL
     * could be pinned onto someone else's character, or someone else's
     * avatar onto yours.
     */
    public Mono<Void> bindToCharacter(Long characterId, String avatarId, String casdoorId) {
        Mono<PlayerCharacter> character = characters.findById(characterId)
                .switchIfEmpty(Mono.error(new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "unknown character: " + characterId)))
                .flatMap(c -> casdoorId != null && casdoorId.equals(c.getCasdoorId())
                        ? Mono.just(c)
                        : Mono.error(new ResponseStatusException(HttpStatus.FORBIDDEN,
                                "character " + characterId + " is not yours")));
        Mono<PlayerCharacter> checked = avatarId == null
                ? character
                : owned(avatarId, casdoorId).then(character);
        return checked.flatMap(c -> {
            c.setAvatarId(avatarId);
            return characters.save(c);
        })
                // The avatar can be deleted between the ownership check and the
                // save; the FK then rejects the insert. That narrow race is a
                // caller-visible "it vanished", not a server fault — map it to
                // the same 404 an up-front miss produces instead of a 500.
                .onErrorMap(org.springframework.dao.DataIntegrityViolationException.class,
                        e -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                                "unknown avatar: " + avatarId, e))
                .then();
    }

    /**
     * The stored spec for rendering. No ownership check on purpose — the
     * image endpoint is readable by any authenticated user (see the
     * controller comment). Unknown id is a 404.
     */
    public Mono<CharacterSpec> specOf(String id) {
        return avatars.findById(id)
                .switchIfEmpty(Mono.error(new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "unknown avatar: " + id)))
                .map(row -> CharacterDocument.fromMap(readDocument(row.getDocument())));
    }

    /** Owner-gated fetch: 404 when the row does not exist, 403 when it is not the caller's. */
    private Mono<UserAvatar> owned(String id, String casdoorId) {
        return avatars.findById(id)
                .switchIfEmpty(Mono.error(new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "unknown avatar: " + id)))
                .flatMap(row -> casdoorId != null && casdoorId.equals(row.getCasdoorId())
                        ? Mono.just(row)
                        : Mono.error(new ResponseStatusException(HttpStatus.FORBIDDEN,
                                "avatar " + id + " is not yours")));
    }

    private UserAvatarDto toDto(UserAvatar row) {
        return new UserAvatarDto(row.getId(), row.getName(), readDocument(row.getDocument()),
                row.isActive(), row.getCreatedAt(), row.getUpdatedAt());
    }

    private Json toJson(CharacterSpec spec, String name) {
        try {
            return Json.of(json.writeValueAsString(CharacterDocument.toMap(spec, name)));
        } catch (JsonProcessingException e) {
            // Unreachable for a Map of strings/numbers, but never swallow.
            throw new IllegalStateException("could not serialise character document", e);
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> readDocument(Json document) {
        try {
            return json.readValue(document.asString(), Map.class);
        } catch (JsonProcessingException e) {
            // A row that fails to parse is corrupt storage, not caller error.
            throw new IllegalStateException("stored character document is not valid JSON", e);
        }
    }
}
