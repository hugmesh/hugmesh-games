package games.hugmesh.bff.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import games.hugmesh.bff.model.PlayerCharacter;
import games.hugmesh.bff.model.UserAvatar;
import games.hugmesh.bff.repository.PlayerCharacterRepository;
import games.hugmesh.bff.repository.UserAvatarRepository;
import games.hugmesh.character.CharacterDocument;
import games.hugmesh.character.CharacterSpec;
import io.r2dbc.postgresql.codec.Json;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.invocation.InvocationOnMock;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Ownership + cap + atomic-select semantics for {@link UserAvatarService},
 * per the repo taxonomy: user-situational, cross-user ALLOW+DENY, fail-closed,
 * concurrent-shape.
 *
 * <p>The select "atomicity" here is proven at the CONTRACT level: the service
 * must delegate the swap to the repository's single-statement
 * {@code activateExclusively} (whose SET {@code is_active = (id = :id)}
 * semantics are modelled by the stateful stub) and must NEVER fall back to a
 * read-then-write {@code save()} — the interleaving-window regression this
 * test exists to catch. The DB-side backstop (partial unique index
 * {@code uq_user_avatars_one_active}, changeset 059-3) is not reachable from
 * a unit test.
 */
class UserAvatarServiceTest {

    private UserAvatarRepository avatars;
    private PlayerCharacterRepository characters;
    private UserAvatarService service;

    private final ObjectMapper mapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        avatars = mock(UserAvatarRepository.class);
        characters = mock(PlayerCharacterRepository.class);
        service = new UserAvatarService(avatars, characters, mapper);
    }

    private UserAvatar row(String id, String owner) {
        UserAvatar a = new UserAvatar();
        a.setId(id);
        a.setCasdoorId(owner);
        a.setName("n");
        try {
            a.setDocument(Json.of(mapper.writeValueAsString(
                    CharacterDocument.toMap(CharacterSpec.defaults(), "n"))));
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new RuntimeException(e);
        }
        a.setCreatedAt(Instant.now());
        a.setUpdatedAt(Instant.now());
        return a;
    }

    private static PlayerCharacter character(Long id, String casdoorId) {
        PlayerCharacter c = new PlayerCharacter();
        try {
            var f = PlayerCharacter.class.getDeclaredField("id");
            f.setAccessible(true);
            f.set(c, id);
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException(e);
        }
        c.setCasdoorId(casdoorId);
        return c;
    }

    private static void expectStatus(Mono<?> mono, HttpStatus status) {
        StepVerifier.create(mono)
                .expectErrorSatisfies(err -> {
                    assertThat(err).isInstanceOf(ResponseStatusException.class);
                    assertThat(((ResponseStatusException) err).getStatusCode()).isEqualTo(status);
                })
                .verify();
    }

    // ── create: cap + server stamping ────────────────────────────────────────

    @Test
    void create_underCap_mintsIdAndStampsOwner() {
        when(avatars.countByCasdoorId("alice")).thenReturn(Mono.just(3L));
        when(avatars.save(any(UserAvatar.class)))
                .thenAnswer((InvocationOnMock inv) -> Mono.just(inv.getArgument(0, UserAvatar.class)));

        StepVerifier.create(service.create("alice", "Hero", CharacterSpec.defaults()))
                .assertNext(dto -> {
                    // Server-minted 43-char SHA3 uid + server-stamped owner: the
                    // caller supplied neither, so mass assignment has no surface.
                    assertThat(dto.id()).hasSize(43);
                    assertThat(dto.name()).isEqualTo("Hero");
                    assertThat(dto.active()).isFalse();
                    assertThat(dto.document()).containsEntry("kind", "hugmesh.character");
                })
                .verifyComplete();
    }

    @Test
    void create_atCap_isRefusedWithMessage_andNeverSaves() {
        // FAIL-CLOSED cap: the 13th create is refused at the service layer so a
        // loop cannot fill the table; the message tells the user what to do.
        when(avatars.countByCasdoorId("alice"))
                .thenReturn(Mono.just((long) UserAvatarService.MAX_AVATARS_PER_USER));

        StepVerifier.create(service.create("alice", "One Too Many", CharacterSpec.defaults()))
                .expectErrorSatisfies(err -> {
                    assertThat(err).isInstanceOf(ResponseStatusException.class);
                    ResponseStatusException rse = (ResponseStatusException) err;
                    assertThat(rse.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
                    assertThat(rse.getReason()).contains("delete one");
                })
                .verify();
        verify(avatars, never()).save(any());
    }

    // ── update / delete / select: cross-user DENY + fail-closed ──────────────

    @Test
    void update_foreignAvatar_isForbidden_andNeverSaves() {
        when(avatars.findById("a1")).thenReturn(Mono.just(row("a1", "victim")));
        expectStatus(service.update("a1", "attacker", "x", CharacterSpec.defaults()),
                HttpStatus.FORBIDDEN);
        verify(avatars, never()).save(any());
    }

    @Test
    void delete_foreignAvatar_isForbidden_andNeverDeletes() {
        when(avatars.findById("a1")).thenReturn(Mono.just(row("a1", "victim")));
        expectStatus(service.delete("a1", "attacker"), HttpStatus.FORBIDDEN);
        verify(avatars, never()).delete(any());
    }

    @Test
    void select_foreignAvatar_isForbidden_andNeverActivates() {
        when(avatars.findById("a1")).thenReturn(Mono.just(row("a1", "victim")));
        expectStatus(service.select("a1", "attacker"), HttpStatus.FORBIDDEN);
        verify(avatars, never()).activateExclusively(anyString(), anyString());
    }

    @Test
    void select_nullCaller_failsClosed() {
        // FAIL-CLOSED: an absent subject can never satisfy the owner check,
        // even against a row whose owner column were somehow null.
        UserAvatar unowned = row("a1", null);
        when(avatars.findById("a1")).thenReturn(Mono.just(unowned));
        expectStatus(service.select("a1", null), HttpStatus.FORBIDDEN);
    }

    @Test
    void update_unknownAvatar_is404() {
        when(avatars.findById("nope")).thenReturn(Mono.empty());
        expectStatus(service.update("nope", "alice", "x", CharacterSpec.defaults()),
                HttpStatus.NOT_FOUND);
    }

    // ── select: atomic single-statement swap ─────────────────────────────────

    @Test
    void select_twiceSequentially_leavesExactlyOneActive_viaSingleStatementSwap() {
        // Stateful stub with the SQL's own semantics: is_active = (id = :id)
        // across the caller's whole set, one statement. Two selects in a row
        // must end with exactly one active (the second), and the service must
        // never have gone through save() — a read-then-write swap is the
        // regression that reopens the two-concurrent-selects window.
        Map<String, UserAvatar> table = new LinkedHashMap<>();
        UserAvatar a1 = row("a1", "alice");
        UserAvatar a2 = row("a2", "alice");
        table.put("a1", a1);
        table.put("a2", a2);

        when(avatars.findById(anyString()))
                .thenAnswer(inv -> Mono.justOrEmpty(table.get(inv.getArgument(0, String.class))));
        when(avatars.activateExclusively(anyString(), anyString()))
                .thenAnswer(inv -> {
                    String me = inv.getArgument(0, String.class);
                    String id = inv.getArgument(1, String.class);
                    long touched = table.values().stream()
                            .filter(r -> me.equals(r.getCasdoorId()))
                            .peek(r -> r.setActive(r.getId().equals(id)))
                            .count();
                    return Mono.just(touched);
                });

        StepVerifier.create(service.select("a1", "alice")).verifyComplete();
        StepVerifier.create(service.select("a2", "alice")).verifyComplete();

        assertThat(table.values().stream().filter(UserAvatar::isActive))
                .extracting(UserAvatar::getId)
                .containsExactly("a2");
        verify(avatars, never()).save(any());
    }

    // ── bind: both sides owner-checked ───────────────────────────────────────

    @Test
    void bind_foreignCharacter_isForbidden() {
        // CROSS-USER DENY: my avatar may not be pinned onto someone else's character.
        when(characters.findById(7L)).thenReturn(Mono.just(character(7L, "victim")));
        when(avatars.findById("a1")).thenReturn(Mono.just(row("a1", "attacker")));
        expectStatus(service.bindToCharacter(7L, "a1", "attacker"), HttpStatus.FORBIDDEN);
        verify(characters, never()).save(any());
    }

    @Test
    void bind_foreignAvatar_isForbidden() {
        // CROSS-USER DENY: someone else's avatar (id harvested from an image
        // URL) may not be bound onto my character.
        when(characters.findById(7L)).thenReturn(Mono.just(character(7L, "alice")));
        when(avatars.findById("theirs")).thenReturn(Mono.just(row("theirs", "victim")));
        expectStatus(service.bindToCharacter(7L, "theirs", "alice"), HttpStatus.FORBIDDEN);
        verify(characters, never()).save(any());
    }

    @Test
    void bind_ownAvatarToOwnCharacter_saves() {
        PlayerCharacter mine = character(7L, "alice");
        when(characters.findById(7L)).thenReturn(Mono.just(mine));
        when(avatars.findById("a1")).thenReturn(Mono.just(row("a1", "alice")));
        when(characters.save(any(PlayerCharacter.class)))
                .thenAnswer(inv -> Mono.just(inv.getArgument(0, PlayerCharacter.class)));

        StepVerifier.create(service.bindToCharacter(7L, "a1", "alice")).verifyComplete();
        assertThat(mine.getAvatarId()).isEqualTo("a1");
    }

    @Test
    void bind_nullAvatarId_unbinds_withoutAvatarLookup() {
        PlayerCharacter mine = character(7L, "alice");
        mine.setAvatarId("a1");
        when(characters.findById(7L)).thenReturn(Mono.just(mine));
        when(characters.save(any(PlayerCharacter.class)))
                .thenAnswer(inv -> Mono.just(inv.getArgument(0, PlayerCharacter.class)));

        StepVerifier.create(service.bindToCharacter(7L, null, "alice")).verifyComplete();
        assertThat(mine.getAvatarId()).isNull();
        verify(avatars, never()).findById(anyString());
    }

    @Test
    void bind_unknownCharacter_is404() {
        when(characters.findById(99L)).thenReturn(Mono.empty());
        when(avatars.findById("a1")).thenReturn(Mono.just(row("a1", "alice")));
        expectStatus(service.bindToCharacter(99L, "a1", "alice"), HttpStatus.NOT_FOUND);
    }
}
